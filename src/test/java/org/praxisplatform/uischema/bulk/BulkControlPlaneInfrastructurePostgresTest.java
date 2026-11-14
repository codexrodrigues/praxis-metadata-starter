package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkControlPlaneInfrastructurePostgresTest {
    private static final String NAMESPACE = "bulk-control-test";
    private static final String OPERATION = "items.bulk.confirm";

    @Test
    void commitsCasUnderSeparateRoleAndRuntimeCanOnlyReadTheDurableFence() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var admin = postgres.getPostgresDatabase();
            var adminSql = new JdbcTemplate(admin);
            var identity = new BulkOperationControlIdentity(NAMESPACE, OPERATION);
            BulkExecutionMigrator.migrateWithOperations(admin, Map.of(NAMESPACE, "logical-deployment"), List.of(identity));

            adminSql.execute("create role bulk_runtime login");
            adminSql.execute("create role bulk_control login");
            adminSql.execute("create role bulk_retention_group nologin noinherit");
            adminSql.execute("create role bulk_retention_login login");
            adminSql.execute("grant praxis_bulk_retention_executor to bulk_retention_group");
            adminSql.execute("grant bulk_retention_group to bulk_retention_login");
            adminSql.execute("grant usage on schema praxis_bulk to bulk_runtime, bulk_control");
            adminSql.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to bulk_runtime");
            adminSql.execute("grant select, insert on praxis_bulk.praxis_bulk_target_manifest, praxis_bulk.praxis_bulk_preview_state, praxis_bulk.praxis_bulk_target_preview, praxis_bulk.praxis_bulk_preview_item_integrity to bulk_runtime");
            adminSql.execute("grant select, insert on praxis_bulk.praxis_bulk_atomic_receipt, praxis_bulk.praxis_bulk_atomic_item_result, praxis_bulk.praxis_bulk_atomic_effect_ref, praxis_bulk.praxis_bulk_atomic_rejection to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.atomic_evidence_complete(uuid,integer) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to bulk_runtime");
            adminSql.execute("grant select on praxis_bulk.praxis_bulk_capacity_marker, "
                    + "praxis_bulk.praxis_bulk_capacity_installation, praxis_bulk.praxis_bulk_capacity_slot, "
                    + "praxis_bulk.praxis_bulk_capacity_occupation to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.lock_capacity_marker(), "
                    + "praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_control");
            adminSql.execute("grant execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) to bulk_control");
            var roles = new BulkExecutionRoleConfiguration("postgres", Set.of("bulk_runtime"),
                    Set.of("bulk_retention_group", "bulk_retention_login"), Set.of("bulk_control"));
            BulkExecutionMigrator.validate(admin, roles);

            var runtimeDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_runtime", "postgres"), "bulk_runtime", "");
            var controlDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_control", "postgres"), "bulk_control", "");
            var runtime = new BulkExecutionInfrastructure(runtimeDs,
                    new DataSourceTransactionManager(runtimeDs), NAMESPACE, "logical-deployment", roles);
            var control = new BulkControlPlaneInfrastructure(controlDs,
                    new DataSourceTransactionManager(controlDs), NAMESPACE, "logical-deployment", "bulk_control", runtime);

            // This executes the canonical live catalog attestor through each least-privilege
            // login, not the provisioning/superuser connection used above.
            try (var runtimeConnection = runtimeDs.getConnection(); var controlConnection = controlDs.getConnection()) {
                BulkExecutionMigrator.validateLiveRuntimeRoleAccess(runtimeConnection, roles);
                BulkExecutionMigrator.validateLiveControlPlaneRoleAccess(controlConnection, roles, "bulk_control");
            }
            adminSql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_runtime");
            try (var runtimeConnection = runtimeDs.getConnection()) {
                assertThatThrownBy(() -> BulkExecutionMigrator.validateLiveRuntimeRoleAccess(runtimeConnection, roles))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("governed lifecycle function grants differ");
            }
            adminSql.execute("revoke execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) from bulk_runtime");
            BulkExecutionMigrator.validate(admin, roles);
            try (var runtimeConnection = runtimeDs.getConnection()) {
                BulkExecutionMigrator.validateLiveRuntimeRoleAccess(runtimeConnection, roles);
            }
            adminSql.execute("alter function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) owner to bulk_runtime");
            try (var runtimeConnection = runtimeDs.getConnection()) {
                assertThatThrownBy(() -> BulkExecutionMigrator.validateLiveRuntimeRoleAccess(runtimeConnection, roles))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("operation-control function has unexpected owner");
            }
            adminSql.execute("alter function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) owner to praxis_bulk_control_owner");
            BulkExecutionMigrator.validate(admin, roles);
            try (var runtimeConnection = admin.getConnection(); var statement = runtimeConnection.createStatement()) {
                statement.execute("set role bulk_control");
                assertThatThrownBy(() -> BulkExecutionMigrator.validateLiveControlPlaneRoleAccess(
                        runtimeConnection, roles, "bulk_control"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("session and effective PostgreSQL identities must match");
            }

            BulkPostgresTestSupport.publishFixture(admin, NAMESPACE);
            var ready = control.withConnection(connection -> JdbcBulkOperationControl.transition(connection,
                    NAMESPACE, OPERATION, 0, JdbcBulkOperationControl.Target.READY,
                    "sha256:" + "c".repeat(64), "structural-r1", 2L, "sha256:" + "0".repeat(64)));
            assertThat(ready).isEqualTo(new JdbcBulkOperationControl.Transition(true, 1));
            JdbcBulkOperationControl.Snapshot readySnapshot = runtime.withLifecycleRead(connection ->
                    JdbcBulkOperationControl.lockForAdmission(connection, NAMESPACE, OPERATION));
            assertThat(readySnapshot).isEqualTo(new JdbcBulkOperationControl.Snapshot(
                            "READY", 1, "sha256:" + "c".repeat(64), "structural-r1"));

            var suspended = control.withConnection(connection -> JdbcBulkOperationControl.transition(connection,
                    NAMESPACE, OPERATION, 1, JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null));
            assertThat(suspended).isEqualTo(new JdbcBulkOperationControl.Transition(true, 2));
            JdbcBulkOperationControl.Snapshot suspendedSnapshot = runtime.withLifecycleRead(connection ->
                    JdbcBulkOperationControl.lockForAdmission(connection, NAMESPACE, OPERATION));
            assertThat(suspendedSnapshot).isEqualTo(new JdbcBulkOperationControl.Snapshot(
                            "SUSPENDED", 2, null, null));
            assertThatThrownBy(() -> runtime.withLifecycleRead(connection ->
                    JdbcBulkOperationControl.transition(connection, NAMESPACE, OPERATION, 2,
                            JdbcBulkOperationControl.Target.READY, "sha256:" + "d".repeat(64), "structural-r2", 2L, "sha256:" + "0".repeat(64))))
                    .isInstanceOf(RuntimeException.class);

            // A cloned logical namespace in another database is not a valid control-plane peer.
            // Both connections have the same schema, namespace, deployment and role names; only
            // the transaction-scoped database-local advisory lock can establish the shared DB.
            adminSql.execute("create database bulk_control_other");
            var otherAdmin = new DriverManagerDataSource(
                    postgres.getJdbcUrl("postgres", "bulk_control_other"), "postgres", "");
            var otherAdminSql = new JdbcTemplate(otherAdmin);
            otherAdminSql.execute("grant praxis_bulk_retention_executor to bulk_retention_group");
            otherAdminSql.execute("grant bulk_retention_group to bulk_retention_login");
            BulkExecutionMigrator.migrate(otherAdmin,
                    Map.of(NAMESPACE, "logical-deployment"),
                    new BulkExecutionRoleConfiguration("postgres", Set.of(),
                            Set.of("bulk_retention_group", "bulk_retention_login"), Set.of()),
                    List.of(identity));
            otherAdminSql.execute("grant usage on schema praxis_bulk to bulk_runtime, bulk_control");
            otherAdminSql.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to bulk_runtime");
            otherAdminSql.execute("grant select, insert on praxis_bulk.praxis_bulk_target_manifest, praxis_bulk.praxis_bulk_preview_state, praxis_bulk.praxis_bulk_target_preview, praxis_bulk.praxis_bulk_preview_item_integrity to bulk_runtime");
            otherAdminSql.execute("grant select, insert on praxis_bulk.praxis_bulk_atomic_receipt, praxis_bulk.praxis_bulk_atomic_item_result, praxis_bulk.praxis_bulk_atomic_effect_ref, praxis_bulk.praxis_bulk_atomic_rejection to bulk_runtime");
            otherAdminSql.execute("grant execute on function praxis_bulk.atomic_evidence_complete(uuid,integer) to bulk_runtime");
            otherAdminSql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to bulk_runtime");
            otherAdminSql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_runtime");
            otherAdminSql.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to bulk_runtime");
            otherAdminSql.execute("grant select on praxis_bulk.praxis_bulk_capacity_marker, "
                    + "praxis_bulk.praxis_bulk_capacity_installation, praxis_bulk.praxis_bulk_capacity_slot, "
                    + "praxis_bulk.praxis_bulk_capacity_occupation to bulk_runtime");
            otherAdminSql.execute("grant execute on function praxis_bulk.lock_capacity_marker(), "
                    + "praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint) to bulk_runtime");
            otherAdminSql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_control");
            otherAdminSql.execute("grant execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) to bulk_control");
            BulkExecutionMigrator.validate(otherAdmin, roles);
            var otherControlDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_control", "bulk_control_other"), "bulk_control", "");
            var otherControl = new BulkControlPlaneInfrastructure(otherControlDs,
                    new DataSourceTransactionManager(otherControlDs), NAMESPACE,
                    "logical-deployment", "bulk_control", runtime);
            assertThatThrownBy(() -> otherControl.withConnection(connection ->
                    JdbcBulkOperationControl.transition(connection, NAMESPACE, OPERATION, 2,
                            JdbcBulkOperationControl.Target.READY, "sha256:" + "d".repeat(64), "structural-r2", 2L, "sha256:" + "0".repeat(64))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("same PostgreSQL database");

            adminSql.execute("alter role bulk_runtime set default_transaction_isolation='repeatable read'");
            var factory = org.mockito.Mockito.mock(org.springframework.orm.jpa.EntityManagerFactoryInfo.class,
                    org.mockito.Mockito.withSettings().extraInterfaces(jakarta.persistence.EntityManagerFactory.class));
            org.mockito.Mockito.when(factory.getDataSource()).thenReturn(runtimeDs);
            org.mockito.Mockito.when(factory.getJpaDialect()).thenReturn(new org.springframework.orm.jpa.vendor.HibernateJpaDialect());
            var jpa = new org.springframework.orm.jpa.JpaTransactionManager((jakarta.persistence.EntityManagerFactory) factory);
            var jpaBoundRuntime = new BulkExecutionInfrastructure(runtimeDs, jpa, NAMESPACE, "logical-deployment", roles,
                    new DataSourceTransactionManager(runtimeDs));
            assertThatThrownBy(() -> new BulkExecutionInfrastructure(runtimeDs, jpa, NAMESPACE,
                    "logical-deployment", roles, new DataSourceTransactionManager(controlDs)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same operational datasource");
            jpaBoundRuntime.withLifecycleRead(connection -> {
                assertThat(setting(connection, "transaction_isolation")).isEqualTo("read committed");
                assertThat(setting(connection, "default_transaction_isolation")).isEqualTo("repeatable read");
                return null;
            });
            var tenantRoute = new java.util.concurrent.atomic.AtomicReference<>("tenant_a");
            try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
                pool.setDataSource(runtimeDs);
                pool.setMaximumPoolSize(2);
                var routedDs = new SinglePoolSchemaRoutingDataSource(pool, () -> {
                    String tenant = tenantRoute.get();
                    if (tenant == null) throw new java.sql.SQLException("Tenant admission required", "42501");
                    return SinglePoolSchemaRoutingDataSource.SchemaRoute.of(tenant, "public", "public");
                });
                var routedManager = new DataSourceTransactionManager(routedDs);
                var routedRuntime = new BulkExecutionInfrastructure(routedDs, routedManager, NAMESPACE,
                        "logical-deployment", roles, routedManager);
                verifyPublicationRace(routedRuntime, control, adminSql);
                for (String tenant : List.of("tenant_a", "tenant_b")) {
                    tenantRoute.set(tenant);
                    assertThat(routedRuntime.readAttestedPublication().published()).isTrue();
                    try (var connection = routedDs.getConnection()) {
                        assertThat(setting(connection, "search_path")).isEqualTo(tenant + ", public");
                    }
                    new TransactionTemplate(routedManager).executeWithoutResult(status -> {
                        assertThat(routedRuntime.readAttestedPublication().published()).isTrue();
                        var bound = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(routedDs);
                        try {
                            assertThat(setting(bound, "search_path")).isEqualTo(tenant + ", public");
                        } catch (java.sql.SQLException failure) {
                            throw new AssertionError(failure);
                        } finally {
                            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(bound, routedDs);
                        }
                    });
                }
                tenantRoute.set(null);
                assertThatThrownBy(routedRuntime::readAttestedPublication)
                        .hasRootCauseInstanceOf(java.sql.SQLException.class)
                        .hasStackTraceContaining("Tenant admission required");
                try (var connection = pool.getConnection()) {
                    assertThat(setting(connection, "search_path")).doesNotContain("tenant_a", "tenant_b", "pg_temp");
                    assertThat(setting(connection, "default_transaction_isolation")).isEqualTo("repeatable read");
                }
            }
            assertThat(jpaBoundRuntime.readAttestedPublication().published()).isTrue();
            org.mockito.Mockito.verify((jakarta.persistence.EntityManagerFactory) factory, org.mockito.Mockito.never())
                    .createEntityManager();
            var listenerFailure = new IllegalStateException("Host transaction listener");
            jpa.addListener(new org.springframework.transaction.TransactionExecutionListener() {
                @Override
                public void beforeBegin(org.springframework.transaction.TransactionExecution transaction) {
                    throw listenerFailure;
                }
            });
            var hostManagedRuntime = new BulkExecutionInfrastructure(runtimeDs, jpa, NAMESPACE, "logical-deployment", roles);
            assertThatThrownBy(() -> hostManagedRuntime.withLifecycleRead(connection -> null)).isSameAs(listenerFailure);
            try (var connection = runtimeDs.getConnection()) {
                assertThat(setting(connection, "transaction_isolation")).isEqualTo("repeatable read");
            }
            adminSql.execute("alter role bulk_runtime reset default_transaction_isolation");

            // Simulate a privileged schema drift that leaves owner, SECURITY DEFINER and grants
            // intact but removes the V7 tuple check. Both live entry points must reject it before
            // their caller's callback can touch mutable state.
            adminSql.execute("""
                    create or replace function praxis_bulk.guard_descriptor_fence()
                    returns trigger language plpgsql security definer
                    set search_path = pg_catalog, pg_temp as $$ begin return new; end $$
                    """);
            var controlCallback = new AtomicBoolean();
            assertThatThrownBy(() -> control.withConnection(connection -> {
                controlCallback.set(true);
                return JdbcBulkOperationControl.transition(connection, NAMESPACE, OPERATION, 3,
                        JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null);
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("descriptor insert fence differs");
            assertThat(controlCallback).isFalse();

            var lifecycleCallback = new AtomicBoolean();
            assertThatThrownBy(() -> jpaBoundRuntime.withLifecycleRead(connection -> {
                lifecycleCallback.set(true);
                return null;
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("descriptor insert fence differs");
            assertThat(lifecycleCallback).isFalse();

            var runtimeCallback = new AtomicBoolean();
            assertThatThrownBy(() -> new TransactionTemplate(runtime.transactionManager()).execute(status -> {
                assertThatThrownBy(() -> runtime.withConnection(connection -> {
                    runtimeCallback.set(true);
                    return null;
                })).isInstanceOf(IllegalStateException.class).hasMessageContaining("descriptor insert fence differs");
                assertThat(status.isRollbackOnly()).isTrue();
                return null;
            })).isInstanceOf(UnexpectedRollbackException.class);
            assertThat(runtimeCallback).isFalse();

        }
    }

    private static String setting(java.sql.Connection connection, String name) throws java.sql.SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("select current_setting('" + name + "')")) {
            assertThat(rows.next()).isTrue();
            String value = rows.getString(1);
            assertThat(rows.next()).isFalse();
            return value;
        }
    }

    private static void verifyPublicationRace(BulkExecutionInfrastructure runtime, BulkControlPlaneInfrastructure control,
            JdbcTemplate adminSql)
            throws Exception {
        var deployment = "logical-deployment";
        try (var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var before = runtime.withLifecycleRead(connection -> {
                assertThat(setting(connection, "transaction_isolation")).isEqualTo("read committed");
                var snapshot = JdbcBulkOpenApiPublication.lockForRead(connection, NAMESPACE, deployment);
                var conflict = writer.submit(() -> org.assertj.core.api.Assertions.catchThrowable(() ->
                        control.withConnection(controlConnection -> {
                            try (var statement = controlConnection.createStatement()) {
                                statement.execute("set local lock_timeout='100ms'");
                            }
                            return JdbcBulkOpenApiPublication.transition(controlConnection, NAMESPACE, deployment,
                                    snapshot.generation(), JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
                        })));
                assertPublicationLockConflict(conflict);
                return snapshot;
            });
            assertThat(before.published()).isTrue();

            String privilege = "execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text)";
            writer.submit(() -> adminSql.execute("grant " + privilege + " to bulk_runtime"))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            try {
                assertThatThrownBy(runtime::readAttestedPublication)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("governed lifecycle function grants differ");
            } finally {
                adminSql.execute("revoke " + privilege + " from bulk_runtime");
            }

            var suspended = writer.submit(() -> control.withConnection(connection ->
                    JdbcBulkOpenApiPublication.transition(connection, NAMESPACE, deployment, before.generation(),
                            JdbcBulkOpenApiPublication.Target.SUSPENDED, null))).get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(suspended.applied()).isTrue();
            var during = runtime.readAttestedPublication();
            assertThat(during.state()).isEqualTo("SUSPENDED");
            assertThat(during.generation()).isEqualTo(suspended.generation());
            assertThat(during.documentDigest()).isNull();

            var republished = writer.submit(() -> control.withConnection(connection ->
                    JdbcBulkOpenApiPublication.transition(connection, NAMESPACE, deployment, suspended.generation(),
                            JdbcBulkOpenApiPublication.Target.PUBLISHED, before.documentDigest())))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(republished.applied()).isTrue();
            var after = runtime.readAttestedPublication();
            assertThat(after.published()).isTrue();
            assertThat(after.documentDigest()).isEqualTo(before.documentDigest());
            assertThat(after.generation()).isEqualTo(before.generation() + 2);
        }
    }

    private static void assertPublicationLockConflict(java.util.concurrent.Future<Throwable> conflict) {
        Throwable failure;
        try {
            failure = conflict.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while testing the publication lock", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unexpected) {
            throw new AssertionError("Unable to observe the conflicting publication transition", unexpected);
        }
        assertThat(failure).hasRootCauseInstanceOf(java.sql.SQLException.class);
        var cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure);
        assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("55P03");
    }

    @Test
    void rejectsSharedDatasourceBindingsAndWrongControlRoleBeforeUse() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var admin = postgres.getPostgresDatabase();
            BulkExecutionMigrator.migrateWithOperations(admin, Map.of(NAMESPACE, "logical-deployment"),
                    List.of(new BulkOperationControlIdentity(NAMESPACE, OPERATION)));
            var runtimeRoles = new BulkExecutionRoleConfiguration("schema_owner_test", Set.of("runtime"), Set.of(), Set.of("postgres"));
            var runtime = new BulkExecutionInfrastructure(admin, new DataSourceTransactionManager(admin),
                    NAMESPACE, "logical-deployment", runtimeRoles);
            assertThatThrownBy(() -> new BulkControlPlaneInfrastructure(admin,
                    new DataSourceTransactionManager(admin), NAMESPACE, "logical-deployment", "postgres", runtime))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("datasources must be distinct");

            admin.getConnection().close();
            var controlDs = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "");
            assertThatThrownBy(() -> new BulkControlPlaneInfrastructure(controlDs,
                    new DataSourceTransactionManager(controlDs), NAMESPACE, "logical-deployment", "not_postgres", runtime))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("explicit control-plane allowlist");
        }
    }

    @Test
    void publicEntriesRejectLiveAclMembershipAndFenceDriftBeforeCallbacksAndCas() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var admin = postgres.getPostgresDatabase();
            var adminSql = new JdbcTemplate(admin);
            BulkExecutionMigrator.migrateWithOperations(admin, Map.of(NAMESPACE, "logical-deployment"),
                    List.of(new BulkOperationControlIdentity(NAMESPACE, OPERATION)));

            adminSql.execute("create role bulk_runtime login");
            adminSql.execute("create role bulk_control login");
            adminSql.execute("grant usage on schema praxis_bulk to bulk_runtime, bulk_control");
            adminSql.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to bulk_runtime");
            adminSql.execute("grant select, insert on praxis_bulk.praxis_bulk_target_manifest, praxis_bulk.praxis_bulk_preview_state, praxis_bulk.praxis_bulk_target_preview, praxis_bulk.praxis_bulk_preview_item_integrity to bulk_runtime");
            adminSql.execute("grant select, insert on praxis_bulk.praxis_bulk_atomic_receipt, praxis_bulk.praxis_bulk_atomic_item_result, praxis_bulk.praxis_bulk_atomic_effect_ref, praxis_bulk.praxis_bulk_atomic_rejection to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.atomic_evidence_complete(uuid,integer) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to bulk_runtime");
            adminSql.execute("grant select on praxis_bulk.praxis_bulk_capacity_marker, "
                    + "praxis_bulk.praxis_bulk_capacity_installation, praxis_bulk.praxis_bulk_capacity_slot, "
                    + "praxis_bulk.praxis_bulk_capacity_occupation to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.lock_capacity_marker(), "
                    + "praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_control");
            adminSql.execute("grant execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) to bulk_control");
            var roles = new BulkExecutionRoleConfiguration("postgres", Set.of("bulk_runtime"), Set.of(), Set.of("bulk_control"));
            assertThatThrownBy(() -> new BulkExecutionRoleConfiguration("postgres",
                    Set.of("bulk_runtime"), Set.of(), Set.of("bulk_runtime")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Control-plane and runtime roles must be disjoint");
            BulkExecutionMigrator.validate(admin, roles);
            var runtimeDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_runtime", "postgres"), "bulk_runtime", "");
            var controlDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_control", "postgres"), "bulk_control", "");
            var runtime = new BulkExecutionInfrastructure(runtimeDs,
                    new DataSourceTransactionManager(runtimeDs), NAMESPACE, "logical-deployment", roles);
            var control = new BulkControlPlaneInfrastructure(controlDs,
                    new DataSourceTransactionManager(controlDs), NAMESPACE, "logical-deployment", "bulk_control", runtime);
            assertThat(controlState(adminSql)).isEqualTo("UNCOMPOSED:0");

            // Each drift is introduced after composition, then rejected at the public boundary.
            adminSql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_runtime");
            assertRuntimeEntryRejected(runtime, "governed lifecycle function grants differ");
            assertControlPlaneEntryRejected(control, "governed lifecycle function grants differ");
            assertThat(controlState(adminSql)).isEqualTo("UNCOMPOSED:0");
            adminSql.execute("revoke execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) from bulk_runtime");

            adminSql.execute("grant insert on praxis_bulk.praxis_bulk_operation_control to bulk_runtime");
            assertRuntimeEntryRejected(runtime, "bulk runtime privilege exceeds its table allowlist");
            adminSql.execute("revoke insert on praxis_bulk.praxis_bulk_operation_control from bulk_runtime");

            adminSql.execute("grant insert on praxis_bulk.praxis_bulk_operation_control to bulk_control");
            assertControlPlaneEntryRejected(control, "unconfigured bulk table grantee: bulk_control");
            adminSql.execute("revoke insert on praxis_bulk.praxis_bulk_operation_control from bulk_control");

            adminSql.execute("grant create on schema praxis_bulk to bulk_runtime");
            assertRuntimeEntryRejected(runtime, "bulk retention schema grants differ");
            adminSql.execute("revoke create on schema praxis_bulk from bulk_runtime");
            adminSql.execute("grant create on schema praxis_bulk to bulk_control");
            assertControlPlaneEntryRejected(control, "bulk retention schema grants differ");
            adminSql.execute("revoke create on schema praxis_bulk from bulk_control");

            adminSql.execute("create role bulk_runtime_elevated nologin createrole");
            adminSql.execute("grant bulk_runtime_elevated to bulk_runtime");
            assertRuntimeEntryRejected(runtime, "configured bulk roles inherit unexpected PostgreSQL roles");
            assertControlPlaneEntryRejected(control, "configured bulk roles inherit unexpected PostgreSQL roles");
            adminSql.execute("revoke bulk_runtime_elevated from bulk_runtime");
            adminSql.execute("grant bulk_runtime_elevated to bulk_control");
            assertControlPlaneEntryRejected(control, "configured bulk roles inherit unexpected PostgreSQL roles");
            adminSql.execute("revoke bulk_runtime_elevated from bulk_control");
            adminSql.execute("drop role bulk_runtime_elevated");

            adminSql.execute("revoke execute on function praxis_bulk.lock_operation_control(text,text) from bulk_runtime");
            assertRuntimeEntryRejected(runtime, "governed lifecycle function grants differ");
            assertControlPlaneEntryRejected(control, "governed lifecycle function grants differ");
            adminSql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_runtime");

            adminSql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to public");
            assertControlPlaneEntryRejected(control, "governed lifecycle function grants differ");
            assertThat(controlState(adminSql)).isEqualTo("UNCOMPOSED:0");
            adminSql.execute("revoke execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) from public");

            adminSql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_control with grant option");
            assertControlPlaneEntryRejected(control, "retention function grant option is forbidden");
            assertThat(controlState(adminSql)).isEqualTo("UNCOMPOSED:0");
            adminSql.execute("revoke execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) from bulk_control");
            adminSql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_control");
            adminSql.execute("grant execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) to bulk_control");

            adminSql.execute("alter function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) owner to bulk_runtime");
            assertRuntimeEntryRejected(runtime, "operation-control function has unexpected owner");
            assertControlPlaneEntryRejected(control, "operation-control function has unexpected owner");
            adminSql.execute("alter function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) owner to praxis_bulk_control_owner");

            var switchedRole = "bulk_runtime_switch";
            adminSql.execute("create role " + switchedRole + " nologin noinherit");
            // Let the catalog resolve names so this case isolates the identity check.
            adminSql.execute("grant usage on schema praxis_bulk to " + switchedRole);
            adminSql.execute("grant " + switchedRole + " to bulk_runtime");
            var switchedDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_runtime", "postgres"), "bulk_runtime", "");
            var startupOptions = new Properties();
            startupOptions.setProperty("options", "-c role=" + switchedRole);
            switchedDs.setConnectionProperties(startupOptions);
            try (var switchedConnection = switchedDs.getConnection()) {
                assertThat(setting(switchedConnection, "role")).isEqualTo(switchedRole);
                assertThat(setting(switchedConnection, "session_authorization")).isEqualTo("bulk_runtime");
            }
            var switched = new BulkExecutionInfrastructure(switchedDs,
                    new DataSourceTransactionManager(switchedDs), NAMESPACE, "logical-deployment", roles);
            assertRuntimeEntryRejected(switched, "session and effective PostgreSQL identities must match");
            adminSql.execute("revoke " + switchedRole + " from bulk_runtime");
            adminSql.execute("revoke usage on schema praxis_bulk from " + switchedRole);
            adminSql.execute("drop role " + switchedRole);

            adminSql.execute("alter table praxis_bulk.praxis_bulk_proposal disable trigger praxis_bulk_proposal_descriptor_fence");
            assertRuntimeEntryRejected(runtime, "descriptor insert fence differs");
            assertThat(controlState(adminSql)).isEqualTo("UNCOMPOSED:0");
            adminSql.execute("alter table praxis_bulk.praxis_bulk_proposal enable trigger praxis_bulk_proposal_descriptor_fence");

            // Restoring the drift restores the live entry without restarting either infrastructure.
            var completed = new AtomicBoolean();
            var result = new TransactionTemplate(runtime.transactionManager()).execute(status ->
                    runtime.withConnection(connection -> { completed.set(true); return "accepted"; }));
            assertThat(result).isEqualTo("accepted");
            assertThat(completed).isTrue();
            var controlCompleted = new AtomicBoolean();
            var controlAccepted = control.withConnection(connection -> {
                controlCompleted.set(true);
                return true;
            });
            assertThat(controlAccepted).isTrue();
            assertThat(controlCompleted).isTrue();
            assertThat(controlState(adminSql)).isEqualTo("UNCOMPOSED:0");

            for (int index = 0; index < 3; index++) {
                assertThat(Boolean.TRUE.equals(runtime.withLifecycleRead(connection -> Boolean.TRUE))).isTrue();
            }
            long aggregateStarted = System.nanoTime();
            int attestedReads = 25;
            for (int index = 0; index < attestedReads; index++) {
                assertThat(Boolean.TRUE.equals(runtime.withLifecycleRead(connection -> Boolean.TRUE))).isTrue();
            }
            long aggregateMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - aggregateStarted);
            System.out.println("H1a live runtime attestation aggregate: " + attestedReads
                    + " lifecycle entries, " + aggregateMillis + " ms (warm pool, Embedded PostgreSQL 14.22)");

            for (int index = 0; index < 3; index++) {
                assertThat(Boolean.TRUE.equals(control.withConnection(connection -> Boolean.TRUE))).isTrue();
            }
            long controlAggregateStarted = System.nanoTime();
            int attestedControlEntries = 25;
            for (int index = 0; index < attestedControlEntries; index++) {
                assertThat(Boolean.TRUE.equals(control.withConnection(connection -> Boolean.TRUE))).isTrue();
            }
            long controlAggregateMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - controlAggregateStarted);
            System.out.println("H1a live control-plane attestation aggregate: " + attestedControlEntries
                    + " entries, " + controlAggregateMillis + " ms (warm pools, Embedded PostgreSQL 14.22)");
        }
    }

    private static void assertRuntimeEntryRejected(BulkExecutionInfrastructure runtime, String message) {
        var callback = new AtomicBoolean();
        assertThatThrownBy(() -> new TransactionTemplate(runtime.transactionManager()).execute(status -> {
            assertThatThrownBy(() -> runtime.withConnection(connection -> { callback.set(true); return null; }))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
            assertThat(status.isRollbackOnly()).isTrue();
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(callback).isFalse();
    }

    private static void assertControlPlaneEntryRejected(BulkControlPlaneInfrastructure control, String message) {
        var callback = new AtomicBoolean();
        assertThatThrownBy(() -> control.withConnection(connection -> {
            callback.set(true);
            return JdbcBulkOperationControl.transition(connection, NAMESPACE, OPERATION, 0,
                    JdbcBulkOperationControl.Target.READY, "sha256:" + "e".repeat(64), "structural-r1", 2L, "sha256:" + "0".repeat(64));
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
        assertThat(callback).isFalse();
    }

    private static String controlState(JdbcTemplate adminSql) {
        return adminSql.queryForObject("select state || ':' || generation from praxis_bulk.praxis_bulk_operation_control "
                + "where namespace_id=? and operation_id=?", String.class, NAMESPACE, OPERATION);
    }

}
