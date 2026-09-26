package org.praxisplatform.uischema.bulk;

import java.util.Map;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit, deterministic deployment bindings for isolated PostgreSQL test databases. */
final class BulkPostgresTestSupport {
    static final String DEPLOYMENT_ID = "deployment-test";

    private BulkPostgresTestSupport() { }

    static int migrate(DataSource dataSource, String namespaceId) {
        return BulkExecutionMigrator.migrate(dataSource, Map.of(namespaceId, DEPLOYMENT_ID));
    }

    static int migrate(DataSource dataSource, Map<String, String> namespaceBindings) {
        return BulkExecutionMigrator.migrate(dataSource, namespaceBindings);
    }

    static void insertLegacyProposal(JdbcTemplate jdbc, BulkStoredProposal proposal) {
        jdbc.update("""
                insert into praxis_bulk.praxis_bulk_proposal
                    (proposal_id, namespace_id, subject_id, resource_key, operation_id,
                     created_at, expires_at, fingerprint, payload)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, proposal.id(), proposal.snapshot().context().namespaceId(),
                proposal.snapshot().context().subjectId(), proposal.snapshot().context().resourceKey(),
                proposal.snapshot().context().operationRef().operationId(),
                OffsetDateTime.ofInstant(proposal.createdAt(), java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(proposal.expiresAt(), java.time.ZoneOffset.UTC),
                proposal.snapshot().fingerprint(), BulkSnapshotStorageCodec.encode(proposal.snapshot()));
    }

    static void insertLegacyInput(JdbcTemplate jdbc, BulkEvaluationSnapshot evaluation) {
        BulkStoredProposal proposal = evaluation.proposal();
        insertLegacyProposal(jdbc, proposal);
        jdbc.update("""
                insert into praxis_bulk.praxis_bulk_evaluation
                    (proposal_id, input_fingerprint, evaluation_fingerprint, payload)
                values (?, ?, ?, ?)
                """, proposal.id(), proposal.snapshot().fingerprint(), evaluation.fingerprint(),
                BulkEvaluationStorageCodec.encode(evaluation));
    }

    /** Explicit composition fixture for tests that exercise a runtime mutation path. */
    static void ready(DataSource dataSource, String namespaceId, String operationId) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                insert into praxis_bulk.praxis_bulk_operation_control
                    (namespace_id, operation_id, state, generation, descriptor_fingerprint,
                     structural_revision, updated_at)
                values (?, ?, 'UNCOMPOSED', 0, null, null, clock_timestamp())
                on conflict (namespace_id, operation_id) do nothing
                """, namespaceId, operationId);
        jdbc.update("""
                update praxis_bulk.praxis_bulk_operation_control
                set state='READY', generation=generation+1, descriptor_fingerprint=?,
                    structural_revision='structural-r1', updated_at=clock_timestamp()
                where namespace_id=? and operation_id=? and state in ('UNCOMPOSED','SUSPENDED')
                """, "sha256:" + "0".repeat(64), namespaceId, operationId);
    }
}
