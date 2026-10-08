package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Protected PostgreSQL kernel for durable SYNC/PER_ITEM execution.
 *
 * <p>The callback is invoked only after a durable attempt marker was committed. Domain work,
 * receipt and the pending-ack barrier share one operational transaction. This class performs no
 * authorization, policy evaluation, operation registration, HTTP projection or background work.</p>
 */
public final class JdbcBulkDurableExecution {
    private static final int MAX_TEXT = 200;
    // B0 bounds both the durable-control and target-row lock waits to one second.
    private static final Duration CONTROL_LOCK_BUDGET = Duration.ofSeconds(1);
    private static final Duration UNIT_BUDGET = Duration.ofSeconds(5);
    private final BulkExecutionInfrastructure infrastructure;
    private final BulkOperationLifecycle lifecycle;
    private final JdbcBulkCapacityInstallation.ExpectedBinding capacityBinding;

    public JdbcBulkDurableExecution(BulkExecutionInfrastructure infrastructure) {
        this(infrastructure, null);
    }

    public JdbcBulkDurableExecution(BulkExecutionInfrastructure infrastructure, BulkOperationLifecycle lifecycle) {
        this(infrastructure, lifecycle, null);
    }

    JdbcBulkDurableExecution(BulkExecutionInfrastructure infrastructure, BulkOperationLifecycle lifecycle,
            JdbcBulkCapacityInstallation.ExpectedBinding capacityBinding) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
        this.lifecycle = lifecycle;
        this.capacityBinding = capacityBinding;
    }

    /** Protected selector position; hints never authorize ownership or mutation. */
    record WorkerQueueHint(UUID executionId, Instant createdAt) { }

    JdbcBulkCapacityInstallation.ExpectedBinding workerBindingIdentity() {
        return Objects.requireNonNull(capacityBinding, "Explicit capacity binding is required");
    }

    Optional<WorkerQueueHint> workerNextQueued(WorkerQueueHint after) {
        requireNoAmbientTransaction();
        return workerTransaction(connection -> {
            JdbcBulkCapacityOccupancy.requireBinding(connection, infrastructure, capacityBinding, false);
            String position = after == null ? "" : " and (created_at,execution_id) > (?,?)";
            try (var statement = connection.prepareStatement("""
                    select execution_id,created_at from praxis_bulk.praxis_bulk_execution
                    where namespace_id=? and execution_mode='ASYNC' and status='QUEUED'
                    """ + position + " order by created_at,execution_id limit 1")) {
                statement.setString(1, infrastructure.namespace());
                if (after != null) {
                    statement.setObject(2, OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC));
                    statement.setObject(3, after.executionId());
                }
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    return Optional.of(new WorkerQueueHint(rows.getObject(1, UUID.class),
                            rows.getObject(2, OffsetDateTime.class).toInstant()));
                }
            }
        });
    }

    BulkFingerprintContext workerContext(UUID executionId) {
        requireNoAmbientTransaction();
        return workerTransaction(connection -> {
            JdbcBulkCapacityOccupancy.requireBinding(connection, infrastructure, capacityBinding, false);
            ExecutionRow execution = row(connection, executionId, false);
            if (!infrastructure.namespace().equals(execution.namespaceId())
                    || !"ASYNC".equals(execution.executionMode()))
                throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
            Evaluation evaluation = loadEvaluation(connection, execution, false);
            validateAsynchronousSubset(evaluation);
            return evaluation.proposal().snapshot().context();
        });
    }

    Optional<UUID> workerAvailableActiveToken() {
        requireNoAmbientTransaction();
        return workerTransaction(connection -> {
            JdbcBulkCapacityOccupancy.requireBinding(connection, infrastructure, capacityBinding, true);
            try (var statement = connection.prepareStatement("""
                    select s.token_id from praxis_bulk.praxis_bulk_capacity_slot s
                    join praxis_bulk.praxis_bulk_capacity_installation i on i.token_id=s.token_id
                    where s.capacity_class='ACTIVE' and s.current_execution_id is null
                    order by i.token_ordinal,s.token_id limit 1
                    """); var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getObject(1, UUID.class)) : Optional.empty();
            }
        });
    }

    /** Terminalizes only an expired, certified empty QUEUED prefix; no ACTIVE right is needed. */
    boolean workerExpireQueued(BulkFingerprintContext scope, UUID executionId) {
        requireNoAmbientTransaction(); requireScope(scope);
        return workerTransaction(connection -> {
            ExecutionRow execution = lockLifecycle(connection, scope, executionId);
            JdbcBulkCapacityOccupancy.requireBinding(connection, infrastructure, capacityBinding, false);
            if (execution.status() != BulkDurableExecutionStatus.QUEUED
                    || clock(connection).isBefore(execution.deadlineAt())) return false;
            Evaluation evaluation = loadEvaluation(connection, execution, false);
            validateAsynchronousSubset(evaluation);
            if (execution.ownerEpoch() != 1 || execution.nextOrdinal() != 0
                    || execution.activeAttemptId() != null
                    || !durablePrefixConsistent(connection, execution, evaluation))
                throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            try (var statement = connection.prepareStatement("""
                    with terminal_clock as materialized (select clock_timestamp() as observed_at)
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED',terminal_reason_code='DEADLINE_EXCEEDED',
                        terminal_at=terminal_clock.observed_at,updated_at=terminal_clock.observed_at
                    from terminal_clock
                    where execution_id=? and namespace_id=? and status='QUEUED' and owner_epoch=1
                      and deadline_at<=terminal_clock.observed_at
                    """)) {
                statement.setObject(1, executionId); statement.setString(2, infrastructure.namespace());
                if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
            }
            return true;
        });
    }

    /** Old workers cannot use conservative recovery to fence a successor. */
    BulkExecutionRecovery workerRecoverOwned(BulkFingerprintContext scope,
            BulkExecutionReservation reservation, String recoveryOwner) {
        requireNoAmbientTransaction(); requireScope(scope); Objects.requireNonNull(reservation, "reservation");
        String owner = text(recoveryOwner, "recoveryOwner");
        return workerTransaction(connection -> {
            ExecutionRow execution = lockLifecycle(connection, scope, reservation.executionId());
            JdbcBulkCapacityOccupancy.requireBinding(connection, infrastructure, capacityBinding, false);
            if (!"ASYNC".equals(execution.executionMode()) || execution.protocolVersion() != 2
                    || execution.atomicity() != ActionCollectionAtomicity.PER_ITEM
                    || execution.activeTokenId() == null || execution.ownerEpoch() < 2
                    || execution.status() == BulkDurableExecutionStatus.QUEUED)
                throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
            if (!execution.proposalId().equals(reservation.proposalId())
                    || !execution.ownerId().equals(reservation.control().ownerId())
                    || execution.ownerEpoch() != reservation.control().epoch())
                throw failure(BulkDurableExecutionException.Reason.FENCED);
            validateReadOccupancy(connection, execution);
            return recover(connection, scope, execution.executionId(), owner);
        });
    }

    private <T> T workerTransaction(SqlWork<T> work) {
        try { return unitTransaction(work); }
        catch (BulkDurableExecutionException error) { throw error; }
        catch (RuntimeException error) { throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE); }
    }

    public BulkExecutionReservation reserve(BulkFingerprintContext scope, UUID proposalId,
            String idempotencyKey, String ownerId, String structuralRevision, Instant deadline) {
        requireNoAmbientTransaction();
        requireScope(scope);
        Objects.requireNonNull(proposalId, "proposalId");
        String key = text(idempotencyKey, "idempotencyKey");
        String owner = text(ownerId, "ownerId");
        String revision = text(structuralRevision, "structuralRevision");
        Instant boundedDeadline = micro(Objects.requireNonNull(deadline, "deadline"));
        String keyDigest = BulkScopeDigests.idempotencyKeyDigest(key);
        try {
            // A committed reservation is authoritative even after publication is suspended.
            QueryProbe probe = transaction(connection -> probeQueryReservation(connection, scope,
                    proposalId, keyDigest, owner, revision, boundedDeadline));
            if (probe.explicitWrite() != null)
                return new BulkExecutionReservation(probe.explicitWrite().snapshot(),
                        !probe.explicitWrite().created());
            if (probe.existing().isPresent())
                return new BulkExecutionReservation(probe.existing().orElseThrow(), true);
            BulkOperationLifecycle.ReadyAdmission queryAdmission = null;
            if (probe.query()) {
                if (lifecycle == null) throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
                try {
                    queryAdmission = lifecycle.requireReady(new BulkOperationControlIdentity(
                            scope.namespaceId(), scope.operationRef().operationId()),
                            BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
                } catch (RuntimeException unavailable) {
                    throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
                }
            }
            BulkOperationLifecycle.ReadyAdmission admission = queryAdmission;
            ReservationWrite write = transaction(connection -> reserve(connection, scope, proposalId,
                    keyDigest, owner, revision, boundedDeadline, admission));
            return new BulkExecutionReservation(write.snapshot(), !write.created());
        } catch (BulkDurableExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            // The insert commit may have succeeded. Readback by both unique identities is the
            // only safe resolution; no replacement key or execution is manufactured.
            try {
                Optional<BulkExecutionSnapshot> recovered = transaction(connection -> {
                    Evaluation evaluation = loadEvaluation(connection, scope, proposalId, false);
                    requireSynchronous(evaluation);
                    validateExecutionSubset(evaluation);
                    String expectedBinding = reservationFingerprint(evaluation, revision);
                    return findReservation(connection, scope, proposalId, keyDigest, expectedBinding);
                });
                if (recovered.isPresent()) return new BulkExecutionReservation(recovered.orElseThrow(), true);
            } catch (RuntimeException ignored) { /* safe failure below, without protected cause */ }
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
    }

    /** Protected enqueue; deliberately unavailable through the public SYNC reservation API. */
    BulkExecutionReservation enqueue(BulkFingerprintContext scope, UUID proposalId, String idempotencyKey,
            String supervisorId, String structuralRevision, Instant deadline, UUID queueTokenId) {
        requireNoAmbientTransaction(); requireScope(scope);
        Objects.requireNonNull(proposalId, "proposalId"); Objects.requireNonNull(queueTokenId, "queueTokenId");
        String keyDigest = BulkScopeDigests.idempotencyKeyDigest(text(idempotencyKey, "idempotencyKey"));
        String owner = text(supervisorId, "supervisorId"); String revision = text(structuralRevision, "structuralRevision");
        Instant bounded = micro(Objects.requireNonNull(deadline, "deadline"));
        try {
        ReservationWrite write = unitTransaction(connection -> {
            JdbcBulkCapacityOccupancy.lockMarker(connection);
            JdbcBulkCapacityOccupancy.requireBinding(connection, infrastructure, capacityBinding, false);
            if (tombstoneExists(connection, scope, keyDigest) || purgedProposal(connection, scope, proposalId))
                throw failure(BulkDurableExecutionException.Reason.RESULT_PURGED);
            Evaluation evaluation = loadEvaluation(connection, scope, proposalId, false);
            validateAsynchronousSubset(evaluation);
            String fingerprint = reservationFingerprint(evaluation, revision);
            Optional<BulkExecutionSnapshot> replay = findReservation(connection, scope, proposalId, keyDigest, fingerprint);
            if (replay.isPresent()) return new ReservationWrite(replay.orElseThrow(), false);
            JdbcBulkCapacityOccupancy.requireBinding(connection, infrastructure, capacityBinding, true);
            BulkQuotaLedger.lockProposal(connection, infrastructure, evaluation.proposal(), true, false);
            replay = findReservation(connection, scope, proposalId, keyDigest, fingerprint);
            if (replay.isPresent()) return new ReservationWrite(replay.orElseThrow(), false);
            BulkOperationControlExpectation expected = evaluation.proposal().controlExpectation();
            Instant now = clock(connection);
            if (!expected.structuralRevision().equals(revision)) throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
            if (!now.isBefore(evaluation.proposal().expiresAt())) throw failure(BulkDurableExecutionException.Reason.EXPIRED);
            if (!bounded.isAfter(now) || bounded.isAfter(now.plus(Duration.ofMinutes(30))))
                throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
            setDeadlineBudget(connection, bounded);
            JdbcBulkCapacityOccupancy.requireInstallation(connection, queueTokenId, "QUEUE");
            UUID executionId = UUID.randomUUID();
            try (var statement = connection.prepareStatement("""
                    insert into praxis_bulk.praxis_bulk_execution
                    (execution_id,proposal_id,namespace_id,subject_id,resource_key,operation_id,
                     idempotency_key_digest,reservation_fingerprint,input_fingerprint,evaluation_fingerprint,
                     structural_revision,control_generation,control_descriptor_fingerprint,owner_id,owner_epoch,
                     status,next_ordinal,target_count,deadline_at,created_at,updated_at,atomicity,protocol_version,
                     execution_mode,queue_token_id)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,'QUEUED',0,?,?,clock_timestamp(),clock_timestamp(),
                           'PER_ITEM',2,'ASYNC',?)
                    """)) {
                statement.setObject(1, executionId); statement.setObject(2, proposalId); bindScope(statement, scope, 3);
                statement.setString(7, keyDigest); statement.setString(8, fingerprint);
                statement.setString(9, evaluation.proposal().snapshot().fingerprint());
                statement.setString(10, evaluation.snapshot().fingerprint()); statement.setString(11, revision);
                statement.setLong(12, expected.generation()); statement.setString(13, expected.descriptorFingerprint());
                statement.setString(14, owner); statement.setInt(15, evaluation.snapshot().targets().size());
                statement.setObject(16, bounded.atOffset(ZoneOffset.UTC)); statement.setObject(17, queueTokenId);
                statement.executeUpdate();
            }
            return new ReservationWrite(snapshot(row(connection, executionId, false)), true);
        });
        return new BulkExecutionReservation(write.snapshot(), !write.created());
        } catch (BulkDurableExecutionException safe) { throw safe; }
        catch (RuntimeException unsafe) {
            try {
                Optional<BulkExecutionSnapshot> recovered = unitTransaction(connection -> {
                    Evaluation evaluation = loadEvaluation(connection, scope, proposalId, false);
                    validateAsynchronousSubset(evaluation);
                    return findReservation(connection, scope, proposalId, keyDigest,
                            reservationFingerprint(evaluation, revision));
                });
                if (recovered.isPresent()) return new BulkExecutionReservation(recovered.orElseThrow(), true);
            } catch (RuntimeException ignored) { /* no protected SQL details escape */ }
            throw capacityFailure(unsafe);
        }
    }

    /** A claim changes ownership only; this method never invokes a callback. */
    Optional<BulkExecutionReservation> claim(BulkFingerprintContext scope, UUID executionId,
            String workerId, UUID activeTokenId) {
        requireNoAmbientTransaction(); requireScope(scope);
        Objects.requireNonNull(executionId, "executionId"); Objects.requireNonNull(activeTokenId, "activeTokenId");
        String worker = text(workerId, "workerId");
        try {
        return unitTransaction(connection -> {
            JdbcBulkCapacityOccupancy.lockMarker(connection);
            JdbcBulkCapacityOccupancy.requireBinding(connection, infrastructure, capacityBinding, true);
            BulkExecutionSnapshot candidate = findScoped(connection, scope, executionId, false)
                    .orElseThrow(() -> failure(BulkDurableExecutionException.Reason.NOT_FOUND));
            Evaluation evaluation = loadEvaluation(connection, scope, candidate.proposalId(), false);
            validateAsynchronousSubset(evaluation);
            if (candidate.status() != BulkDurableExecutionStatus.QUEUED) return Optional.empty();
            JdbcBulkCapacityOccupancy.requireInstallation(connection, activeTokenId, "ACTIVE");
            try (var statement = connection.prepareStatement("select praxis_bulk.claim_capacity_execution(?,?,?,?,?)")) {
                statement.setObject(1, executionId); statement.setString(2, scope.namespaceId());
                statement.setString(3, worker); statement.setObject(4, activeTokenId);
                statement.setLong(5, candidate.control().epoch());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    boolean won = rows.getBoolean(1);
                    if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    if (!won) return Optional.empty();
                }
            }
            return Optional.of(new BulkExecutionReservation(snapshot(row(connection, executionId, false)), false));
        });
        } catch (BulkDurableExecutionException safe) { throw safe; }
        catch (RuntimeException unsafe) {
            try {
                Optional<BulkExecutionReservation> recovered = unitTransaction(connection -> {
                    ExecutionRow row = row(connection, executionId, false);
                    if (!scope.namespaceId().equals(row.namespaceId()) || !scope.subjectId().equals(row.subjectId())
                            || !scope.resourceKey().equals(row.resourceKey())
                            || !scope.operationRef().operationId().equals(row.operationId()))
                        throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
                    if (!worker.equals(row.ownerId()) || row.ownerEpoch() != 2
                            || !activeTokenId.equals(row.activeTokenId())) return Optional.empty();
                    validateReadOccupancy(connection, row);
                    return Optional.of(new BulkExecutionReservation(snapshot(row), true));
                });
                if (recovered.isPresent()) return recovered;
            } catch (RuntimeException ignored) { /* readback uncertainty is never a new claim */ }
            throw capacityFailure(unsafe);
        }
    }

    private static BulkDurableExecutionException capacityFailure(RuntimeException unsafe) {
        for (Throwable cause = unsafe; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && "53300".equals(sql.getSQLState()))
                return failure(BulkDurableExecutionException.Reason.CAPACITY);
        return failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
    }

    private static boolean purgedProposal(Connection connection, BulkFingerprintContext scope, UUID proposalId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                select 1 from praxis_bulk.praxis_bulk_tombstone where proposal_id=? and namespace_id=?
                  and resource_key=? and operation_id=? and authorization_scope_digest_version=?
                  and authorization_scope_digest=?
                """)) {
            statement.setObject(1, proposalId); statement.setString(2, scope.namespaceId());
            statement.setString(3, scope.resourceKey()); statement.setString(4, scope.operationRef().operationId());
            statement.setInt(5, BulkScopeDigests.VERSION);
            statement.setString(6, BulkScopeDigests.authorizationScopeDigest(scope.namespaceId(), scope.subjectId(),
                    scope.resourceKey(), scope.operationRef().operationId()));
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static void validateAsynchronousSubset(Evaluation evaluation) {
        JsonNode intent = evaluation.proposal().snapshot().intent();
        JsonNode targets = intent.at("/selection/targets");
        if (!"ASYNC".equals(intent.path("executionMode").asText())
                || evaluation.protocolVersion() != 2 || evaluation.proposal().controlExpectation() == null
                || evaluation.proposal().snapshot().mode() != BulkMode.UNIFORM_UPDATE
                || evaluation.proposal().snapshot().context().atomicity() != ActionCollectionAtomicity.PER_ITEM
                || !"EXPLICIT".equals(intent.at("/selection/mode").asText())
                || !targets.isArray() || targets.isEmpty() || targets.size() > 10000
                || targets.size() != evaluation.snapshot().targets().size()
                || !evaluation.snapshot().hasTypedEligibility()
                || evaluation.snapshot().targets().stream().anyMatch(target ->
                    target.eligibility().isEmpty() || !target.eligibility().orElseThrow().isExecutable()))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
    }

    private void requireCapacityUnit(Connection connection, ExecutionRow execution, Evaluation evaluation)
            throws SQLException {
        if ("ASYNC".equals(evaluation.proposal().snapshot().intent().path("executionMode").asText())) {
            validateAsynchronousSubset(evaluation);
            JdbcBulkCapacityOccupancy.requireActive(connection, infrastructure, capacityBinding,
                    execution.executionId(), execution.ownerEpoch());
        }
    }

    private QueryProbe probeQueryReservation(Connection connection, BulkFingerprintContext scope,
            UUID proposalId, String keyDigest, String owner, String revision, Instant deadline) throws SQLException {
        if (tombstoneExists(connection, scope, keyDigest))
            throw failure(BulkDurableExecutionException.Reason.RESULT_PURGED);
        Evaluation evaluation = loadEvaluation(connection, scope, proposalId, false);
        if (!"SYNC".equals(evaluation.proposal().snapshot().intent().path("executionMode").asText()))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (!query(evaluation))
            return new QueryProbe(false, Optional.empty(), reserveLoaded(connection, scope,
                    proposalId, keyDigest, owner, revision, deadline, null, evaluation));
        String binding = reservationFingerprint(evaluation, revision);
        return new QueryProbe(true, findReservation(connection, scope, proposalId, keyDigest, binding), null);
    }

    private record QueryProbe(boolean query, Optional<BulkExecutionSnapshot> existing,
            ReservationWrite explicitWrite) { }

    private static boolean query(Evaluation evaluation) {
        return "QUERY".equals(evaluation.proposal().snapshot().intent().at("/selection/mode").asText());
    }

    /**
     * Advances a scoped reservation synchronously through fresh, acknowledged units. The host
     * obtains this protected token from {@link #reserve} after resolving the authenticated scope;
     * it is not a public input or an independent subject-authorization boundary. Durable control
     * is reread before even a terminal no-op, and each unit retains its own transaction and fences.
     * A replay, uncertain outcome or non-running state ends this call without dispatching a suffix.
     * No public result is synthesized; authorized readers remain the source of execution results.
     *
     * @param reservation protected reservation obtained from the scoped reserve operation
     * @param admission current per-unit admission in the kernel-owned operational transaction
     * @param mutation domain mutation in the same transaction as its durable receipt
     */
    public void advance(BulkExecutionReservation reservation, BulkUnitAdmissionCallback admission,
            BulkUnitMutationCallback mutation) {
        requireNoAmbientTransaction();
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(mutation, "mutation");
        final boolean terminal;
        try {
            terminal = unitTransaction(connection -> {
                ExecutionRow execution = lockControl(connection, reservation.control());
                if (!execution.executionId().equals(reservation.executionId())
                        || !execution.proposalId().equals(reservation.proposalId())
                        || execution.targetCount() != reservation.targetCount()
                        || reservation.nextOrdinal() < 0
                        || reservation.nextOrdinal() > execution.nextOrdinal())
                    throw failure(BulkDurableExecutionException.Reason.CONFLICT);
                return execution.status() == BulkDurableExecutionStatus.COMPLETED
                        || execution.status() == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS
                        || execution.status() == BulkDurableExecutionStatus.STOPPED;
            });
        } catch (BulkDurableExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE);
        }
        if (terminal) return;

        int ordinal = reservation.nextOrdinal();
        while (ordinal < reservation.targetCount()) {
            BulkUnitExecutionResult result = executeUnit(reservation.control(), ordinal, admission, mutation);
            // Includes ACK readback after a later unit in this call: its suffix requires another
            // explicit request, even when the acknowledged durable state is already RUNNING.
            if (result.replayed() || result.status() != BulkDurableExecutionStatus.RUNNING) return;
            if (!result.durableResultPresent() || result.execution().nextOrdinal() != ordinal + 1)
                throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            ordinal = result.execution().nextOrdinal();
        }
    }

    public BulkUnitExecutionResult executeUnit(BulkExecutionControl control, int expectedOrdinal,
            BulkUnitAdmissionCallback admission, BulkUnitMutationCallback callback) {
        requireNoAmbientTransaction();
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(callback, "callback");
        if (expectedOrdinal < 0) throw new IllegalArgumentException("expectedOrdinal must be nonnegative");

        Preparation preparation;
        try {
            preparation = unitTransaction(connection -> prepare(connection, control, expectedOrdinal));
        } catch (BulkDurableExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            // No callback has run. A committed marker remains blocking; a rolled-back marker may
            // be prepared again only by an explicit retry of this same ordinal.
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }

        if (preparation.receipt() != null) {
            return acknowledgeReceipt(control, expectedOrdinal, preparation.receipt(), true);
        }
        if (preparation.admission() != null) return acknowledgeAdmission(control, preparation.admission(), true);
        if (preparation.stoppedSnapshot() != null)
            return result(control.executionId(), expectedOrdinal, null, BulkItemStatus.NOT_PROCESSED,
                    preparation.stoppedSnapshot().terminalReasonCode(), false, preparation.stoppedSnapshot());

        UnitWrite write;
        try {
            write = unitTransaction(connection ->
                    applyAndReceipt(connection, control, preparation.attempt(), admission, callback));
        } catch (RuntimeException error) {
            BulkDurableExecutionException.Reason knownReason = error instanceof BulkDurableExecutionException durable
                    ? durable.reason() : null;
            return resolveFailedUnit(control, preparation.attempt(), knownReason);
        }
        if (write.receipt() != null) return acknowledgeReceipt(control, expectedOrdinal, write.receipt(), false);
        if (write.admission() != null) return acknowledgeAdmission(control, write.admission(), false);
        return result(control.executionId(), expectedOrdinal, null, BulkItemStatus.NOT_PROCESSED,
                write.stopReason(), false, write.snapshot());
    }

    /** One set attempt, with one durable header and all child projections in the domain transaction. */
    public BulkAtomicExecutionResult executeAtomic(BulkExecutionControl control,
            BulkAtomicAdmissionCallback admission, BulkAtomicMutationCallback mutation) {
        requireNoAmbientTransaction();
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(mutation, "mutation");
        AtomicPreparation preparation;
        try {
            preparation = unitTransaction(connection -> prepareAtomic(connection, control));
        } catch (BulkDurableExecutionException error) { throw error; }
        catch (RuntimeException error) {
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
        if (preparation.header() != null)
            return acknowledgeAtomic(control, preparation.header(), true);
        if (preparation.stopped() != null)
            return new BulkAtomicExecutionResult(preparation.stopped(), true, false);

        AtomicHeader written;
        try {
            written = unitTransaction(connection -> applyAtomic(connection, control,
                    preparation.attempt(), admission, mutation));
        } catch (AtomicAdmissionRejected rejected) {
            return resolveFailedAtomic(control, preparation.attempt(), rejected.reason());
        } catch (CallbackFailure error) {
            return resolveFailedAtomic(control, preparation.attempt(), BulkUnitReasonCode.UNIT_ROLLED_BACK);
        } catch (BulkDurableExecutionException error) {
            if (error.reason() == BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED)
                return resolveFailedAtomic(control, preparation.attempt(), BulkUnitReasonCode.DEADLINE_EXCEEDED);
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        } catch (RuntimeException error) {
            // A SQL/commit failure may have lost its ACK. The receipt must be read before
            // any retry, and absence is not proof that a domain callback did not commit.
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
        return acknowledgeAtomic(control, written, false);
    }

    private AtomicHeader atomicHeader(Connection connection, UUID executionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select attempt_id,owner_epoch,set_digest,target_count,effect_count,effect_digest,
                       confirmed_at,unit_deadline_at
                  from praxis_bulk.praxis_bulk_atomic_receipt where execution_id=?
                """)) {
            statement.setObject(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                AtomicHeader header = new AtomicHeader((UUID) rows.getObject(1), rows.getLong(2),
                        rows.getString(3), rows.getInt(4), rows.getInt(5), rows.getString(6),
                        rows.getObject(7, OffsetDateTime.class).toInstant(),
                        rows.getObject(8, OffsetDateTime.class).toInstant());
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return header;
            }
        }
    }

    private String atomicSetDigest(Evaluation evaluation) {
        var targets = evaluation.snapshot().targets();
        var digests = new ArrayList<String>(targets.size());
        for (int ordinal = 0; ordinal < targets.size(); ordinal++)
            digests.add(targetDigest(evaluation, ordinal, targets.get(ordinal)));
        return BulkTargetDigest.setOf(evaluation.snapshot().fingerprint(), digests);
    }

    private boolean validAtomicHeader(Connection connection, ExecutionRow execution,
            Evaluation evaluation, AtomicHeader header) throws SQLException {
        if (execution.atomicity() != ActionCollectionAtomicity.ATOMIC || execution.protocolVersion() != 2
                || header.targetCount() != execution.targetCount() || header.ownerEpoch() > execution.ownerEpoch()
                || !header.setDigest().equals(atomicSetDigest(evaluation))
                || !header.confirmedAt().isBefore(header.unitDeadline())
                || !header.confirmedAt().isBefore(execution.deadlineAt())) return false;
        try (var statement = connection.prepareStatement("""
                select exists(select 1 from praxis_bulk.praxis_bulk_item_receipt r
                               where r.execution_id=?)
                    or exists(select 1 from praxis_bulk.praxis_bulk_admission a
                               where a.execution_id=?)
                    or exists(select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
                               where j.execution_id=?)
                    or exists(select 1 from praxis_bulk.praxis_bulk_atomic_effect_ref f
                               join praxis_bulk.praxis_bulk_atomic_item_result i
                                 on i.execution_id=f.execution_id and i.unit_ordinal=f.unit_ordinal
                              where f.execution_id=? and i.outcome='UNCHANGED')
                    or exists(select 1 from praxis_bulk.praxis_bulk_atomic_effect_ref f
                               where f.execution_id=? group by f.unit_ordinal having count(*)>8)
                """)) {
            for (int index = 1; index <= 5; index++) statement.setObject(index, execution.executionId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getBoolean(1) || rows.next()) return false;
            }
        }
        int ordinal = 0;
        try (var statement = connection.prepareStatement("""
                select unit_ordinal,target_digest,expected_version,outcome
                  from praxis_bulk.praxis_bulk_atomic_item_result
                 where execution_id=? order by unit_ordinal
                """)) {
            statement.setObject(1, execution.executionId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (ordinal >= execution.targetCount() || rows.getInt(1) != ordinal) return false;
                    var evidence = evaluation.snapshot().targets().get(ordinal);
                    if (!targetDigest(evaluation, ordinal, evidence).equals(rows.getString(2))
                            || !evidence.target().expectedVersion().equals(rows.getString(3))) return false;
                    try { BulkUnitOutcome.valueOf(rows.getString(4)); }
                    catch (RuntimeException invalid) { return false; }
                    ordinal++;
                }
            }
        }
        if (ordinal != execution.targetCount()) return false;
        List<BulkTargetDigest.EffectReference> effects = atomicEffects(connection, execution.executionId());
        if (header.effectCount() != effects.size()
                || !header.effectDigest().equals(BulkTargetDigest.effectsOf(effects))) return false;
        if (execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK)
            return execution.nextOrdinal() == 0
                    && header.attemptId().equals(execution.activeAttemptId())
                    && header.setDigest().equals(execution.activeSetDigest())
                    && Objects.equals(header.ownerEpoch(), execution.activeAttemptEpoch())
                    && header.unitDeadline().equals(execution.activeUnitDeadline());
        return execution.status() == BulkDurableExecutionStatus.COMPLETED
                && execution.nextOrdinal() == execution.targetCount()
                || execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED
                && execution.nextOrdinal() == 0;
    }

    private static List<BulkTargetDigest.EffectReference> atomicEffects(Connection connection,
            UUID executionId) throws SQLException {
        var effects = new ArrayList<BulkTargetDigest.EffectReference>();
        try (var statement = connection.prepareStatement("""
                select unit_ordinal,effect_ref from praxis_bulk.praxis_bulk_atomic_effect_ref
                 where execution_id=? order by unit_ordinal,effect_ref collate "C"
                """)) {
            statement.setObject(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (effects.size() >= 400)
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    effects.add(new BulkTargetDigest.EffectReference(rows.getInt(1), rows.getString(2)));
                }
            }
        }
        return effects;
    }

    private BulkAtomicExecutionResult acknowledgeAtomic(BulkExecutionControl control,
            AtomicHeader expected, boolean replayed) {
        try {
            BulkExecutionSnapshot state = unitTransaction(connection -> {
                ExecutionRow execution = lockControl(connection, control);
                Evaluation evaluation = loadEvaluation(connection, execution, false);
                AtomicHeader actual = atomicHeader(connection, execution.executionId());
                if (!Objects.equals(expected, actual) || !validAtomicHeader(connection, execution, evaluation, actual))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                if (execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK) {
                    try (var statement = connection.prepareStatement("""
                            update praxis_bulk.praxis_bulk_execution
                               set status='COMPLETED',next_ordinal=target_count,
                                   active_attempt_id=null,active_attempt_ordinal=null,
                                   active_target_digest=null,active_set_digest=null,
                                   active_attempt_epoch=null,active_unit_deadline_at=null,
                                   updated_at=clock_timestamp(),terminal_at=clock_timestamp()
                             where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                               and status='UNIT_COMMITTED_PENDING_ACK' and active_attempt_id=?
                            """)) {
                        statement.setObject(1, control.executionId());
                        statement.setString(2, infrastructure.namespace());
                        statement.setString(3, control.ownerId()); statement.setLong(4, control.epoch());
                        statement.setObject(5, expected.attemptId());
                        if (statement.executeUpdate() != 1)
                            throw failure(BulkDurableExecutionException.Reason.FENCED);
                    }
                    execution = row(connection, control.executionId(), false);
                }
                return snapshot(execution);
            });
            return new BulkAtomicExecutionResult(state, replayed, true);
        } catch (RuntimeException uncertain) {
            try {
                return unitTransaction(connection -> {
                    ExecutionRow execution = row(connection, control.executionId(), false);
                    Evaluation evaluation = loadEvaluation(connection, execution, false);
                    AtomicHeader actual = atomicHeader(connection, execution.executionId());
                    if (!Objects.equals(expected, actual)
                            || !validAtomicHeader(connection, execution, evaluation, actual))
                        throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
                    return new BulkAtomicExecutionResult(snapshot(execution), true, true);
                });
            } catch (RuntimeException unreadable) {
                throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
            }
        }
    }

    private BulkAtomicExecutionResult resolveFailedAtomic(BulkExecutionControl control,
            AtomicAttempt attempt, BulkUnitReasonCode reason) {
        try {
            return unitTransaction(connection -> {
                ExecutionRow execution = lockControl(connection, control);
                if (atomicHeader(connection, execution.executionId()) != null)
                    throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
                if (execution.status() != BulkDurableExecutionStatus.UNIT_IN_FLIGHT
                        || !attempt.attemptId().equals(execution.activeAttemptId())
                        || !attempt.setDigest().equals(execution.activeSetDigest())
                        || attempt.epoch() != execution.ownerEpoch())
                    throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
                BulkUnitReasonCode stop = reason == BulkUnitReasonCode.DEADLINE_EXCEEDED
                        ? reason : BulkUnitReasonCode.UNIT_ROLLED_BACK;
                try (var statement = connection.prepareStatement("""
                        insert into praxis_bulk.praxis_bulk_atomic_rejection
                          (execution_id,attempt_id,set_digest,reason_code,recorded_at)
                        values (?,?,?,?,clock_timestamp())
                        """)) {
                    statement.setObject(1, execution.executionId()); statement.setObject(2, attempt.attemptId());
                    statement.setString(3, attempt.setDigest()); statement.setString(4, reason.name());
                    if (statement.executeUpdate() != 1)
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                }
                try (var statement = connection.prepareStatement("""
                        update praxis_bulk.praxis_bulk_execution
                           set status='STOPPED',terminal_reason_code=?,
                               active_attempt_id=null,active_attempt_ordinal=null,
                               active_target_digest=null,active_set_digest=null,
                               active_attempt_epoch=null,active_unit_deadline_at=null,
                               updated_at=clock_timestamp(),terminal_at=clock_timestamp()
                         where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                           and status='UNIT_IN_FLIGHT' and active_attempt_id=?
                        """)) {
                    statement.setString(1, stop.name()); statement.setObject(2, execution.executionId());
                    statement.setString(3, infrastructure.namespace()); statement.setString(4, control.ownerId());
                    statement.setLong(5, control.epoch()); statement.setObject(6, attempt.attemptId());
                    if (statement.executeUpdate() != 1)
                        throw failure(BulkDurableExecutionException.Reason.FENCED);
                }
                return new BulkAtomicExecutionResult(snapshot(row(connection, execution.executionId(), false)),
                        false, false);
            });
        } catch (RuntimeException uncertain) {
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
    }

    private BulkConsistentExecutionRead inspectAtomic(Connection connection, BulkFingerprintContext scope,
            ExecutionRow execution, Evaluation evaluation) throws SQLException {
        AtomicHeader header = atomicHeader(connection, execution.executionId());
        boolean committed = execution.status() == BulkDurableExecutionStatus.COMPLETED;
        boolean pending = execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK;
        if ((committed || pending) != (header != null)
                && execution.status() != BulkDurableExecutionStatus.RECONCILIATION_REQUIRED)
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (header != null && !validAtomicHeader(connection, execution, evaluation, header))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (header == null && atomicItemCount(connection, execution.executionId()) != 0)
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (header == null && !validAtomicAbsence(connection, execution, evaluation))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (committed && execution.nextOrdinal() != execution.targetCount()
                || !committed && execution.nextOrdinal() != 0)
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        validateReadAllocations(connection, scope, execution);
        Instant[] times = readExecutionTimes(connection, execution.executionId());
        int confirmed = 0, unchanged = 0;
        if (committed) {
            try (var statement = connection.prepareStatement("""
                    select outcome,count(*) from praxis_bulk.praxis_bulk_atomic_item_result
                     where execution_id=? group by outcome
                    """)) {
                statement.setObject(1, execution.executionId());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if ("CONFIRMED".equals(rows.getString(1))) confirmed = rows.getInt(2);
                        else if ("UNCHANGED".equals(rows.getString(1))) unchanged = rows.getInt(2);
                        else throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    }
                }
            }
            if (confirmed + unchanged != execution.targetCount())
                throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        }
        int visible = committed ? execution.targetCount() : 0;
        BulkExecutionSnapshot certified = new BulkExecutionSnapshot(execution.executionId(),
                execution.proposalId(), execution.status(), visible, execution.targetCount(),
                visible, 0, execution.deadlineAt(), new BulkExecutionControl(execution.executionId(),
                execution.ownerId(), execution.ownerEpoch()), execution.terminalReasonCode(),
                execution.cancelRequestedAt());
        return new BulkConsistentExecutionRead(BulkConsistentExecutionRead.Kind.LIVE,
                certified, times[0], times[1], times[2], confirmed, unchanged, 0, 0, 0,
                execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED
                        ? execution.targetCount() : 0, null);
    }

    private static int atomicItemCount(Connection connection, UUID executionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*) from praxis_bulk.praxis_bulk_atomic_item_result where execution_id=?
                """)) {
            statement.setObject(1, executionId);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private BulkExecutionSnapshot requestAtomicCancel(Connection connection, ExecutionRow execution,
            Evaluation evaluation) throws SQLException {
        AtomicHeader header = atomicHeader(connection, execution.executionId());
        if (header != null && !validAtomicHeader(connection, execution, evaluation, header))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (execution.status() == BulkDurableExecutionStatus.RUNNING && header == null) {
            try (var statement = connection.prepareStatement("""
                    update praxis_bulk.praxis_bulk_execution
                       set status='STOPPED',terminal_reason_code='CANCELLED_BY_USER',
                           cancel_requested_at=clock_timestamp(),terminal_at=clock_timestamp(),
                           updated_at=clock_timestamp()
                     where execution_id=? and namespace_id=? and owner_epoch=? and status='RUNNING'
                    """)) {
                statement.setObject(1, execution.executionId());
                statement.setString(2, infrastructure.namespace());
                statement.setLong(3, execution.ownerEpoch());
                if (statement.executeUpdate() != 1)
                    throw failure(BulkDurableExecutionException.Reason.FENCED);
            }
        } else {
            try (var statement = connection.prepareStatement("""
                    update praxis_bulk.praxis_bulk_execution
                       set status='RECONCILIATION_REQUIRED',cancel_requested_at=clock_timestamp(),
                           updated_at=clock_timestamp()
                     where execution_id=? and namespace_id=? and owner_epoch=?
                       and status in ('UNIT_IN_FLIGHT','UNIT_COMMITTED_PENDING_ACK','RECONCILIATION_REQUIRED')
                       and cancel_requested_at is null
                    """)) {
                statement.setObject(1, execution.executionId());
                statement.setString(2, infrastructure.namespace());
                statement.setLong(3, execution.ownerEpoch());
                if (statement.executeUpdate() != 1)
                    throw failure(BulkDurableExecutionException.Reason.FENCED);
            }
        }
        return snapshot(row(connection, execution.executionId(), false));
    }

    private BulkExecutionRecovery recoverAtomic(Connection connection, ExecutionRow execution,
            Evaluation evaluation, String owner) throws SQLException {
        AtomicHeader header = atomicHeader(connection, execution.executionId());
        if (header != null && !validAtomicHeader(connection, execution, evaluation, header))
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        if (header == null && atomicItemCount(connection, execution.executionId()) != 0)
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        if (header == null && !validAtomicAbsence(connection, execution, evaluation))
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        if (terminal(execution.status())) return new BulkExecutionRecovery(snapshot(execution));
        if (execution.status() == BulkDurableExecutionStatus.UNIT_IN_FLIGHT
                && execution.activeUnitDeadline() != null
                && clock(connection).isBefore(execution.activeUnitDeadline()))
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        if (execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK && header == null)
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        long epoch = Math.addExact(execution.ownerEpoch(), 1);
        boolean committed = header != null;
        String status = committed ? "COMPLETED" : "STOPPED";
        String reason = committed ? null : execution.cancelRequestedAt() != null
                ? "CANCELLED_BY_USER" : "RECOVERY_STOPPED";
        try (var statement = connection.prepareStatement("""
                update praxis_bulk.praxis_bulk_execution
                   set owner_id=?,owner_epoch=?,status=?,next_ordinal=?,terminal_reason_code=?,
                       active_attempt_id=null,active_attempt_ordinal=null,
                       active_target_digest=null,active_set_digest=null,
                       active_attempt_epoch=null,active_unit_deadline_at=null,
                       updated_at=clock_timestamp(),terminal_at=clock_timestamp()
                 where execution_id=? and namespace_id=? and owner_epoch=?
                """)) {
            statement.setString(1, owner); statement.setLong(2, epoch); statement.setString(3, status);
            statement.setInt(4, committed ? execution.targetCount() : 0);
            statement.setString(5, reason); statement.setObject(6, execution.executionId());
            statement.setString(7, infrastructure.namespace()); statement.setLong(8, execution.ownerEpoch());
            if (statement.executeUpdate() != 1)
                throw failure(BulkDurableExecutionException.Reason.FENCED);
        }
        return new BulkExecutionRecovery(snapshot(row(connection, execution.executionId(), false)));
    }

    private boolean validAtomicAbsence(Connection connection, ExecutionRow execution,
            Evaluation evaluation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select attempt_id,set_digest,reason_code
                  from praxis_bulk.praxis_bulk_atomic_rejection where execution_id=?
                """)) {
            statement.setObject(1, execution.executionId());
            try (ResultSet rows = statement.executeQuery()) {
                if (execution.status() != BulkDurableExecutionStatus.STOPPED)
                    return !rows.next();
                if (rows.next()) {
                    if (rows.getObject(1, UUID.class) == null
                            || !atomicSetDigest(evaluation).equals(rows.getString(2))) return false;
                    String reason = rows.getString(3);
                    if (execution.terminalReasonCode() == BulkUnitReasonCode.DEADLINE_EXCEEDED) {
                        if (!"DEADLINE_EXCEEDED".equals(reason)) return false;
                    } else if (execution.terminalReasonCode() != BulkUnitReasonCode.UNIT_ROLLED_BACK
                            || "DEADLINE_EXCEEDED".equals(reason)) return false;
                    if (rows.next()) return false;
                } else if (execution.terminalReasonCode() == BulkUnitReasonCode.UNIT_ROLLED_BACK)
                    return false;
            }
        }
        try (var statement = connection.prepareStatement("""
                select praxis_bulk.atomic_evidence_complete(?,0)
                """)) {
            statement.setObject(1, execution.executionId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1) && !rows.next();
            }
        }
    }

    private AtomicPreparation prepareAtomic(Connection connection, BulkExecutionControl control)
            throws SQLException {
        ExecutionRow execution = lockControl(connection, control);
        if (execution.atomicity() != ActionCollectionAtomicity.ATOMIC || execution.protocolVersion() != 2)
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        AtomicHeader header = atomicHeader(connection, execution.executionId());
        Evaluation evaluation = loadEvaluation(connection, execution, false);
        if (header != null) {
            if (!validAtomicHeader(connection, execution, evaluation, header))
                throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
            return new AtomicPreparation(null, header, null);
        }
        if (execution.status() == BulkDurableExecutionStatus.STOPPED
                && execution.nextOrdinal() == 0) {
            if (atomicItemCount(connection, execution.executionId()) != 0
                    || !validAtomicAbsence(connection, execution, evaluation))
                throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            return new AtomicPreparation(null, null, snapshot(execution));
        }
        if (execution.status() != BulkDurableExecutionStatus.RUNNING
                || execution.nextOrdinal() != 0 || execution.activeAttemptId() != null)
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        if (execution.cancelRequestedAt() != null)
            return new AtomicPreparation(null, null, finishCancelled(connection, execution));
        validateExecutionSubset(evaluation);
        BulkOrdinalManifest.validateOne(connection, evaluation.snapshot());
        requireReadyControlFence(connection, execution, evaluation);
        requireCapacityUnit(connection, execution, evaluation);
        if (!clock(connection).isBefore(execution.deadlineAt())) {
            try (var statement = connection.prepareStatement("""
                    update praxis_bulk.praxis_bulk_execution
                       set status='STOPPED',terminal_reason_code='DEADLINE_EXCEEDED',
                           terminal_at=clock_timestamp(),updated_at=clock_timestamp()
                     where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                       and status='RUNNING' and next_ordinal=0 and atomicity='ATOMIC'
                    """)) {
                statement.setObject(1, execution.executionId());
                statement.setString(2, infrastructure.namespace());
                statement.setString(3, control.ownerId()); statement.setLong(4, control.epoch());
                if (statement.executeUpdate() != 1)
                    throw failure(BulkDurableExecutionException.Reason.FENCED);
            }
            return new AtomicPreparation(null, null,
                    snapshot(row(connection, execution.executionId(), false)));
        }
        String setDigest = atomicSetDigest(evaluation);
        UUID attemptId = UUID.randomUUID();
        Instant unitDeadline;
        try (var statement = connection.prepareStatement("""
                with attempt_clock as (select clock_timestamp() as started_at)
                update praxis_bulk.praxis_bulk_execution e
                set status='UNIT_IN_FLIGHT',active_attempt_id=?,active_set_digest=?,
                    active_attempt_epoch=owner_epoch,
                    active_unit_deadline_at=least(e.deadline_at,
                        attempt_clock.started_at + (? * interval '1 millisecond')),
                    updated_at=attempt_clock.started_at
                from attempt_clock
                where e.execution_id=? and e.namespace_id=? and e.owner_id=? and e.owner_epoch=?
                  and e.atomicity='ATOMIC' and e.protocol_version=2 and e.status='RUNNING'
                  and e.next_ordinal=0 and attempt_clock.started_at<e.deadline_at
                returning e.active_unit_deadline_at
                """)) {
            statement.setObject(1, attemptId); statement.setString(2, setDigest);
            statement.setLong(3, UNIT_BUDGET.toMillis()); statement.setObject(4, control.executionId());
            statement.setString(5, infrastructure.namespace()); statement.setString(6, control.ownerId());
            statement.setLong(7, control.epoch());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
                unitDeadline = rows.getObject(1, OffsetDateTime.class).toInstant();
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        long started = System.nanoTime();
        long remaining = Duration.between(clock(connection), unitDeadline).toNanos()
                - Math.max(0, System.nanoTime() - started);
        if (remaining <= 0) throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        return new AtomicPreparation(new AtomicAttempt(attemptId, setDigest, control.epoch(),
                unitDeadline, System.nanoTime() + remaining), null, null);
    }

    private AtomicHeader applyAtomic(Connection connection, BulkExecutionControl control,
            AtomicAttempt attempt, BulkAtomicAdmissionCallback admissionCallback,
            BulkAtomicMutationCallback mutationCallback) throws SQLException {
        ExecutionRow execution = lockControl(connection, control);
        if (execution.atomicity() != ActionCollectionAtomicity.ATOMIC
                || execution.status() != BulkDurableExecutionStatus.UNIT_IN_FLIGHT
                || !attempt.attemptId().equals(execution.activeAttemptId())
                || !attempt.setDigest().equals(execution.activeSetDigest())
                || attempt.epoch() != execution.ownerEpoch()
                || !attempt.unitDeadline().equals(execution.activeUnitDeadline()))
            throw failure(BulkDurableExecutionException.Reason.FENCED);
        if (atomicHeader(connection, execution.executionId()) != null)
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        if (execution.cancelRequestedAt() != null)
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        Evaluation evaluation = loadEvaluation(connection, execution, false);
        validateExecutionSubset(evaluation);
        BulkOrdinalManifest.validateOne(connection, evaluation.snapshot());
        if (!attempt.setDigest().equals(atomicSetDigest(evaluation)))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        requireReadyControlFence(connection, execution, evaluation);
        setDeadlineBudget(connection, min(execution.deadlineAt(), attempt.unitDeadline()));
        var units = new ArrayList<BulkExecutionUnit>(execution.targetCount());
        for (int ordinal = 0; ordinal < execution.targetCount(); ordinal++)
            units.add(new BulkExecutionUnit(execution.executionId(), attempt.attemptId(), ordinal,
                    evaluation.snapshot().targets().get(ordinal), evaluation.proposal().snapshot(),
                    evaluation.snapshot().governance(), execution.deadlineAt(), attempt.unitDeadline(),
                    attempt.monotonicDeadlineNanos(), control));
        BulkAtomicExecutionSet set = new BulkAtomicExecutionSet(execution.executionId(),
                attempt.attemptId(), attempt.setDigest(), units, attempt.unitDeadline(),
                attempt.monotonicDeadlineNanos());
        BulkAtomicAdmission admission;
        try { admission = Objects.requireNonNull(admissionCallback.admit(set), "atomic admission"); }
        catch (RuntimeException error) { throw new CallbackFailure(); }
        setDeadlineBudget(connection, min(execution.deadlineAt(), attempt.unitDeadline()));
        if (!admission.admitted()) {
            // Even an admission callback can have dirtied the operational transaction.
            // Abort it first; a separate fenced transaction records only the rejection.
            throw new AtomicAdmissionRejected(admission.rejection());
        }
        BulkAtomicMutationResult result;
        try { result = Objects.requireNonNull(mutationCallback.apply(set), "atomic mutation"); }
        catch (RuntimeException error) { throw new CallbackFailure(); }
        if (result.items().size() != execution.targetCount()) throw new CallbackFailure();
        var declaredEffects = new ArrayList<BulkTargetDigest.EffectReference>();
        for (BulkAtomicMutationResult.Item outcome : result.items())
            for (String reference : outcome.effectReferences())
                declaredEffects.add(new BulkTargetDigest.EffectReference(outcome.ordinal(), reference));
        String effectDigest = BulkTargetDigest.effectsOf(declaredEffects);
        requireAtomicAppendBudget(connection, execution, attempt);
        Instant confirmedAt;
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_atomic_receipt
                  (execution_id,attempt_id,owner_epoch,set_digest,target_count,effect_count,
                   effect_digest,confirmed_at,unit_deadline_at)
                select execution_id,active_attempt_id,owner_epoch,active_set_digest,target_count,?,?,
                       clock_timestamp(),active_unit_deadline_at
                  from praxis_bulk.praxis_bulk_execution
                 where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                   and atomicity='ATOMIC' and status='UNIT_IN_FLIGHT' and active_attempt_id=?
                   and clock_timestamp()<deadline_at and clock_timestamp()<active_unit_deadline_at
                returning confirmed_at
                """)) {
            statement.setInt(1, declaredEffects.size()); statement.setString(2, effectDigest);
            statement.setObject(3, execution.executionId()); statement.setString(4, infrastructure.namespace());
            statement.setString(5, control.ownerId()); statement.setLong(6, control.epoch());
            statement.setObject(7, attempt.attemptId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
                confirmedAt = rows.getObject(1, OffsetDateTime.class).toInstant();
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        try (var item = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_atomic_item_result
                  (execution_id,unit_ordinal,target_digest,expected_version,outcome)
                values (?,?,?,?,?)
                """); var effect = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_atomic_effect_ref
                  (execution_id,unit_ordinal,effect_ref) values (?,?,?)
                """)) {
            for (int ordinal = 0; ordinal < execution.targetCount(); ordinal++) {
                requireAtomicAppendBudget(connection, execution, attempt);
                BulkAtomicMutationResult.Item outcome = result.items().get(ordinal);
                var target = evaluation.snapshot().targets().get(ordinal).target();
                item.setObject(1, execution.executionId()); item.setInt(2, ordinal);
                item.setString(3, targetDigest(evaluation, ordinal,
                        evaluation.snapshot().targets().get(ordinal)));
                item.setString(4, target.expectedVersion()); item.setString(5, outcome.outcome().name());
                if (item.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                for (String reference : outcome.effectReferences()) {
                    requireAtomicMonotonicBudget(attempt);
                    effect.setObject(1, execution.executionId()); effect.setInt(2, ordinal);
                    effect.setString(3, reference);
                    if (effect.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                }
            }
        }
        requireAtomicAppendBudget(connection, execution, attempt);
        try (var statement = connection.prepareStatement("""
                update praxis_bulk.praxis_bulk_execution
                   set status='UNIT_COMMITTED_PENDING_ACK',updated_at=clock_timestamp()
                 where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                   and status='UNIT_IN_FLIGHT' and active_attempt_id=?
                   and clock_timestamp()<deadline_at and clock_timestamp()<active_unit_deadline_at
                """)) {
            statement.setObject(1, execution.executionId()); statement.setString(2, infrastructure.namespace());
            statement.setString(3, control.ownerId()); statement.setLong(4, control.epoch());
            statement.setObject(5, attempt.attemptId());
            if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        }
        // A slow transition trigger cannot make an over-budget set appear committed.
        requireAtomicMonotonicBudget(attempt);
        if (!clock(connection).isBefore(min(execution.deadlineAt(), attempt.unitDeadline())))
            throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        return new AtomicHeader(attempt.attemptId(), attempt.epoch(), attempt.setDigest(),
                execution.targetCount(), declaredEffects.size(), effectDigest,
                confirmedAt, attempt.unitDeadline());
    }

    public Optional<BulkExecutionSnapshot> find(BulkFingerprintContext scope, UUID executionId) {
        requireNoAmbientTransaction();
        requireScope(scope);
        Objects.requireNonNull(executionId, "executionId");
        try {
            return transaction(connection -> findScoped(connection, scope, executionId, false));
        } catch (BulkDurableExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE);
        }
    }

    /** Internal RS3 foundation; the host must authorize before any future public projection. */
    BulkConsistentExecutionRead inspectConsistent(BulkFingerprintContext scope, UUID executionId) {
        requireNoAmbientTransaction();
        requireScope(scope);
        Objects.requireNonNull(executionId, "executionId");
        try {
            return infrastructure.withConsistentRead(connection -> inspectConsistent(connection, scope, executionId));
        } catch (BulkDurableExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE);
        }
    }

    /** Internal summary only; the host must authorize before deriving a response. */
    BulkExecutionSummary summarizeConsistent(BulkFingerprintContext scope, UUID executionId) {
        return BulkExecutionSummary.from(inspectConsistent(scope, executionId));
    }

    BulkConsistentExecutionRead inspectConsistent(Connection connection, BulkFingerprintContext scope,
            UUID executionId) throws SQLException {
        Optional<BulkExecutionSnapshot> scoped = findScoped(connection, scope, executionId, false);
        String tombstone = scopedTombstone(connection, scope, executionId);
        if (scoped.isEmpty()) return tombstone == null
                ? BulkConsistentExecutionRead.absent() : BulkConsistentExecutionRead.tombstone(tombstone);
        if (tombstone != null) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        ExecutionRow execution = row(connection, executionId, false);
        if (!execution.proposalId().equals(scoped.orElseThrow().proposalId())
                || !scope.namespaceId().equals(execution.namespaceId())
                || !scope.subjectId().equals(execution.subjectId())
                || !scope.resourceKey().equals(execution.resourceKey())
                || !scope.operationRef().operationId().equals(execution.operationId()))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        Evaluation evaluation = loadEvaluation(connection, execution, false);
        if (evaluation.snapshot().targets().size() != execution.targetCount())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        try {
            validateExecutionSubset(evaluation);
            BulkOrdinalManifest.validateOne(connection, evaluation.snapshot());
        } catch (RuntimeException invalid) {
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        }
        if (execution.atomicity() == ActionCollectionAtomicity.ATOMIC)
            return inspectAtomic(connection, scope, execution, evaluation);
        List<Receipt> receipts = receipts(connection, executionId);
        List<AdmissionRecord> admissions = admissions(connection, executionId);
        if (receipts.size() != execution.receiptCount()
                || admissions.size() != execution.admissionCount()
                || !readEvidenceConsistent(receipts, admissions, execution, evaluation))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        validateReadState(execution, evaluation, receipts, admissions);
        validateReadAllocations(connection, scope, execution);
        Instant[] times = readExecutionTimes(connection, executionId);
        int confirmed = 0, unchanged = 0, denied = 0, invalid = 0, conflict = 0;
        boolean reconciling = execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED;
        int certifiedReceipts = 0, certifiedAdmissions = 0;
        for (Receipt receipt : receipts) {
            // A durable receipt awaiting ACK is not yet part of the certified prefix either.
            if (receipt.ordinal() >= execution.nextOrdinal()) continue;
            certifiedReceipts++;
            if (receipt.outcome() == BulkUnitOutcome.CONFIRMED) confirmed++;
            else unchanged++;
        }
        for (AdmissionRecord admission : admissions) {
            if (admission.ordinal() >= execution.nextOrdinal()) continue;
            certifiedAdmissions++;
            switch (admission.status()) {
                case DENIED -> denied++;
                case INVALID -> invalid++;
                case CONFLICT -> conflict++;
                default -> throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        BulkExecutionSnapshot certified = new BulkExecutionSnapshot(execution.executionId(),
                execution.proposalId(), execution.status(), execution.nextOrdinal(), execution.targetCount(),
                certifiedReceipts, certifiedAdmissions, execution.deadlineAt(),
                new BulkExecutionControl(execution.executionId(), execution.ownerId(), execution.ownerEpoch()),
                execution.terminalReasonCode(), execution.cancelRequestedAt());
        return new BulkConsistentExecutionRead(BulkConsistentExecutionRead.Kind.LIVE,
                certified, times[0], times[1], times[2],
                confirmed, unchanged, denied, invalid, conflict,
                reconciling ? execution.targetCount() - execution.nextOrdinal() : 0, null);
    }

    /** Same-snapshot RS3 projection for an authorized compositor that owns the connection boundary. */
    BulkExecutionSummary summarizeConsistent(Connection connection, BulkFingerprintContext scope,
            UUID executionId) throws SQLException {
        requireScope(scope);
        Objects.requireNonNull(executionId, "executionId");
        return BulkExecutionSummary.from(inspectConsistent(connection, scope, executionId));
    }

    /**
     * Admits one durable, idempotent cancellation request after host authorization.
     * It never invokes a domain callback or treats a lock timeout as evidence of rollback.
     */
    public BulkExecutionSnapshot requestCancel(BulkFingerprintContext scope, UUID executionId) {
        requireNoAmbientTransaction();
        requireScope(scope);
        Objects.requireNonNull(executionId, "executionId");
        try {
            return unitTransaction(connection -> requestCancel(connection, scope, executionId));
        } catch (BulkDurableExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            // The request commit may have succeeded. A later scoped retry resolves it without
            // manufacturing a successful acknowledgement from an uncertain transaction.
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
    }

    public BulkExecutionRecovery recover(BulkFingerprintContext scope, UUID executionId,
            String recoveryOwner) {
        requireNoAmbientTransaction();
        requireScope(scope);
        Objects.requireNonNull(executionId, "executionId");
        String owner = text(recoveryOwner, "recoveryOwner");
        try {
            return unitTransaction(connection -> recover(connection, scope, executionId, owner));
        } catch (BulkDurableExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE);
        }
    }

    private BulkExecutionSnapshot requestCancel(Connection connection, BulkFingerprintContext scope,
            UUID executionId) throws SQLException {
        ExecutionRow execution = lockLifecycle(connection, scope, executionId);
        if (terminal(execution.status()) || execution.cancelRequestedAt() != null)
            return snapshot(execution);
        Evaluation evaluation = loadEvaluation(connection, execution, false);
        if (execution.atomicity() == ActionCollectionAtomicity.ATOMIC)
            return requestAtomicCancel(connection, execution, evaluation);
        boolean prefixConsistent = durablePrefixConsistent(connection, execution, evaluation);
        if ((execution.status() == BulkDurableExecutionStatus.RUNNING
                || execution.status() == BulkDurableExecutionStatus.QUEUED)
                && prefixConsistent) {
            try (var statement = connection.prepareStatement("""
                    with terminal_clock as materialized (select clock_timestamp() as observed_at)
                    update praxis_bulk.praxis_bulk_execution
                    set cancel_requested_at=terminal_clock.observed_at, status='STOPPED',
                        terminal_reason_code='CANCELLED_BY_USER', terminal_at=terminal_clock.observed_at,
                        updated_at=terminal_clock.observed_at
                    from terminal_clock
                    where execution_id=? and namespace_id=? and status in ('RUNNING','QUEUED')
                      and cancel_requested_at is null and owner_epoch=?
                    """)) {
                statement.setObject(1, executionId);
                statement.setString(2, infrastructure.namespace());
                statement.setLong(3, execution.ownerEpoch());
                if (statement.executeUpdate() != 1)
                    throw failure(BulkDurableExecutionException.Reason.FENCED);
            }
        } else {
            try (var statement = connection.prepareStatement("""
                    update praxis_bulk.praxis_bulk_execution
                    set cancel_requested_at=clock_timestamp(),
                        status=case when ? then status else 'RECONCILIATION_REQUIRED' end,
                        updated_at=clock_timestamp()
                    where execution_id=? and namespace_id=? and cancel_requested_at is null
                      and owner_epoch=?
                    """)) {
                statement.setBoolean(1, prefixConsistent);
                statement.setObject(2, executionId);
                statement.setString(3, infrastructure.namespace());
                statement.setLong(4, execution.ownerEpoch());
                if (statement.executeUpdate() != 1)
                    throw failure(BulkDurableExecutionException.Reason.FENCED);
            }
        }
        return snapshot(row(connection, executionId, false));
    }

    private static boolean terminal(BulkDurableExecutionStatus status) {
        return status == BulkDurableExecutionStatus.COMPLETED
                || status == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS
                || status == BulkDurableExecutionStatus.STOPPED;
    }

    private ReservationWrite reserve(Connection connection, BulkFingerprintContext scope, UUID proposalId,
            String keyDigest, String owner, String revision, Instant deadline,
            BulkOperationLifecycle.ReadyAdmission queryAdmission) throws SQLException {
        if (tombstoneExists(connection, scope, keyDigest))
            throw failure(BulkDurableExecutionException.Reason.RESULT_PURGED);
        Evaluation evaluation = loadEvaluation(connection, scope, proposalId, false);
        return reserveLoaded(connection, scope, proposalId, keyDigest, owner, revision, deadline,
                queryAdmission, evaluation);
    }

    private static boolean tombstoneExists(Connection connection, BulkFingerprintContext scope,
            String keyDigest) throws SQLException {
        String authorizationDigest = BulkScopeDigests.authorizationScopeDigest(scope.namespaceId(),
                scope.subjectId(), scope.resourceKey(), scope.operationRef().operationId());
        return BulkQuotaLedger.tombstoneExists(connection, scope, authorizationDigest, keyDigest);
    }

    private ReservationWrite reserveLoaded(Connection connection, BulkFingerprintContext scope, UUID proposalId,
            String keyDigest, String owner, String revision, Instant deadline,
            BulkOperationLifecycle.ReadyAdmission queryAdmission, Evaluation evaluation) throws SQLException {
        requireSynchronous(evaluation);
        JdbcBulkCapacityOccupancy.lockMarker(connection);
        BulkOperationControlExpectation expectation = evaluation.proposal().controlExpectation();
        String reservationFingerprint = reservationFingerprint(evaluation, revision);
        if (reservationExists(connection, scope, proposalId, keyDigest)) {
            Optional<BulkExecutionSnapshot> existing = findReservation(connection, scope, proposalId,
                    keyDigest, reservationFingerprint);
            if (existing.isEmpty()) throw failure(BulkDurableExecutionException.Reason.CONFLICT);
            return new ReservationWrite(existing.orElseThrow(), false);
        }
        if (query(evaluation) && !matchesQueryAdmission(queryAdmission, evaluation))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (!query(evaluation) && queryAdmission != null)
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        BulkQuotaLedger.Scope quota;
        try {
            quota = BulkQuotaLedger.lockProposal(connection, infrastructure, evaluation.proposal(), true, false);
        } catch (BulkProposalStorageException error) {
            if (error.reason() == BulkProposalStorageException.Reason.CAPACITY)
                throw failure(BulkDurableExecutionException.Reason.CAPACITY);
            throw failure(error.reason() == BulkProposalStorageException.Reason.CORRUPT
                    ? BulkDurableExecutionException.Reason.CORRUPT
                    : BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        }
        // A reservation which committed while this transaction waited for the deployment
        // bucket wins before quota checks; idempotent replay is never blocked by saturation.
        Optional<BulkExecutionSnapshot> existing = findReservation(connection, scope, proposalId,
                keyDigest, reservationFingerprint);
        if (existing.isPresent()) return new ReservationWrite(existing.orElseThrow(), false);
        // Historical protocol-1 proposals remain readable/replayable, but cannot create
        // a new protocol-2 execution or enter its callback path.
        if (evaluation.protocolVersion() != 2)
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (expectation == null || !expectation.structuralRevision().equals(revision))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (!"SYNC".equals(evaluation.proposal().snapshot().intent().path("executionMode").asText()))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        validateExecutionSubset(evaluation);
        Instant databaseNow = clock(connection);
        if (!databaseNow.isBefore(evaluation.proposal().expiresAt()))
            throw failure(BulkDurableExecutionException.Reason.EXPIRED);
        if (!deadline.isAfter(databaseNow)) throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        BulkQuotaLedger.requireExecutionRoom(connection, quota);
        UUID executionId = UUID.randomUUID();
        int inserted;
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_execution
                (execution_id, proposal_id, namespace_id, subject_id, resource_key, operation_id,
                 idempotency_key_digest, reservation_fingerprint, input_fingerprint,
                 evaluation_fingerprint, structural_revision, control_generation,
                 control_descriptor_fingerprint, owner_id, owner_epoch, status,
                 next_ordinal, target_count, deadline_at, created_at, updated_at,
                 atomicity, protocol_version, execution_mode)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'RUNNING', 0, ?, ?, clock_timestamp(), clock_timestamp(), ?, 2, 'SYNC')
                on conflict do nothing
                """)) {
            statement.setObject(1, executionId); statement.setObject(2, proposalId);
            statement.setString(3, scope.namespaceId()); statement.setString(4, scope.subjectId());
            statement.setString(5, scope.resourceKey()); statement.setString(6, scope.operationRef().operationId());
            statement.setString(7, keyDigest); statement.setString(8, reservationFingerprint);
            statement.setString(9, evaluation.proposal().snapshot().fingerprint());
            statement.setString(10, evaluation.snapshot().fingerprint()); statement.setString(11, revision);
            if (expectation == null) {
                statement.setNull(12, java.sql.Types.BIGINT);
                statement.setNull(13, java.sql.Types.VARCHAR);
            } else {
                statement.setLong(12, expectation.generation());
                statement.setString(13, expectation.descriptorFingerprint());
            }
            statement.setString(14, owner); statement.setInt(15, evaluation.snapshot().targets().size());
            statement.setObject(16, deadline.atOffset(ZoneOffset.UTC));
            statement.setString(17, evaluation.proposal().snapshot().context().atomicity().name());
            inserted = statement.executeUpdate();
        }
        if (inserted == 1) BulkQuotaLedger.activateExecution(connection, scope, proposalId, executionId, quota);
        Optional<BulkExecutionSnapshot> selected = findReservation(connection, scope, proposalId, keyDigest,
                reservationFingerprint);
        if (selected.isEmpty()) throw failure(BulkDurableExecutionException.Reason.CONFLICT);
        return new ReservationWrite(selected.orElseThrow(), inserted == 1);
    }

    private static boolean matchesQueryAdmission(BulkOperationLifecycle.ReadyAdmission admission,
            Evaluation evaluation) {
        if (admission == null) return false;
        var proposal = evaluation.proposal();
        var scope = proposal.snapshot().context();
        return admission.mode() == BulkMode.UNIFORM_UPDATE
                && proposal.snapshot().mode() == BulkMode.UNIFORM_UPDATE
                && admission.atomicity() == ActionCollectionAtomicity.PER_ITEM
                && scope.atomicity() == admission.atomicity()
                && admission.executionMode() == BulkExecutionMode.SYNC
                && admission.selectionMode() == BulkSelectionMode.QUERY
                && admission.identity().namespaceId().equals(scope.namespaceId())
                && admission.identity().confirmationOperationId().equals(scope.operationRef().operationId())
                && admission.expectation().equals(proposal.controlExpectation())
                && evaluation.snapshot().targets().size() <= admission.maxTargets();
    }

    private boolean reservationExists(Connection connection, BulkFingerprintContext scope, UUID proposalId,
            String keyDigest) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select exists (
                    select 1 from praxis_bulk.praxis_bulk_execution e
                     where e.namespace_id=? and e.subject_id=? and e.resource_key=? and e.operation_id=?
                       and (e.proposal_id=? or e.idempotency_key_digest=?)
                )
                """)) {
            bindScope(statement, scope, 1);
            statement.setObject(5, proposalId);
            statement.setString(6, keyDigest);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }

    private Optional<BulkExecutionSnapshot> findReservation(Connection connection, BulkFingerprintContext scope,
            UUID proposalId, String keyDigest, String expectedBinding) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select e.*, (case when e.atomicity='ATOMIC' then
                            (select count(*) from praxis_bulk.praxis_bulk_atomic_item_result r
                             where r.execution_id=e.execution_id)
                        else (select count(*) from praxis_bulk.praxis_bulk_item_receipt r
                             where r.execution_id=e.execution_id) end) receipt_count,
                       (select count(*) from praxis_bulk.praxis_bulk_admission a where a.execution_id=e.execution_id) admission_count
                from praxis_bulk.praxis_bulk_execution e
                where e.namespace_id=? and e.subject_id=? and e.resource_key=? and e.operation_id=?
                  and (e.proposal_id=? or e.idempotency_key_digest=?)
                order by e.execution_id
                for update
                """)) {
            bindScope(statement, scope, 1);
            statement.setObject(5, proposalId); statement.setString(6, keyDigest);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                ExecutionRow row = row(rows);
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CONFLICT);
                if (expectedBinding != null && !expectedBinding.equals(row.reservationFingerprint()))
                    throw failure(BulkDurableExecutionException.Reason.CONFLICT);
                return Optional.of(snapshot(row));
            }
        }
    }

    private Preparation prepare(Connection connection, BulkExecutionControl control, int ordinal) throws SQLException {
        ExecutionRow execution = lockControl(connection, control);
        if (execution.atomicity() != ActionCollectionAtomicity.PER_ITEM)
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        Optional<Receipt> existing = findReceipt(connection, control.executionId(), ordinal);
        Optional<AdmissionRecord> existingAdmission = findAdmission(connection, control.executionId(), ordinal);
        if (existing.isPresent() && existingAdmission.isPresent())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        Evaluation evaluation = loadEvaluation(connection, execution, false);
        if (existing.isPresent()) {
            if (!receiptReplayable(execution, existing.orElseThrow())
                    || !validReceipt(execution, evaluation, existing.orElseThrow()))
                throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
            return new Preparation(null, existing.orElseThrow(), null, null);
        }
        if (existingAdmission.isPresent()) {
            if (!admissionReplayable(execution, existingAdmission.orElseThrow())
                    || !validAdmission(execution, evaluation, existingAdmission.orElseThrow()))
                throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
            return new Preparation(null, null, existingAdmission.orElseThrow(), null);
        }
        // Historical protocol-one receipts remain replayable, but no protocol-one
        // execution may enter a fresh callback after the non-rolling V16 cutover.
        if (execution.protocolVersion() != 2)
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (!durablePrefixConsistent(connection, execution, evaluation))
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        if (execution.cancelRequestedAt() != null) {
            if (execution.status() == BulkDurableExecutionStatus.RUNNING)
                return new Preparation(null, null, null, finishCancelled(connection, execution));
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
        if (execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK
                || execution.status() == BulkDurableExecutionStatus.UNIT_IN_FLIGHT
                || execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED) {
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
        if (execution.status() != BulkDurableExecutionStatus.RUNNING || ordinal != execution.nextOrdinal())
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        requireReadyControlFence(connection, execution, evaluation);
        requireCapacityUnit(connection, execution, evaluation);
        if (!clock(connection).isBefore(execution.deadlineAt())) {
            try (var statement = connection.prepareStatement("""
                    with terminal_clock as materialized (select clock_timestamp() as observed_at)
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_reason_code='DEADLINE_EXCEEDED',
                        terminal_at=terminal_clock.observed_at, updated_at=terminal_clock.observed_at
                    from terminal_clock
                    where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                      and status='RUNNING' and next_ordinal=?
                    """)) {
                statement.setObject(1, execution.executionId()); statement.setString(2, infrastructure.namespace());
                statement.setString(3, control.ownerId()); statement.setLong(4, control.epoch());
                statement.setInt(5, ordinal);
                if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
            }
            return new Preparation(null, null, null, snapshot(row(connection, execution.executionId(), false)));
        }
        if (!evaluation.snapshot().hasTypedEligibility())
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        BulkTargetEvidence<?> evidence = evaluation.snapshot().targets().get(ordinal);
        String targetDigest = targetDigest(evaluation, ordinal, evidence);
        UUID attemptId = UUID.randomUUID();
        Instant unitDeadline;
        long monotonicDeadlineNanos;
        try (var statement = connection.prepareStatement("""
                with attempt_clock as (select clock_timestamp() as started_at)
                update praxis_bulk.praxis_bulk_execution e
                set status='UNIT_IN_FLIGHT', active_attempt_id=?, active_attempt_ordinal=?,
                    active_target_digest=?, active_attempt_epoch=owner_epoch,
                    active_unit_deadline_at=least(e.deadline_at, attempt_clock.started_at + (? * interval '1 millisecond')),
                    updated_at=attempt_clock.started_at
                from attempt_clock
                where e.execution_id=? and e.namespace_id=? and e.owner_id=? and e.owner_epoch=?
                  and e.status='RUNNING' and e.next_ordinal=? and attempt_clock.started_at < e.deadline_at
                returning e.active_unit_deadline_at
                """)) {
            statement.setObject(1, attemptId); statement.setInt(2, ordinal); statement.setString(3, targetDigest);
            statement.setLong(4, UNIT_BUDGET.toMillis()); statement.setObject(5, control.executionId());
            statement.setString(6, infrastructure.namespace()); statement.setString(7, control.ownerId());
            statement.setLong(8, control.epoch()); statement.setInt(9, ordinal);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
                unitDeadline = rows.getObject(1, OffsetDateTime.class).toInstant();
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
            long clockReadStarted = System.nanoTime();
            Instant databaseNow = clock(connection);
            long clockReadElapsed = Math.max(0, System.nanoTime() - clockReadStarted);
            long remainingNanos = Duration.between(databaseNow, unitDeadline).toNanos() - clockReadElapsed;
            if (remainingNanos <= 0) throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
            monotonicDeadlineNanos = System.nanoTime() + remainingNanos;
        }
        return new Preparation(new Attempt(attemptId, ordinal, targetDigest, control.epoch(), unitDeadline,
                monotonicDeadlineNanos), null, null, null);
    }

    private UnitWrite applyAndReceipt(Connection connection, BulkExecutionControl control, Attempt attempt,
            BulkUnitAdmissionCallback admissionCallback, BulkUnitMutationCallback callback) throws SQLException {
        ExecutionRow execution = lockControl(connection, control);
        requireAttempt(execution, attempt, BulkDurableExecutionStatus.UNIT_IN_FLIGHT);
        if (execution.cancelRequestedAt() != null)
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (findReceipt(connection, execution.executionId(), attempt.ordinal()).isPresent()
                || findAdmission(connection, execution.executionId(), attempt.ordinal()).isPresent())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (!clock(connection).isBefore(execution.deadlineAt()))
            throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        Evaluation evaluation = loadEvaluation(connection, execution, false);
        if (!durablePrefixConsistent(connection, execution, evaluation))
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        // The operation-control share lock is acquired before this execution row lock. A control
        // transition which won between prepare and apply therefore fences this attempt before
        // its domain callback can run.
        requireReadyControlFence(connection, execution, evaluation);
        requireCapacityUnit(connection, execution, evaluation);
        if (!evaluation.snapshot().hasTypedEligibility())
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        BulkTargetEvidence<?> evidence = evaluation.snapshot().targets().get(attempt.ordinal());
        if (!attempt.targetDigest().equals(targetDigest(evaluation, attempt.ordinal(), evidence)))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        BulkExecutionUnit unit = new BulkExecutionUnit(execution.executionId(), attempt.attemptId(), attempt.ordinal(),
                evidence, evaluation.proposal().snapshot(), evaluation.snapshot().governance(), execution.deadlineAt(),
                attempt.unitDeadline(), attempt.monotonicDeadlineNanos(), control);
        if (attempt.unitDeadline() == null || !clock(connection).isBefore(attempt.unitDeadline()))
            throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        setDeadlineBudget(connection, min(execution.deadlineAt(), attempt.unitDeadline()));
        BulkUnitAdmission admission;
        try {
            admission = Objects.requireNonNull(admissionCallback.admit(unit), "admission result");
        } catch (RuntimeException error) {
            throw new CallbackFailure();
        }
        // The policy/grant callbacks may consult independent databases. Reapply the same absolute
        // unit deadline before any domain write; an exhausted budget rolls this transaction back.
        setDeadlineBudget(connection, min(execution.deadlineAt(), attempt.unitDeadline()));
        if (!clock(connection).isBefore(attempt.unitDeadline()))
            throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        if (admission.decision() != BulkUnitAdmission.Decision.ADMIT) {
            if (admission.decision() == BulkUnitAdmission.Decision.STOP) {
                stop(connection, execution, attempt, admission.reason());
                return new UnitWrite(null, null, admission.reason(), snapshot(row(connection, execution.executionId(), false)));
            }
            AdmissionRecord persisted = persistAdmission(connection, execution, attempt, evidence, admission);
            return new UnitWrite(null, persisted, null, snapshot(row(connection, execution.executionId(), false)));
        }
        if (!clock(connection).isBefore(attempt.unitDeadline()))
            throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        BulkUnitMutationResult result;
        try {
            result = Objects.requireNonNull(callback.apply(unit), "callback result");
        } catch (RuntimeException error) {
            throw new CallbackFailure();
        }
        String expectedVersion = evidence.target().expectedVersion();
        Instant confirmedAt;
        try (var statement = connection.prepareStatement("""
                with receipt_clock as (select clock_timestamp() as confirmed_at)
                insert into praxis_bulk.praxis_bulk_item_receipt
                (execution_id, unit_ordinal, target_digest, expected_version, attempt_id,
                 owner_epoch, outcome, confirmed_at, unit_deadline_at)
                select execution_id, ?, ?, ?, ?, owner_epoch, ?, receipt_clock.confirmed_at, active_unit_deadline_at
                from praxis_bulk.praxis_bulk_execution
                cross join receipt_clock
                where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                  and status='UNIT_IN_FLIGHT' and active_attempt_id=?
                  and active_attempt_ordinal=? and active_target_digest=?
                  and receipt_clock.confirmed_at < deadline_at
                  and receipt_clock.confirmed_at < active_unit_deadline_at
                returning confirmed_at, unit_deadline_at
                """)) {
            statement.setInt(1, attempt.ordinal()); statement.setString(2, attempt.targetDigest());
            statement.setString(3, expectedVersion); statement.setObject(4, attempt.attemptId());
            statement.setString(5, result.outcome().name()); statement.setObject(6, execution.executionId());
            statement.setString(7, infrastructure.namespace()); statement.setString(8, control.ownerId());
            statement.setLong(9, control.epoch()); statement.setObject(10, attempt.attemptId());
            statement.setInt(11, attempt.ordinal()); statement.setString(12, attempt.targetDigest());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
                confirmedAt = rows.getObject(1, OffsetDateTime.class).toInstant();
                Instant confirmedUnitDeadline = rows.getObject(2, OffsetDateTime.class).toInstant();
                if (!confirmedUnitDeadline.equals(attempt.unitDeadline()))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        try (var statement = connection.prepareStatement("""
                update praxis_bulk.praxis_bulk_execution
                set status='UNIT_COMMITTED_PENDING_ACK', updated_at=clock_timestamp()
                where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                  and status='UNIT_IN_FLIGHT' and active_attempt_id=?
                """)) {
            statement.setObject(1, execution.executionId()); statement.setString(2, infrastructure.namespace());
            statement.setString(3, control.ownerId()); statement.setLong(4, control.epoch());
            statement.setObject(5, attempt.attemptId());
            if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
        }
        return new UnitWrite(new Receipt(attempt.ordinal(), attempt.targetDigest(), expectedVersion,
                attempt.attemptId(), attempt.epoch(), result.outcome(), confirmedAt, attempt.unitDeadline()), null, null,
                snapshot(row(connection, execution.executionId(), false)));
    }

    private BulkUnitExecutionResult acknowledgeReceipt(BulkExecutionControl control, int ordinal,
            Receipt receipt, boolean replayed) {
        try {
            BulkExecutionSnapshot acknowledged = unitTransaction(connection -> acknowledge(connection, control, ordinal, receipt));
            return result(control.executionId(), ordinal, receipt.outcome(), receipt.outcome() == BulkUnitOutcome.CONFIRMED
                    ? BulkItemStatus.CONFIRMED : BulkItemStatus.UNCHANGED, null, replayed, acknowledged);
        } catch (RuntimeException error) {
            // C only runs after B was confirmed or a durable receipt was read. Resolve an ACK-lost
            // commit by readback; never dispatch the next ordinal from this call. Recovery may
            // have fenced this owner meanwhile, in which case the receipt remains readable but
            // the returned control stays stale and cannot mutate the recovered execution.
            try {
                BulkExecutionSnapshot readback = unitTransaction(connection -> {
                    ExecutionRow row = row(connection, control.executionId(), true);
                    Receipt durable = requireReceipt(connection, control.executionId(), ordinal);
                    if (!sameReceipt(receipt, durable)) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    Evaluation evaluation = loadEvaluation(connection, row, false);
                    if (!receiptReplayable(row, durable) || !validReceipt(row, evaluation, durable))
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    BulkExecutionSnapshot current = snapshot(row(connection, control.executionId(), false));
                    return new BulkExecutionSnapshot(current.executionId(), current.proposalId(), current.status(),
                            current.nextOrdinal(), current.targetCount(), current.receiptCount(), current.admissionCount(), current.deadlineAt(), control, current.terminalReasonCode(), current.cancelRequestedAt());
                });
                return result(control.executionId(), ordinal, receipt.outcome(), receipt.outcome() == BulkUnitOutcome.CONFIRMED
                        ? BulkItemStatus.CONFIRMED : BulkItemStatus.UNCHANGED, null, true, readback);
            } catch (RuntimeException ignored) {
                throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
            }
        }
    }

    private BulkUnitExecutionResult acknowledgeAdmission(BulkExecutionControl control, AdmissionRecord admission,
            boolean replayed) {
        try {
            BulkExecutionSnapshot state = unitTransaction(connection -> {
                ExecutionRow row = row(connection, control.executionId(), true);
                AdmissionRecord durable = findAdmission(connection, control.executionId(), admission.ordinal())
                        .orElseThrow(() -> failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
                Evaluation evaluation = loadEvaluation(connection, row, false);
                if (!sameAdmission(admission, durable) || !admissionReplayable(row, durable)
                        || !validAdmission(row, evaluation, durable))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return snapshot(row);
            });
            return result(control.executionId(), admission.ordinal(), null, admission.status(),
                    admission.reasonCode(), replayed, state);
        } catch (RuntimeException error) {
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
    }

    private AdmissionRecord persistAdmission(Connection connection, ExecutionRow execution, Attempt attempt,
            BulkTargetEvidence<?> evidence, BulkUnitAdmission admission) throws SQLException {
        if (findReceipt(connection, execution.executionId(), attempt.ordinal()).isPresent()
                || findAdmission(connection, execution.executionId(), attempt.ordinal()).isPresent())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        BulkItemStatus status = switch (admission.decision()) {
            case DENIED -> BulkItemStatus.DENIED;
            case INVALID -> BulkItemStatus.INVALID;
            case CONFLICT -> BulkItemStatus.CONFLICT;
            default -> throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        };
        Instant recordedAt;
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_admission
                (execution_id, unit_ordinal, target_digest, expected_version, attempt_id, owner_epoch,
                 outcome, reason_code, recorded_at)
                select execution_id, ?, ?, ?, ?, owner_epoch, ?, ?, clock_timestamp()
                from praxis_bulk.praxis_bulk_execution
                where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                  and status='UNIT_IN_FLIGHT' and active_attempt_id=?
                  and active_attempt_ordinal=? and active_target_digest=? and active_attempt_epoch=?
                  and clock_timestamp() < deadline_at
                  and clock_timestamp() < active_unit_deadline_at
                returning recorded_at
                """)) {
            statement.setInt(1, attempt.ordinal()); statement.setString(2, attempt.targetDigest());
            statement.setString(3, evidence.target().expectedVersion()); statement.setObject(4, attempt.attemptId());
            statement.setString(5, status.name()); statement.setString(6, admission.reason().name());
            statement.setObject(7, execution.executionId()); statement.setString(8, infrastructure.namespace());
            statement.setString(9, execution.ownerId()); statement.setLong(10, attempt.epoch());
            statement.setObject(11, attempt.attemptId()); statement.setInt(12, attempt.ordinal());
            statement.setString(13, attempt.targetDigest()); statement.setLong(14, attempt.epoch());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.FENCED);
                recordedAt = rows.getObject(1, OffsetDateTime.class).toInstant();
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        boolean completed = attempt.ordinal() + 1 == execution.targetCount();
        boolean cancelled = !completed && execution.cancelRequestedAt() != null;
        try (var statement = connection.prepareStatement("""
                with terminal_clock as materialized (select clock_timestamp() as observed_at)
                update praxis_bulk.praxis_bulk_execution
                set next_ordinal=?, status=?, active_attempt_id=null, active_attempt_ordinal=null,
                    active_target_digest=null, active_attempt_epoch=null, active_unit_deadline_at=null,
                    terminal_reason_code=?, updated_at=terminal_clock.observed_at,
                    terminal_at=case when ? then terminal_clock.observed_at else null end
                from terminal_clock
                where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                  and status='UNIT_IN_FLIGHT' and active_attempt_id=? and next_ordinal=?
                """)) {
            statement.setInt(1, attempt.ordinal() + 1);
            statement.setString(2, completed ? "COMPLETED_WITH_ERRORS" : cancelled ? "STOPPED" : "RUNNING");
            statement.setString(3, cancelled ? "CANCELLED_BY_USER" : null);
            statement.setBoolean(4, completed || cancelled); statement.setObject(5, execution.executionId());
            statement.setString(6, infrastructure.namespace()); statement.setString(7, execution.ownerId());
            statement.setLong(8, attempt.epoch()); statement.setObject(9, attempt.attemptId());
            statement.setInt(10, attempt.ordinal());
            if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
        }
        return new AdmissionRecord(attempt.ordinal(), attempt.targetDigest(), evidence.target().expectedVersion(),
                attempt.attemptId(), attempt.epoch(), status, admission.reason(), recordedAt);
    }

    private void stop(Connection connection, ExecutionRow execution, Attempt attempt, BulkUnitReasonCode reason) throws SQLException {
        try (var statement = connection.prepareStatement("""
                with terminal_clock as materialized (select clock_timestamp() as observed_at)
                update praxis_bulk.praxis_bulk_execution
                set status='STOPPED', terminal_reason_code=?, updated_at=terminal_clock.observed_at, terminal_at=terminal_clock.observed_at,
                    active_attempt_id=null, active_attempt_ordinal=null, active_target_digest=null,
                    active_attempt_epoch=null, active_unit_deadline_at=null
                from terminal_clock
                where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                  and status='UNIT_IN_FLIGHT' and active_attempt_id=? and next_ordinal=?
                """)) {
            statement.setString(1, reason.name()); statement.setObject(2, execution.executionId());
            statement.setString(3, infrastructure.namespace()); statement.setString(4, execution.ownerId());
            statement.setLong(5, attempt.epoch()); statement.setObject(6, attempt.attemptId());
            statement.setInt(7, attempt.ordinal());
            if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
        }
    }

    private BulkExecutionSnapshot finishCancelled(Connection connection, ExecutionRow execution) throws SQLException {
        if (execution.cancelRequestedAt() == null || execution.status() != BulkDurableExecutionStatus.RUNNING
                || execution.nextOrdinal() >= execution.targetCount())
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        try (var statement = connection.prepareStatement("""
                with terminal_clock as materialized (select clock_timestamp() as observed_at)
                update praxis_bulk.praxis_bulk_execution
                set status='STOPPED', terminal_reason_code='CANCELLED_BY_USER',
                    terminal_at=terminal_clock.observed_at, updated_at=terminal_clock.observed_at
                from terminal_clock
                where execution_id=? and namespace_id=? and owner_epoch=? and status='RUNNING'
                  and cancel_requested_at is not null and next_ordinal=?
                """)) {
            statement.setObject(1, execution.executionId());
            statement.setString(2, infrastructure.namespace());
            statement.setLong(3, execution.ownerEpoch());
            statement.setInt(4, execution.nextOrdinal());
            if (statement.executeUpdate() != 1)
                throw failure(BulkDurableExecutionException.Reason.FENCED);
        }
        return snapshot(row(connection, execution.executionId(), false));
    }

    private static BulkUnitExecutionResult result(UUID executionId, int ordinal, BulkUnitOutcome outcome,
            BulkItemStatus itemStatus, BulkUnitReasonCode reason, boolean replayed, BulkExecutionSnapshot snapshot) {
        return new BulkUnitExecutionResult(executionId, ordinal, outcome, itemStatus, reason, replayed, snapshot);
    }

    private BulkExecutionSnapshot acknowledge(Connection connection, BulkExecutionControl control,
            int ordinal, Receipt receipt) throws SQLException {
        ExecutionRow execution = lockControl(connection, control);
        Receipt durable = requireReceipt(connection, execution.executionId(), ordinal);
        if (!sameReceipt(receipt, durable)) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        Evaluation evaluation = loadEvaluation(connection, execution, false);
        if (!validReceipt(execution, evaluation, durable))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED) {
            if (!receiptReplayable(execution, durable))
                throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
            return snapshot(execution);
        }
        if (execution.nextOrdinal() > ordinal) return snapshot(execution);
        if (execution.nextOrdinal() != ordinal
                || execution.status() != BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK
                || !Objects.equals(execution.activeAttemptId(), receipt.attemptId())
                || !Objects.equals(execution.activeTargetDigest(), receipt.targetDigest())
                || !Objects.equals(execution.activeAttemptEpoch(), receipt.epoch())
                || !Objects.equals(execution.activeUnitDeadline(), receipt.unitDeadline()))
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        boolean completed = ordinal + 1 == execution.targetCount();
        boolean cancelled = !completed && execution.cancelRequestedAt() != null;
        try (var statement = connection.prepareStatement("""
                with terminal_clock as materialized (select clock_timestamp() as observed_at)
                update praxis_bulk.praxis_bulk_execution
                set next_ordinal=?, status=?, active_attempt_id=null, active_attempt_ordinal=null,
                    active_target_digest=null, active_attempt_epoch=null, active_unit_deadline_at=null,
                    terminal_reason_code=?, updated_at=terminal_clock.observed_at,
                    terminal_at=case when ? then terminal_clock.observed_at else null end
                from terminal_clock
                where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                  and status='UNIT_COMMITTED_PENDING_ACK' and active_attempt_id=?
                """)) {
            statement.setInt(1, ordinal + 1);
            statement.setString(2, completed ? (execution.admissionCount() > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED")
                    : cancelled ? "STOPPED" : "RUNNING");
            statement.setString(3, cancelled ? "CANCELLED_BY_USER" : null);
            statement.setBoolean(4, completed || cancelled); statement.setObject(5, execution.executionId());
            statement.setString(6, infrastructure.namespace()); statement.setString(7, control.ownerId());
            statement.setLong(8, control.epoch()); statement.setObject(9, receipt.attemptId());
            if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
        }
        return snapshot(row(connection, execution.executionId(), false));
    }

    private BulkUnitExecutionResult resolveFailedUnit(BulkExecutionControl control, Attempt attempt,
            BulkDurableExecutionException.Reason observedReason) {
        try {
            return unitTransaction(connection -> {
                ExecutionRow execution = lockControl(connection, control);
                Optional<Receipt> receipt = findReceipt(connection, execution.executionId(), attempt.ordinal());
                Optional<AdmissionRecord> admission = findAdmission(connection, execution.executionId(), attempt.ordinal());
                if (receipt.isPresent() && admission.isPresent())
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                if (receipt.isPresent() || admission.isPresent()) {
                    // The domain transaction may have committed and only its acknowledgement was
                    // lost. Keep the pending barrier; this call must not acknowledge or continue.
                    throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
                }
                requireAttempt(execution, attempt, BulkDurableExecutionStatus.UNIT_IN_FLIGHT);
                Instant observedAt = clock(connection);
                BulkUnitReasonCode stopReason = execution.cancelRequestedAt() != null
                        ? BulkUnitReasonCode.CANCELLED_BY_USER
                        : observedReason == BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED
                        || !observedAt.isBefore(execution.deadlineAt())
                        || !observedAt.isBefore(attempt.unitDeadline())
                        ? BulkUnitReasonCode.DEADLINE_EXCEEDED : BulkUnitReasonCode.UNIT_ROLLED_BACK;
                try (var statement = connection.prepareStatement("""
                        with terminal_clock as materialized (select clock_timestamp() as observed_at)
                        update praxis_bulk.praxis_bulk_execution
                        set status='STOPPED', terminal_reason_code=?,
                            active_attempt_id=null, active_attempt_ordinal=null,
                            active_target_digest=null, active_attempt_epoch=null, active_unit_deadline_at=null,
                            updated_at=terminal_clock.observed_at, terminal_at=terminal_clock.observed_at
                        from terminal_clock
                        where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                          and status='UNIT_IN_FLIGHT' and active_attempt_id=?
                        """)) {
                    statement.setString(1, stopReason.name()); statement.setObject(2, execution.executionId());
                    statement.setString(3, infrastructure.namespace()); statement.setString(4, control.ownerId());
                    statement.setLong(5, control.epoch()); statement.setObject(6, attempt.attemptId());
                    if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
                }
                return result(control.executionId(), attempt.ordinal(), null, BulkItemStatus.NOT_PROCESSED,
                        stopReason, false, snapshot(row(connection, execution.executionId(), false)));
            });
        } catch (BulkDurableExecutionException error) {
            if (error.reason() == BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED) throw error;
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        } catch (RuntimeException error) {
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
    }

    private BulkExecutionRecovery recover(Connection connection, BulkFingerprintContext scope,
            UUID executionId, String owner) throws SQLException {
        ExecutionRow execution = lockLifecycle(connection, scope, executionId);
        Evaluation evaluation = loadEvaluation(connection, execution, false);
        if (execution.atomicity() == ActionCollectionAtomicity.ATOMIC)
            return recoverAtomic(connection, execution, evaluation, owner);
        List<Receipt> receipts = receipts(connection, executionId);
        List<AdmissionRecord> admissions = admissions(connection, executionId);
        var byOrdinal = new java.util.HashMap<Integer, Object>();
        boolean receiptRowsValid = durablePrefixConsistent(receipts, admissions, execution, evaluation);
        for (Receipt receipt : receipts) {
            receiptRowsValid &= validReceipt(execution, evaluation, receipt)
                    && byOrdinal.putIfAbsent(receipt.ordinal(), receipt) == null;
        }
        for (AdmissionRecord admission : admissions) {
            receiptRowsValid &= validAdmission(execution, evaluation, admission)
                    && byOrdinal.putIfAbsent(admission.ordinal(), admission) == null;
        }
        int verifiedPrefix = 0;
        while (verifiedPrefix < execution.targetCount() && byOrdinal.containsKey(verifiedPrefix)) verifiedPrefix++;
        boolean contiguous = verifiedPrefix == byOrdinal.size();
        int durableResults = receipts.size() + admissions.size();
        int expectedReceipts = switch (execution.status()) {
            case QUEUED, RUNNING, UNIT_IN_FLIGHT, RECONCILIATION_REQUIRED, STOPPED -> execution.nextOrdinal();
            case UNIT_COMMITTED_PENDING_ACK -> execution.nextOrdinal() + 1;
            case COMPLETED, COMPLETED_WITH_ERRORS -> execution.targetCount();
        };
        boolean progressValid = durableResults == expectedReceipts && contiguous;
        if (receiptRowsValid && execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK) {
            Receipt pending = receipts.stream().filter(value -> value.ordinal() == execution.nextOrdinal())
                    .findFirst().orElse(null);
            boolean pendingAttemptValid = pending != null
                    && Objects.equals(execution.activeAttemptId(), pending.attemptId())
                    && Objects.equals(execution.activeAttemptOrdinal(), pending.ordinal())
                    && Objects.equals(execution.activeTargetDigest(), pending.targetDigest())
                    && Objects.equals(execution.activeAttemptEpoch(), pending.epoch())
                    && Objects.equals(execution.activeUnitDeadline(), pending.unitDeadline());
            progressValid &= pendingAttemptValid;
            if (!pendingAttemptValid) verifiedPrefix = Math.min(verifiedPrefix, execution.nextOrdinal());
        }
        boolean valid = receiptRowsValid && progressValid && verifiedPrefix == durableResults;
        if ((execution.status() == BulkDurableExecutionStatus.COMPLETED
                || execution.status() == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS)
                || execution.status() == BulkDurableExecutionStatus.STOPPED) {
            if (valid) return new BulkExecutionRecovery(snapshot(execution));
        }
        long epoch = Math.addExact(execution.ownerEpoch(), 1);
        BulkDurableExecutionStatus status;
        int safePrefix = Math.min(verifiedPrefix, expectedReceipts);
        int next;
        if (execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED || !valid)
            { status = BulkDurableExecutionStatus.RECONCILIATION_REQUIRED; next = safePrefix; }
        else if (durableResults == execution.targetCount())
            { status = admissions.isEmpty() ? BulkDurableExecutionStatus.COMPLETED : BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS; next = durableResults; }
        else { status = BulkDurableExecutionStatus.STOPPED; next = durableResults; }
        try (var statement = connection.prepareStatement("""
                with terminal_clock as materialized (select clock_timestamp() as observed_at)
                update praxis_bulk.praxis_bulk_execution
                set owner_id=?, owner_epoch=?, status=?, next_ordinal=?,
                    terminal_reason_code=case when ?='STOPPED' then ? else null end,
                    active_attempt_id=null, active_attempt_ordinal=null,
                    active_target_digest=null, active_attempt_epoch=null, active_unit_deadline_at=null,
                    updated_at=terminal_clock.observed_at,
                    terminal_at=case when ? in ('COMPLETED','COMPLETED_WITH_ERRORS','STOPPED') then terminal_clock.observed_at else null end
                from terminal_clock
                where execution_id=? and namespace_id=? and owner_epoch=?
                """)) {
            statement.setString(1, owner); statement.setLong(2, epoch); statement.setString(3, status.name());
            statement.setInt(4, next); statement.setString(5, status.name());
            statement.setString(6, execution.cancelRequestedAt() != null ? "CANCELLED_BY_USER" : "RECOVERY_STOPPED");
            statement.setString(7, status.name()); statement.setObject(8, executionId);
            statement.setString(9, infrastructure.namespace()); statement.setLong(10, execution.ownerEpoch());
            if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
        }
        return new BulkExecutionRecovery(snapshot(row(connection, executionId, false)));
    }

    private Evaluation loadEvaluation(Connection connection, BulkFingerprintContext scope, UUID proposalId,
            boolean lockProposal) throws SQLException {
        String lock = lockProposal ? " for share of p, e" : "";
        try (var statement = connection.prepareStatement("""
                select p.created_at, p.expires_at, p.fingerprint, p.payload,
                       p.control_generation, p.control_descriptor_fingerprint, p.control_structural_revision,
                       e.evaluation_fingerprint, e.payload, p.atomicity, p.protocol_version, p.execution_mode
                from praxis_bulk.praxis_bulk_proposal p
                join praxis_bulk.praxis_bulk_evaluation e on e.proposal_id=p.proposal_id
                  and e.input_fingerprint=p.fingerprint
                where p.proposal_id=? and p.namespace_id=? and p.subject_id=?
                  and p.resource_key=? and p.operation_id=?
                """ + lock)) {
            statement.setObject(1, proposalId); bindScope(statement, scope, 2);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
                BulkIntentSnapshot intent = BulkSnapshotStorageCodec.decode(rows.getBytes(4), rows.getString(3));
                if (!intent.context().atomicity().name().equals(rows.getString(10)))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                short protocolVersion = rows.getShort(11);
                if (protocolVersion != 1 && protocolVersion != 2)
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                if (!intent.intent().path("executionMode").asText().equals(rows.getString(12))
                        || "ASYNC".equals(rows.getString(12)) && protocolVersion != 2)
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                BulkStoredProposal proposal = BulkStoredProposal.decoded(proposalId,
                        rows.getObject(1, OffsetDateTime.class).toInstant(),
                        rows.getObject(2, OffsetDateTime.class).toInstant(), intent,
                        JdbcBulkProposalStore.expectation(rows.getObject(5, Long.class), rows.getString(6), rows.getString(7)));
                BulkFingerprintContext storedScope = intent.context();
                if (!storedScope.namespaceId().equals(scope.namespaceId())
                        || !storedScope.subjectId().equals(scope.subjectId())
                        || !storedScope.resourceKey().equals(scope.resourceKey())
                        || !storedScope.operationRef().operationId().equals(scope.operationRef().operationId()))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                BulkEvaluationSnapshot evaluation = BulkEvaluationStorageCodec.decode(proposal,
                        rows.getBytes(9), rows.getString(8));
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return new Evaluation(proposal, evaluation, protocolVersion);
            } catch (BulkDurableExecutionException error) { throw error; }
            catch (RuntimeException error) { throw failure(BulkDurableExecutionException.Reason.CORRUPT); }
        }
    }

    private Evaluation loadEvaluation(Connection connection, ExecutionRow execution, boolean lock) throws SQLException {
        var operation = new org.praxisplatform.uischema.openapi.CanonicalOperationRef(null,
                execution.operationId(), "/protected", "POST");
        // Only namespace/subject/resource/operation participate in the SQL scope. The exact
        // operation path/group/schema are reloaded and verified from the protected payload below.
        BulkFingerprintContext queryScope = new BulkFingerprintContext(execution.namespaceId(), execution.subjectId(),
                execution.resourceKey(), operation, execution.structuralRevision(), execution.atomicity());
        Evaluation evaluation = loadEvaluation(connection, queryScope, execution.proposalId(), lock);
        if (!evaluation.proposal().snapshot().fingerprint().equals(execution.inputFingerprint())
                || !evaluation.snapshot().fingerprint().equals(execution.evaluationFingerprint())
                || evaluation.proposal().snapshot().context().atomicity() != execution.atomicity()
                || evaluation.protocolVersion() != execution.protocolVersion()
                || !execution.executionMode().equals(evaluation.proposal().snapshot().intent().path("executionMode").asText()))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        return evaluation;
    }

    private void validateExecutionSubset(Evaluation evaluation) {
        JsonNode intent = evaluation.proposal().snapshot().intent();
        ActionCollectionAtomicity atomicity = evaluation.proposal().snapshot().context().atomicity();
        if ("ASYNC".equals(intent.path("executionMode").asText())) {
            validateAsynchronousSubset(evaluation);
            return;
        }
        if (!"SYNC".equals(intent.path("executionMode").asText())
                || (atomicity != ActionCollectionAtomicity.PER_ITEM && atomicity != ActionCollectionAtomicity.ATOMIC)
                || (atomicity == ActionCollectionAtomicity.ATOMIC && evaluation.protocolVersion() != 2))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        JsonNode targets = evaluation.proposal().snapshot().mode() == BulkMode.PER_ITEM_UPDATE
                ? intent.get("items") : intent.at("/selection/targets");
        boolean query = query(evaluation);
        if (query && (evaluation.proposal().snapshot().mode() != BulkMode.UNIFORM_UPDATE
                || atomicity != ActionCollectionAtomicity.PER_ITEM))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (!query && evaluation.proposal().snapshot().mode() != BulkMode.PER_ITEM_UPDATE
                && !"EXPLICIT".equals(intent.at("/selection/mode").asText()))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (query) {
            if (evaluation.snapshot().targets().isEmpty() || evaluation.snapshot().targets().size() > 200)
                throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        } else if (targets == null || !targets.isArray() || targets.isEmpty()
                || targets.size() != evaluation.snapshot().targets().size())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (atomicity == ActionCollectionAtomicity.ATOMIC && targets.size() > 50)
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (!evaluation.snapshot().hasTypedEligibility() || evaluation.snapshot().targets().stream()
                .anyMatch(target -> target.eligibility().isEmpty() || !target.eligibility().orElseThrow().isExecutable()))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
    }

    private ExecutionRow lockControl(Connection connection, BulkExecutionControl control) throws SQLException {
        JdbcBulkCapacityOccupancy.lockMarker(connection);
        String operationId;
        try (var statement = connection.prepareStatement("""
                select operation_id from praxis_bulk.praxis_bulk_execution
                where execution_id=? and namespace_id=?
                """)) {
            statement.setObject(1, control.executionId());
            statement.setString(2, infrastructure.namespace());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
                operationId = rows.getString(1);
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        // This non-locking identity read is followed by the durable V6 share lock and only then
        // the execution FOR UPDATE lock. The execution binding is checked again after locking.
        JdbcBulkOperationControl.lockForAdmission(connection, infrastructure.namespace(), operationId);
        ExecutionRow execution = row(connection, control.executionId(), true);
        if (!infrastructure.namespace().equals(execution.namespaceId()))
            throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
        if (!operationId.equals(execution.operationId()))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (!control.ownerId().equals(execution.ownerId()) || control.epoch() != execution.ownerEpoch())
            throw failure(BulkDurableExecutionException.Reason.FENCED);
        return execution;
    }

    /** Same lifecycle lock order as V9 retention, without requiring a READY descriptor. */
    private ExecutionRow lockLifecycle(Connection connection, BulkFingerprintContext scope,
            UUID executionId) throws SQLException {
        JdbcBulkCapacityOccupancy.lockMarker(connection);
        BulkExecutionSnapshot candidate = findScoped(connection, scope, executionId, false)
                .orElseThrow(() -> failure(BulkDurableExecutionException.Reason.NOT_FOUND));
        String subjectDigest = BulkScopeDigests.subjectQuotaDigest(
                infrastructure.deploymentId(), scope.subjectId());
        try (var statement = connection.prepareStatement("""
                select 1 from praxis_bulk.praxis_bulk_namespace_binding
                where namespace_id=? and deployment_id=? for share
                """)) {
            statement.setString(1, scope.namespaceId());
            statement.setString(2, infrastructure.deploymentId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        if (JdbcBulkOperationControl.lockForAdmission(connection, scope.namespaceId(),
                scope.operationRef().operationId()) == null)
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        try (var statement = connection.prepareStatement("""
                select 1 from praxis_bulk.praxis_bulk_deployment_bucket
                where deployment_id=? for update
                """)) {
            statement.setString(1, infrastructure.deploymentId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        try (var statement = connection.prepareStatement("""
                select 1 from praxis_bulk.praxis_bulk_subject_bucket
                where deployment_id=? and subject_scope_digest_version=?
                  and subject_scope_digest=? for update
                """)) {
            statement.setString(1, infrastructure.deploymentId());
            statement.setInt(2, BulkScopeDigests.VERSION);
            statement.setString(3, subjectDigest);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        try (var statement = connection.prepareStatement("""
                select 1 from praxis_bulk.praxis_bulk_proposal
                where proposal_id=? and namespace_id=? and subject_id=?
                  and resource_key=? and operation_id=? for update
                """)) {
            statement.setObject(1, candidate.proposalId());
            bindScope(statement, scope, 2);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.next()) throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
            }
        }
        ExecutionRow execution = row(connection, executionId, true);
        if (!candidate.proposalId().equals(execution.proposalId())
                || !scope.namespaceId().equals(execution.namespaceId())
                || !scope.subjectId().equals(execution.subjectId())
                || !scope.resourceKey().equals(execution.resourceKey())
                || !scope.operationRef().operationId().equals(execution.operationId()))
            throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
        try (var statement = connection.prepareStatement("""
                select 1 from praxis_bulk.praxis_bulk_allocation
                where execution_id=? and namespace_id=? and deployment_id=?
                  and subject_scope_digest_version=? and subject_scope_digest=?
                  and kind in ('EXECUTION_ACTIVE','EXECUTION_ASYNC')
                """)) {
            statement.setObject(1, executionId);
            statement.setString(2, scope.namespaceId());
            statement.setString(3, infrastructure.deploymentId());
            statement.setInt(4, BulkScopeDigests.VERSION);
            statement.setString(5, subjectDigest);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        validateReadAllocations(connection, scope, execution);
        return execution;
    }

    private void requireReadyControlFence(Connection connection, ExecutionRow execution, Evaluation evaluation)
            throws SQLException {
        JdbcBulkOperationControl.Snapshot current = JdbcBulkOperationControl.lockForAdmission(connection,
                execution.namespaceId(), execution.operationId());
        BulkOperationControlExpectation proposal = evaluation.proposal().controlExpectation();
        if (current == null || !current.ready() || proposal == null
                || execution.controlGeneration() == null || execution.controlDescriptorFingerprint() == null
                || current.generation() != proposal.generation()
                || current.generation() != execution.controlGeneration()
                || !proposal.descriptorFingerprint().equals(current.descriptorFingerprint())
                || !proposal.descriptorFingerprint().equals(execution.controlDescriptorFingerprint())
                || !proposal.structuralRevision().equals(current.structuralRevision())
                || !proposal.structuralRevision().equals(execution.structuralRevision()))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
    }

    private Optional<BulkExecutionSnapshot> findScoped(Connection connection, BulkFingerprintContext scope,
            UUID executionId, boolean lock) throws SQLException {
        String suffix = lock ? " for update" : "";
        try (var statement = connection.prepareStatement("""
                select e.*, (case when e.atomicity='ATOMIC' then
                            (select count(*) from praxis_bulk.praxis_bulk_atomic_item_result r
                             where r.execution_id=e.execution_id)
                        else (select count(*) from praxis_bulk.praxis_bulk_item_receipt r
                             where r.execution_id=e.execution_id) end) receipt_count,
                       (select count(*) from praxis_bulk.praxis_bulk_admission a where a.execution_id=e.execution_id) admission_count
                from praxis_bulk.praxis_bulk_execution e
                where e.execution_id=? and e.namespace_id=? and e.subject_id=?
                  and e.resource_key=? and e.operation_id=?
                """ + suffix)) {
            statement.setObject(1, executionId); bindScope(statement, scope, 2);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                ExecutionRow value = row(rows);
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return Optional.of(snapshot(value));
            }
        }
    }

    static String scopedTombstone(Connection connection, BulkFingerprintContext scope,
            UUID executionId) throws SQLException {
        return scopedTombstone(connection, scope.namespaceId(), scope.subjectId(), scope.resourceKey(),
                scope.operationRef().operationId(), executionId);
    }

    static String scopedTombstone(Connection connection, String namespaceId, String subjectId,
            String resourceKey, String operationId, UUID executionId) throws SQLException {
        String digest = BulkScopeDigests.authorizationScopeDigest(namespaceId, subjectId,
                resourceKey, operationId);
        try (var statement = connection.prepareStatement("""
                select terminal_status from praxis_bulk.praxis_bulk_tombstone
                where execution_id=? and namespace_id=?
                  and authorization_scope_digest_version=? and authorization_scope_digest=?
                  and resource_key=? and operation_id=?
                """)) {
            statement.setObject(1, executionId);
            statement.setString(2, namespaceId);
            statement.setInt(3, BulkScopeDigests.VERSION);
            statement.setString(4, digest);
            statement.setString(5, resourceKey);
            statement.setString(6, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                String terminal = rows.getString(1);
                if (!List.of("COMPLETED", "COMPLETED_WITH_ERRORS", "STOPPED", "CANCELLED").contains(terminal)
                        || rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return terminal;
            }
        }
    }

    static String scopedProposalTombstone(Connection connection, String namespaceId, String subjectId,
            String resourceKey, String operationId, UUID proposalId) throws SQLException {
        String digest = BulkScopeDigests.authorizationScopeDigest(namespaceId, subjectId,
                resourceKey, operationId);
        try (var statement = connection.prepareStatement("""
                select terminal_status from praxis_bulk.praxis_bulk_tombstone
                where proposal_id=? and namespace_id=?
                  and authorization_scope_digest_version=? and authorization_scope_digest=?
                  and resource_key=? and operation_id=?
                """)) {
            statement.setObject(1, proposalId);
            statement.setString(2, namespaceId);
            statement.setInt(3, BulkScopeDigests.VERSION);
            statement.setString(4, digest);
            statement.setString(5, resourceKey);
            statement.setString(6, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                String terminalStatus = rows.getString(1);
                if (!List.of("COMPLETED", "COMPLETED_WITH_ERRORS", "STOPPED", "CANCELLED")
                        .contains(terminalStatus) || rows.next())
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return terminalStatus;
            }
        }
    }

    private static void validateReadState(ExecutionRow execution, Evaluation evaluation,
            List<Receipt> receipts, List<AdmissionRecord> admissions) {
        if (execution.status() == BulkDurableExecutionStatus.QUEUED
                && (!"ASYNC".equals(execution.executionMode()) || execution.nextOrdinal() != 0
                    || execution.ownerEpoch() != 1 || !receipts.isEmpty() || !admissions.isEmpty()))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        boolean active = execution.status() == BulkDurableExecutionStatus.UNIT_IN_FLIGHT
                || execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK;
        if (active) {
            if (execution.activeAttemptId() == null || execution.activeAttemptOrdinal() == null
                    || execution.activeAttemptOrdinal() != execution.nextOrdinal()
                    || execution.nextOrdinal() < 0
                    || execution.nextOrdinal() >= evaluation.snapshot().targets().size()
                    || execution.activeUnitDeadline() == null
                    || execution.activeUnitDeadline().isAfter(execution.deadlineAt()))
                throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            var evidence = evaluation.snapshot().targets().get(execution.nextOrdinal());
            if (!targetDigest(evaluation, execution.nextOrdinal(), evidence)
                    .equals(execution.activeTargetDigest()))
                throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        } else if (execution.status() != BulkDurableExecutionStatus.RECONCILIATION_REQUIRED
                && execution.activeAttemptId() != null) {
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        }
        if (execution.status() == BulkDurableExecutionStatus.STOPPED
                && (execution.nextOrdinal() >= execution.targetCount()
                    || execution.terminalReasonCode() == null))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (execution.status() == BulkDurableExecutionStatus.COMPLETED && !admissions.isEmpty())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (execution.status() == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS && admissions.isEmpty())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (receipts.size() + admissions.size() > execution.targetCount())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
    }

    /** Reconciliation may retain a structurally valid but unacknowledged suffix. */
    private static boolean readEvidenceConsistent(List<Receipt> receipts, List<AdmissionRecord> admissions,
            ExecutionRow execution, Evaluation evaluation) {
        if (execution.status() != BulkDurableExecutionStatus.RECONCILIATION_REQUIRED)
            return durablePrefixConsistent(receipts, admissions, execution, evaluation);
        if (receipts.size() + admissions.size() > execution.targetCount()) return false;
        var byOrdinal = new java.util.HashMap<Integer, Object>();
        for (Receipt receipt : receipts) {
            if (!validReceipt(execution, evaluation, receipt)
                    || byOrdinal.putIfAbsent(receipt.ordinal(), receipt) != null) return false;
        }
        for (AdmissionRecord admission : admissions) {
            if (!validAdmission(execution, evaluation, admission)
                    || byOrdinal.putIfAbsent(admission.ordinal(), admission) != null) return false;
        }
        for (int ordinal = 0; ordinal < execution.nextOrdinal(); ordinal++) {
            Object certified = byOrdinal.get(ordinal);
            if (certified instanceof Receipt receipt) {
                if (!receiptReplayable(execution, receipt)) return false;
            } else if (certified instanceof AdmissionRecord admission) {
                if (!admissionReplayable(execution, admission)) return false;
            } else return false;
        }
        return true;
    }

    private void validateReadAllocations(Connection connection, BulkFingerprintContext scope,
            ExecutionRow execution) throws SQLException {
        String subjectDigest = BulkScopeDigests.subjectQuotaDigest(infrastructure.deploymentId(), scope.subjectId());
        String authorizationDigest = BulkScopeDigests.authorizationScopeDigest(scope.namespaceId(),
                scope.subjectId(), scope.resourceKey(), scope.operationRef().operationId());
        boolean proposal = false, active = false;
        try (var statement = connection.prepareStatement("""
                select kind, state, namespace_id, deployment_id,
                       subject_scope_digest_version, subject_scope_digest,
                       authorization_scope_digest_version, authorization_scope_digest,
                       proposal_id, execution_id
                from praxis_bulk.praxis_bulk_allocation
                where proposal_id=? or execution_id=?
                """)) {
            statement.setObject(1, execution.proposalId());
            statement.setObject(2, execution.executionId());
            try (ResultSet rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    if (count > 2 || !scope.namespaceId().equals(rows.getString(3))
                            || !infrastructure.deploymentId().equals(rows.getString(4))
                            || rows.getInt(5) != BulkScopeDigests.VERSION
                            || !subjectDigest.equals(rows.getString(6))
                            || rows.getInt(7) != BulkScopeDigests.VERSION
                            || !authorizationDigest.equals(rows.getString(8)))
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    if ("PROPOSAL_PENDING".equals(rows.getString(1))
                            && "CONSUMED".equals(rows.getString(2))
                            && execution.proposalId().equals(rows.getObject(9, UUID.class))
                            && rows.getObject(10, UUID.class) == null) proposal = true;
                    else if (("ASYNC".equals(execution.executionMode()) ? "EXECUTION_ASYNC" : "EXECUTION_ACTIVE").equals(rows.getString(1))
                            && execution.executionId().equals(rows.getObject(10, UUID.class))
                            && rows.getObject(9, UUID.class) == null
                            && (terminal(execution.status()) ? "RELEASED"
                                : execution.status() == BulkDurableExecutionStatus.QUEUED ? "QUEUED" : "ACTIVE").equals(rows.getString(2))) active = true;
                    else throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                }
                if (count != 2 || !proposal || !active)
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        validateReadOccupancy(connection, execution);
    }

    private static void requireSynchronous(Evaluation evaluation) {
        if (!"SYNC".equals(evaluation.proposal().snapshot().intent().path("executionMode").asText()))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
    }

    private static void validateReadOccupancy(Connection connection, ExecutionRow execution) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select s.token_id,s.capacity_class,s.current_owner_epoch,s.occupancy_sequence,
                    h.execution_id,h.owner_epoch
                from praxis_bulk.praxis_bulk_capacity_slot s
                left join praxis_bulk.praxis_bulk_capacity_occupation h
                    on h.token_id=s.token_id and h.occupancy_sequence=s.occupancy_sequence
                where s.current_execution_id=?
                """)) {
            statement.setObject(1, execution.executionId());
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    boolean queued = execution.status() == BulkDurableExecutionStatus.QUEUED;
                    if (!"ASYNC".equals(execution.executionMode()) || terminal(execution.status()) || count > 1
                            || !(queued ? execution.queueTokenId() : execution.activeTokenId()).equals(rows.getObject(1, UUID.class))
                            || !(queued ? "QUEUE" : "ACTIVE").equals(rows.getString(2))
                            || execution.ownerEpoch() != rows.getLong(3) || rows.getLong(4) < 1
                            || !execution.executionId().equals(rows.getObject(5, UUID.class))
                            || rows.getLong(6) < 1 || rows.getLong(6) > execution.ownerEpoch())
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                }
                int expected = "ASYNC".equals(execution.executionMode()) && !terminal(execution.status()) ? 1 : 0;
                if (count != expected) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
    }

    private static Instant[] readExecutionTimes(Connection connection, UUID executionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select created_at, updated_at, terminal_at from praxis_bulk.praxis_bulk_execution
                where execution_id=?
                """)) {
            statement.setObject(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                Instant created = rows.getObject(1, OffsetDateTime.class).toInstant();
                Instant updated = rows.getObject(2, OffsetDateTime.class).toInstant();
                OffsetDateTime terminal = rows.getObject(3, OffsetDateTime.class);
                if (rows.next() || updated.isBefore(created))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return new Instant[]{created, updated, terminal == null ? null : terminal.toInstant()};
            }
        }
    }

    private ExecutionRow row(Connection connection, UUID executionId, boolean lock) throws SQLException {
        String suffix = lock ? " for update" : "";
        try (var statement = connection.prepareStatement("""
                select e.*, (case when e.atomicity='ATOMIC' then
                            (select count(*) from praxis_bulk.praxis_bulk_atomic_item_result r
                             where r.execution_id=e.execution_id)
                        else (select count(*) from praxis_bulk.praxis_bulk_item_receipt r
                             where r.execution_id=e.execution_id) end) receipt_count,
                       (select count(*) from praxis_bulk.praxis_bulk_admission a where a.execution_id=e.execution_id) admission_count
                from praxis_bulk.praxis_bulk_execution e where e.execution_id=? and e.namespace_id=?
                """ + suffix)) {
            statement.setObject(1, executionId); statement.setString(2, infrastructure.namespace());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
                ExecutionRow value = row(rows);
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return value;
            }
        }
    }

    private static ExecutionRow row(ResultSet rows) throws SQLException {
        return new ExecutionRow(rows.getObject("execution_id", UUID.class), rows.getObject("proposal_id", UUID.class),
                rows.getString("namespace_id"), rows.getString("subject_id"), rows.getString("resource_key"),
                rows.getString("operation_id"), rows.getString("reservation_fingerprint"),
                rows.getString("input_fingerprint"), rows.getString("evaluation_fingerprint"),
                rows.getString("structural_revision"), rows.getObject("control_generation", Long.class),
                rows.getString("control_descriptor_fingerprint"), rows.getString("owner_id"), rows.getLong("owner_epoch"),
                BulkDurableExecutionStatus.valueOf(rows.getString("status")), rows.getInt("next_ordinal"),
                rows.getInt("target_count"), rows.getObject("deadline_at", OffsetDateTime.class).toInstant(),
                rows.getObject("active_attempt_id", UUID.class), rows.getObject("active_attempt_ordinal", Integer.class),
                rows.getString("active_target_digest"), rows.getObject("active_attempt_epoch", Long.class),
                rows.getObject("active_unit_deadline_at", OffsetDateTime.class) == null ? null
                        : rows.getObject("active_unit_deadline_at", OffsetDateTime.class).toInstant(),
                rows.getInt("receipt_count"), rows.getInt("admission_count"),
                rows.getString("terminal_reason_code") == null ? null : BulkUnitReasonCode.valueOf(rows.getString("terminal_reason_code")),
                rows.getObject("cancel_requested_at", OffsetDateTime.class) == null ? null
                        : rows.getObject("cancel_requested_at", OffsetDateTime.class).toInstant(),
                ActionCollectionAtomicity.valueOf(rows.getString("atomicity")), rows.getShort("protocol_version"),
                rows.getString("active_set_digest"), rows.getString("execution_mode"),
                rows.getObject("queue_token_id", UUID.class), rows.getObject("active_token_id", UUID.class));
    }

    private static BulkExecutionSnapshot snapshot(ExecutionRow row) {
        return new BulkExecutionSnapshot(row.executionId(), row.proposalId(), row.status(), row.nextOrdinal(),
                row.targetCount(), row.receiptCount(), row.admissionCount(), row.deadlineAt(),
                new BulkExecutionControl(row.executionId(), row.ownerId(), row.ownerEpoch()),
                row.terminalReasonCode(), row.cancelRequestedAt());
    }

    private Optional<Receipt> findReceipt(Connection connection, UUID executionId, int ordinal) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select unit_ordinal, target_digest, expected_version, attempt_id, owner_epoch,
                       outcome, confirmed_at, unit_deadline_at
                from praxis_bulk.praxis_bulk_item_receipt
                where execution_id=? and unit_ordinal=?
                """)) {
            statement.setObject(1, executionId); statement.setInt(2, ordinal);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                Receipt value = receipt(rows);
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return Optional.of(value);
            }
        }
    }

    private Optional<AdmissionRecord> findAdmission(Connection connection, UUID executionId, int ordinal) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select unit_ordinal, target_digest, expected_version, attempt_id, owner_epoch,
                       outcome, reason_code, recorded_at
                from praxis_bulk.praxis_bulk_admission where execution_id=? and unit_ordinal=?
                """)) {
            statement.setObject(1, executionId); statement.setInt(2, ordinal);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                AdmissionRecord value = new AdmissionRecord(rows.getInt(1), rows.getString(2), rows.getString(3),
                        rows.getObject(4, UUID.class), rows.getLong(5), BulkItemStatus.valueOf(rows.getString(6)),
                        BulkUnitReasonCode.valueOf(rows.getString(7)), rows.getObject(8, OffsetDateTime.class).toInstant());
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return Optional.of(value);
            }
        }
    }

    private Receipt requireReceipt(Connection connection, UUID executionId, int ordinal) throws SQLException {
        return findReceipt(connection, executionId, ordinal)
                .orElseThrow(() -> failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
    }

    private List<Receipt> receipts(Connection connection, UUID executionId) throws SQLException {
        var values = new java.util.ArrayList<Receipt>();
        try (var statement = connection.prepareStatement("""
                select unit_ordinal, target_digest, expected_version, attempt_id, owner_epoch,
                       outcome, confirmed_at, unit_deadline_at
                from praxis_bulk.praxis_bulk_item_receipt where execution_id=? order by unit_ordinal
                """)) {
            statement.setObject(1, executionId);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) values.add(receipt(rows)); }
        }
        return List.copyOf(values);
    }

    private List<AdmissionRecord> admissions(Connection connection, UUID executionId) throws SQLException {
        var values = new ArrayList<AdmissionRecord>();
        try (var statement = connection.prepareStatement("""
                select unit_ordinal, target_digest, expected_version, attempt_id, owner_epoch,
                       outcome, reason_code, recorded_at
                from praxis_bulk.praxis_bulk_admission where execution_id=? order by unit_ordinal
                """)) {
            statement.setObject(1, executionId);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) values.add(new AdmissionRecord(
                    rows.getInt(1), rows.getString(2), rows.getString(3), rows.getObject(4, UUID.class), rows.getLong(5),
                    BulkItemStatus.valueOf(rows.getString(6)), BulkUnitReasonCode.valueOf(rows.getString(7)),
                    rows.getObject(8, OffsetDateTime.class).toInstant())); }
        }
        return List.copyOf(values);
    }

    private boolean durablePrefixConsistent(Connection connection, ExecutionRow execution, Evaluation evaluation)
            throws SQLException {
        return durablePrefixConsistent(receipts(connection, execution.executionId()),
                admissions(connection, execution.executionId()), execution, evaluation);
    }

    private static boolean durablePrefixConsistent(List<Receipt> receipts, List<AdmissionRecord> admissions,
            ExecutionRow execution, Evaluation evaluation) {
        if (receipts.size() + admissions.size() > execution.targetCount()) return false;
        var byOrdinal = new java.util.HashMap<Integer, Object>();
        for (Receipt receipt : receipts) {
            if (!validReceipt(execution, evaluation, receipt)
                    || !receiptReplayable(execution, receipt)
                    || byOrdinal.putIfAbsent(receipt.ordinal(), receipt) != null) return false;
        }
        for (AdmissionRecord admission : admissions) {
            if (!validAdmission(execution, evaluation, admission)
                    || !admissionReplayable(execution, admission)
                    || byOrdinal.putIfAbsent(admission.ordinal(), admission) != null) return false;
        }
        int prefix = 0;
        while (prefix < execution.targetCount() && byOrdinal.containsKey(prefix)) prefix++;
        if (prefix != byOrdinal.size()) return false;
        int expected = switch (execution.status()) {
            case UNIT_COMMITTED_PENDING_ACK -> execution.nextOrdinal() + 1;
            case COMPLETED, COMPLETED_WITH_ERRORS -> execution.targetCount();
            case QUEUED, RUNNING, UNIT_IN_FLIGHT, RECONCILIATION_REQUIRED, STOPPED -> execution.nextOrdinal();
        };
        if (byOrdinal.size() != expected) return false;
        if (execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK) {
            Object pending = byOrdinal.get(execution.nextOrdinal());
            if (!(pending instanceof Receipt receipt)
                    || !Objects.equals(execution.activeAttemptId(), receipt.attemptId())
                    || !Objects.equals(execution.activeAttemptOrdinal(), receipt.ordinal())
                    || !Objects.equals(execution.activeTargetDigest(), receipt.targetDigest())
                    || !Objects.equals(execution.activeAttemptEpoch(), receipt.epoch())
                    || !Objects.equals(execution.activeUnitDeadline(), receipt.unitDeadline())) return false;
        }
        if (execution.status() == BulkDurableExecutionStatus.COMPLETED && !admissions.isEmpty()) return false;
        if (execution.status() == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS && admissions.isEmpty()) return false;
        return true;
    }

    private static Receipt receipt(ResultSet rows) throws SQLException {
        return new Receipt(rows.getInt(1), rows.getString(2), rows.getString(3),
                rows.getObject(4, UUID.class), rows.getLong(5), BulkUnitOutcome.valueOf(rows.getString(6)),
                rows.getObject(7, OffsetDateTime.class).toInstant(),
                rows.getObject(8, OffsetDateTime.class) == null ? null
                        : rows.getObject(8, OffsetDateTime.class).toInstant());
    }

    private static boolean sameReceipt(Receipt first, Receipt second) {
        return first.ordinal() == second.ordinal() && first.epoch() == second.epoch()
                && first.targetDigest().equals(second.targetDigest())
                && first.expectedVersion().equals(second.expectedVersion())
                && first.attemptId().equals(second.attemptId()) && first.outcome() == second.outcome()
                && first.confirmedAt().equals(second.confirmedAt())
                && Objects.equals(first.unitDeadline(), second.unitDeadline());
    }

    private static boolean sameAdmission(AdmissionRecord first, AdmissionRecord second) {
        return first.ordinal() == second.ordinal() && first.epoch() == second.epoch()
                && first.targetDigest().equals(second.targetDigest())
                && first.expectedVersion().equals(second.expectedVersion())
                && first.attemptId().equals(second.attemptId()) && first.status() == second.status()
                && first.reasonCode() == second.reasonCode() && first.recordedAt().equals(second.recordedAt());
    }

    private static boolean admissionReplayable(ExecutionRow execution, AdmissionRecord admission) {
        return admission.ordinal() >= 0 && admission.ordinal() < execution.nextOrdinal()
                && admission.epoch() > 0 && admission.epoch() <= execution.ownerEpoch()
                && (execution.status() == BulkDurableExecutionStatus.RUNNING
                    || execution.status() == BulkDurableExecutionStatus.UNIT_IN_FLIGHT
                    || execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK
                    || execution.status() == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS
                    || execution.status() == BulkDurableExecutionStatus.STOPPED
                    || execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED);
    }

    private static boolean validReceipt(ExecutionRow execution, Evaluation evaluation, Receipt receipt) {
        if (receipt.ordinal() < 0 || receipt.ordinal() >= evaluation.snapshot().targets().size()
                || receipt.epoch() < 1 || receipt.epoch() > execution.ownerEpoch()
                || !receipt.confirmedAt().isBefore(execution.deadlineAt())) return false;
        if (receipt.unitDeadline() != null && (!receipt.confirmedAt().isBefore(receipt.unitDeadline())
                || receipt.unitDeadline().isAfter(execution.deadlineAt()))) return false;
        BulkTargetEvidence<?> evidence = evaluation.snapshot().targets().get(receipt.ordinal());
        return receipt.expectedVersion().equals(evidence.target().expectedVersion())
                && receipt.targetDigest().equals(targetDigest(evaluation, receipt.ordinal(), evidence));
    }

    private static boolean validAdmission(ExecutionRow execution, Evaluation evaluation, AdmissionRecord admission) {
        if (admission.ordinal() < 0 || admission.ordinal() >= evaluation.snapshot().targets().size()
                || admission.epoch() < 1 || admission.epoch() > execution.ownerEpoch()
                || !admission.recordedAt().isBefore(execution.deadlineAt())) return false;
        BulkTargetEvidence<?> evidence = evaluation.snapshot().targets().get(admission.ordinal());
        if (!admission.expectedVersion().equals(evidence.target().expectedVersion())
                || !admission.targetDigest().equals(targetDigest(evaluation, admission.ordinal(), evidence))) return false;
        return switch (admission.status()) {
            case DENIED -> admission.reasonCode() == BulkUnitReasonCode.TARGET_DENIED;
            case INVALID -> admission.reasonCode() == BulkUnitReasonCode.TARGET_NOT_FOUND
                    || admission.reasonCode() == BulkUnitReasonCode.TARGET_INVALID;
            case CONFLICT -> admission.reasonCode() == BulkUnitReasonCode.TARGET_VERSION_CONFLICT
                    || admission.reasonCode() == BulkUnitReasonCode.TARGET_STATE_CONFLICT
                    || admission.reasonCode() == BulkUnitReasonCode.TARGET_DEPENDENCY_CHANGED;
            default -> false;
        };
    }

    /** In reconciliation, nextOrdinal is the verified contiguous receipt prefix. */
    private static boolean receiptReplayable(ExecutionRow execution, Receipt receipt) {
        int ordinal = receipt.ordinal();
        if (execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED)
            return ordinal < execution.nextOrdinal();
        return ordinal < execution.nextOrdinal()
                || (execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK
                    && ordinal == execution.nextOrdinal()
                    && Objects.equals(execution.activeAttemptId(), receipt.attemptId())
                    && Objects.equals(execution.activeAttemptOrdinal(), receipt.ordinal())
                    && Objects.equals(execution.activeTargetDigest(), receipt.targetDigest())
                    && Objects.equals(execution.activeAttemptEpoch(), receipt.epoch())
                    && Objects.equals(execution.activeUnitDeadline(), receipt.unitDeadline()));
    }

    private static void requireAttempt(ExecutionRow execution, Attempt attempt,
            BulkDurableExecutionStatus status) {
        if (execution.status() != status || !attempt.attemptId().equals(execution.activeAttemptId())
                || execution.activeAttemptOrdinal() == null || execution.activeAttemptOrdinal() != attempt.ordinal()
                || !attempt.targetDigest().equals(execution.activeTargetDigest())
                || execution.activeAttemptEpoch() == null || execution.activeAttemptEpoch() != attempt.epoch()
                || !Objects.equals(execution.activeUnitDeadline(), attempt.unitDeadline()))
            throw failure(BulkDurableExecutionException.Reason.FENCED);
    }

    private static String targetDigest(Evaluation evaluation, int ordinal, BulkTargetEvidence<?> evidence) {
        return BulkTargetDigest.of(evaluation.snapshot().fingerprint(), ordinal,
                evidence.target().id(), evidence.target().expectedVersion());
    }

    private static String reservationFingerprint(Evaluation evaluation, String structuralRevision) {
        BulkFingerprintContext context = evaluation.proposal().snapshot().context();
        BulkOperationControlExpectation expectation = evaluation.proposal().controlExpectation();
        if (expectation == null) {
            // Retain the original binding for safe receipt/result replay of pre-fence executions.
            return digest("praxis.bulk.reservation/1", evaluation.proposal().id().toString(),
                    evaluation.proposal().snapshot().fingerprint(), evaluation.snapshot().fingerprint(),
                    context.namespaceId(), context.subjectId(), context.resourceKey(),
                    context.operationRef().operationId(), structuralRevision);
        }
        return digest("praxis.bulk.reservation/2", evaluation.proposal().id().toString(),
                evaluation.proposal().snapshot().fingerprint(), evaluation.snapshot().fingerprint(),
                context.namespaceId(), context.subjectId(), context.resourceKey(),
                context.operationRef().operationId(), Long.toString(expectation.generation()),
                expectation.descriptorFingerprint(), expectation.structuralRevision(), structuralRevision);
    }

    private static String digest(String framing, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, framing);
            for (String value : values) update(digest, value);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }

    private static Instant clock(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select clock_timestamp()")) {
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getObject(1, OffsetDateTime.class).toInstant(); }
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        TransactionTemplate template = new TransactionTemplate(infrastructure.transactionManager());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> infrastructure.withConnection(connection -> work.apply(connection)));
    }

    /** Unit-control acquisition has a bounded wait even when the execution deadline is distant. */
    private <T> T unitTransaction(SqlWork<T> work) {
        return transaction(connection -> {
            setLocalTimeout(connection, "statement_timeout", CONTROL_LOCK_BUDGET.toMillis());
            setLocalTimeout(connection, "lock_timeout", CONTROL_LOCK_BUDGET.toMillis());
            return work.apply(connection);
        });
    }

    /** After the control row is locked and replay checked, remaining deadline bounds DB work and target locks. */
    private static void setDeadlineBudget(Connection connection, Instant deadline) throws SQLException {
        long remainingMillis = Duration.between(clock(connection), deadline).toMillis();
        if (remainingMillis <= 0) throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        setLocalTimeout(connection, "statement_timeout", remainingMillis);
        setLocalTimeout(connection, "lock_timeout", Math.min(remainingMillis, CONTROL_LOCK_BUDGET.toMillis()));
        setLocalTimeout(connection, "idle_in_transaction_session_timeout", remainingMillis);
    }

    private static void requireAtomicMonotonicBudget(AtomicAttempt attempt) {
        if (System.nanoTime() - attempt.monotonicDeadlineNanos() >= 0)
            throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
    }

    private static void requireAtomicAppendBudget(Connection connection, ExecutionRow execution,
            AtomicAttempt attempt) throws SQLException {
        requireAtomicMonotonicBudget(attempt);
        setDeadlineBudget(connection, min(execution.deadlineAt(), attempt.unitDeadline()));
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static void setLocalTimeout(Connection connection, String setting, long millis) throws SQLException {
        if (!"statement_timeout".equals(setting) && !"lock_timeout".equals(setting)
                && !"idle_in_transaction_session_timeout".equals(setting))
            throw new IllegalArgumentException("Unsupported local timeout setting");
        try (var statement = connection.prepareStatement("select set_config(?, ?, true)")) {
            statement.setString(1, setting);
            statement.setString(2, Math.max(1, millis) + "ms");
            statement.execute();
        }
    }

    private void requireScope(BulkFingerprintContext scope) {
        Objects.requireNonNull(scope, "scope");
        if (!infrastructure.namespace().equals(scope.namespaceId()))
            throw new IllegalArgumentException("Operational namespace mismatch");
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Durable execution owns its transaction boundaries");
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(name + " must contain 1 to 200 safe characters");
        return value;
    }

    private static Instant micro(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }

    private static void bindScope(java.sql.PreparedStatement statement, BulkFingerprintContext scope,
            int index) throws SQLException {
        statement.setString(index, scope.namespaceId()); statement.setString(index + 1, scope.subjectId());
        statement.setString(index + 2, scope.resourceKey());
        statement.setString(index + 3, scope.operationRef().operationId());
    }

    private static BulkDurableExecutionException failure(BulkDurableExecutionException.Reason reason) {
        return new BulkDurableExecutionException(reason);
    }

    @FunctionalInterface private interface SqlWork<T> { T apply(Connection connection) throws SQLException; }
    private static final class CallbackFailure extends RuntimeException { private CallbackFailure() { super("Domain callback failed"); } }
    private static final class AtomicAdmissionRejected extends RuntimeException {
        private final BulkUnitReasonCode reason;
        private AtomicAdmissionRejected(BulkUnitReasonCode reason) {
            super("Atomic admission rejected");
            this.reason = reason;
        }
        private BulkUnitReasonCode reason() { return reason; }
    }
    private record Evaluation(BulkStoredProposal proposal, BulkEvaluationSnapshot snapshot,
            short protocolVersion) { }
    private record ReservationWrite(BulkExecutionSnapshot snapshot, boolean created) { }
    private record Attempt(UUID attemptId, int ordinal, String targetDigest, long epoch, Instant unitDeadline,
            long monotonicDeadlineNanos) { }
    private record AtomicAttempt(UUID attemptId, String setDigest, long epoch, Instant unitDeadline,
            long monotonicDeadlineNanos) { }
    private record AtomicHeader(UUID attemptId, long ownerEpoch, String setDigest, int targetCount,
            int effectCount, String effectDigest, Instant confirmedAt, Instant unitDeadline) { }
    private record AtomicPreparation(AtomicAttempt attempt, AtomicHeader header,
            BulkExecutionSnapshot stopped) { }
    private record Receipt(int ordinal, String targetDigest, String expectedVersion, UUID attemptId,
            long epoch, BulkUnitOutcome outcome, Instant confirmedAt, Instant unitDeadline) { }
    private record Preparation(Attempt attempt, Receipt receipt, AdmissionRecord admission, BulkExecutionSnapshot stoppedSnapshot) { }
    private record AdmissionRecord(int ordinal, String targetDigest, String expectedVersion, UUID attemptId,
            long epoch, BulkItemStatus status, BulkUnitReasonCode reasonCode, Instant recordedAt) { }
    private record UnitWrite(Receipt receipt, AdmissionRecord admission, BulkUnitReasonCode stopReason,
            BulkExecutionSnapshot snapshot) { }
    private record ExecutionRow(UUID executionId, UUID proposalId, String namespaceId, String subjectId,
            String resourceKey, String operationId, String reservationFingerprint, String inputFingerprint,
            String evaluationFingerprint, String structuralRevision, Long controlGeneration,
            String controlDescriptorFingerprint, String ownerId, long ownerEpoch,
            BulkDurableExecutionStatus status, int nextOrdinal, int targetCount, Instant deadlineAt,
            UUID activeAttemptId, Integer activeAttemptOrdinal, String activeTargetDigest,
            Long activeAttemptEpoch, Instant activeUnitDeadline, int receiptCount, int admissionCount,
            BulkUnitReasonCode terminalReasonCode, Instant cancelRequestedAt,
            ActionCollectionAtomicity atomicity, short protocolVersion, String activeSetDigest, String executionMode, UUID queueTokenId, UUID activeTokenId) { }
}
