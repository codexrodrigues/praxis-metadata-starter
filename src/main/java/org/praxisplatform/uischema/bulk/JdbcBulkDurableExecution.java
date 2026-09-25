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
 * Protected PostgreSQL kernel for durable EXPLICIT/SYNC/PER_ITEM execution.
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

    public JdbcBulkDurableExecution(BulkExecutionInfrastructure infrastructure) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
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
            ReservationWrite write = transaction(connection -> reserve(connection, scope, proposalId,
                    keyDigest, owner, revision, boundedDeadline));
            return new BulkExecutionReservation(write.snapshot(), !write.created());
        } catch (BulkDurableExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            // The insert commit may have succeeded. Readback by both unique identities is the
            // only safe resolution; no replacement key or execution is manufactured.
            try {
                Optional<BulkExecutionSnapshot> recovered = transaction(connection -> {
                    Evaluation evaluation = loadEvaluation(connection, scope, proposalId, false);
                    validateExecutionSubset(evaluation);
                    String expectedBinding = reservationFingerprint(evaluation, revision);
                    return findReservation(connection, scope, proposalId, keyDigest, expectedBinding);
                });
                if (recovered.isPresent()) return new BulkExecutionReservation(recovered.orElseThrow(), true);
            } catch (RuntimeException ignored) { /* safe failure below, without protected cause */ }
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
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

    private ReservationWrite reserve(Connection connection, BulkFingerprintContext scope, UUID proposalId,
            String keyDigest, String owner, String revision, Instant deadline) throws SQLException {
        String authorizationDigest = BulkScopeDigests.authorizationScopeDigest(scope.namespaceId(),
                scope.subjectId(), scope.resourceKey(), scope.operationRef().operationId());
        if (BulkQuotaLedger.tombstoneExists(connection, scope, authorizationDigest, keyDigest))
            throw failure(BulkDurableExecutionException.Reason.RESULT_PURGED);
        Evaluation evaluation = loadEvaluation(connection, scope, proposalId, false);
        String reservationFingerprint = reservationFingerprint(evaluation, revision);
        if (reservationExists(connection, scope, proposalId, keyDigest)) {
            Optional<BulkExecutionSnapshot> existing = findReservation(connection, scope, proposalId,
                    keyDigest, reservationFingerprint);
            return new ReservationWrite(existing.orElseThrow(), false);
        }
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
                 evaluation_fingerprint, structural_revision, owner_id, owner_epoch, status,
                 next_ordinal, target_count, deadline_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'RUNNING', 0, ?, ?, clock_timestamp(), clock_timestamp())
                on conflict do nothing
                """)) {
            statement.setObject(1, executionId); statement.setObject(2, proposalId);
            statement.setString(3, scope.namespaceId()); statement.setString(4, scope.subjectId());
            statement.setString(5, scope.resourceKey()); statement.setString(6, scope.operationRef().operationId());
            statement.setString(7, keyDigest); statement.setString(8, reservationFingerprint);
            statement.setString(9, evaluation.proposal().snapshot().fingerprint());
            statement.setString(10, evaluation.snapshot().fingerprint()); statement.setString(11, revision);
            statement.setString(12, owner); statement.setInt(13, evaluation.snapshot().targets().size());
            statement.setObject(14, deadline.atOffset(ZoneOffset.UTC));
            inserted = statement.executeUpdate();
        }
        if (inserted == 1) BulkQuotaLedger.activateExecution(connection, scope, proposalId, executionId, quota);
        Optional<BulkExecutionSnapshot> selected = findReservation(connection, scope, proposalId, keyDigest,
                reservationFingerprint);
        if (selected.isEmpty()) throw failure(BulkDurableExecutionException.Reason.CONFLICT);
        return new ReservationWrite(selected.orElseThrow(), inserted == 1);
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
                select e.*, (select count(*) from praxis_bulk.praxis_bulk_item_receipt r
                             where r.execution_id=e.execution_id) receipt_count,
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
        if (!durablePrefixConsistent(connection, execution, evaluation))
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        if (execution.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK
                || execution.status() == BulkDurableExecutionStatus.UNIT_IN_FLIGHT
                || execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED) {
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        }
        if (execution.status() != BulkDurableExecutionStatus.RUNNING || ordinal != execution.nextOrdinal())
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (!clock(connection).isBefore(execution.deadlineAt())) {
            try (var statement = connection.prepareStatement("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_reason_code='DEADLINE_EXCEEDED',
                        terminal_at=clock_timestamp(), updated_at=clock_timestamp()
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
        if (findReceipt(connection, execution.executionId(), attempt.ordinal()).isPresent()
                || findAdmission(connection, execution.executionId(), attempt.ordinal()).isPresent())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (!clock(connection).isBefore(execution.deadlineAt()))
            throw failure(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        Evaluation evaluation = loadEvaluation(connection, execution, false);
        if (!durablePrefixConsistent(connection, execution, evaluation))
            throw failure(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
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
                            current.nextOrdinal(), current.targetCount(), current.receiptCount(), current.admissionCount(), current.deadlineAt(), control, current.terminalReasonCode());
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
        try (var statement = connection.prepareStatement("""
                update praxis_bulk.praxis_bulk_execution
                set next_ordinal=?, status=?, active_attempt_id=null, active_attempt_ordinal=null,
                    active_target_digest=null, active_attempt_epoch=null, active_unit_deadline_at=null,
                    updated_at=clock_timestamp(), terminal_at=case when ? then clock_timestamp() else null end
                where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                  and status='UNIT_IN_FLIGHT' and active_attempt_id=? and next_ordinal=?
                """)) {
            statement.setInt(1, attempt.ordinal() + 1);
            statement.setString(2, completed ? "COMPLETED_WITH_ERRORS" : "RUNNING");
            statement.setBoolean(3, completed); statement.setObject(4, execution.executionId());
            statement.setString(5, infrastructure.namespace()); statement.setString(6, execution.ownerId());
            statement.setLong(7, attempt.epoch()); statement.setObject(8, attempt.attemptId());
            statement.setInt(9, attempt.ordinal());
            if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
        }
        return new AdmissionRecord(attempt.ordinal(), attempt.targetDigest(), evidence.target().expectedVersion(),
                attempt.attemptId(), attempt.epoch(), status, admission.reason(), recordedAt);
    }

    private void stop(Connection connection, ExecutionRow execution, Attempt attempt, BulkUnitReasonCode reason) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update praxis_bulk.praxis_bulk_execution
                set status='STOPPED', terminal_reason_code=?, updated_at=clock_timestamp(), terminal_at=clock_timestamp(),
                    active_attempt_id=null, active_attempt_ordinal=null, active_target_digest=null,
                    active_attempt_epoch=null, active_unit_deadline_at=null
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
        try (var statement = connection.prepareStatement("""
                update praxis_bulk.praxis_bulk_execution
                set next_ordinal=?, status=?, active_attempt_id=null, active_attempt_ordinal=null,
                    active_target_digest=null, active_attempt_epoch=null, active_unit_deadline_at=null,
                    updated_at=clock_timestamp(),
                    terminal_at=case when ? then clock_timestamp() else null end
                where execution_id=? and namespace_id=? and owner_id=? and owner_epoch=?
                  and status='UNIT_COMMITTED_PENDING_ACK' and active_attempt_id=?
                """)) {
            statement.setInt(1, ordinal + 1);
            statement.setString(2, completed ? (execution.admissionCount() > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED") : "RUNNING");
            statement.setBoolean(3, completed); statement.setObject(4, execution.executionId());
            statement.setString(5, infrastructure.namespace()); statement.setString(6, control.ownerId());
            statement.setLong(7, control.epoch()); statement.setObject(8, receipt.attemptId());
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
                BulkUnitReasonCode stopReason = observedReason == BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED
                        || !observedAt.isBefore(execution.deadlineAt())
                        || !observedAt.isBefore(attempt.unitDeadline())
                        ? BulkUnitReasonCode.DEADLINE_EXCEEDED : BulkUnitReasonCode.UNIT_ROLLED_BACK;
                try (var statement = connection.prepareStatement("""
                        update praxis_bulk.praxis_bulk_execution
                        set status='STOPPED', terminal_reason_code=?,
                            active_attempt_id=null, active_attempt_ordinal=null,
                            active_target_digest=null, active_attempt_epoch=null, active_unit_deadline_at=null,
                            updated_at=clock_timestamp(), terminal_at=clock_timestamp()
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
        if (findScoped(connection, scope, executionId, true).isEmpty())
            throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
        ExecutionRow execution = row(connection, executionId, false);
        Evaluation evaluation = loadEvaluation(connection, execution, false);
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
            case RUNNING, UNIT_IN_FLIGHT, RECONCILIATION_REQUIRED, STOPPED -> execution.nextOrdinal();
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
                update praxis_bulk.praxis_bulk_execution
                set owner_id=?, owner_epoch=?, status=?, next_ordinal=?,
                    terminal_reason_code=case when ?='STOPPED' then coalesce(terminal_reason_code, 'RECOVERY_STOPPED') else null end,
                    active_attempt_id=null, active_attempt_ordinal=null,
                    active_target_digest=null, active_attempt_epoch=null, active_unit_deadline_at=null,
                    updated_at=clock_timestamp(),
                    terminal_at=case when ? in ('COMPLETED','COMPLETED_WITH_ERRORS','STOPPED') then clock_timestamp() else null end
                where execution_id=? and namespace_id=? and owner_epoch=?
                """)) {
            statement.setString(1, owner); statement.setLong(2, epoch); statement.setString(3, status.name());
            statement.setInt(4, next); statement.setString(5, status.name()); statement.setString(6, status.name()); statement.setObject(7, executionId);
            statement.setString(8, infrastructure.namespace()); statement.setLong(9, execution.ownerEpoch());
            if (statement.executeUpdate() != 1) throw failure(BulkDurableExecutionException.Reason.FENCED);
        }
        return new BulkExecutionRecovery(snapshot(row(connection, executionId, false)));
    }

    private Evaluation loadEvaluation(Connection connection, BulkFingerprintContext scope, UUID proposalId,
            boolean lockProposal) throws SQLException {
        String lock = lockProposal ? " for share of p, e" : "";
        try (var statement = connection.prepareStatement("""
                select p.created_at, p.expires_at, p.fingerprint, p.payload,
                       e.evaluation_fingerprint, e.payload
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
                BulkStoredProposal proposal = new BulkStoredProposal(proposalId,
                        rows.getObject(1, OffsetDateTime.class).toInstant(),
                        rows.getObject(2, OffsetDateTime.class).toInstant(), intent);
                BulkFingerprintContext storedScope = intent.context();
                if (!storedScope.namespaceId().equals(scope.namespaceId())
                        || !storedScope.subjectId().equals(scope.subjectId())
                        || !storedScope.resourceKey().equals(scope.resourceKey())
                        || !storedScope.operationRef().operationId().equals(scope.operationRef().operationId()))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                BulkEvaluationSnapshot evaluation = BulkEvaluationStorageCodec.decode(proposal,
                        rows.getBytes(6), rows.getString(5));
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                return new Evaluation(proposal, evaluation);
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
                execution.resourceKey(), operation, execution.structuralRevision(), ActionCollectionAtomicity.PER_ITEM);
        Evaluation evaluation = loadEvaluation(connection, queryScope, execution.proposalId(), lock);
        if (!evaluation.proposal().snapshot().fingerprint().equals(execution.inputFingerprint())
                || !evaluation.snapshot().fingerprint().equals(execution.evaluationFingerprint()))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        return evaluation;
    }

    private void validateExecutionSubset(Evaluation evaluation) {
        JsonNode intent = evaluation.proposal().snapshot().intent();
        if (!"SYNC".equals(intent.path("executionMode").asText())
                || evaluation.proposal().snapshot().context().atomicity() != ActionCollectionAtomicity.PER_ITEM)
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        JsonNode targets = evaluation.proposal().snapshot().mode() == BulkMode.PER_ITEM_UPDATE
                ? intent.get("items") : intent.at("/selection/targets");
        if (evaluation.proposal().snapshot().mode() != BulkMode.PER_ITEM_UPDATE
                && !"EXPLICIT".equals(intent.at("/selection/mode").asText()))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        if (targets == null || !targets.isArray() || targets.isEmpty()
                || targets.size() != evaluation.snapshot().targets().size())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (!evaluation.snapshot().hasTypedEligibility() || evaluation.snapshot().targets().stream()
                .anyMatch(target -> target.eligibility().isEmpty() || !target.eligibility().orElseThrow().isExecutable()))
            throw failure(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
    }

    private ExecutionRow lockControl(Connection connection, BulkExecutionControl control) throws SQLException {
        ExecutionRow execution = row(connection, control.executionId(), true);
        if (!infrastructure.namespace().equals(execution.namespaceId()))
            throw failure(BulkDurableExecutionException.Reason.NOT_FOUND);
        if (!control.ownerId().equals(execution.ownerId()) || control.epoch() != execution.ownerEpoch())
            throw failure(BulkDurableExecutionException.Reason.FENCED);
        return execution;
    }

    private Optional<BulkExecutionSnapshot> findScoped(Connection connection, BulkFingerprintContext scope,
            UUID executionId, boolean lock) throws SQLException {
        String suffix = lock ? " for update" : "";
        try (var statement = connection.prepareStatement("""
                select e.*, (select count(*) from praxis_bulk.praxis_bulk_item_receipt r
                             where r.execution_id=e.execution_id) receipt_count,
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

    private ExecutionRow row(Connection connection, UUID executionId, boolean lock) throws SQLException {
        String suffix = lock ? " for update" : "";
        try (var statement = connection.prepareStatement("""
                select e.*, (select count(*) from praxis_bulk.praxis_bulk_item_receipt r
                             where r.execution_id=e.execution_id) receipt_count,
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
                rows.getString("structural_revision"), rows.getString("owner_id"), rows.getLong("owner_epoch"),
                BulkDurableExecutionStatus.valueOf(rows.getString("status")), rows.getInt("next_ordinal"),
                rows.getInt("target_count"), rows.getObject("deadline_at", OffsetDateTime.class).toInstant(),
                rows.getObject("active_attempt_id", UUID.class), rows.getObject("active_attempt_ordinal", Integer.class),
                rows.getString("active_target_digest"), rows.getObject("active_attempt_epoch", Long.class),
                rows.getObject("active_unit_deadline_at", OffsetDateTime.class) == null ? null
                        : rows.getObject("active_unit_deadline_at", OffsetDateTime.class).toInstant(),
                rows.getInt("receipt_count"), rows.getInt("admission_count"),
                rows.getString("terminal_reason_code") == null ? null : BulkUnitReasonCode.valueOf(rows.getString("terminal_reason_code")));
    }

    private static BulkExecutionSnapshot snapshot(ExecutionRow row) {
        return new BulkExecutionSnapshot(row.executionId(), row.proposalId(), row.status(), row.nextOrdinal(),
                row.targetCount(), row.receiptCount(), row.admissionCount(), row.deadlineAt(),
                new BulkExecutionControl(row.executionId(), row.ownerId(), row.ownerEpoch()), row.terminalReasonCode());
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
            case RUNNING, UNIT_IN_FLIGHT, RECONCILIATION_REQUIRED, STOPPED -> execution.nextOrdinal();
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
        Object id = evidence.target().id();
        String type = id instanceof Integer ? "integer" : "string";
        return digest("praxis.bulk.unit/1", evaluation.snapshot().fingerprint(), Integer.toString(ordinal),
                type, id.toString(), evidence.target().expectedVersion());
    }

    private static String reservationFingerprint(Evaluation evaluation, String structuralRevision) {
        BulkFingerprintContext context = evaluation.proposal().snapshot().context();
        return digest("praxis.bulk.reservation/1", evaluation.proposal().id().toString(),
                evaluation.proposal().snapshot().fingerprint(), evaluation.snapshot().fingerprint(),
                context.namespaceId(), context.subjectId(), context.resourceKey(),
                context.operationRef().operationId(), structuralRevision);
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
    private record Evaluation(BulkStoredProposal proposal, BulkEvaluationSnapshot snapshot) { }
    private record ReservationWrite(BulkExecutionSnapshot snapshot, boolean created) { }
    private record Attempt(UUID attemptId, int ordinal, String targetDigest, long epoch, Instant unitDeadline,
            long monotonicDeadlineNanos) { }
    private record Receipt(int ordinal, String targetDigest, String expectedVersion, UUID attemptId,
            long epoch, BulkUnitOutcome outcome, Instant confirmedAt, Instant unitDeadline) { }
    private record Preparation(Attempt attempt, Receipt receipt, AdmissionRecord admission, BulkExecutionSnapshot stoppedSnapshot) { }
    private record AdmissionRecord(int ordinal, String targetDigest, String expectedVersion, UUID attemptId,
            long epoch, BulkItemStatus status, BulkUnitReasonCode reasonCode, Instant recordedAt) { }
    private record UnitWrite(Receipt receipt, AdmissionRecord admission, BulkUnitReasonCode stopReason,
            BulkExecutionSnapshot snapshot) { }
    private record ExecutionRow(UUID executionId, UUID proposalId, String namespaceId, String subjectId,
            String resourceKey, String operationId, String reservationFingerprint, String inputFingerprint,
            String evaluationFingerprint, String structuralRevision, String ownerId, long ownerEpoch,
            BulkDurableExecutionStatus status, int nextOrdinal, int targetCount, Instant deadlineAt,
            UUID activeAttemptId, Integer activeAttemptOrdinal, String activeTargetDigest,
            Long activeAttemptEpoch, Instant activeUnitDeadline, int receiptCount, int admissionCount,
            BulkUnitReasonCode terminalReasonCode) { }
}
