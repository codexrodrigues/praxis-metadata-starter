package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Internal RS1 observation of protected input and evidence, never an HTTP representation. */
@JsonIgnoreType
final class BulkProtectedProposalReader {
    private final BulkExecutionInfrastructure infrastructure;

    BulkProtectedProposalReader(BulkExecutionInfrastructure infrastructure) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
    }

    enum Kind { ABSENT, NOT_EVALUATED, EVALUATED }

    @JsonIgnoreType
    static final class Observation {
        private final Kind kind;
        private final BulkStoredProposal proposal;
        private final BulkEvaluationSnapshot evaluation;

        private Observation(Kind kind, BulkStoredProposal proposal, BulkEvaluationSnapshot evaluation) {
            this.kind = kind;
            this.proposal = proposal;
            this.evaluation = evaluation;
        }

        Kind kind() { return kind; }
        BulkStoredProposal proposal() { return proposal; }
        BulkEvaluationSnapshot evaluation() { return evaluation; }
        @Override public String toString() { return "BulkProtectedProposalObservation[protected]"; }
    }

    /** Scope is trusted server context; authorization, retention and HTTP status are caller decisions. */
    Observation read(BulkFingerprintContext scope, UUID proposalId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(proposalId, "proposalId");
        if (!infrastructure.namespace().equals(scope.namespaceId()))
            throw new IllegalArgumentException("Operational namespace mismatch");
        try {
            return infrastructure.withConsistentRead(connection -> read(connection, scope, proposalId));
        } catch (BulkProposalStorageException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
        }
    }

    private static Observation read(Connection connection, BulkFingerprintContext scope,
            UUID proposalId) throws SQLException {
        var proposal = JdbcBulkProposalStore.readProposal(connection, scope, proposalId);
        if (proposal.isEmpty()) return new Observation(Kind.ABSENT, null, null);
        var stored = proposal.orElseThrow();
        var evaluation = JdbcBulkProposalStore.readEvaluation(connection, stored);
        if (evaluation.isEmpty()) {
            requireNoDependentRows(connection, proposalId);
            return new Observation(Kind.NOT_EVALUATED, stored, null);
        }
        return new Observation(Kind.EVALUATED, stored, evaluation.orElseThrow());
    }

    private static void requireNoDependentRows(Connection connection, UUID proposalId) throws SQLException {
        // Static table names are deliberately closed; no caller-supplied SQL identifiers.
        for (String table : new String[]{"praxis_bulk_target_manifest", "praxis_bulk_preview_state",
                "praxis_bulk_target_preview", "praxis_bulk_preview_item_integrity"}) {
            try (var statement = connection.prepareStatement(
                    "select 1 from praxis_bulk." + table + " where proposal_id=? limit 1")) {
                statement.setObject(1, proposalId);
                try (var rows = statement.executeQuery()) {
                    if (rows.next())
                        throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
                }
            }
        }
    }
}
