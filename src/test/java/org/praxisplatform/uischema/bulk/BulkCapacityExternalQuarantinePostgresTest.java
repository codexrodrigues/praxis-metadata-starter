package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * C0-02 proves an external HBA quarantine in an owned cluster with trusted administration.
 * Only JVM restart and HBA reload are exercised: no PostgreSQL restart, HA, cluster restore,
 * external journal continuity, rights succession or universal anti-clone contract is certified.
 */
class BulkCapacityExternalQuarantinePostgresTest {
    private static final String ORIGIN = "capacity_local_1";
    private static final String COPY = "capacity_local_clone";
    private static final String RUNTIME = "bulk_runtime_test";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void externalHbaQuarantinesGenuineCloneAndRetiredOriginAcrossJvmRestart() throws Exception {
        try (var proof = new Proof()) {
            proof.bootstrapHba();
            var builder = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false)
                    .setServerConfig("hba_file", proof.hbaFile.toString())
                    .setServerConfig("listen_addresses", "127.0.0.1")
                    .setServerConfig("unix_socket_directories", "");
            try (var scope = new BulkCapacityOccupancyPostgresFixture.SharedScope(builder, proof.credentials, connection -> {
                        proof.closeBootstrapTrust(connection); return null;
                    });
                    var childCleanup = (AutoCloseable) proof::stopChildren) {
                var local = scope.local("external-hba-quarantine-origin", 1);
                var admin = new JdbcTemplate(scope.source("postgres", "postgres"));
                attestHba(admin, proof.hbaFile, true);
                assertThat(admin.queryForObject("select current_user", String.class)).isEqualTo("postgres");
                wrongPassword(scope.postgres.getJdbcUrl("postgres", "postgres"), "postgres", scope.password(RUNTIME));
                for (String role : List.of("occupancy_provisioner", "occupancy_allocator", "occupancy_reader"))
                    wrongPassword(scope.postgres.getJdbcUrl(role, "capacity_global"), role, scope.password(RUNTIME));
                emptyAdminPasswordRejected(scope.postgres.getJdbcUrl("postgres", "postgres"));
                proof.phase("ADMIN_AND_AUTHORITY_REQUIRE_DISTINCT_AUTHENTICATED_CREDENTIALS");
                local.activate();
                var queue = local.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
                var active = local.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
                var queued = local.enqueue(local.persist(), "external-quarantine-key", queue);
                var claimed = local.kernel.claim(local.context, queued.executionId(), "external-quarantine-worker", active).orElseThrow();
                assertThat(claimed.control().epoch()).isEqualTo(2);
                var physical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
                var first = local.kernel.executeUnit(claimed.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                    physical.set(local.writeDomain(unit)); return BulkUnitMutationResult.confirmed();
                });
                assertThat(first.receiptPresent()).isTrue();
                assertThat(first.execution().nextOrdinal()).isEqualTo(1);
                assertThat(first.execution().status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
                local.assertPhysicalCommit(claimed.executionId(), physical.get());
                proof.phase("ORIGIN_DOMAIN_AND_RECEIPT_CONFIRMED")
                        .put("backendPid", physical.get().backendPid()).put("transactionId", physical.get().transactionId());
                var authorityBefore = authorityRows(scope);
                var rowsBefore = fullRows(local.observer);
                var catalogBefore = catalog(local.ownerSource);
                var rolesBefore = clusterRoles(admin);
                var observed = envelope(admin, ORIGIN);
                copyDatabase(scope.source("postgres", "postgres"), admin, observed, proof);
                var copyOwner = source(scope, observed.owner(), COPY);
                var copyObserver = new JdbcTemplate(copyOwner);
                var copied = envelope(admin, COPY);
                assertThat(copied.oid()).isNotEqualTo(observed.oid());
                sameEnvelope(observed, copied);
                unchanged(fullRows(copyObserver), rowsBefore, "copied rows, bytes, xmin and Flyway history");
                unchanged(catalog(copyOwner), catalogBefore, "copied catalog and ACLs");
                unchanged(clusterRoles(admin), rolesBefore, "copied cluster roles and memberships");
                unchanged(authorityRows(scope), authorityBefore, "authority unchanged by copy");
                for (JdbcTemplate sql : List.of(local.observer, copyObserver)) {
                    assertThat(sql.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)).isEqualTo("ACTIVE");
                    assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_slot where current_execution_id=? and current_owner_epoch=2", Long.class, claimed.executionId())).isEqualTo(1);
                }
                validate(local.ownerSource); validate(copyOwner);
                // Every concrete non-administrative login is covered by the terminal HBA reject.
                var logins = admin.queryForList("select rolname from pg_roles where rolcanlogin and rolname<>'postgres' order by 1", String.class);
                assertThat(logins).contains(RUNTIME, "occupancy_provisioner", "occupancy_allocator", "occupancy_reader");
                for (String role : logins) deniedConnection(source(scope, role, COPY));
                proof.phase("GENUINE_ACTIVE_TEMPLATE_COPY_QUARANTINED")
                        .put("originDatabaseOid", observed.oid()).put("copyDatabaseOid", copied.oid())
                        .put("coveredLoginCount", logins.size());

                var config = configuration(scope, local, claimed.control());
                var original = proof.launch(config, false);
                var ready = proof.await(original, "ready");
                assertThat(ready.path("positiveReceiptReplay").asBoolean()).isTrue();
                var held = session(admin, ready.path("heldPid").asInt(), observed.oid());
                assertThat(held.role()).isEqualTo(RUNTIME);
                unchanged(fullRows(local.observer), rowsBefore, "positive original replay is read-only in effect");
                var oldHba = Files.readString(proof.hbaFile);
                proof.hba(false);
                assertThat(Files.readString(proof.hbaFile)).isNotEqualTo(oldHba);
                assertThat(admin.queryForObject("select pg_reload_conf()", Boolean.class)).isTrue();
                attestHba(admin, proof.hbaFile, false);
                awaitAppliedDeny(source(scope, RUNTIME, ORIGIN));
                for (String role : logins) {
                    deniedConnection(source(scope, role, ORIGIN)); deniedConnection(source(scope, role, COPY));
                }
                proof.phase("EXTERNAL_ALLOW_REMOVED_AND_REAL_DENIAL_OBSERVED").put("nativeSqlState", "28000");
                proof.signal(original, "retire");
                var probe = proof.await(original, "probe");
                assertThat(probe.path("backendPid").asInt()).isEqualTo(held.pid());
                assertThat(probe.path("writesInsideTransaction").asInt()).isEqualTo(1);
                assertThat(probe.path("transactionId").asLong()).isPositive();
                assertThat(local.observer.queryForObject("select writes from occupancy_domain_witness where id=2", Integer.class)).isZero();
                assertThat(admin.queryForObject("select count(*) from pg_stat_activity where pid=? and datid=? and backend_xid::text::bigint=? and state='idle in transaction'", Long.class,
                        held.pid(), held.databaseOid(), probe.path("transactionId").asLong())).isEqualTo(1);
                proof.signal(original, "rollback");
                proof.await(original, "rolled-back");
                unchanged(fullRows(local.observer), rowsBefore, "retained physical writer UPDATE rolled back");
                assertThat(session(admin, held.pid(), held.databaseOid())).isEqualTo(held);
                assertThat(admin.queryForObject("select pg_terminate_backend(pid,1000) from pg_stat_activity where pid=? and datid=? and usename=? and backend_start=?", Boolean.class,
                        held.pid(), held.databaseOid(), held.role(), OffsetDateTime.ofInstant(held.start(), java.time.ZoneOffset.UTC))).isTrue();
                awaitAbsent(admin, held);
                proof.phase("EXACT_OWNED_RUNTIME_SESSION_EXIT_CONFIRMED").put("backendPid", held.pid());
                proof.signal(original, "terminated");
                proof.await(original, "result"); proof.join(original);
                var restarted = proof.launch(config, true);
                assertThat(restarted.process().pid()).isNotEqualTo(original.process().pid());
                proof.await(restarted, "ready"); proof.await(restarted, "result"); proof.join(restarted);
                unchanged(fullRows(local.observer), rowsBefore, "retired origin preserved confirmed prefix and domain");
                unchanged(fullRows(copyObserver), rowsBefore, "quarantined copy has zero extra callbacks or writes");
                unchanged(catalog(local.ownerSource), catalogBefore, "origin catalog unchanged");
                unchanged(catalog(copyOwner), catalogBefore, "clone catalog unchanged");
                unchanged(clusterRoles(admin), rolesBefore, "quarantine creates no grant or role drift");
                unchanged(authorityRows(scope), authorityBefore, "external HBA does not mutate authority");
                sameEnvelope(observed, envelope(admin, ORIGIN)); sameEnvelope(observed, envelope(admin, COPY));
                assertThat(backends(admin, ORIGIN)).isZero(); assertThat(backends(admin, COPY)).isZero();
                local.assertionsComplete();
                proof.phase("QUARANTINE_CONFIRMED_WITHOUT_PG_RESTART_OR_JOURNAL");
                proof.complete = true;
            }
            proof.scopeClosed = true;
        }
    }

    private static void attestHba(JdbcTemplate admin, Path hba, boolean originAllowed) throws Exception {
        Path actual = Path.of(admin.queryForObject("show hba_file", String.class)).toRealPath();
        Path data = Path.of(admin.queryForObject("show data_directory", String.class)).toRealPath();
        assertThat(actual.isAbsolute()).isTrue(); assertThat(actual).isEqualTo(hba.toRealPath());
        assertThat(actual.startsWith(data)).isFalse();
        assertThat(admin.queryForObject("show listen_addresses", String.class)).isEqualTo("127.0.0.1");
        assertThat(admin.queryForObject("show unix_socket_directories", String.class)).isEmpty();
        assertThat(admin.queryForObject("select count(*) from pg_hba_file_rules where error is not null", Long.class)).isZero();
        var methods = admin.queryForList("select auth_method from pg_hba_file_rules order by line_number", String.class);
        assertThat(methods).isEqualTo(originAllowed
                ? List.of("scram-sha-256", "scram-sha-256", "scram-sha-256", "reject", "reject", "reject")
                : List.of("scram-sha-256", "scram-sha-256", "reject", "reject", "reject"));
        // Exact serialized ordering proves first match and absence of an earlier wildcard allow.
        assertThat(Files.readString(hba)).isEqualTo(hbaText(originAllowed));
    }

    private static String hbaText(boolean allowOrigin) {
        return "host all postgres 127.0.0.1/32 scram-sha-256\n"
                + "host capacity_global occupancy_provisioner,occupancy_allocator,occupancy_reader 127.0.0.1/32 scram-sha-256\n"
                + (allowOrigin ? "host " + ORIGIN + " " + RUNTIME + " 127.0.0.1/32 scram-sha-256\n" : "")
                + "host all all 127.0.0.1/32 reject\n"
                + "host all all ::1/128 reject\n"
                + "local all all reject\n";
    }

    private static DriverManagerDataSource source(BulkCapacityOccupancyPostgresFixture.SharedScope scope, String role, String db) {
        return source(scope.postgres.getJdbcUrl(role, db), role, scope.password(role));
    }
    private static DriverManagerDataSource source(String url, String role, String password) {
        var source = new DriverManagerDataSource(url, role, password);
        var options = new Properties(); options.setProperty("connectTimeout", "1");
        options.setProperty("ApplicationName", "external_quarantine_runtime");
        source.setConnectionProperties(options); return source;
    }
    private static void validate(DataSource owner) { BulkExecutionMigrator.validate(owner, BulkPostgresTestSupport.testRoleConfiguration()); }
    private static void deniedConnection(DataSource source) throws SQLException {
        try (var ignored = source.getConnection()) { throw new AssertionError("Quarantine accepted a connection"); }
        catch (SQLException rejected) { require("28000".equals(rejected.getSQLState())); }
    }
    private static void wrongPassword(String url, String role, String runtimePassword) throws SQLException {
        try (var ignored = source(url, role, runtimePassword).getConnection()) {
            throw new AssertionError("Administrative identity accepted the runtime password");
        } catch (SQLException rejected) { require("28P01".equals(rejected.getSQLState())); }
    }
    private static void emptyAdminPasswordRejected(String url) throws SQLException {
        try (var ignored = source(url, "postgres", "").getConnection()) {
            throw new AssertionError("Administrative identity accepted an empty password");
        } catch (SQLException rejected) {
            // PGJDBC's exact SCRAM empty-password branch is client-side CONNECTION_REJECTED,
            // independently classified from server 28P01 and HBA's native 28000 rejection.
            require(rejected instanceof org.postgresql.util.PSQLException
                    && "08004".equals(rejected.getSQLState())
                    && org.postgresql.util.GT.tr("The server requested SCRAM-based authentication, but the password is an empty string.")
                            .equals(rejected.getMessage()));
        }
    }
    private static void awaitAppliedDeny(DataSource source) throws Exception {
        long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (true) {
            try (var ignored = source.getConnection()) { require(System.nanoTime() < stop); }
            catch (SQLException rejected) { require("28000".equals(rejected.getSQLState())); return; }
            Thread.sleep(10);
        }
    }
    private record Session(int pid, long databaseOid, String role, Instant start) { }
    private static Session session(JdbcTemplate admin, int pid, long databaseOid) {
        var rows = admin.query("select pid,datid::bigint,usename,backend_start from pg_stat_activity where pid=? and datid=?",
                (rs, n) -> new Session(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class).toInstant()), pid, databaseOid);
        assertThat(rows).hasSize(1); return rows.get(0);
    }
    private static void awaitAbsent(JdbcTemplate admin, Session s) throws Exception {
        long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (admin.queryForObject("select count(*) from pg_stat_activity where pid=? and datid=? and usename=? and backend_start=?", Long.class,
                s.pid(), s.databaseOid(), s.role(), OffsetDateTime.ofInstant(s.start(), java.time.ZoneOffset.UTC)) != 0) {
            require(System.nanoTime() < stop); Thread.sleep(10);
        }
    }

    private record DatabaseGrant(String grantor, String grantee, String privilege, boolean grantOption) { }
    private record DatabaseSetting(String role, String key, String value) { }
    private record Envelope(long oid, String owner, boolean allowConnections, int connectionLimit, boolean template,
                            String encoding, String collation, String ctype, String tablespace,
                            List<DatabaseGrant> grants, List<DatabaseSetting> settings) { }

    /** ACL defaults are expanded explicitly; complete grantor/grantee/options are compared. */
    private static Envelope envelope(JdbcTemplate admin, String database) {
        var row = admin.queryForMap("""
                select d.oid::bigint as oid,r.rolname as owner,d.datallowconn,d.datconnlimit,d.datistemplate,
                       pg_encoding_to_char(d.encoding) as encoding,d.datcollate,d.datctype,t.spcname
                from pg_database d join pg_roles r on r.oid=d.datdba join pg_tablespace t on t.oid=d.dattablespace
                where d.datname=?
                """, database);
        var grants = admin.query("""
                select grantor.rolname as grantor,grantee.rolname as grantee,a.privilege_type,a.is_grantable
                from pg_database d cross join lateral aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
                join pg_roles grantor on grantor.oid=a.grantor left join pg_roles grantee on grantee.oid=a.grantee
                where d.datname=? order by grantor.rolname,grantee.rolname nulls first,a.privilege_type,a.is_grantable
                """, (rs, index) -> new DatabaseGrant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), database);
        var settings = admin.query("""
                select r.rolname,v.value from pg_db_role_setting s join pg_database d on d.oid=s.setdatabase
                left join pg_roles r on r.oid=s.setrole cross join lateral unnest(s.setconfig) v(value)
                where d.datname=? order by r.rolname nulls first,v.value
                """, (rs, index) -> {
                    String raw = rs.getString(2);
                    int separator = raw.indexOf('=');
                    if (separator <= 0) throw new IllegalStateException("Unsupported observed database setting representation");
                    String key = raw.substring(0, separator);
                    // This bounded fixture copier is not a general backup/settings implementation.
                    if (!Set.of("search_path", "statement_timeout", "lock_timeout", "idle_in_transaction_session_timeout").contains(key))
                        throw new IllegalStateException("Unsupported observed database setting");
                    return new DatabaseSetting(rs.getString(1), key, raw.substring(separator + 1));
                }, database);
        return new Envelope(((Number) row.get("oid")).longValue(), (String) row.get("owner"),
                (Boolean) row.get("datallowconn"), ((Number) row.get("datconnlimit")).intValue(),
                (Boolean) row.get("datistemplate"), (String) row.get("encoding"), (String) row.get("datcollate"),
                (String) row.get("datctype"), (String) row.get("spcname"), List.copyOf(grants), List.copyOf(settings));
    }

    private static void sameEnvelope(Envelope a, Envelope b) {
        assertThat(a.owner().equals(b.owner()) && a.allowConnections() == b.allowConnections()
                && a.connectionLimit() == b.connectionLimit() && a.template() == b.template()
                && a.encoding().equals(b.encoding()) && a.collation().equals(b.collation())
                && a.ctype().equals(b.ctype()) && a.tablespace().equals(b.tablespace())
                && a.grants().equals(b.grants()) && a.settings().equals(b.settings()))
                .as("observed database envelope matches except physical name/OID").isTrue();
    }

    /** CREATE DATABASE runs outside a transaction. Only this disposable origin is quiesced. */
    private static void copyDatabase(DataSource cluster, JdbcTemplate admin, Envelope original, Proof proof) throws Exception {
        for (var grant : original.grants()) {
            if (!original.owner().equals(grant.grantor()) || !Set.of("CONNECT", "CREATE", "TEMPORARY").contains(grant.privilege()))
                throw new IllegalStateException("Unsupported observed database ACL grantor or privilege");
        }
        boolean connectionsDisabled = false;
        Throwable primary = null;
        try (var connection = cluster.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(20); // fixture copy only; kernel and host native budgets remain untouched
                statement.execute("alter database " + identifier(ORIGIN) + " allow_connections false");
                connectionsDisabled = true;
                assertThat(backends(admin, ORIGIN)).as("all original leases closed before physical copy").isZero();
                proof.phase("ORIGIN_QUIESCENT_WITH_NEW_CONNECTIONS_DISABLED").put("originBackends", 0);
                statement.execute("create database " + identifier(COPY) + " with template " + identifier(ORIGIN)
                        + " owner " + identifier(original.owner()) + " allow_connections false"
                        + " connection limit " + original.connectionLimit() + " is_template " + original.template());
            }
            transplantEnvelope(cluster, admin, original);
            proof.phase("DATABASE_TEMPLATE_COPY_AND_OBSERVED_ENVELOPE_TRANSPLANT_COMPLETE");
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (connectionsDisabled) {
                try {
                    admin.execute("alter database " + identifier(ORIGIN) + " allow_connections " + original.allowConnections());
                    assertThat(admin.queryForObject("select datallowconn from pg_database where datname=?", Boolean.class, ORIGIN))
                            .isEqualTo(original.allowConnections());
                    proof.phase("ORIGIN_CONNECTION_POLICY_RESTORED");
                } catch (Exception | Error cleanup) {
                    if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
                }
            }
        }
    }

    /** Transplant only values observed on the origin, including grantor and grant options. */
    private static void transplantEnvelope(DataSource cluster, JdbcTemplate admin, Envelope original) throws SQLException {
        var created = envelope(admin, COPY);
        assertThat(created.owner()).isEqualTo(original.owner());
        try (var connection = cluster.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            connection.setAutoCommit(false);
            boolean committed = false;
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(20);
                if (!created.grants().equals(original.grants())) {
                    var grantees = new LinkedHashSet<String>();
                    for (var grant : created.grants()) grantees.add(grant.grantee());
                    for (var grant : original.grants()) grantees.add(grant.grantee());
                    for (String grantee : grantees)
                        statement.execute("revoke all privileges on database " + identifier(COPY) + " from " + grantee(grantee));
                    statement.execute("set local role " + identifier(original.owner()));
                    for (var grant : original.grants())
                        statement.execute("grant " + grant.privilege() + " on database " + identifier(COPY) + " to "
                                + grantee(grant.grantee()) + (grant.grantOption() ? " with grant option" : ""));
                    statement.execute("set local role none");
                }
                // A new database should have none; a nonempty unexpected envelope is not silently cleared.
                assertThat(created.settings().isEmpty()).as("new TEMPLATE copy has no invented database settings").isTrue();
                for (var setting : original.settings()) {
                    String target = setting.role() == null ? "database " + identifier(COPY)
                            : "role " + identifier(setting.role()) + " in database " + identifier(COPY);
                    statement.execute("alter " + target + " set " + identifier(setting.key()) + " to " + literal(setting.value()));
                }
                statement.execute("alter database " + identifier(COPY) + " allow_connections " + original.allowConnections());
                connection.commit(); committed = true;
            } finally { if (!committed) connection.rollback(); }
        }
        sameEnvelope(original, envelope(admin, COPY));
    }

    private static String identifier(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid fixture identifier");
        return '"' + value.replace("\"", "\"\"") + '"';
    }
    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static String grantee(String role) { return role == null ? "PUBLIC" : identifier(role); }
    private static long backends(JdbcTemplate admin, String database) {
        return admin.queryForObject("select count(*) from pg_stat_activity where datname=?", Long.class, database);
    }

    /** All bulk/Flyway rows and the domain fixture, including bytea content and copied xmin. */
    private static Map<String, List<String>> fullRows(JdbcTemplate sql) {
        var result = new LinkedHashMap<String, List<String>>();
        var tables = sql.queryForList("""
                select n.nspname||'.'||c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where c.relkind='r' and (n.nspname='praxis_bulk'
                    or (n.nspname='public' and c.relname='occupancy_domain_witness')) order by 1
                """, String.class);
        assertThat(tables).contains("public.occupancy_domain_witness", "praxis_bulk.praxis_bulk_capacity_marker",
                "praxis_bulk." + BulkExecutionMigrator.HISTORY_TABLE);
        for (String table : tables) {
            if (!table.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")) throw new IllegalStateException("Unsafe fixture relation");
            result.put(table, sql.queryForList("select to_jsonb(t)::text from (select *,xmin::text as copy_xmin from "
                    + table + ") t order by 1", String.class));
        }
        return result;
    }

    /** Deparse under pg_catalog so qualification cannot depend on either connection's search_path. */
    private static Map<String, List<String>> catalog(DataSource owner) {
        return new JdbcTemplate(owner).execute((ConnectionCallback<Map<String, List<String>>>) connection -> {
            assertThat(connection.getAutoCommit()).isTrue();
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) { statement.execute("set local search_path=pg_catalog"); }
                var result = new LinkedHashMap<String, List<String>>();
                result.put("schemas", strings(connection, """
                        select jsonb_build_array(nspname,pg_get_userbyid(nspowner),nspacl)::text
                        from pg_namespace where nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("relations", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,c.relkind,pg_get_userbyid(c.relowner),c.relacl,c.reloptions)::text
                        from pg_class c join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("columns", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod),
                            a.attnotnull,a.attidentity,a.attgenerated,a.attacl,pg_get_expr(d.adbin,d.adrelid))::text
                        from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace
                        left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                        where n.nspname in ('praxis_bulk','public') and a.attnum>0 and not a.attisdropped order by 1
                        """));
                result.put("constraints", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,k.conname,k.contype,k.convalidated,k.condeferrable,
                            k.condeferred,pg_get_constraintdef(k.oid))::text
                        from pg_constraint k join pg_class c on c.oid=k.conrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("indexes", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,i.indisvalid,i.indisready,pg_get_indexdef(i.indexrelid))::text
                        from pg_index i join pg_class c on c.oid=i.indrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("functions", strings(connection, """
                        select jsonb_build_array(n.nspname,p.proname,pg_get_userbyid(p.proowner),p.proacl,
                            p.prosecdef,p.provolatile,p.proconfig,pg_get_functiondef(p.oid))::text
                        from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("triggers", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,t.tgname,t.tgenabled,t.tgisinternal,pg_get_triggerdef(t.oid))::text
                        from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                return result;
            } finally { connection.rollback(); }
        });
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        var result = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static Map<String, List<String>> clusterRoles(JdbcTemplate admin) {
        assertThat(admin.queryForObject("""
                select count(*) from pg_authid where rolname in
                    ('postgres','occupancy_provisioner','occupancy_allocator','occupancy_reader',
                     'bulk_runtime_test','durable_runtime')
                  and rolpassword like 'SCRAM-SHA-256$%'
                """, Long.class) == 6L).as("all six seeded identities use SCRAM before role snapshots").isTrue();
        // Real verifiers and all authentication attributes remain in parent memory only.
        // The caller compares this protected snapshot through unchanged(boolean), never an
        // assertion that prints rows; neither the snapshot nor its values enter certificates.
        var authentication = admin.queryForList("""
                select to_jsonb(r)::text from pg_authid r where rolname in
                    ('postgres','occupancy_provisioner','occupancy_allocator','occupancy_reader',
                     'bulk_runtime_test','durable_runtime') order by 1
                """, String.class);
        return Map.of("roles", admin.queryForList("select to_jsonb(r)::text from pg_roles r order by 1", String.class),
                "seededAuthentication", authentication,
                "memberships", admin.queryForList("select to_jsonb(m)::text from pg_auth_members m order by 1", String.class),
                "globalSettings", admin.queryForList("select to_jsonb(s)::text from pg_db_role_setting s where setdatabase=0 order by 1", String.class));
    }

    private static Map<String, List<String>> authorityRows(BulkCapacityOccupancyPostgresFixture.SharedScope scope) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : scope.authorityObserver.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and c.relkind='r' order by 1
                """, String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalStateException("Unsafe fixture relation");
            result.put(table, scope.authorityObserver.queryForList("select to_jsonb(t)::text from praxis_bulk_capacity."
                    + table + " t order by 1", String.class));
        }
        return result;
    }

    private static void unchanged(Object actual, Object expected, String purpose) {
        assertThat(actual.equals(expected)).as(purpose).isTrue(); // Never print protected full-row snapshots.
    }


    private static Properties configuration(BulkCapacityOccupancyPostgresFixture.SharedScope scope,
            BulkCapacityOccupancyPostgresFixture local, BulkExecutionControl control) throws Exception {
        var p = new Properties();
        p.setProperty("originUrl", scope.postgres.getJdbcUrl(RUNTIME, ORIGIN));
        p.setProperty("copyUrl", scope.postgres.getJdbcUrl(RUNTIME, COPY));
        p.setProperty("runtimePassword", scope.password(RUNTIME));
        p.setProperty("runtimeRoles", String.join(",", local.runtime.roleConfiguration().runtimeGranteeRoles()));
        p.setProperty("ownerRole", local.runtime.roleConfiguration().expectedSchemaOwnerRole());
        var e = local.expected;
        p.setProperty("deployment", e.deploymentId()); p.setProperty("tenant", e.tenantId());
        p.setProperty("environment", e.environment()); p.setProperty("binding", e.bindingId());
        p.setProperty("generation", Long.toString(e.generation())); p.setProperty("databaseId", e.databaseId().toString());
        p.setProperty("attestationId", e.attestationId().toString()); p.setProperty("authorityId", e.authorityId().toString());
        p.setProperty("authorityEpoch", Long.toString(e.authorityEpoch()));
        contextProperties(p, "async", local.context); controlProperties(p, "async", control);
        p.setProperty("kernelHash", classHash(JdbcBulkDurableExecution.class));
        p.setProperty("processHash", classHash(RuntimeProcess.class));
        p.setProperty("kernelCodeSource", codeSource(JdbcBulkDurableExecution.class));
        p.setProperty("processCodeSource", codeSource(RuntimeProcess.class));
        return p;
    }
    private static void contextProperties(Properties p, String prefix, BulkFingerprintContext c) {
        p.setProperty(prefix + ".namespace", c.namespaceId()); p.setProperty(prefix + ".subject", c.subjectId());
        p.setProperty(prefix + ".resource", c.resourceKey()); p.setProperty(prefix + ".revision", c.schemaRevision());
        p.setProperty(prefix + ".group", c.operationRef().group()); p.setProperty(prefix + ".operation", c.operationRef().operationId());
        p.setProperty(prefix + ".path", c.operationRef().path()); p.setProperty(prefix + ".method", c.operationRef().method());
    }

    private static void controlProperties(Properties p, String prefix, BulkExecutionControl c) {
        p.setProperty(prefix + ".executionId", c.executionId().toString()); p.setProperty(prefix + ".ownerId", c.ownerId());
        p.setProperty(prefix + ".epoch", Long.toString(c.epoch()));
    }

    private static String classHash(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            // Nested class resource names contain the enclosing binary name.
            if (input != null) return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            require(input != null);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }

    private static String codeSource(Class<?> type) { return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm(); }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("External quarantine proof invariant failed");
    }

    /** Runtime-only fixed protocol: private input paths, no administrative credentials or SQL commands. */
    public static final class RuntimeProcess {
        public static void main(String[] arguments) {
            Path directory = null;
            String stage = "SETUP";
            try {
                require(arguments.length == 2);
                var p = new Properties();
                try (var input = Files.newInputStream(Path.of(arguments[0]))) { p.load(input); }
                directory = Path.of(arguments[1]);
                require(required(p, "kernelHash").equals(classHash(JdbcBulkDurableExecution.class)));
                require(required(p, "processHash").equals(classHash(RuntimeProcess.class)));
                require(required(p, "kernelCodeSource").equals(codeSource(JdbcBulkDurableExecution.class)));
                require(required(p, "processCodeSource").equals(codeSource(RuntimeProcess.class)));
                boolean restart = Boolean.parseBoolean(required(p, "restart"));
                var roles = new BulkExecutionRoleConfiguration(required(p, "ownerRole"),
                        Set.of(required(p, "runtimeRoles").split(",")), Set.of(), Set.of());
                var context = context(p, "async");
                var control = control(p, "async");
                var expected = new BulkCapacityBinding(required(p, "deployment"),
                        required(p, "tenant"), required(p, "environment"), required(p, "binding"), number(p, "generation"),
                        uuid(p, "databaseId"), uuid(p, "attestationId"), uuid(p, "authorityId"), number(p, "authorityEpoch"));
                var originSource = source(required(p, "originUrl"), RUNTIME, required(p, "runtimePassword"));
                var copySource = source(required(p, "copyUrl"), RUNTIME, required(p, "runtimePassword"));
                var origin = kernel(originSource, context, roles, expected);
                var copy = kernel(copySource, context, roles, expected);
                var ready = event(restart ? "RESTARTED_JVM_CERTIFIED" : "ORIGINAL_JVM_CERTIFIED")
                        .put("kernelClassSha256", classHash(JdbcBulkDurableExecution.class))
                        .put("processClassSha256", classHash(RuntimeProcess.class)).put("codeSourceMatchesParent", true);
                if (restart) {
                    emit(directory, "ready", ready);
                    stage = "RESTARTED_DENIALS";
                    denyKernel(originSource, origin, control); denyKernel(copySource, copy, control);
                    emit(directory, "result", event("RESTARTED_RUNTIME_BOTH_DATABASES_DENIED")
                            .put("nativeSqlState", "28000").put("admissions", 0).put("callbacks", 0));
                    return;
                }
                stage = "ORIGINAL_POSITIVE";
                try (var held = originSource.getConnection()) {
                    require(held.getAutoCommit());
                    int pid;
                    try (var statement = held.createStatement(); var row = statement.executeQuery("select pg_backend_pid(),current_user,current_database()")) {
                        require(row.next() && RUNTIME.equals(row.getString(2)) && ORIGIN.equals(row.getString(3)));
                        pid = row.getInt(1); require(!row.next());
                    }
                    require(origin.find(context, control.executionId()).orElseThrow().control().epoch() == control.epoch());
                    var replayCallbacks = new AtomicInteger();
                    var replay = origin.executeUnit(control, 0, unit -> {
                        replayCallbacks.incrementAndGet(); return BulkUnitAdmission.admit();
                    }, unit -> { replayCallbacks.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
                    require(replay.receiptPresent() && replay.replayed() && replayCallbacks.get() == 0);
                    denyKernel(copySource, copy, control);
                    emit(directory, "ready", ready.put("positiveReceiptReplay", true).put("heldPid", pid));
                    await(directory, "retire", Duration.ofSeconds(30));
                    stage = "EXTERNAL_RELOAD_DENIALS";
                    denyKernel(originSource, origin, control); denyKernel(copySource, copy, control);
                    stage = "RETAINED_UPDATE_ROLLBACK";
                    held.setAutoCommit(false);
                    try {
                        try (var statement = held.createStatement()) {
                            statement.setQueryTimeout(3);
                            statement.execute("set local statement_timeout='3s'");
                            statement.execute("set local lock_timeout='3s'");
                            statement.execute("set local idle_in_transaction_session_timeout='3s'");
                            require(statement.executeUpdate("update occupancy_domain_witness set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current() where id=2") == 1);
                            try (var row = statement.executeQuery("select pg_backend_pid(),txid_current(),writes from occupancy_domain_witness where id=2")) {
                                require(row.next() && row.getInt(1) == pid && row.getInt(3) == 1);
                                emit(directory, "probe", event("RETAINED_SESSION_REAL_UPDATE_UNCOMMITTED")
                                        .put("backendPid", pid).put("transactionId", row.getLong(2)).put("writesInsideTransaction", row.getInt(3)));
                                require(!row.next());
                            }
                        }
                        // This is the only held transaction; the observer must release it within 2s.
                        await(directory, "rollback", Duration.ofSeconds(2));
                    } finally { held.rollback(); held.setAutoCommit(true); }
                    emit(directory, "rolled-back", event("RETAINED_UPDATE_ROLLBACK_CONFIRMED").put("backendPid", pid));
                    stage = "WAITING_SELECTIVE_TERMINATION";
                    await(directory, "terminated", Duration.ofSeconds(30));
                    denyKernel(originSource, origin, control); denyKernel(copySource, copy, control);
                    emit(directory, "result", event("ORIGINAL_RUNTIME_BOTH_DATABASES_DENIED")
                            .put("nativeSqlState", "28000").put("admissions", 0).put("callbacks", 0));
                }
            } catch (Exception | Error failure) {
                if (directory != null) {
                    try { emit(directory, "result", event("FAILED").put("stage", stage).put("failureClass", failure.getClass().getSimpleName())); }
                    catch (Exception ignored) { }
                }
                System.exit(2);
            }
        }

        private static JdbcBulkDurableExecution kernel(DataSource source, BulkFingerprintContext context,
                BulkExecutionRoleConfiguration roles, BulkCapacityBinding expected) {
            var infrastructure = new BulkExecutionInfrastructure(source, new DataSourceTransactionManager(source),
                    context.namespaceId(), expected.deploymentId(), roles);
            return new JdbcBulkDurableExecution(infrastructure, null, expected);
        }
        private static void denyKernel(DataSource source, JdbcBulkDurableExecution kernel, BulkExecutionControl control) throws SQLException {
            deniedConnection(source); // Independent native cause; a safe kernel error alone is insufficient.
            var admissions = new AtomicInteger(); var callbacks = new AtomicInteger();
            try {
                kernel.executeUnit(control, 1, unit -> { admissions.incrementAndGet(); return BulkUnitAdmission.admit(); },
                        unit -> { callbacks.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
                throw new AssertionError("Quarantined kernel accepted a fresh unit");
            } catch (BulkDurableExecutionException denied) {
                require(denied.reason() == BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
            }
            require(admissions.get() == 0 && callbacks.get() == 0);
        }
        private static BulkFingerprintContext context(Properties p, String prefix) {
            return new BulkFingerprintContext(required(p, prefix + ".namespace"), required(p, prefix + ".subject"),
                    required(p, prefix + ".resource"), new CanonicalOperationRef(required(p, prefix + ".group"),
                    required(p, prefix + ".operation"), required(p, prefix + ".path"), required(p, prefix + ".method")),
                    required(p, prefix + ".revision"), ActionCollectionAtomicity.PER_ITEM);
        }
        private static BulkExecutionControl control(Properties p, String prefix) {
            return new BulkExecutionControl(uuid(p, prefix + ".executionId"), required(p, prefix + ".ownerId"), number(p, prefix + ".epoch"));
        }
        private static UUID uuid(Properties p, String key) { return UUID.fromString(required(p, key)); }
        private static long number(Properties p, String key) { long value = Long.parseLong(required(p, key)); require(value > 0); return value; }
        private static String required(Properties p, String key) { String value = p.getProperty(key); require(value != null && !value.isBlank()); return value; }
    }

    private static void await(Path directory, String signal, Duration budget) throws Exception {
        long stop = System.nanoTime() + budget.toNanos();
        while (!Files.exists(directory.resolve(signal))) {
            require(!Files.exists(directory.resolve("abort")) && System.nanoTime() < stop); Thread.sleep(10);
        }
        require(!Files.exists(directory.resolve("abort")));
    }
    private static ObjectNode event(String phase) {
        require(phase.matches("[A-Z][A-Z0-9_]{0,100}"));
        return JSON.createObjectNode().put("phase", phase).put("osPid", ProcessHandle.current().pid());
    }
    private static void emit(Path directory, String name, ObjectNode value) throws Exception {
        Path temporary = directory.resolve(name + ".json.tmp");
        Files.writeString(temporary, value.toPrettyString(), StandardCharsets.UTF_8);
        Files.move(temporary, directory.resolve(name + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private record Child(Path directory, Process process, boolean restart) { }

    /** Private handoff/credentials are deleted, while only sanitized certificates leave the harness. */
    private static final class Proof implements AutoCloseable {
        final Path directory = Files.createTempDirectory("praxis-external-quarantine-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final Path hbaFile = Files.createFile(directory.resolve("external-hba.conf"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).toAbsolutePath();
        final ObjectNode manifest = JSON.createObjectNode().put("caseId", "external-hba-quarantine")
                .put("harnessPid", ProcessHandle.current().pid()).put("scope", "JVM_RESTART_AND_HBA_RELOAD_ONLY")
                .put("barriersUsed", true);
        final com.fasterxml.jackson.databind.node.ArrayNode events = manifest.putArray("events");
        final List<Child> children = new ArrayList<>();
        final Map<String, String> credentials = Map.of(
                "postgres", UUID.randomUUID().toString(), "occupancy_provisioner", UUID.randomUUID().toString(),
                "occupancy_allocator", UUID.randomUUID().toString(), "occupancy_reader", UUID.randomUUID().toString(),
                "bulk_runtime_test", UUID.randomUUID().toString(), "durable_runtime", UUID.randomUUID().toString());
        boolean complete;
        boolean scopeClosed;
        boolean stopped;
        Proof() throws Exception { }
        ObjectNode phase(String phase) { return events.addObject().put("phase", phase); }
        void bootstrapHba() throws Exception {
            writeHba("host all postgres 127.0.0.1/32 trust\n"
                    + "host all all 127.0.0.1/32 reject\n"
                    + "host all all ::1/128 reject\nlocal all all reject\n");
        }
        void closeBootstrapTrust(Connection connection) throws SQLException {
            require(connection.getAutoCommit());
            char[] password = credentials.get("postgres").toCharArray();
            try {
                connection.unwrap(org.postgresql.PGConnection.class).alterUserPassword("postgres", password, "scram-sha-256");
            } finally { java.util.Arrays.fill(password, '\0'); }
            try { hba(true); } catch (Exception failure) { throw new IllegalStateException("Private HBA bootstrap replacement failed", failure); }
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                try (var row = statement.executeQuery("select pg_reload_conf()")) { require(row.next() && row.getBoolean(1) && !row.next()); }
            }
            // A reload signal is not enough: wrong-password authentication proves trust is gone.
            long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            var incorrect = source(connection.getMetaData().getURL(), "postgres", credentials.get(RUNTIME));
            while (true) {
                try (var ignored = incorrect.getConnection()) { require(System.nanoTime() < stop); }
                catch (SQLException rejected) { require("28P01".equals(rejected.getSQLState())); break; }
                try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("HBA bootstrap interrupted"); }
            }
            phase("BOOTSTRAP_TRUST_CLOSED_BEFORE_AUTHORITY_AND_DOMAIN_SETUP");
        }
        void hba(boolean originAllowed) throws Exception { writeHba(hbaText(originAllowed)); }
        void writeHba(String content) throws Exception {
            Path staging = Files.createTempFile(directory, "hba-stage-", ".conf",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(staging, content);
            Files.move(staging, hbaFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        Child launch(Properties source, boolean restart) throws Exception {
            var p = new Properties(); p.putAll(source); p.setProperty("restart", Boolean.toString(restart));
            Path childDirectory = Files.createDirectory(directory.resolve(restart ? "restarted-runtime" : "original-runtime"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path input = Files.createFile(childDirectory.resolve("configuration.properties"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var output = Files.newOutputStream(input)) { p.store(output, "Private fixed runtime test input"); }
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Process process = new ProcessBuilder(java, "-cp", classpath, RuntimeProcess.class.getName(), input.toString(), childDirectory.toString())
                    .redirectErrorStream(true).redirectOutput(childDirectory.resolve("private-output.log").toFile()).start();
            var child = new Child(childDirectory, process, restart); children.add(child);
            phase("RUNTIME_PROCESS_LAUNCHED").put("osPid", process.pid()).put("restarted", restart);
            return child;
        }
        void signal(Child child, String name) throws Exception {
            require(Set.of("retire", "rollback", "terminated", "abort").contains(name));
            Files.writeString(child.directory().resolve(name), "go", StandardCharsets.UTF_8);
        }
        JsonNode await(Child child, String name) throws Exception {
            long stop = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            Path path = child.directory().resolve(name + ".json");
            while (!Files.exists(path)) {
                if (Files.exists(child.directory().resolve("result.json"))) {
                    require(!"FAILED".equals(JSON.readTree(Files.readString(child.directory().resolve("result.json"))).path("phase").asText()));
                }
                require(child.process().isAlive() && System.nanoTime() < stop); Thread.sleep(10);
            }
            var value = JSON.readTree(Files.readString(path));
            require(!"FAILED".equals(value.path("phase").asText()));
            assertThat(value.path("osPid").asLong()).isEqualTo(child.process().pid());
            if (name.equals("ready")) {
                assertThat(value.path("kernelClassSha256").asText()).isEqualTo(classHash(JdbcBulkDurableExecution.class));
                assertThat(value.path("processClassSha256").asText()).isEqualTo(classHash(RuntimeProcess.class));
                assertThat(value.path("codeSourceMatchesParent").asBoolean()).isTrue();
            }
            if (name.equals("result")) {
                assertThat(value.path("nativeSqlState").asText()).isEqualTo("28000");
                assertThat(value.path("admissions").asInt()).isZero(); assertThat(value.path("callbacks").asInt()).isZero();
                assertThat(value.path("phase").asText()).isEqualTo(child.restart()
                        ? "RESTARTED_RUNTIME_BOTH_DATABASES_DENIED" : "ORIGINAL_RUNTIME_BOTH_DATABASES_DENIED");
            }
            events.add(value.deepCopy()); return value;
        }
        void join(Child child) throws Exception {
            assertThat(child.process().waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(child.process().exitValue()).isZero();
            phase("RUNTIME_PROCESS_EXIT_CONFIRMED").put("osPid", child.process().pid()).put("exitCode", child.process().exitValue());
        }
        void stopChildren() throws Exception {
            if (stopped) return; stopped = true;
            Throwable failure = null;
            // Release/abort every barrier before waiting for any child. Never strand the probe TX.
            for (var child : children) if (child.process().isAlive()) {
                try { signal(child, "rollback"); signal(child, "abort"); }
                catch (Exception | Error error) { failure = add(failure, error); }
            }
            for (var child : children) {
                try {
                    if (!child.process().waitFor(20, TimeUnit.SECONDS)) {
                        child.process().destroy();
                        if (!child.process().waitFor(3, TimeUnit.SECONDS)) child.process().destroyForcibly();
                        require(child.process().waitFor(3, TimeUnit.SECONDS));
                    }
                    phase("OWNED_PROCESS_CLEANUP").put("osPid", child.process().pid()).put("exitCode", child.process().exitValue());
                } catch (Exception | Error error) {
                    child.process().destroyForcibly(); failure = add(failure, error);
                    try { require(child.process().waitFor(3, TimeUnit.SECONDS)); }
                    catch (Exception | Error join) { failure = add(failure, join); }
                }
            }
            if (failure instanceof Exception error) throw error;
            if (failure instanceof Error error) throw error;
        }
        @Override public void close() throws Exception {
            Throwable failure = null;
            try { stopChildren(); } catch (Exception | Error error) { failure = error; }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (Exception | Error error) { failure = add(failure, error); }
            manifest.put("subprocesses", children.size()).put("privateConfigurationRemoved", !Files.exists(directory))
                    .put("ownSharedScopeClosed", scopeClosed).put("caseOutcome", complete && failure == null ? "ASSERTIONS_COMPLETE" : "INCOMPLETE_OR_FAILED");
            String configured = System.getProperty("praxis.bulk.proof.directory");
            if (configured != null && !configured.isBlank()) {
                try {
                    Path output = Path.of(configured); Files.createDirectories(output);
                    Path destination = output.resolve("external-hba-quarantine.json");
                    require(!Files.exists(destination)); Files.writeString(destination, manifest.toPrettyString());
                } catch (Exception | Error error) { failure = add(failure, error); }
            }
            if (failure instanceof Exception error) throw error;
            if (failure instanceof Error error) throw error;
        }
        private static Throwable add(Throwable primary, Throwable secondary) {
            if (primary == null) return secondary; primary.addSuppressed(secondary); return primary;
        }
    }
}
