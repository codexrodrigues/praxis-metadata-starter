package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.Map;
import java.util.Set;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit, deterministic deployment bindings for isolated PostgreSQL test databases. */
final class BulkPostgresTestSupport {
    static final String DEPLOYMENT_ID = "deployment-test";
    private static final BulkExecutionRoleConfiguration TEST_ROLE_CONFIGURATION =
            new BulkExecutionRoleConfiguration("postgres", java.util.Set.of("bulk_runtime_test", "durable_runtime"),
                    java.util.Set.of(), java.util.Set.of());

    private BulkPostgresTestSupport() { }

    static int migrate(DataSource dataSource, String namespaceId) {
        return migrate(dataSource, Map.of(namespaceId, DEPLOYMENT_ID));
    }

    static int migrate(DataSource dataSource, Map<String, String> namespaceBindings) {
        var jdbc = new JdbcTemplate(dataSource);
        boolean runtimePrivilegesAlreadyProvisioned = Boolean.TRUE.equals(jdbc.queryForObject("""
                select case when to_regclass('praxis_bulk.praxis_bulk_proposal') is not null
                                  and exists (select 1 from pg_roles where rolname='bulk_runtime_test')
                            then has_table_privilege('bulk_runtime_test', 'praxis_bulk.praxis_bulk_proposal', 'select')
                            else false end
                """, Boolean.class));
        if (runtimePrivilegesAlreadyProvisioned) {
            return BulkExecutionMigrator.migrate(dataSource, namespaceBindings, TEST_ROLE_CONFIGURATION);
        }
        int applied = BulkExecutionMigrator.migrate(dataSource, namespaceBindings);
        grantRuntimeRole(dataSource, "bulk_runtime_test");
        grantRuntimeRole(dataSource, "durable_runtime");
        BulkExecutionMigrator.validate(dataSource, TEST_ROLE_CONFIGURATION);
        return applied;
    }

