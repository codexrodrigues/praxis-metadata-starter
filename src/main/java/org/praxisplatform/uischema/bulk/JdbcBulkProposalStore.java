package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
