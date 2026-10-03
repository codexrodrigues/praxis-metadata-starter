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

            // Existing tighter operator deadlines survive the infrastructure's bounded lifecycle defaults.
            adminSql.execute("alter role bulk_runtime set statement_timeout='100ms'");
            adminSql.execute("alter role bulk_control set statement_timeout='100ms'");
            String controlTimeout = control.withConnection(connection -> setting(connection, "statement_timeout"));
            String lifecycleTimeout = runtime.withLifecycleRead(connection -> setting(connection, "statement_timeout"));
            String runtimeTimeout = new TransactionTemplate(runtime.transactionManager()).execute(status ->
                    runtime.withConnection(connection -> setting(connection, "statement_timeout")));
            assertThat(controlTimeout).isEqualTo("100ms");
            assertThat(lifecycleTimeout).isEqualTo("100ms");
            assertThat(runtimeTimeout).isEqualTo("100ms");

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
