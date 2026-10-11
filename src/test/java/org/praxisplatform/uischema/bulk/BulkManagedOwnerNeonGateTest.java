package org.praxisplatform.uischema.bulk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Serial B/C/H proof on one assigned disposable Neon child, with one owned database per case. */
@EnabledIfEnvironmentVariable(named = "PRAXIS_BULK_NEON_CONNECTION_FILE", matches = ".+")
class BulkManagedOwnerNeonGateTest {
    private static final List<String> INTERNAL_ROLES = List.of("praxis_bulk_retention_owner",
            "praxis_bulk_retention_executor", "praxis_bulk_control_owner", "praxis_bulk_capacity_owner");
    private static final Map<String, String> BINDING = Map.of("managed-neon", "managed-neon-deployment");

    @Test
    void managedOwnerRetentionRecoveryAndHistoricalPrefixes() throws Exception {
        Assignment assignment = Assignment.load();
        run(assignment, "configured-retention-fresh", true, BulkManagedOwnerNeonGateTest::configuredRetentionFresh);
        run(assignment, "v19-pending-retry", false, BulkManagedOwnerNeonGateTest::pendingNineteenRetry);
        run(assignment, "v19-partial-grant-denied", false, BulkManagedOwnerNeonGateTest::partialGrantDenied);
        run(assignment, "v19-after-grant-rollback", false, BulkManagedOwnerNeonGateTest::afterGrantRollback);
        run(assignment, "v20-revoked-grant-denied", false, BulkManagedOwnerNeonGateTest::completeRevokedDenied);
        run(assignment, "original-v4-upgrade", false, BulkManagedOwnerNeonGateTest::originalFourUpgrade);
        run(assignment, "managed-v12-upgrade", false, BulkManagedOwnerNeonGateTest::managedTwelveUpgrade);
        run(assignment, "managed-v13-complete-missing-retention", true,
                BulkManagedOwnerNeonGateTest::completedThirteenMissingRetention);
    }

    private static void run(Assignment assignment, String label, boolean retention, Case body) throws Exception {
        try (var fixture = new Fixture(assignment, retention)) {
            body.run(fixture);
        }
        // Emitted only after exact database/role cleanup succeeded; never contains credentials.
        System.out.println("NEON_MANAGED_CASE=" + label + ",PASS");
    }

    private static void configuredRetentionFresh(Fixture f) throws Exception {
        assertThat(f.migrate()).isEqualTo(20);
        f.validate();
        assertThat(f.count("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid "
                + "join pg_roles member on member.oid=m.member where r.rolname='praxis_bulk_retention_executor' "
                + "and member.rolname='" + f.retentionRole + "' and not m.admin_option "
                + "and m.inherit_option and m.set_option")).isEqualTo(1);
        assertThat(f.count("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid "
                + "where r.rolname in ('praxis_bulk_retention_owner','praxis_bulk_control_owner','praxis_bulk_capacity_owner') "
                + "and m.member=(select oid from pg_roles where rolname='" + f.assignment.ownerRole + "') "
                + "and m.admin_option and not m.inherit_option and not m.set_option")).isEqualTo(3);
        String history = f.historyPrefix(20);
        List<String> catalogue = f.catalogueSnapshot();
        assertThat(f.migrate()).isZero();
        assertThat(f.historyPrefix(20)).isEqualTo(history);
        assertThat(f.catalogueSnapshot()).isEqualTo(catalogue);
        f.assertRuntimeCannotOwnDdl();
    }

    private static void pendingNineteenRetry(Fixture f) throws Exception {
        f.ddlTarget(19);
        String prefix = f.historyPrefix(19);
        assertThat(f.count("select count(*) from praxis_bulk.praxis_bulk_manifest_bootstrap where phase='PENDING'"))
                .isEqualTo(1);
        assertThat(f.migrate()).isEqualTo(1);
        assertThat(f.historyPrefix(19)).isEqualTo(prefix);
        f.validate();
        assertThat(f.migrate()).isZero();
    }