    static DataSource runtimeDataSource(EmbeddedPostgres postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl("bulk_runtime_test", "postgres"),
                "bulk_runtime_test", "");
    }

    static BulkExecutionRoleConfiguration testRoleConfiguration() {
        return TEST_ROLE_CONFIGURATION;
    }

    /**
     * Grants the bounded runtime access used by PostgreSQL integration tests. Keep this in
     * lockstep with BulkExecutionMigrator's canonical runtime table/function allowlists.
     */
    static BulkExecutionRoleConfiguration grantRuntimeRole(DataSource schemaOwner, String runtimeRole) {
        if (runtimeRole == null || !runtimeRole.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Test runtime role must be a simple PostgreSQL identifier");
        }
        var admin = new JdbcTemplate(schemaOwner);
        admin.execute("do $$ begin create role " + runtimeRole
                + " login; exception when duplicate_object then null; end $$");
        admin.execute("grant usage on schema praxis_bulk to " + runtimeRole);
        admin.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to " + runtimeRole);
        admin.execute("grant update (deployment_id) on praxis_bulk.praxis_bulk_namespace_binding to " + runtimeRole);
        admin.execute("grant select, update (deployment_id) on praxis_bulk.praxis_bulk_deployment_bucket to " + runtimeRole);
        admin.execute("grant select, insert, update (deployment_id) on praxis_bulk.praxis_bulk_subject_bucket to " + runtimeRole);
        admin.execute("grant select, insert, update (proposal_id) on praxis_bulk.praxis_bulk_proposal to " + runtimeRole);
        admin.execute("grant select, insert on praxis_bulk.praxis_bulk_evaluation to " + runtimeRole);
        if (Boolean.TRUE.equals(admin.queryForObject(
                "select to_regclass('praxis_bulk.praxis_bulk_target_manifest') is not null", Boolean.class))) {
            admin.execute("grant select, insert on praxis_bulk.praxis_bulk_target_manifest to " + runtimeRole);
        }
        if (Boolean.TRUE.equals(admin.queryForObject(
                "select to_regclass('praxis_bulk.praxis_bulk_preview_state') is not null", Boolean.class))) {
            admin.execute("grant select, insert on praxis_bulk.praxis_bulk_preview_state to " + runtimeRole);
            admin.execute("grant select, insert on praxis_bulk.praxis_bulk_target_preview to " + runtimeRole);
        }
        if (Boolean.TRUE.equals(admin.queryForObject(
                "select to_regclass('praxis_bulk.praxis_bulk_preview_item_integrity') is not null", Boolean.class))) {
            admin.execute("grant select, insert on praxis_bulk.praxis_bulk_preview_item_integrity to " + runtimeRole);
        }
        admin.execute("grant select, insert, update on praxis_bulk.praxis_bulk_execution to " + runtimeRole);
        admin.execute("grant select, insert on praxis_bulk.praxis_bulk_item_receipt to " + runtimeRole);
        admin.execute("grant select, insert on praxis_bulk.praxis_bulk_admission to " + runtimeRole);
        for (String table : Set.of("praxis_bulk_atomic_receipt", "praxis_bulk_atomic_item_result",
                "praxis_bulk_atomic_effect_ref", "praxis_bulk_atomic_rejection")) {
            if (Boolean.TRUE.equals(admin.queryForObject(
                    "select to_regclass(?) is not null", Boolean.class, "praxis_bulk." + table))) {
                admin.execute("grant select, insert on praxis_bulk." + table + " to " + runtimeRole);
            }
        }
        admin.execute("grant select, insert, update (state, released_at, release_reason) "
                + "on praxis_bulk.praxis_bulk_allocation to " + runtimeRole);
        admin.execute("grant select on praxis_bulk.praxis_bulk_tombstone to " + runtimeRole);
        admin.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to " + runtimeRole);
        if (Boolean.TRUE.equals(admin.queryForObject(
                "select to_regprocedure('praxis_bulk.lock_openapi_publication(text,text)') is not null", Boolean.class))) {
            admin.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to " + runtimeRole);
        }
        if (Boolean.TRUE.equals(admin.queryForObject("""
                select to_regprocedure('praxis_bulk.assert_preview_integrity_complete()') is not null
                """, Boolean.class))) {
            admin.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to "
                    + runtimeRole);
        }
        if (Boolean.TRUE.equals(admin.queryForObject(
                "select to_regprocedure('praxis_bulk.atomic_evidence_complete(uuid,integer)') is not null", Boolean.class))) {
            admin.execute("grant execute on function praxis_bulk.atomic_evidence_complete(uuid,integer) to "
                    + runtimeRole);
        }
        return new BulkExecutionRoleConfiguration("postgres", Set.of(runtimeRole), Set.of(), Set.of());
    }

    static void grantControlRole(DataSource schemaOwner, String controlRole) {
        if (controlRole == null || !controlRole.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Test control role must be a simple PostgreSQL identifier");
        }
        var admin = new JdbcTemplate(schemaOwner);
        admin.execute("do $$ begin create role " + controlRole
                + " login; exception when duplicate_object then null; end $$");
        admin.execute("grant usage on schema praxis_bulk to " + controlRole);
        String transitionArguments = Boolean.TRUE.equals(admin.queryForObject(
                "select to_regprocedure('praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text)') is not null", Boolean.class))
                ? "text,text,bigint,text,text,text,bigint,text" : "text,text,bigint,text,text,text";
        admin.execute("grant execute on function praxis_bulk.transition_operation_control(" + transitionArguments + ") to " + controlRole);
        if (Boolean.TRUE.equals(admin.queryForObject(
                "select to_regprocedure('praxis_bulk.transition_openapi_publication(text,text,bigint,text,text)') is not null", Boolean.class))) {
            admin.execute("grant execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) to "
                    + controlRole);
        }
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

    /** Test-only current publication observation through the real physical transaction. */
    static JdbcBulkOpenApiPublication.Snapshot publication(DataSource schemaOwner, String namespaceId) {
        String deployment = new JdbcTemplate(schemaOwner).queryForObject(
                "select deployment_id from praxis_bulk.praxis_bulk_namespace_binding where namespace_id=?", String.class, namespaceId);
        try (var connection = schemaOwner.getConnection()) {
            connection.setAutoCommit(false);
            var result = JdbcBulkOpenApiPublication.lockForRead(connection, namespaceId, deployment);
            connection.commit(); return result;
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Unable to observe the test publication", failure);
        }
    }

    /** Fixture publication only; never presented as a production OpenAPI capture. */
    static void publishFixture(DataSource schemaOwner, String namespaceId) {
        String deployment = new JdbcTemplate(schemaOwner).queryForObject(
                "select deployment_id from praxis_bulk.praxis_bulk_namespace_binding where namespace_id=?", String.class, namespaceId);
        try (var connection = schemaOwner.getConnection()) {
            connection.setAutoCommit(false);
            var current = JdbcBulkOpenApiPublication.lockForRead(connection, namespaceId, deployment);
            if (!current.published()) {
                long generation = current.generation();
                if ("UNCOMPOSED".equals(current.state())) generation = JdbcBulkOpenApiPublication.transition(connection,
                        namespaceId, deployment, generation, JdbcBulkOpenApiPublication.Target.SUSPENDED, null).generation();
                if (!JdbcBulkOpenApiPublication.transition(connection, namespaceId, deployment, generation,
                        JdbcBulkOpenApiPublication.Target.PUBLISHED, "sha256:" + "0".repeat(64)).applied())
                    throw new java.sql.SQLException("Test publication lost CAS");
            }
            connection.commit();
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Unable to publish the test fixture", failure); }
    }

    /**
     * Concrete test-only publication and operation composition. The fixed digest is fixture
     * evidence, not a production OpenAPI capture or a claim of operational readiness.
     * Historical migration fixtures retain their historical physical READY shape only.
     */
    static void ready(DataSource dataSource, String namespaceId, String operationId) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                insert into praxis_bulk.praxis_bulk_operation_control
                    (namespace_id, operation_id, state, generation, descriptor_fingerprint,
                     structural_revision, updated_at)
                values (?, ?, 'UNCOMPOSED', 0, null, null, clock_timestamp())
                on conflict (namespace_id, operation_id) do nothing
                """, namespaceId, operationId);
        boolean publicationBound = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from information_schema.columns where table_schema='praxis_bulk'
                 and table_name='praxis_bulk_operation_control' and column_name='publication_generation')
                """, Boolean.class));
        if (!publicationBound) {
            jdbc.update("""
                    update praxis_bulk.praxis_bulk_operation_control
                    set state='READY', generation=generation+1, descriptor_fingerprint=?,
                        structural_revision='structural-r1', updated_at=clock_timestamp()
                    where namespace_id=? and operation_id=? and state in ('UNCOMPOSED','SUSPENDED')
                    """, "sha256:" + "0".repeat(64), namespaceId, operationId);
            return;
        }
        String deployment = jdbc.queryForObject("select deployment_id from praxis_bulk.praxis_bulk_namespace_binding where namespace_id=?",
                String.class, namespaceId);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var publication = JdbcBulkOpenApiPublication.lockForRead(connection, namespaceId, deployment);
                if (!publication.published()) {
                    long generation = publication.generation();
                    if ("UNCOMPOSED".equals(publication.state())) {
                        var suspended = JdbcBulkOpenApiPublication.transition(connection, namespaceId, deployment,
                                generation, JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
                        if (!suspended.applied()) throw new java.sql.SQLException("Test publication lost suspension CAS");
                        generation = suspended.generation();
                    }
                    var published = JdbcBulkOpenApiPublication.transition(connection, namespaceId, deployment,
                            generation, JdbcBulkOpenApiPublication.Target.PUBLISHED, "sha256:" + "0".repeat(64));
                    if (!published.applied()) throw new java.sql.SQLException("Test publication lost publication CAS");
                    publication = JdbcBulkOpenApiPublication.lockForRead(connection, namespaceId, deployment);
                }
                var control = JdbcBulkOperationControl.lockForAdmission(connection, namespaceId, operationId);
                if (control == null) throw new java.sql.SQLException("Test control publication tuple is corrupt");
                if (!control.ready()) {
                    var changed = JdbcBulkOperationControl.transition(connection, namespaceId, operationId, control.generation(),
                            JdbcBulkOperationControl.Target.READY, "sha256:" + "0".repeat(64), "structural-r1",
                            publication.generation(), publication.documentDigest());
                    if (!changed.applied()) throw new java.sql.SQLException("Test control lost CAS");
                }
                connection.commit();
            } catch (java.sql.SQLException | RuntimeException failure) {
                connection.rollback(); throw failure;
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Unable to compose the test-only publication fence", failure);
        }
    }
}
