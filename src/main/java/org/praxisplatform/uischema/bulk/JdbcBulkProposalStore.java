package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;

/** Append-only protected input storage. It does not evaluate, authorize, admit or execute a proposal. */
public final class JdbcBulkProposalStore {
    private final BulkExecutionInfrastructure infrastructure;
    public JdbcBulkProposalStore(BulkExecutionInfrastructure infrastructure) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
    }

    /** Returned insertion is provisional until the owning transaction commits. Duplicate IDs are conflicts. */
    public void insert(BulkStoredProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        requireLegacySelection(proposal);
        requireNamespace(proposal.snapshot().context());
        requireControlExpectation(proposal);
        try {
            infrastructure.withConnection(connection -> {
                BulkQuotaLedger.Scope quota = BulkQuotaLedger.lockProposal(connection, infrastructure, proposal, false, true);
                insertProposal(connection, proposal);
                BulkQuotaLedger.insertPending(connection, proposal, quota);
                return null;
            });
        } catch (DataAccessException error) { throw safe(error); }
    }

    /**
     * Persists protected input and its immutable evaluation evidence in the caller's one required
     * transaction. A companion failure marks that transaction rollback-only, including when a
     * caller later catches the safe storage exception.
     */
    public void insertEvaluated(BulkEvaluationSnapshot evaluation, BulkPreviewProjection preview) {
        Objects.requireNonNull(evaluation, "evaluation");
        requireLegacySelection(evaluation.proposal());
        Objects.requireNonNull(preview, "preview").requireMatches(evaluation);
        BulkStoredProposal proposal = evaluation.proposal();
        requireNamespace(proposal.snapshot().context());
        requireControlExpectation(proposal);
        byte[] evaluationPayload = BulkEvaluationStorageCodec.encode(evaluation);
        try {
            infrastructure.withConnection(connection -> {
                BulkQuotaLedger.Scope quota = BulkQuotaLedger.lockProposal(connection, infrastructure, proposal, false, true);
                insertProposal(connection, proposal);
                insertEvaluation(connection, proposal, evaluation, evaluationPayload);
                BulkOrdinalManifest.insert(connection, evaluation);
                BulkPreviewStorage.insert(connection, evaluation, preview);
                BulkQuotaLedger.insertPending(connection, proposal, quota);
                return null;
            });
        } catch (DataAccessException error) { throw safe(error); }
    }

    /**
     * Captures and stores one EXPLICIT/SYNC evaluation in the caller's writable REPEATABLE READ
     * operational transaction. The quota/control locks precede the trusted capture callback;
     * no protected row or allocation is durable until that same transaction commits.
     * The callback must retain neither the connection nor its supplier and must not manage transactions.
     */
    public BulkEvaluationSnapshot captureAndInsertEvaluated(BulkStoredProposal proposal,
            BiFunction<Connection, Supplier<Duration>, BulkEvaluationSnapshot> capture,
            Function<BulkEvaluationSnapshot, BulkPreviewProjection> project,
            Supplier<Duration> callerRemaining) {
        return captureAndInsertEvaluated(null, proposal, capture, project, callerRemaining);
    }

    /** QUERY requires a lifecycle-minted, publication-bound admission before this transaction. */
    public BulkEvaluationSnapshot captureAndInsertEvaluated(BulkOperationLifecycle.ReadyAdmission admission,
            BulkStoredProposal proposal,
            BiFunction<Connection, Supplier<Duration>, BulkEvaluationSnapshot> capture,
            Function<BulkEvaluationSnapshot, BulkPreviewProjection> project,
            Supplier<Duration> callerRemaining) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(callerRemaining, "callerRemaining");
        requireNamespace(proposal.snapshot().context());
        requireControlExpectation(proposal);
        boolean query = "QUERY".equals(proposal.snapshot().intent().at("/selection/mode").asText());
        if (!"SYNC".equals(proposal.snapshot().intent().path("executionMode").asText())
                || query && (admission == null || !matchesQueryAdmission(admission, proposal))
                || !query && admission != null
                || !query && proposal.snapshot().mode() != BulkMode.PER_ITEM_UPDATE
                    && !"EXPLICIT".equals(proposal.snapshot().intent().at("/selection/mode").asText()))
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
        long began = System.nanoTime();
        try {
            return infrastructure.withConnection(connection -> {
                requireCaptureTransaction(connection);
                Duration initial = positive(callerRemaining.get());
                CaptureBudget budget = new CaptureBudget(connection, proposal, callerRemaining,
                        began, initial);
                Throwable failure = null;
                boolean capturingSelection = false;
                try {
                    budget.check();
                    BulkQuotaLedger.Scope quota = BulkQuotaLedger.lockProposal(
                            connection, infrastructure, proposal, false, true);
                    budget.check();
                    capturingSelection = true;
                    BulkEvaluationSnapshot evaluation = Objects.requireNonNull(capture.apply(connection, budget),
                            "capture returned no evaluation");
                    if (query && (evaluation.targets().isEmpty()
                            || evaluation.targets().size() > admission.maxTargets()))
                        throw BulkProposalStorageException.invalidSelection();
                    capturingSelection = false;
                    requireCapturedProposal(proposal, evaluation.proposal());
                    budget.check();
                    BulkPreviewProjection preview = Objects.requireNonNull(project.apply(evaluation),
                            "project returned no preview");
                    preview.requireMatches(evaluation);
                    budget.check();
                    byte[] evaluationPayload = BulkEvaluationStorageCodec.encode(evaluation);
                    budget.check();
                    insertProposal(connection, proposal);
                    budget.check();
                    insertEvaluation(connection, proposal, evaluation, evaluationPayload);
                    budget.check();
                    BulkOrdinalManifest.insert(connection, evaluation);
                    budget.check();
                    BulkPreviewStorage.insert(connection, evaluation, preview);
                    budget.check();
                    BulkQuotaLedger.insertPending(connection, proposal, quota);
                    budget.check();
                    return evaluation;
                } catch (RuntimeException error) {
                    failure = error;
                    if (error instanceof BulkProposalStorageException storage
                            && (storage.reason() != BulkProposalStorageException.Reason.INVALID_SELECTION
                                || capturingSelection)) throw storage;
                    throw unavailable();
                } catch (SQLException | Error error) {
                    failure = error;
                    throw error;
                } finally {
                    try { budget.restore(); }
                    catch (SQLException restore) {
                        if (failure != null) failure.addSuppressed(restore);
                        else throw restore;
                    }
                }
            });
        } catch (DataAccessException error) { throw safe(error); }
        catch (BulkProposalStorageException safe) { throw safe; }
        catch (RuntimeException unsafe) { throw unavailable(); }
    }

    private static void requireLegacySelection(BulkStoredProposal proposal) {
        if ("QUERY".equals(proposal.snapshot().intent().at("/selection/mode").asText()))
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
    }

    private static boolean matchesQueryAdmission(BulkOperationLifecycle.ReadyAdmission admission,
            BulkStoredProposal proposal) {
        var snapshot = proposal.snapshot();
        return admission.mode() == BulkMode.UNIFORM_UPDATE
                && snapshot.mode() == BulkMode.UNIFORM_UPDATE
                && admission.atomicity() == org.praxisplatform.uischema.action.ActionCollectionAtomicity.PER_ITEM
                && snapshot.context().atomicity() == admission.atomicity()
                && admission.executionMode() == BulkExecutionMode.SYNC
                && admission.selectionMode() == BulkSelectionMode.QUERY
                && admission.identity().namespaceId().equals(snapshot.context().namespaceId())
                && admission.identity().confirmationOperationId().equals(snapshot.context().operationRef().operationId())
                && admission.expectation().equals(proposal.controlExpectation())
                && admission.maxTargets() >= 1 && admission.maxTargets() <= 200;
    }

    private static void requireCaptureTransaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit() || connection.isReadOnly())
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select current_setting('transaction_isolation'), current_setting('transaction_read_only')")) {
            if (!rows.next() || !"repeatable read".equals(rows.getString(1))
                    || !"off".equals(rows.getString(2)) || rows.next())
                throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
        }
    }

    private static void requireCapturedProposal(BulkStoredProposal input, BulkStoredProposal result) {
        if (!input.id().equals(result.id())
                || !input.createdAt().equals(result.createdAt())
                || !input.expiresAt().equals(result.expiresAt())
                || !input.snapshot().context().equals(result.snapshot().context())
                || !input.snapshot().fingerprint().equals(result.snapshot().fingerprint())
                || !input.controlExpectation().equals(result.controlExpectation()))
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
    }

    private static Duration positive(Duration remaining) {
        if (remaining == null || remaining.isNegative() || remaining.isZero())
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
        return remaining;
    }

    private static final class CaptureBudget implements Supplier<Duration> {
        private final Connection connection;
        private final BulkStoredProposal proposal;
        private final Supplier<Duration> caller;
        private final long began;
        private final Duration initial;
        private final Duration expiryWindow;
        private final long expiryBegan;
        private final String priorStatement;
        private final String priorLock;

        CaptureBudget(Connection connection, BulkStoredProposal proposal, Supplier<Duration> caller,
                long began, Duration initial) throws SQLException {
            this.connection = connection;
            this.proposal = proposal;
            this.caller = caller;
            this.began = began;
            this.initial = initial;
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "select current_setting('statement_timeout'), current_setting('lock_timeout')")) {
                if (!rows.next()) throw unavailable();
                priorStatement = rows.getString(1);
                priorLock = rows.getString(2);
                if (rows.next()) throw unavailable();
            }
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("select clock_timestamp()")) {
                if (!rows.next()) throw unavailable();
                expiryWindow = Duration.between(rows.getObject(1, OffsetDateTime.class).toInstant(),
                        proposal.expiresAt());
                if (rows.next() || expiryWindow.isNegative() || expiryWindow.isZero()) throw unavailable();
            }
            expiryBegan = System.nanoTime();
        }

        @Override public Duration get() {
            Duration remaining = positive(caller.get());
            long elapsed = Math.max(0L, System.nanoTime() - began);
            Duration own = Duration.ofSeconds(30).minusNanos(elapsed);
            Duration original = initial.minusNanos(elapsed);
            Duration expiry = expiryWindow.minusNanos(Math.max(0L, System.nanoTime() - expiryBegan));
            remaining = least(remaining, least(own, least(original, expiry)));
            if (remaining.isNegative() || remaining.isZero()) throw unavailable();
            try { tighten(remaining); }
            catch (SQLException error) { throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE); }
            return remaining;
        }

        void check() throws SQLException {
            get();
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("select clock_timestamp()")) {
                if (!rows.next() || !rows.getObject(1, OffsetDateTime.class).toInstant().isBefore(proposal.expiresAt())
                        || rows.next()) throw unavailable();
            }
        }

        private void tighten(Duration remaining) throws SQLException {
            long milliseconds = remaining.toMillis();
            if (milliseconds < 1) throw unavailable();
            String limit = milliseconds + "ms";
            try (var statement = connection.prepareStatement("""
                    select set_config('statement_timeout',
                               case when current_setting('statement_timeout') = '0'
                                      or current_setting('statement_timeout')::interval > ?::interval
                                    then ? else current_setting('statement_timeout') end, true),
                           set_config('lock_timeout',
                               case when current_setting('lock_timeout') = '0'
                                      or current_setting('lock_timeout')::interval > ?::interval
                                    then ? else current_setting('lock_timeout') end, true)
                    """)) {
                statement.setString(1, limit); statement.setString(2, limit);
                statement.setString(3, limit); statement.setString(4, limit);
                statement.execute();
            }
        }

        void restore() throws SQLException {
            try (var statement = connection.prepareStatement("""
                    select set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)
                    """)) {
                statement.setString(1, priorStatement); statement.setString(2, priorLock);
                statement.execute();
            }
        }

        private static Duration least(Duration left, Duration right) {
            return left.compareTo(right) <= 0 ? left : right;
        }
    }

    private static BulkProposalStorageException unavailable() {
        return new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
    }

    /** Scope comes from trusted server context. Current grants/policy/expiry must still be checked by the caller. */
    public Optional<BulkStoredProposal> find(BulkFingerprintContext scope, UUID id) {
        Objects.requireNonNull(id, "id"); requireNamespace(scope);
        try {
            return infrastructure.withConnection(connection -> readProposal(connection, scope, id));
        } catch (DataAccessException error) { throw safe(error); }
    }

    /** Shared protected decoder; callers choose their own transaction/isolation boundary. */
    static Optional<BulkStoredProposal> readProposal(Connection connection,
            BulkFingerprintContext scope, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select created_at, expires_at, fingerprint, payload,
                       control_generation, control_descriptor_fingerprint, control_structural_revision,
                       atomicity, protocol_version
                from praxis_bulk.praxis_bulk_proposal
                where proposal_id=? and namespace_id=? and subject_id=? and resource_key=? and operation_id=?
                """)) {
            statement.setObject(1, id); statement.setString(2, scope.namespaceId());
            statement.setString(3, scope.subjectId()); statement.setString(4, scope.resourceKey());
            statement.setString(5, scope.operationRef().operationId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(decodedProposal(rows, id, scope.namespaceId(), scope.subjectId(),
                        scope.resourceKey(), scope.operationRef().operationId()));
            }
        }
    }

    /** Route-bound locator used only by the authorized read compositor before the creator is known. */
    static Optional<BulkStoredProposal> locateProposal(Connection connection, String namespaceId,
            String resourceKey, String operationId, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select created_at, expires_at, fingerprint, payload,
                       control_generation, control_descriptor_fingerprint, control_structural_revision,
                       atomicity, protocol_version, subject_id
                from praxis_bulk.praxis_bulk_proposal
                where proposal_id=? and namespace_id=? and resource_key=? and operation_id=?
                """)) {
            statement.setObject(1, id); statement.setString(2, namespaceId);
            statement.setString(3, resourceKey); statement.setString(4, operationId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(decodedProposal(rows, id, namespaceId, rows.getString("subject_id"),
                        resourceKey, operationId));
            }
        }
    }

    private static BulkStoredProposal decodedProposal(java.sql.ResultSet rows, UUID id,
            String namespaceId, String subjectId, String resourceKey, String operationId) throws SQLException {
        try {
            var snapshot = BulkSnapshotStorageCodec.decode(rows.getBytes(4), rows.getString(3));
            var stored = snapshot.context();
            if (!stored.atomicity().name().equals(rows.getString("atomicity"))
                    || (rows.getShort("protocol_version") != 1 && rows.getShort("protocol_version") != 2))
                throw new IllegalArgumentException("Protected atomicity or protocol mismatch");
            if (!stored.namespaceId().equals(namespaceId) || !stored.subjectId().equals(subjectId)
                    || !stored.resourceKey().equals(resourceKey)
                    || !stored.operationRef().operationId().equals(operationId)) {
                throw new IllegalArgumentException("Protected scope mismatch");
            }
            var result = new BulkStoredProposal(id,
                    rows.getObject(1, OffsetDateTime.class).toInstant(),
                    rows.getObject(2, OffsetDateTime.class).toInstant(), snapshot,
                    expectation(rows.getObject(5, Long.class), rows.getString(6), rows.getString(7)));
            if (rows.next()) throw new IllegalArgumentException("Duplicate protected proposal");
            return result;
        } catch (RuntimeException error) {
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
        }
    }

    private static void requireControlExpectation(BulkStoredProposal proposal) {
        if (proposal.controlExpectation() == null)
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
    }

    static BulkOperationControlExpectation expectation(Long generation, String fingerprint, String revision) {
        if (generation == null && fingerprint == null && revision == null) return null;
        try {
            if (generation == null || fingerprint == null || revision == null)
                throw new IllegalArgumentException("Incomplete persisted descriptor tuple");
            return new BulkOperationControlExpectation(generation, fingerprint, revision);
        } catch (RuntimeException error) {
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
        }
    }

    /**
     * Reads evidence only after the trusted, scope-bound protected input has been recovered.
     * It remains evidence of facts and plans; callers must independently revalidate policy,
     * grants, expiry and execution admission.
     */
    public Optional<BulkEvaluationSnapshot> findEvaluation(BulkFingerprintContext scope, UUID id) {
        Objects.requireNonNull(id, "id");
        requireNamespace(scope);
        Optional<BulkStoredProposal> proposal = find(scope, id);
        if (proposal.isEmpty()) return Optional.empty();
        BulkStoredProposal input = proposal.orElseThrow();
        try {
            return infrastructure.withConnection(connection -> readEvaluation(connection, input));
        } catch (DataAccessException error) { throw safe(error); }
    }

    /** Shared protected decoder; never establishes authorization or current eligibility. */
    static Optional<BulkEvaluationSnapshot> readEvaluation(Connection connection,
            BulkStoredProposal input) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select input_fingerprint, evaluation_fingerprint, payload
                from praxis_bulk.praxis_bulk_evaluation
                where proposal_id=?
                """)) {
            statement.setObject(1, input.id());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                try {
                    if (!input.snapshot().fingerprint().equals(rows.getString(1))) {
                        throw new IllegalArgumentException("Protected evaluation input binding mismatch");
                    }
                    var evaluation = BulkEvaluationStorageCodec.decode(input, rows.getBytes(3), rows.getString(2));
                    if (rows.next()) throw new IllegalArgumentException("Duplicate protected evaluation evidence");
                    return Optional.of(evaluation);
                } catch (RuntimeException error) {
                    throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
                }
            }
        }
    }

    private static void insertProposal(Connection connection, BulkStoredProposal proposal) throws SQLException {
        var snapshot = proposal.snapshot();
        var context = snapshot.context();
        byte[] payload = BulkSnapshotStorageCodec.encode(snapshot);
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_proposal
                (proposal_id, namespace_id, subject_id, resource_key, operation_id, created_at, expires_at,
                 fingerprint, payload, control_generation, control_descriptor_fingerprint, control_structural_revision,
                 atomicity, protocol_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 2)
                """)) {
            BulkOperationControlExpectation expectation = proposal.controlExpectation();
            statement.setObject(1, proposal.id()); statement.setString(2, context.namespaceId());
            statement.setString(3, context.subjectId()); statement.setString(4, context.resourceKey());
            statement.setString(5, context.operationRef().operationId());
            statement.setObject(6, proposal.createdAt().atOffset(ZoneOffset.UTC));
            statement.setObject(7, proposal.expiresAt().atOffset(ZoneOffset.UTC));
            statement.setString(8, snapshot.fingerprint()); statement.setBytes(9, payload);
            statement.setLong(10, expectation.generation());
            statement.setString(11, expectation.descriptorFingerprint());
            statement.setString(12, expectation.structuralRevision());
            statement.setString(13, context.atomicity().name());
            statement.executeUpdate();
        }
    }

    private static void insertEvaluation(Connection connection, BulkStoredProposal proposal,
            BulkEvaluationSnapshot evaluation, byte[] payload) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_evaluation
                (proposal_id, input_fingerprint, evaluation_fingerprint, payload)
                values (?, ?, ?, ?)
                """)) {
            statement.setObject(1, proposal.id());
            statement.setString(2, proposal.snapshot().fingerprint());
            statement.setString(3, evaluation.fingerprint());
            statement.setBytes(4, payload);
            statement.executeUpdate();
        }
    }

    private void requireNamespace(BulkFingerprintContext context) {
        Objects.requireNonNull(context, "context");
        if (!infrastructure.namespace().equals(context.namespaceId())) throw new IllegalArgumentException("Operational namespace mismatch");
    }
    private static BulkProposalStorageException safe(DataAccessException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState()))
                return new BulkProposalStorageException(BulkProposalStorageException.Reason.CONFLICT);
        return new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
    }
}