    private static void partialGrantDenied(Fixture f) throws Exception {
        f.ddlTarget(19);
        f.execute("grant usage on schema praxis_bulk to " + f.runtimeRole);
        String prefix = f.historyPrefix(19);
        long acl = f.count("select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a "
                + "where n.nspname='praxis_bulk' and a.grantee='" + f.runtimeRole + "'::regrole "
                + "and a.privilege_type='USAGE'");
        assertThat(acl).isEqualTo(1);
        assertThatThrownBy(f::migrate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bulk retention schema grants differ");
        assertThat(f.historyPrefix(19)).isEqualTo(prefix);
        assertThat(f.count("select count(*) from praxis_bulk.praxis_bulk_manifest_bootstrap where phase='PENDING'"))
                .isEqualTo(1);
        assertThat(f.count("select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a "
                + "where n.nspname='praxis_bulk' and a.grantee='" + f.runtimeRole + "'::regrole "
                + "and a.privilege_type='USAGE'")).isEqualTo(acl);
        assertThat(f.count("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                + "cross join lateral aclexplode(c.relacl) a where n.nspname='praxis_bulk' "
                + "and a.grantee='" + f.runtimeRole + "'::regrole")).isZero();
    }

    private static void afterGrantRollback(Fixture f) throws Exception {
        f.ddlTarget(19);
        String prefix = f.historyPrefix(19);
        List<String> catalogue = f.catalogueSnapshot();
        List<String> markers = f.manifestMarkers();
        DataSource failing = (DataSource) Proxy.newProxyInstance(BulkManagedOwnerNeonGateTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(method, f.owner, args);
                    if (!method.getName().equals("getConnection")) return result;
                    Connection actual = (Connection) result;
                    return Proxy.newProxyInstance(BulkManagedOwnerNeonGateTest.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (connectionProxy, connectionMethod, connectionArgs) -> {
                                Object answer = invoke(connectionMethod, actual, connectionArgs);
                                if (!(answer instanceof Statement statement)
                                        || !connectionMethod.getName().equals("createStatement")) return answer;
                                return Proxy.newProxyInstance(BulkManagedOwnerNeonGateTest.class.getClassLoader(),
                                        new Class<?>[] {Statement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                            Object value = invoke(statementMethod, statement, statementArgs);
                                            if (statementMethod.getName().equals("execute") && statementArgs != null
                                                    && ("grant usage on schema praxis_bulk to \"" + f.runtimeRole + "\"")
                                                            .equals(statementArgs[0]))
                                                throw new AssertionError("injected after actual first base grant");
                                            return value;
                                        });
                            });
                });
        assertThatThrownBy(() -> f.migrate(failing)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("after actual first base grant");
        assertThat(f.historyPrefix(19)).isEqualTo(prefix);
        assertThat(f.catalogueSnapshot()).isEqualTo(catalogue);
        assertThat(f.manifestMarkers()).isEqualTo(markers);
        assertThat(f.count("select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a "
                + "where n.nspname='praxis_bulk' and a.grantee='" + f.runtimeRole + "'::regrole")).isZero();
        assertThat(f.count("select count(*) from praxis_bulk.praxis_bulk_manifest_bootstrap where phase='PENDING'"))
                .isEqualTo(1);
        assertThat(f.migrate()).isEqualTo(1);
        f.validate();
    }

    private static void completeRevokedDenied(Fixture f) throws Exception {
        assertThat(f.migrate()).isEqualTo(20);
        f.validate();
        f.execute("revoke usage on schema praxis_bulk from " + f.runtimeRole);
        String history = f.historyPrefix(20);
        assertThatThrownBy(f::migrate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bulk retention schema grants differ");
        assertThat(f.historyPrefix(20)).isEqualTo(history);
        assertThat(f.count("select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a "
                + "where n.nspname='praxis_bulk' and a.grantee='" + f.runtimeRole + "'::regrole")).isZero();
    }

    private static void originalFourUpgrade(Fixture f) throws Exception {
        f.originalFour();
        String prefix = f.historyPrefix(4);
        assertThat(f.migrate()).isEqualTo(16);
        assertThat(f.historyPrefix(4)).isEqualTo(prefix);
        f.validate();
        assertThat(f.migrate()).isZero();
    }

    private static void managedTwelveUpgrade(Fixture f) throws Exception {
        f.ddlTarget(12);
        String prefix = f.historyPrefix(12);
        assertThat(f.migrate()).isEqualTo(8);
        assertThat(f.historyPrefix(12)).isEqualTo(prefix);
        f.validate();
        assertThat(f.migrate()).isZero();
    }

    private static void completedThirteenMissingRetention(Fixture f) throws Exception {
        f.ddlTarget(13);
        f.execute("update praxis_bulk.praxis_bulk_manifest_bootstrap set phase='COMPLETE' where bootstrap_version=8");
        String prefix = f.historyPrefix(13);
        List<String> before = f.catalogueSnapshot();
        List<String> markers = f.manifestMarkers();
        assertThatThrownBy(f::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("retention executor members differ from explicit host configuration");
        assertThat(f.historyPrefix(13)).isEqualTo(prefix);
        assertThat(f.catalogueSnapshot()).isEqualTo(before);
        assertThat(f.manifestMarkers()).isEqualTo(markers);
        assertThat(f.count("select max(version::int) from praxis_bulk.praxis_bulk_schema_history")).isEqualTo(13);
        assertThat(f.sql.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_openapi_publication')::text",
                String.class)).isNull();
        assertThat(f.count("select count(*) from pg_auth_members where roleid='praxis_bulk_retention_executor'::regrole "
                + "and member='" + f.retentionRole + "'::regrole")).isZero();
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @FunctionalInterface private interface Case { void run(Fixture fixture) throws Exception; }

    private record Assignment(URI uri, String initialDatabase, String ownerRole, String ownerPassword) {
        static Assignment load() throws Exception {
            URI uri = URI.create(Files.readString(Path.of(required("PRAXIS_BULK_NEON_CONNECTION_FILE"))).strip());
            assertThat(uri.getScheme()).isIn("postgresql", "postgres");
            assertThat(uri.getHost()).isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_HOST"));
            assertThat(uri.getHost()).endsWith(".neon.tech").doesNotContain("-pooler");
            assertThat(uri.getRawUserInfo()).isNotNull().contains(":");
            assertThat(uri.getRawPath()).isNotBlank().startsWith("/");
            assertThat(uri.getRawQuery()).isNotBlank();
            assertThat(List.of(uri.getRawQuery().split("&"))).contains("sslmode=verify-full");
            String[] credentials = uri.getRawUserInfo().split(":", 2);
            String role = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8);
            String password = URLDecoder.decode(credentials[1], StandardCharsets.UTF_8);
            assertThat(role).isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_OWNER"));
            assertThat(password).isNotBlank();
            var assignment = new Assignment(uri, URLDecoder.decode(uri.getRawPath().substring(1),
                    StandardCharsets.UTF_8), role, password);
            var source = assignment.source(assignment.initialDatabase, role, password);
            var sql = new JdbcTemplate(source);
            assertThat(sql.queryForObject("select current_user=session_user and current_user=?", Boolean.class, role)).isTrue();
            assertThat(sql.queryForObject("select not rolsuper and rolcreaterole and rolcreatedb "
                    + "from pg_roles where rolname=current_user", Boolean.class)).isTrue();
            assertThat(sql.queryForObject("select current_setting('server_version_num')::integer between 170000 and 179999",
                    Boolean.class)).isTrue();
            // Neon may terminate client TLS at a proxy; pg_stat_ssl on the server is not the client witness.
            try (var connection = source.getConnection()) {
                assertThat(source.getSslMode()).isEqualTo("verify-full");
                assertThat(source.getProperty(org.postgresql.PGProperty.SSL_FACTORY))
                        .isEqualTo("org.postgresql.ssl.DefaultJavaSSLFactory");
                var executor = connection.unwrap(org.postgresql.jdbc.PgConnection.class).getQueryExecutor();
                var field = org.postgresql.core.QueryExecutorBase.class.getDeclaredField("pgStream");
                field.setAccessible(true);
                var stream = (org.postgresql.core.PGStream) field.get(executor);
                assertThat(stream.getSocket()).isInstanceOf(javax.net.ssl.SSLSocket.class);
                var tls = ((javax.net.ssl.SSLSocket) stream.getSocket()).getSession();
                assertThat(tls.getPeerHost()).isEqualTo(uri.getHost());
                assertThat(tls.isValid()).isTrue();
            }
            assertThat(sql.queryForObject("select to_regnamespace('praxis_bulk') is null", Boolean.class)).isTrue();
            return assignment;
        }

        PGSimpleDataSource source(String database, String user, String password) {
            var source = new PGSimpleDataSource();
            source.setServerNames(new String[] {uri.getHost()});
            source.setPortNumbers(new int[] {uri.getPort() < 0 ? 5432 : uri.getPort()});
            source.setDatabaseName(database); source.setUser(user); source.setPassword(password);
            source.setSslMode("verify-full");
            source.setProperty(org.postgresql.PGProperty.SSL_FACTORY, "org.postgresql.ssl.DefaultJavaSSLFactory");
            source.setConnectTimeout(10); source.setSocketTimeout(30);
            return source;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Assignment assignment;
        final String database;
        final String runtimeRole;
        final String controlRole;
        final String retentionRole;
        final String runtimePassword;
        final PGSimpleDataSource initial;
        final PGSimpleDataSource owner;
        final JdbcTemplate sql;
        final BulkExecutionRoleConfiguration roles;
        final LinkedHashSet<String> createdRoles = new LinkedHashSet<>();
        boolean databaseMayExist;
        boolean migrationStarted;

        Fixture(Assignment assignment, boolean configuredRetention) throws Exception {
            this.assignment = assignment;
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            database = "bulk_gate_" + suffix;
            runtimeRole = "bulk_gate_runtime_" + suffix;
            controlRole = "bulk_gate_control_" + suffix;
            retentionRole = configuredRetention ? "bulk_gate_retention_" + suffix : null;
            runtimePassword = randomPassword();
            initial = assignment.source(assignment.initialDatabase, assignment.ownerRole, assignment.ownerPassword);
            owner = assignment.source(database, assignment.ownerRole, assignment.ownerPassword);
            sql = new JdbcTemplate(owner);
            roles = new BulkExecutionRoleConfiguration(assignment.ownerRole, Set.of(runtimeRole),
                    configuredRetention ? Set.of(retentionRole) : Set.of(), Set.of(controlRole));
            assertNoCanonicalRoles();
            assertThat(roleExists(runtimeRole)).isFalse();
            assertThat(roleExists(controlRole)).isFalse();
            if (configuredRetention) assertThat(roleExists(retentionRole)).isFalse();
            assertThat(new JdbcTemplate(initial).queryForObject("select count(*) from pg_database where datname=?",
                    Integer.class, database)).isZero();
            try {
                databaseMayExist = true;
                provision(initial, "create database " + database);
                createRole(runtimeRole, runtimePassword);
                createRole(controlRole, randomPassword());
                if (configuredRetention) createRole(retentionRole, randomPassword());
                assertThat(sql.queryForObject("select current_user=session_user and current_user=?",
                        Boolean.class, assignment.ownerRole)).isTrue();
                assertThat(sql.queryForObject("select to_regnamespace('praxis_bulk') is null", Boolean.class)).isTrue();
                System.out.println("NEON_MANAGED_DATABASE=" + database + ",roles=" + createdRoles);
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        int migrate() { return migrate(owner); }
        int migrate(DataSource source) { migrationStarted = true; return BulkExecutionMigrator.migrate(source, BINDING, roles); }
        void validate() { BulkExecutionMigrator.validate(owner, roles); }
        void originalFour() {
            migrationStarted = true;
            Flyway.configure().dataSource(owner).schemas("praxis_bulk").table("praxis_bulk_schema_history")
                    .locations("classpath:db/praxis-bulk-migration-lineage/original").target("4").load().migrate();
        }
        void ddlTarget(int version) {
            migrationStarted = true;
            Flyway.configure().dataSource(owner).schemas("praxis_bulk").table("praxis_bulk_schema_history")
                    .resourceProvider(BulkMigrationLineage.resolve(List.of())).target(Integer.toString(version))
                    .load().migrate();
        }
        void execute(String statement) throws Exception { provision(owner, statement); }
        long count(String query) { return sql.queryForObject(query, Long.class); }
        String historyPrefix(int maximum) throws Exception {
            try (var connection = owner.getConnection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("select installed_rank,version,description,type,script,checksum,"
                            + "installed_by,installed_on,execution_time,success "
                            + "from praxis_bulk.praxis_bulk_schema_history where version is null or version::int <= "
                            + maximum + " order by installed_rank")) {
                var result = new StringBuilder();
                while (rows.next()) {
                    for (int column = 1; column <= 10; column++) result.append(rows.getObject(column)).append('|');
                    result.append('\n');
                }
                return result.toString();
            }
        }
        List<String> catalogueSnapshot() {
            // Snapshot only owned/canonical identities; unrelated Neon cluster metadata can change independently.
            return sql.queryForList("""
                    select value from (
                        select 'role:'||to_jsonb(r)::text value from pg_roles r where r.rolname in
                            ('praxis_bulk_retention_owner','praxis_bulk_retention_executor',
                             'praxis_bulk_control_owner','praxis_bulk_capacity_owner', ?, ?, ?, ?)
                        union all select 'member:'||to_jsonb(m)::text from pg_auth_members m
                            join pg_roles r on r.oid=m.roleid where r.rolname in
                            ('praxis_bulk_retention_owner','praxis_bulk_retention_executor',
                             'praxis_bulk_control_owner','praxis_bulk_capacity_owner')
                        union all select 'namespace:'||to_jsonb(n)::text from pg_namespace n where nspname='praxis_bulk'
                        union all select 'relation:'||to_jsonb(c)::text from pg_class c
                            join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk'
                        union all select 'function:'||to_jsonb(p)::text from pg_proc p
                            join pg_namespace n on n.oid=p.pronamespace where n.nspname='praxis_bulk'
                    ) snapshot order by value
                    """, String.class, assignment.ownerRole, runtimeRole, controlRole,
                    retentionRole == null ? "" : retentionRole);
        }
        List<String> manifestMarkers() {
            return sql.queryForList("select to_jsonb(b)::text from praxis_bulk.praxis_bulk_manifest_bootstrap b "
                    + "order by bootstrap_version", String.class);
        }
        void assertRuntimeCannotOwnDdl() throws Exception {
            var runtime = assignment.source(database, runtimeRole, runtimePassword);
            var runtimeSql = new JdbcTemplate(runtime);
            assertThat(runtimeSql.queryForObject("select current_user=session_user and current_user=?",
                    Boolean.class, runtimeRole)).isTrue();
            assertThat(runtimeSql.queryForObject("select not rolsuper and not rolcreaterole and not rolcreatedb "
                    + "from pg_roles where rolname=current_user", Boolean.class)).isTrue();
            try (var connection = runtime.getConnection(); var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("create table praxis_bulk.fixture_unauthorized(id integer)"))
                        .isInstanceOf(java.sql.SQLException.class)
                        .extracting(failure -> ((java.sql.SQLException) failure).getSQLState())
                        .isEqualTo("42501");
            }
            assertThat(sql.queryForObject("select to_regclass('praxis_bulk.fixture_unauthorized')::text",
                    String.class)).isNull();
        }

        private void createRole(String role, String password) throws Exception {
            createdRoles.add(role); // exact random name was preflighted absent; include unknown-outcome CREATE in cleanup.
            provision(initial, "create role " + role + " login inherit nocreaterole nosuperuser nocreatedb "
                    + "noreplication nobypassrls password '" + password + "'");
        }
        private void assertNoCanonicalRoles() {
            assertThat(new JdbcTemplate(initial).queryForObject("select count(*) from pg_roles where rolname in "
                    + "('praxis_bulk_retention_owner','praxis_bulk_retention_executor',"
                    + "'praxis_bulk_control_owner','praxis_bulk_capacity_owner')", Integer.class)).isZero();
        }
        private boolean roleExists(String role) {
            return new JdbcTemplate(initial).queryForObject("select count(*) from pg_roles where rolname=?",
                    Integer.class, role) != 0;
        }
        @Override public void close() throws Exception {
            if (!databaseMayExist) return;
            // A failed DROP DATABASE stops cleanup; no role is removed while it may still own objects.
            provision(initial, "drop database if exists " + database);
            for (String role : createdRoles) if (roleExists(role)) provision(initial, "drop role " + role);
            if (migrationStarted) {
                for (String role : List.of("praxis_bulk_capacity_owner", "praxis_bulk_control_owner",
                        "praxis_bulk_retention_executor", "praxis_bulk_retention_owner"))
                    if (roleExists(role)) provision(initial, "drop role " + role);
            }
            assertThat(new JdbcTemplate(initial).queryForObject("select count(*) from pg_database where datname=?",
                    Integer.class, database)).isZero();
            for (String role : createdRoles) assertThat(roleExists(role)).isFalse();
            assertNoCanonicalRoles();
        }
    }

    private static void provision(PGSimpleDataSource source, String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(30); statement.execute(sql);
        } catch (java.sql.SQLException failure) {
            // A role CREATE may contain a random password. Never include SQL or its message in evidence.
            throw new IllegalStateException("Assigned Neon fixture SQL failed; SQLSTATE=" + failure.getSQLState());
        }
    }
    private static String randomPassword() {
        byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required Neon gate setting absent: " + name);
        return value;
    }
}
