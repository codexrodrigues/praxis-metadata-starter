package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import static org.assertj.core.api.Assertions.*;

/** Real managed-owner installation, not a superuser-precreated internal-role fixture. */
class BulkManagedOwnerPostgresTest {
    private static final String OWNER = "managed_bulk_owner_test";
    private static final String RUNTIME = "managed_bulk_runtime_test";
    private static final String CONTROL = "managed_bulk_control_test";
    private static final Map<String, String> BINDING = Map.of("managed-test", "managed-deployment-test");

    @Test void freshManagedOwnerCreatesRolesAndCompletesExactBootstrapThenRepeatsZero() throws Exception {
        try (var fixture = new Fixture()) {
            assertThat(BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles)).isEqualTo(20);
            BulkExecutionMigrator.validate(fixture.owner, fixture.roles);
            assertThat(BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles)).isZero();
            assertThat(fixture.count("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid "
                    + "where r.rolname in ('praxis_bulk_retention_owner','praxis_bulk_control_owner','praxis_bulk_capacity_owner') "
                    + "and m.member=(select oid from pg_roles where rolname='" + OWNER + "') "
                    + "and m.admin_option and not m.inherit_option and not m.set_option")).isEqualTo(3);
        }
    }

    @Test void originalSuccessfulFourVersionPrefixContinuesWithoutChangingHistory() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.originalFour();
            String before = fixture.historyPrefix(4);
            assertThat(BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles)).isEqualTo(16);
            assertThat(fixture.historyPrefix(4)).isEqualTo(before);
            BulkExecutionMigrator.validate(fixture.owner, fixture.roles);
        }
    }

    @Test void committedManagedDdlNineteenEmptyAclRetryCompletesWithoutReapplyingDdl() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ddlNineteen();
            String before = fixture.historyPrefix(19);
            assertThat(BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles)).isEqualTo(1);
            assertThat(fixture.historyPrefix(19)).isEqualTo(before);
            BulkExecutionMigrator.validate(fixture.owner, fixture.roles);
        }
    }

    @Test void pendingMarkerWithPartialHostAclIsDeniedWithoutHealing() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ddlNineteen();
            fixture.execute("grant usage on schema praxis_bulk to " + RUNTIME);
            String history = fixture.historyPrefix(19);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.historyPrefix(19)).isEqualTo(history);
            assertThat(fixture.count("select count(*) from praxis_bulk.praxis_bulk_manifest_bootstrap where phase='PENDING'"))
                    .isEqualTo(1);
            assertThat(fixture.count("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                    + "cross join lateral aclexplode(c.relacl) a where n.nspname='praxis_bulk' "
                    + "and a.grantee=(select oid from pg_roles where rolname='" + RUNTIME + "')")).isZero();
        }
    }

    @Test void completedBootstrapDoesNotHealRevokedRuntimePermission() throws Exception {
        try (var fixture = new Fixture()) {
            BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles);
            fixture.execute("revoke usage on schema praxis_bulk from " + RUNTIME);
            String history = fixture.historyPrefix(20);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.historyPrefix(20)).isEqualTo(history);
            assertThat(fixture.count("select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a "
                    + "where n.nspname='praxis_bulk' and a.grantee=(select oid from pg_roles where rolname='" + RUNTIME + "')")).isZero();
        }
    }

    @Test void errorAfterFirstBaseGrantRollsBackAllAclAndLeavesRetryablePendingWitness() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ddlNineteen();
            DataSource failing = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                Object result = invoke(method, fixture.owner, args);
                if (!method.getName().equals("getConnection")) return result;
                Connection actual = (Connection) result;
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                        (connectionProxy, connectionMethod, connectionArgs) -> {
                    Object answer = invoke(connectionMethod, actual, connectionArgs);
                    if (!(answer instanceof Statement statement) || !connectionMethod.getName().equals("createStatement")) return answer;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                            (statementProxy, statementMethod, statementArgs) -> {
                        Object value = invoke(statementMethod, statement, statementArgs);
                        if (statementMethod.getName().equals("execute") && statementArgs != null
                                && ("grant usage on schema praxis_bulk to \"" + RUNTIME + "\"").equals(statementArgs[0]))
                            throw new AssertionError("injected after actual first base grant");
                        return value;
                    });
                });
            });
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(failing, BINDING, fixture.roles))
                    .isInstanceOf(AssertionError.class);
            assertThat(fixture.count("select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a "
                    + "where n.nspname='praxis_bulk' and a.grantee=(select oid from pg_roles where rolname='" + RUNTIME + "')")).isZero();
            assertThat(fixture.count("select count(*) from praxis_bulk.praxis_bulk_manifest_bootstrap where phase='PENDING'"))
                    .isEqualTo(1);
            assertThat(BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles)).isEqualTo(1);
        }
    }

    @Test void preexistingInternalSchemaCreateIsDeniedBeforeDdlAndPreserved() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.originalFour();
            fixture.execute("create role praxis_bulk_retention_owner nologin noinherit");
            fixture.execute("grant create on schema praxis_bulk to praxis_bulk_retention_owner");
            String history = fixture.historyPrefix(4);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.historyPrefix(4)).isEqualTo(history);
            assertThat(fixture.count("select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a "
                    + "join pg_roles r on r.oid=a.grantee where n.nspname='praxis_bulk' "
                    + "and r.rolname='praxis_bulk_retention_owner' and a.privilege_type='CREATE'")).isEqualTo(1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"admin false, inherit false, set true",
            "admin false, inherit true, set false"})
    void preexistingUnsafeInternalEdgeIsDeniedWithoutCleanup(String options) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.originalFour();
            fixture.execute("create role praxis_bulk_retention_owner nologin noinherit");
            fixture.execute("grant praxis_bulk_retention_owner to " + OWNER + " with " + options + " granted by current_user");
            String history = fixture.historyPrefix(4);
            long edges = fixture.count("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid "
                    + "where r.rolname='praxis_bulk_retention_owner'");
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.historyPrefix(4)).isEqualTo(history);
            assertThat(fixture.count("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid "
                    + "where r.rolname='praxis_bulk_retention_owner'")).isEqualTo(edges);
        }
    }

    @Test void wrongGrantorAdminOnlyEdgeIsDeniedWithoutCleanup() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.originalFour();
            // SUPERUSER prepares an otherwise unreachable adversarial catalogue only.
            // The code under test still runs through the non-superuser owner datasource.
            try (var admin = fixture.postgres.getPostgresDatabase().getConnection(); var sql = admin.createStatement()) {
                sql.execute("create role praxis_bulk_retention_owner nologin noinherit");
                sql.execute("create role managed_bulk_wrong_grantor nologin noinherit");
                sql.execute("grant praxis_bulk_retention_owner to managed_bulk_wrong_grantor with admin true, inherit false, set false granted by current_user");
                sql.execute("set role managed_bulk_wrong_grantor");
                sql.execute("grant praxis_bulk_retention_owner to " + OWNER + " with admin true, inherit false, set false granted by current_user");
                sql.execute("reset role");
            }
            String history = fixture.historyPrefix(4);
            long edges = fixture.count("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid "
                    + "where r.rolname='praxis_bulk_retention_owner'");
            assertThat(fixture.count("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.grantor "
                    + "where m.roleid='praxis_bulk_retention_owner'::regrole and m.member='" + OWNER + "'::regrole "
                    + "and r.rolname='managed_bulk_wrong_grantor' and m.admin_option and not m.inherit_option and not m.set_option")).isEqualTo(1);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.historyPrefix(4)).isEqualTo(history);
            assertThat(fixture.count("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid "
                    + "where r.rolname='praxis_bulk_retention_owner'")).isEqualTo(edges);
        }
    }

    @Test void managedTwelvePrefixContinuesAndPreservesCommittedHistory() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ddlTarget(12);
            String history = fixture.historyPrefix(12);
            assertThat(BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles)).isEqualTo(8);
            assertThat(fixture.historyPrefix(12)).isEqualTo(history);
            BulkExecutionMigrator.validate(fixture.owner, fixture.roles);
        }
    }

    @Test void managedTwelveWithInternalCreateIsDeniedWithoutHealing() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ddlTarget(12);
            fixture.execute("grant create on schema praxis_bulk to praxis_bulk_retention_owner");
            String history = fixture.historyPrefix(12);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.historyPrefix(12)).isEqualTo(history);
            assertThat(fixture.count("select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a "
                    + "where n.nspname='praxis_bulk' and a.grantee='praxis_bulk_retention_owner'::regrole "
                    + "and a.privilege_type='CREATE'")).isEqualTo(1);
            assertThat(fixture.count("select max(version::int) from praxis_bulk.praxis_bulk_schema_history")).isEqualTo(12);
        }
    }

    @Test void publicTableAclIsDeniedWithoutHealing() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ddlNineteen();
            fixture.execute("grant select on praxis_bulk.praxis_bulk_execution to public");
            String history = fixture.historyPrefix(19);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.historyPrefix(19)).isEqualTo(history);
            assertThat(fixture.count("select count(*) from pg_class c cross join lateral aclexplode(c.relacl) a "
                    + "where c.oid='praxis_bulk.praxis_bulk_execution'::regclass and a.grantee=0 "
                    + "and a.privilege_type='SELECT'")).isEqualTo(1);
        }
    }

    @Test void runtimeReachabilityToInternalOwnerIsDeniedWithoutCleanup() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ddlNineteen();
            fixture.execute("grant praxis_bulk_retention_owner to " + RUNTIME
                    + " with admin false, inherit false, set true granted by current_user");
            String history = fixture.historyPrefix(19);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.historyPrefix(19)).isEqualTo(history);
            assertThat(fixture.count("select count(*) from pg_auth_members where roleid='praxis_bulk_retention_owner'::regrole "
                    + "and member='" + RUNTIME + "'::regrole and set_option")).isEqualTo(1);
        }
    }

    @Test void originalFivePrefixIsDeniedBeforeDdlAndPreservesCommittedHistory() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.originalFour();
            // Construct the genuine historical predecessor using archived SQL, never synthetic history.
            var admin = new PGSimpleDataSource();
            admin.setServerNames(new String[] {"localhost"}); admin.setPortNumbers(new int[] {fixture.postgres.getPort()});
            admin.setDatabaseName("managed_bulk_test"); admin.setUser("postgres");
            Flyway.configure().dataSource(admin).schemas("praxis_bulk").table("praxis_bulk_schema_history")
                    .locations("classpath:db/praxis-bulk-migration-lineage/original").target("5").load().migrate();
            String history = fixture.historyPrefix(5);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(fixture.owner, BINDING, fixture.roles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Original bulk prefixes V5-V16");
            assertThat(fixture.historyPrefix(5)).isEqualTo(history);
            assertThat(fixture.count("select max(version::int) from praxis_bulk.praxis_bulk_schema_history")).isEqualTo(5);
        }
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static final class Fixture implements AutoCloseable {
        final EmbeddedPostgres postgres;
        final PGSimpleDataSource owner = new PGSimpleDataSource();
        final BulkExecutionRoleConfiguration roles = new BulkExecutionRoleConfiguration(OWNER,
                Set.of(RUNTIME), Set.of(), Set.of(CONTROL));
        Fixture() throws Exception {
            var dataDirectory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                    .resolve("managed-owner-pg-" + java.util.UUID.randomUUID());
            postgres = EmbeddedPostgres.builder().setDataDirectory(dataDirectory).setCleanDataDirectory(true)
                    .setRegisterShutdownHook(false).setPGStartupWait(java.time.Duration.ofSeconds(30)).start();
            try {
            try (var connection = postgres.getPostgresDatabase().getConnection(); var sql = connection.createStatement()) {
                assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
                assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("17.11");
                sql.execute("create role " + OWNER + " login inherit createrole nosuperuser nocreatedb noreplication nobypassrls");
                sql.execute("create database managed_bulk_test owner " + OWNER);
            }
            owner.setServerNames(new String[] {"localhost"}); owner.setPortNumbers(new int[] {postgres.getPort()});
            owner.setDatabaseName("managed_bulk_test"); owner.setUser(OWNER);
            // The actual non-superuser actor creates host identities: automatic ADMIN-only edges are real.
            execute("create role " + RUNTIME + " login inherit nocreaterole nosuperuser nocreatedb noreplication nobypassrls");
            execute("create role " + CONTROL + " login inherit nocreaterole nosuperuser nocreatedb noreplication nobypassrls");
            } catch (Exception | Error failure) {
                try { postgres.close(); } catch (Exception close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
        void originalFour() {
            Flyway.configure().dataSource(owner).schemas("praxis_bulk").table("praxis_bulk_schema_history")
                    .locations("classpath:db/praxis-bulk-migration-lineage/original").target("4").load().migrate();
        }
        void ddlNineteen() { ddlTarget(19); }
        void ddlTarget(int version) {
            Flyway.configure().dataSource(owner).schemas("praxis_bulk").table("praxis_bulk_schema_history")
                    .resourceProvider(BulkMigrationLineage.resolve(java.util.List.of())).target(Integer.toString(version)).load().migrate();
        }
        void execute(String sql) throws Exception {
            try (var connection = owner.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
        }
        long count(String sql) throws Exception {
            try (var connection = owner.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                assertThat(rows.next()).isTrue(); long value=rows.getLong(1); assertThat(rows.next()).isFalse(); return value;
            }
        }
        String historyPrefix(int maximum) throws Exception {
            try (var connection = owner.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "select installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success "
                    + "from praxis_bulk.praxis_bulk_schema_history where version is null or version::int <= " + maximum + " order by installed_rank")) {
                var value = new StringBuilder();
                while (rows.next()) { for (int column=1;column<=10;column++) value.append(rows.getObject(column)).append('|'); value.append('\n'); }
                return value.toString();
            }
        }
        @Override public void close() throws Exception { postgres.close(); }
    }
}
