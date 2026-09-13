package org.praxisplatform.uischema.bulk;

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
        var snapshot = proposal.snapshot(); var context = snapshot.context(); requireNamespace(context);
        byte[] payload = BulkSnapshotStorageCodec.encode(snapshot);
        try {
            infrastructure.withConnection(connection -> {
                try (var statement = connection.prepareStatement("""
                        insert into praxis_bulk.praxis_bulk_proposal
                        (proposal_id, namespace_id, subject_id, resource_key, operation_id, created_at, expires_at, fingerprint, payload)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, proposal.id()); statement.setString(2, context.namespaceId());
                    statement.setString(3, context.subjectId()); statement.setString(4, context.resourceKey());
                    statement.setString(5, context.operationRef().operationId());
                    statement.setObject(6, proposal.createdAt().atOffset(ZoneOffset.UTC));
                    statement.setObject(7, proposal.expiresAt().atOffset(ZoneOffset.UTC));
                    statement.setString(8, snapshot.fingerprint()); statement.setBytes(9, payload);
                    statement.executeUpdate(); return null;
                }
            });
        } catch (DataAccessException error) { throw safe(error); }
    }

    /** Scope comes from trusted server context. Current grants/policy/expiry must still be checked by the caller. */
    public Optional<BulkStoredProposal> find(BulkFingerprintContext scope, UUID id) {
        Objects.requireNonNull(id, "id"); requireNamespace(scope);
        try {
            return infrastructure.withConnection(connection -> {
                try (var statement = connection.prepareStatement("""
                        select created_at, expires_at, fingerprint, payload
                        from praxis_bulk.praxis_bulk_proposal
                        where proposal_id=? and namespace_id=? and subject_id=? and resource_key=? and operation_id=?
                        """)) {
                    statement.setObject(1, id); statement.setString(2, scope.namespaceId());
                    statement.setString(3, scope.subjectId()); statement.setString(4, scope.resourceKey());
                    statement.setString(5, scope.operationRef().operationId());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) return Optional.empty();
                        try {
                            var snapshot = BulkSnapshotStorageCodec.decode(rows.getBytes(4), rows.getString(3));
                            var stored = snapshot.context();
                            if (!stored.namespaceId().equals(scope.namespaceId()) || !stored.subjectId().equals(scope.subjectId())
                                    || !stored.resourceKey().equals(scope.resourceKey())
                                    || !stored.operationRef().operationId().equals(scope.operationRef().operationId())) {
                                throw new IllegalArgumentException("Protected scope mismatch");
                            }
                            return Optional.of(new BulkStoredProposal(id,
                                    rows.getObject(1, OffsetDateTime.class).toInstant(),
                                    rows.getObject(2, OffsetDateTime.class).toInstant(), snapshot));
                        } catch (RuntimeException error) {
                            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
                        }
                    }
                }
            });
        } catch (DataAccessException error) { throw safe(error); }
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
