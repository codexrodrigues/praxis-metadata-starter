package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
            adminSql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to bulk_runtime");
            adminSql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text) to bulk_control");
            var roles = new BulkExecutionRoleConfiguration("postgres", Set.of("bulk_runtime"),
                    Set.of("bulk_retention_group", "bulk_retention_login"), Set.of("bulk_control"));
            BulkExecutionMigrator.validate(admin, roles);

            var runtimeDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_runtime", "postgres"), "bulk_runtime", "");
            var controlDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_control", "postgres"), "bulk_control", "");
            var runtime = new BulkExecutionInfrastructure(runtimeDs,
                    new DataSourceTransactionManager(runtimeDs), NAMESPACE, "logical-deployment");
            var control = new BulkControlPlaneInfrastructure(controlDs,
                    new DataSourceTransactionManager(controlDs), NAMESPACE, "logical-deployment", "bulk_control", runtime);

            var ready = control.withConnection(connection -> JdbcBulkOperationControl.transition(connection,
                    NAMESPACE, OPERATION, 0, JdbcBulkOperationControl.Target.READY,
                    "sha256:" + "c".repeat(64), "structural-r1"));
            assertThat(ready).isEqualTo(new JdbcBulkOperationControl.Transition(true, 1));
            JdbcBulkOperationControl.Snapshot readySnapshot = runtime.withLifecycleRead(connection ->
                    JdbcBulkOperationControl.lockForAdmission(connection, NAMESPACE, OPERATION));
            assertThat(readySnapshot).isEqualTo(new JdbcBulkOperationControl.Snapshot(
                            "READY", 1, "sha256:" + "c".repeat(64), "structural-r1"));

            var suspended = control.withConnection(connection -> JdbcBulkOperationControl.transition(connection,
                    NAMESPACE, OPERATION, 1, JdbcBulkOperationControl.Target.SUSPENDED, null, null));
            assertThat(suspended).isEqualTo(new JdbcBulkOperationControl.Transition(true, 2));
            JdbcBulkOperationControl.Snapshot suspendedSnapshot = runtime.withLifecycleRead(connection ->
                    JdbcBulkOperationControl.lockForAdmission(connection, NAMESPACE, OPERATION));
            assertThat(suspendedSnapshot).isEqualTo(new JdbcBulkOperationControl.Snapshot(
                            "SUSPENDED", 2, null, null));
            assertThatThrownBy(() -> runtime.withLifecycleRead(connection ->
                    JdbcBulkOperationControl.transition(connection, NAMESPACE, OPERATION, 2,
                            JdbcBulkOperationControl.Target.READY, "sha256:" + "d".repeat(64), "structural-r2")))
                    .isInstanceOf(RuntimeException.class);

            // A cloned logical namespace in another database is not a valid control-plane peer.
            // Both connections have the same schema, namespace, deployment and role names; only
            // the transaction-scoped database-local advisory lock can establish the shared DB.
            adminSql.execute("create database bulk_control_other");
            var otherAdmin = new DriverManagerDataSource(
                    postgres.getJdbcUrl("postgres", "bulk_control_other"), "postgres", "");
            var otherAdminSql = new JdbcTemplate(otherAdmin);
            otherAdminSql.execute("create schema praxis_bulk");
            otherAdminSql.execute("create table praxis_bulk.praxis_bulk_namespace_binding(namespace_id text primary key, deployment_id text not null)");
            otherAdminSql.update("insert into praxis_bulk.praxis_bulk_namespace_binding(namespace_id, deployment_id) values (?, ?)",
                    NAMESPACE, "logical-deployment");
            var otherControlDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_control", "bulk_control_other"), "bulk_control", "");
            var otherControl = new BulkControlPlaneInfrastructure(otherControlDs,
                    new DataSourceTransactionManager(otherControlDs), NAMESPACE,
                    "logical-deployment", "bulk_control", runtime);
            assertThatThrownBy(() -> otherControl.withConnection(connection ->
                    JdbcBulkOperationControl.transition(connection, NAMESPACE, OPERATION, 2,
                            JdbcBulkOperationControl.Target.READY, "sha256:" + "d".repeat(64), "structural-r2")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("same PostgreSQL database");
        }
    }

    @Test
    void rejectsSharedDatasourceBindingsAndWrongControlRoleBeforeUse() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var admin = postgres.getPostgresDatabase();
            BulkExecutionMigrator.migrateWithOperations(admin, Map.of(NAMESPACE, "logical-deployment"),
                    List.of(new BulkOperationControlIdentity(NAMESPACE, OPERATION)));
            var runtime = new BulkExecutionInfrastructure(admin, new DataSourceTransactionManager(admin),
                    NAMESPACE, "logical-deployment");
            assertThatThrownBy(() -> new BulkControlPlaneInfrastructure(admin,
                    new DataSourceTransactionManager(admin), NAMESPACE, "logical-deployment", "postgres", runtime))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("datasources must be distinct");

            admin.getConnection().close();
            var controlDs = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "");
            var mismatched = new BulkControlPlaneInfrastructure(controlDs,
                    new DataSourceTransactionManager(controlDs), NAMESPACE, "logical-deployment", "not_postgres", runtime);
            assertThatThrownBy(() -> mismatched.withConnection(connection ->
                    JdbcBulkOperationControl.transition(connection, NAMESPACE, OPERATION, 0,
                            JdbcBulkOperationControl.Target.SUSPENDED, null, null)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("configured PostgreSQL role");
        }
    }
}
