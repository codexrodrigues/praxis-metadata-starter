package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Administrative withdrawal of one accessible, disposable origin and an enumerated runtime.
 * This proves neither exclusion of clones nor continuity after authority loss. Owner/superuser
 * remains trusted and can reopen the database. No arbitrary pool, HA or PostgreSQL restart is tested.
 */
class BulkCapacityControlledWithdrawalPostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATABASE = "capacity_local_1";
    private static final String RUNTIME_ROLE = "bulk_runtime_test";

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void accessibleOriginWithdrawalDrainsAsyncAndExcludesKnownRuntimeSessionsAndRestart() throws Exception {
        try (var harness = new Harness()) {
            try (var scope = new BulkCapacityOccupancyPostgresFixture.SharedScope();
                    var cleanup = (AutoCloseable) harness::shutdown) {
                var local = scope.local("controlled-withdrawal-origin", 1);
                local.activate();
                var queue = local.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
                var active = local.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
                var queued = local.enqueue(local.persist(), "withdrawal-async-key", queue);
                var async = local.kernel.claim(local.context, queued.executionId(), "withdrawal-worker", active).orElseThrow();
                var syncInput = local.persistSync();
                var sync = local.kernel.reserve(local.syncContext, syncInput.proposal().id(), "withdrawal-sync-key",
                        "withdrawal-sync-owner", local.syncContext.schemaRevision(), Instant.now().plusSeconds(300));
                local.observer.update("insert into occupancy_domain_witness(id,writes) values(3,0),(4,0),(5,0)");
                var admin = new JdbcTemplate(scope.postgres.getPostgresDatabase());
                long databaseOid = admin.queryForObject("select oid::bigint from pg_database where datname=?", Long.class, DATABASE);
                var authorityBefore = authorityRows(scope);
                assertRuntimePrivileges(admin, databaseOid);
                var configuration = configuration(scope, local, async.control(), sync.control());
                var original = harness.launch(configuration, false);
                var ready = harness.await(original, "ready", Duration.ofSeconds(30));
                assertThat(ready.path("phase").asText()).isEqualTo("ORIGINAL_READY_OUTSIDE_UNIT_TX");
                var held = attestSession(admin, ready.path("heldPid").asInt(), databaseOid);
                harness.session("HELD_RUNTIME_SESSION_ATTESTED", held);
                harness.signal(original, "unit-start");
                var entered = harness.await(original, "entered", Duration.ofSeconds(30));
                var callback = attestSession(admin, entered.path("backendPid").asInt(), databaseOid);
                assertThat(callback.pid()).isNotEqualTo(held.pid());
                assertThat(local.observer.queryForObject("select writes from occupancy_domain_witness where id=1", Integer.class)).isZero();
                assertThat(local.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?",
                        Long.class, async.executionId())).isZero();
                harness.phase("UNCOMMITTED_DOMAIN_AND_RECEIPT_ABSENT_ON_INDEPENDENT_OBSERVER")
                        .put("domainWrites", 0).put("receipts", 0).put("callbackBackendPid", callback.pid());

                var owner = new DriverManagerDataSource(scope.postgres.getJdbcUrl("postgres", DATABASE), "postgres", "");
                var ownerProperties = new Properties();
                ownerProperties.setProperty("ApplicationName", "withdrawal_owner_fence");
                owner.setConnectionProperties(ownerProperties);
                var installation = new JdbcBulkCapacityInstallation(local.expected, owner,
                        new DataSourceTransactionManager(owner), "postgres", local.runtime, scope.provisioner,
                        scope.reader, Duration.ofSeconds(20), Duration.ofSeconds(3));
                harness.fence = harness.executor.submit(installation::fence);
                int fencePid = awaitMarkerEdge(admin, databaseOid, callback.pid(), harness.fence);
                harness.phase("MARKER_FENCE_BLOCKED_BY_REAL_CALLBACK").put("ownerBackendPid", fencePid)
                        .put("callbackBackendPid", callback.pid()).put("callbackXid", entered.path("transactionId").asLong());
                // Release immediately on the observed edge. No ACK, result, process join or future wait
                // occurs while the callback still holds its SHARE marker lock.
                harness.signal(original, "callback-release");
                harness.fence.get(20, TimeUnit.SECONDS);
                var committed = harness.await(original, "committed", Duration.ofSeconds(30));
                assertThat(committed.path("receiptPresent").asBoolean()).isTrue();
                harness.events.add(assertPhysical(local.observer, async.executionId(), entered, 1, 2));
                assertThat(local.observer.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)).isEqualTo("FENCED");
                harness.signal(original, "after-fence");
                var fenced = harness.await(original, "fenced", Duration.ofSeconds(30));
                assertThat(fenced.path("safeReason").asText()).isEqualTo("FENCED");
                assertThat(fenced.path("replayed").asBoolean()).isTrue();
                assertThat(fenced.path("negativeCallbacks").asInt()).isZero();
                var syncPhysical = harness.await(original, "sync", Duration.ofSeconds(30));
                harness.events.add(assertPhysical(local.observer, sync.executionId(), syncPhysical, 3, 1));

                // This explicitly privileged reconciliation lease exists before admission is disabled.
                // It is never supplied to a runtime; READ COMMITTED gives fresh read-only statements.
                harness.observer = local.ownerSource.getConnection();
                harness.observer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                harness.observer.setReadOnly(true);
                harness.observer.setAutoCommit(false);
                assertThat(single(harness.observer, "show transaction_read_only").get("transaction_read_only")).isEqualTo("on");
                int observerPid = ((Number) single(harness.observer, "select pg_backend_pid() as pid").get("pid")).intValue();
                assertThat(observerPid).isNotEqualTo(held.pid());
                harness.phase("PRIVILEGED_READ_ONLY_RECONCILIATION_LEASE_ATTESTED").put("backendPid", observerPid);
                var confirmedPrefix = fullRows(harness.observer);
                admin.execute("alter database capacity_local_1 allow_connections false");
                assertThat(admin.queryForObject("select datallowconn from pg_database where oid=?", Boolean.class, databaseOid)).isFalse();
                assertThat(attestSession(admin, held.pid(), databaseOid)).isEqualTo(held);
                harness.signal(original, "after-block");
                var probe = harness.await(original, "probe", Duration.ofSeconds(30));
                assertThat(probe.path("backendPid").asInt()).isEqualTo(held.pid());
                assertThat(probe.path("writesInsideTransaction").asInt()).isEqualTo(1);
                assertThat(single(harness.observer, "select writes from occupancy_domain_witness where id=5").get("writes")).isEqualTo(0);
                harness.signal(original, "probe-release");
                var rolledBack = harness.await(original, "rolled-back", Duration.ofSeconds(30));
                assertThat(rolledBack.path("rollbackComplete").asBoolean()).isTrue();
                assertThat(rolledBack.path("reopenSqlState").asText()).isEqualTo("42501");
                assertThat(rolledBack.path("connectionSqlState").asText()).isEqualTo("55000");
                unchanged(fullRows(harness.observer), confirmedPrefix, "held-session probe rolled back without replacing confirmed receipts");
                assertThat(admin.queryForObject("select datallowconn from pg_database where oid=?", Boolean.class, databaseOid)).isFalse();

                // Earlier unpooled kernel loans have closed. Require the exact finite runtime set;
                // unexpected sessions fail this proof instead of being killed by a broad database filter.
                var runtimeSessions = runtimeSessions(admin, databaseOid);
                assertThat(runtimeSessions).containsExactly(held);
                for (var session : runtimeSessions) {
                    assertThat(attestSession(admin, session.pid(), databaseOid)).isEqualTo(session);
                    assertThat(admin.queryForObject("""
                            select pg_terminate_backend(pid,1000) from pg_stat_activity
                            where pid=? and datid=? and usename=? and backend_start=?
                            """, Boolean.class, session.pid(), session.databaseOid(), session.role(),
                            OffsetDateTime.ofInstant(session.backendStart(), java.time.ZoneOffset.UTC))).isTrue();
                    awaitAbsent(admin, session);
                    harness.session("EXACT_OWN_RUNTIME_BACKEND_TERMINATED_AND_ABSENT", session);
                }
                assertThat(runtimeSessions(admin, databaseOid)).isEmpty();
                harness.signal(original, "after-termination");
                var originalResult = harness.await(original, "result", Duration.ofSeconds(30));
                assertDeniedResult(originalResult, "ORIGINAL_RUNTIME_DENIED_AFTER_WITHDRAWAL");
                harness.join(original);
                var restarted = harness.launch(configuration, true);
                var restartReady = harness.await(restarted, "ready", Duration.ofSeconds(30));
                assertThat(restartReady.path("phase").asText()).isEqualTo("RESTART_SETUP_CERTIFIED_WITHOUT_DATABASE_LOAN");
                var restartedResult = harness.await(restarted, "result", Duration.ofSeconds(30));
                assertDeniedResult(restartedResult, "RESTARTED_RUNTIME_DENIED_AFTER_WITHDRAWAL");
                harness.join(restarted);
                assertThat(original.process().pid()).isNotEqualTo(restarted.process().pid());
                unchanged(fullRows(harness.observer), confirmedPrefix, "old and restarted runtime attempts add no domain, attempt, admission or receipt");
                unchanged(authorityRows(scope), authorityBefore, "withdrawal neither promotes nor reissues global rights");
                assertThat(runtimeSessions(admin, databaseOid)).isEmpty();
                assertThat(admin.queryForObject("select count(*) from pg_stat_activity where datid=?", Long.class, databaseOid)).isEqualTo(1);
                harness.observer.rollback();
                harness.observer.close();
                harness.observer = null;
                assertThat(admin.queryForObject("select count(*) from pg_stat_activity where datid=?", Long.class, databaseOid)).isZero();
                assertThat(admin.queryForObject("select datallowconn from pg_database where oid=?", Boolean.class, databaseOid)).isFalse();
                harness.phase("CONFIRMED_PREFIX_PRESERVED_AND_ALL_ORIGIN_BACKENDS_ABSENT").put("originBackends", 0);
                local.assertionsComplete();
            }
            harness.scopeClosed = true;
        }
    }

    private record Session(int pid, long databaseOid, String role, Instant backendStart) { }

    private static Session attestSession(JdbcTemplate admin, int pid, long databaseOid) {
        var sessions = admin.query("select pid,datid::bigint,usename,backend_start from pg_stat_activity where pid=? and datid=?",
                (rs, index) -> new Session(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class).toInstant()), pid, databaseOid);
        assertThat(sessions).hasSize(1);
        assertThat(sessions.getFirst().role()).isEqualTo(RUNTIME_ROLE);
        return sessions.getFirst();
    }

    private static List<Session> runtimeSessions(JdbcTemplate admin, long databaseOid) {
        return admin.query("select pid,datid::bigint,usename,backend_start from pg_stat_activity where datid=? and usename in ('bulk_runtime_test','durable_runtime') order by pid",
                (rs, index) -> new Session(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class).toInstant()), databaseOid);
    }

    private static void assertRuntimePrivileges(JdbcTemplate admin, long oid) {
        var rows = admin.queryForList("""
                select r.rolsuper,r.rolcreatedb,r.rolcanlogin,r.oid=d.datdba as owns_database,
                    pg_has_role(r.oid,d.datdba,'MEMBER') as member_of_owner,
                    pg_has_role(r.oid,'praxis_bulk_capacity_owner','MEMBER') as member_of_capacity_owner
                from pg_roles r cross join pg_database d where r.rolname=? and d.oid=?
                """, RUNTIME_ROLE, oid);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("rolsuper", false).containsEntry("rolcreatedb", false)
                .containsEntry("rolcanlogin", true).containsEntry("owns_database", false)
                .containsEntry("member_of_owner", false).containsEntry("member_of_capacity_owner", false);
    }

    private static int awaitMarkerEdge(JdbcTemplate admin, long oid, int callbackPid, Future<?> fence) throws Exception {
        long stop = System.nanoTime() + Duration.ofMillis(700).toNanos();
        while (System.nanoTime() < stop) {
            if (fence.isDone()) { fence.get(); throw new AssertionError("Fence completed without the held callback edge"); }
            var edges = admin.queryForList("""
                    select a.pid from pg_stat_activity a
                    where a.datid=? and a.application_name='withdrawal_owner_fence' and a.usename='postgres'
                      and a.wait_event_type='Lock' and ?=any(pg_blocking_pids(a.pid))
                    """, oid, callbackPid);
            if (!edges.isEmpty()) { assertThat(edges).hasSize(1); return ((Number) edges.getFirst().get("pid")).intValue(); }
            Thread.sleep(10);
        }
        throw new AssertionError("Causal marker edge absent within the original 700ms observation envelope");
    }

    private static void awaitAbsent(JdbcTemplate admin, Session session) throws Exception {
        long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (admin.queryForObject("select count(*) from pg_stat_activity where pid=? and datid=? and usename=? and backend_start=?",
                Long.class, session.pid(), session.databaseOid(), session.role(), OffsetDateTime.ofInstant(session.backendStart(), java.time.ZoneOffset.UTC)) != 0) {
            assertThat(System.nanoTime()).as("the signalled exact backend actually exited").isLessThan(stop);
            Thread.sleep(10);
        }
    }

    private static ObjectNode assertPhysical(JdbcTemplate observer, UUID execution, JsonNode physical, int domainId, long epoch) {
        var row = observer.queryForMap("""
                select pg_backend_pid() as observer_pid,d.writes,d.last_pid,d.last_xid,d.xmin::text::bigint as domain_xid,
                    r.xmin::text::bigint as receipt_xid,r.owner_epoch,r.outcome
                from occupancy_domain_witness d join praxis_bulk.praxis_bulk_item_receipt r
                    on r.execution_id=? and r.unit_ordinal=0 where d.id=?
                """, execution, domainId);
        long xid = physical.path("transactionId").asLong();
        int pid = physical.path("backendPid").asInt();
        assertThat(row).containsEntry("writes", 1).containsEntry("last_pid", pid).containsEntry("last_xid", xid)
                .containsEntry("domain_xid", xid & 0xffffffffL).containsEntry("receipt_xid", xid & 0xffffffffL)
                .containsEntry("owner_epoch", epoch).containsEntry("outcome", "CONFIRMED");
        assertThat(((Number) row.get("observer_pid")).intValue()).isNotEqualTo(pid);
        return event(epoch == 2 ? "ASYNC_PHYSICAL_COMMIT_CERTIFIED_BY_INDEPENDENT_OBSERVER" : "SYNC_PHYSICAL_COMMIT_CERTIFIED_BY_INDEPENDENT_OBSERVER")
                .put("backendPid", pid).put("transactionId", xid).put("observerPid", ((Number) row.get("observer_pid")).intValue())
                .put("domainXid", ((Number) row.get("domain_xid")).longValue())
                .put("receiptXid", ((Number) row.get("receipt_xid")).longValue()).put("ownerEpoch", epoch);
    }

    private static List<Map<String, Object>> rows(Connection connection, String sql, Object... arguments) throws SQLException {
        var result = new ArrayList<Map<String, Object>>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(3);
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            try (var values = statement.executeQuery()) {
                while (values.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int column = 1; column <= values.getMetaData().getColumnCount(); column++)
                        row.put(values.getMetaData().getColumnLabel(column), values.getObject(column));
                    result.add(row);
                }
            }
        }
        return result;
    }

    private static Map<String, Object> single(Connection connection, String sql, Object... arguments) throws SQLException {
        var result = rows(connection, sql, arguments);
        assertThat(result).hasSize(1);
        return result.getFirst();
    }

    /** Full-row JSON normalizes bytea by content. These protected snapshots are never printed. */
    private static Map<String, List<String>> fullRows(Connection observer) throws SQLException {
        var result = new LinkedHashMap<String, List<String>>();
        for (var row : rows(observer, "select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk' and c.relkind='r' order by 1")) {
            String name = (String) row.get("relname");
            require(name.matches("[a-z_][a-z0-9_]*"));
            result.put(name, strings(observer, "select to_jsonb(t)::text from praxis_bulk." + name + " t order by 1"));
        }
        require(result.containsKey("praxis_bulk_item_receipt") && result.containsKey(BulkExecutionMigrator.HISTORY_TABLE));
        result.put("domain", strings(observer, "select to_jsonb(t)::text from (select *,xmin::text as row_xid from occupancy_domain_witness) t order by 1"));
        return result;
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        var result = new ArrayList<String>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery(sql)) { while (rows.next()) result.add(rows.getString(1)); }
        }
        return result;
    }

    private static Map<String, List<String>> authorityRows(BulkCapacityOccupancyPostgresFixture.SharedScope scope) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : scope.authorityObserver.queryForList("select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk_capacity' and c.relkind='r' order by 1", String.class)) {
            require(table.matches("[a-z_][a-z0-9_]*"));
            result.put(table, scope.authorityObserver.queryForList("select to_jsonb(t)::text from praxis_bulk_capacity." + table + " t order by 1", String.class));
        }
        require(result.containsKey("binding_attestation"));
        return result;
    }

    private static void unchanged(Object actual, Object expected, String purpose) {
        assertThat(actual.equals(expected)).as(purpose).isTrue();
    }

    private static void assertDeniedResult(JsonNode result, String phase) {
        assertThat(result.path("phase").asText()).isEqualTo(phase);
        assertThat(result.path("connectionSqlState").asText()).isEqualTo("55000");
        assertThat(result.path("asyncReason").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(result.path("syncReason").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(result.path("admissions").asInt()).isZero();
        assertThat(result.path("callbacks").asInt()).isZero();
    }

    private static Properties configuration(BulkCapacityOccupancyPostgresFixture.SharedScope scope,
            BulkCapacityOccupancyPostgresFixture local, BulkExecutionControl async, BulkExecutionControl sync) throws Exception {
        var p = new Properties();
        p.setProperty("runtimeUrl", scope.postgres.getJdbcUrl(RUNTIME_ROLE, DATABASE));
        p.setProperty("maintenanceUrl", scope.postgres.getJdbcUrl(RUNTIME_ROLE, "postgres"));
        p.setProperty("runtimeUser", RUNTIME_ROLE);
        p.setProperty("runtimePassword", "");
        p.setProperty("ownerRole", local.runtime.roleConfiguration().expectedSchemaOwnerRole());
        p.setProperty("runtimeRoles", String.join(",", local.runtime.roleConfiguration().runtimeGranteeRoles()));
        var e = local.expected;
        p.setProperty("deployment", e.deploymentId()); p.setProperty("tenant", e.tenantId());
        p.setProperty("environment", e.environment()); p.setProperty("binding", e.bindingId());
        p.setProperty("generation", Long.toString(e.generation())); p.setProperty("databaseId", e.databaseId().toString());
        p.setProperty("attestationId", e.attestationId().toString()); p.setProperty("authorityId", e.authorityId().toString());
        p.setProperty("authorityEpoch", Long.toString(e.authorityEpoch()));
        contextProperties(p, "async", local.context); contextProperties(p, "sync", local.syncContext);
        controlProperties(p, "async", async); controlProperties(p, "sync", sync);
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
    private static void require(boolean condition) { if (!condition) throw new AssertionError("Controlled withdrawal proof invariant failed"); }

    /** Launchable nested class, solely in test sources; accepts private paths, never SQL commands. */
    public static final class RuntimeProcess {
        public static void main(String[] arguments) {
            Path directory = null;
            String stage = "SETUP";
            try {
                require(arguments != null && arguments.length == 2);
                var p = new Properties();
                try (var input = Files.newInputStream(Path.of(arguments[0]))) { p.load(input); }
                directory = Path.of(arguments[1]);
                boolean restart = Boolean.parseBoolean(required(p, "restart"));
                require(required(p, "kernelHash").equals(classHash(JdbcBulkDurableExecution.class)));
                require(required(p, "processHash").equals(classHash(RuntimeProcess.class)));
                require(required(p, "kernelCodeSource").equals(codeSource(JdbcBulkDurableExecution.class)));
                require(required(p, "processCodeSource").equals(codeSource(RuntimeProcess.class)));
                var context = context(p, "async");
                var syncContext = context(p, "sync");
                require(context.namespaceId().equals(syncContext.namespaceId()));
                var source = runtimeSource(p, "runtimeUrl");
                var roles = new BulkExecutionRoleConfiguration(required(p, "ownerRole"),
                        Set.of(required(p, "runtimeRoles").split(",")), Set.of(), Set.of());
                var infrastructure = new BulkExecutionInfrastructure(source, new DataSourceTransactionManager(source),
                        context.namespaceId(), required(p, "deployment"), roles);
                var expected = new BulkCapacityBinding(required(p, "deployment"), required(p, "tenant"),
                        required(p, "environment"), required(p, "binding"), number(p, "generation"), uuid(p, "databaseId"),
                        uuid(p, "attestationId"), uuid(p, "authorityId"), number(p, "authorityEpoch"));
                var kernel = new JdbcBulkDurableExecution(infrastructure, null, expected);
                var async = control(p, "async");
                var sync = control(p, "sync");
                var sql = new JdbcTemplate(source);
                var setup = event(restart ? "RESTART_SETUP_CERTIFIED_WITHOUT_DATABASE_LOAN" : "ORIGINAL_READY_OUTSIDE_UNIT_TX")
                        .put("kernelClassSha256", classHash(JdbcBulkDurableExecution.class))
                        .put("processClassSha256", classHash(RuntimeProcess.class)).put("codeSourceMatchesParent", true);
                if (restart) {
                    emit(directory, "ready", setup);
                    stage = "RESTART_DENIALS";
                    deniedAfterWithdrawal(source, kernel, async, sync, directory, true);
                    return;
                }
                // Unpooled operational loans close normally. This separate native session remains
                // alive across ALLOW_CONNECTIONS=false and is deliberately never a kernel datasource.
                try (var held = source.getConnection()) {
                    var identity = single(held, "select pg_backend_pid() as pid,current_user as role,current_database() as db");
                    require(identity.get("role").equals(required(p, "runtimeUser")) && identity.get("db").equals(DATABASE));
                    int heldPid = ((Number) identity.get("pid")).intValue();
                    require(held.getAutoCommit());
                    require(kernel.find(context, async.executionId()).orElseThrow().control().epoch() == async.epoch());
                    require(kernel.find(syncContext, sync.executionId()).orElseThrow().control().epoch() == sync.epoch());
                    emit(directory, "ready", setup.put("heldPid", heldPid));
                    stage = "ASYNC_DOMAIN_RECEIPT_AND_DRAIN";
                    await(directory, "unit-start", Duration.ofSeconds(30));
                    Path callbackDirectory = directory;
                    var result = kernel.executeUnit(async, 0, unit -> BulkUnitAdmission.admit(), unit -> {
                        var physical = writeDomain(infrastructure, sql, 1);
                        try {
                            emit(callbackDirectory, "entered", physical.put("phase", "ASYNC_DOMAIN_WRITTEN_INSIDE_UNIT_TX")
                                    .put("remainingBudgetMillis", unit.remainingBudget().toMillis()));
                            await(callbackDirectory, "callback-release", unit.remainingBudget());
                        } catch (Exception failure) { throw new IllegalStateException("Controlled withdrawal callback barrier failed"); }
                        return BulkUnitMutationResult.confirmed();
                    });
                    require(result.receiptPresent() && result.outcome() == BulkUnitOutcome.CONFIRMED && result.execution().nextOrdinal() == 1);
                    emit(directory, "committed", event("ASYNC_RECEIPT_RETURNED_AFTER_CALLBACK_RELEASE").put("receiptPresent", true));
                    stage = "FENCED_ASYNC_REPLAY_AND_SYNC_POSITIVE";
                    await(directory, "after-fence", Duration.ofSeconds(30));
                    var admissions = new AtomicInteger();
                    var callbacks = new AtomicInteger();
                    expectKernelReason(() -> kernel.executeUnit(async, 1, unit -> {
                        admissions.incrementAndGet(); return BulkUnitAdmission.admit();
                    }, unit -> { callbacks.incrementAndGet(); writeDomain(infrastructure, sql, 2); return BulkUnitMutationResult.confirmed(); }),
                            BulkDurableExecutionException.Reason.FENCED);
                    var replay = kernel.executeUnit(async, 0, unit -> {
                        admissions.incrementAndGet(); return BulkUnitAdmission.admit();
                    }, unit -> { callbacks.incrementAndGet(); writeDomain(infrastructure, sql, 1); return BulkUnitMutationResult.confirmed(); });
                    require(replay.receiptPresent() && replay.replayed() && admissions.get() == 0 && callbacks.get() == 0);
                    emit(directory, "fenced", event("FENCED_ASYNC_DENIED_AND_RECEIPT_REPLAYED").put("safeReason", "FENCED")
                            .put("replayed", true).put("negativeAdmissions", 0).put("negativeCallbacks", 0));
                    var physicalSync = new java.util.concurrent.atomic.AtomicReference<ObjectNode>();
                    var syncResult = kernel.executeUnit(sync, 0, unit -> BulkUnitAdmission.admit(), unit -> {
                        physicalSync.set(writeDomain(infrastructure, sql, 3)); return BulkUnitMutationResult.confirmed();
                    });
                    require(syncResult.receiptPresent() && syncResult.outcome() == BulkUnitOutcome.CONFIRMED && syncResult.execution().nextOrdinal() == 1);
                    emit(directory, "sync", physicalSync.get().put("phase", "SYNC_CONFIRMED_AFTER_CAPACITY_FENCE"));
                    stage = "HELD_SESSION_ROLLBACK_AND_PRIVILEGE_DENIAL";
                    await(directory, "after-block", Duration.ofSeconds(30));
                    // A genuine writer probe, not a SELECT or manufactured receipt. Its uncommitted
                    // effect is observed by the parent on its independently preopened read-only lease.
                    held.setAutoCommit(false);
                    try {
                        try (var update = held.prepareStatement("update occupancy_domain_witness set writes=writes+1 where id=5")) {
                            update.setQueryTimeout(3); require(update.executeUpdate() == 1);
                        }
                        var probe = single(held, "select pg_backend_pid() as pid,txid_current() as xid,writes from occupancy_domain_witness where id=5");
                        require(((Number) probe.get("pid")).intValue() == heldPid && ((Number) probe.get("writes")).intValue() == 1);
                        emit(directory, "probe", event("HELD_SESSION_WRITE_ACCESS_REMAINS_AFTER_CONNECTION_BLOCK")
                                .put("backendPid", heldPid).put("transactionId", ((Number) probe.get("xid")).longValue())
                                .put("writesInsideTransaction", 1));
                        // This reversible probe has its own short envelope. The 30s process/setup
                        // waits do not authorize retaining a writer transaction for that duration.
                        await(directory, "probe-release", Duration.ofSeconds(3));
                    } finally { held.rollback(); held.setAutoCommit(true); }
                    require(((Number) single(held, "select writes from occupancy_domain_witness where id=5").get("writes")).intValue() == 0);
                    try (var maintenance = runtimeSource(p, "maintenanceUrl").getConnection()) {
                        require(single(maintenance, "select current_user as role,current_database() as db").get("role").equals(required(p, "runtimeUser")));
                        require(single(maintenance, "select current_database() as db").get("db").equals("postgres"));
                        expectSqlState(() -> {
                            try (var statement = maintenance.createStatement()) {
                                statement.setQueryTimeout(3); statement.execute("alter database capacity_local_1 allow_connections true");
                            }
                        }, "42501");
                    }
                    expectSqlState(() -> { try (var ignored = source.getConnection()) { throw new AssertionError("Origin accepted a new runtime connection"); } }, "55000");
                    emit(directory, "rolled-back", event("HELD_PROBE_ROLLED_BACK_AND_RUNTIME_CANNOT_REOPEN")
                            .put("rollbackComplete", true).put("reopenSqlState", "42501").put("connectionSqlState", "55000"));
                    stage = "ORIGINAL_DENIALS";
                    await(directory, "after-termination", Duration.ofSeconds(30));
                    deniedAfterWithdrawal(source, kernel, async, sync, directory, false);
                }
            } catch (Throwable failure) {
                if (directory != null) {
                    try { emit(directory, "result", event("FAILED").put("stage", stage)); }
                    catch (Exception ignored) { /* A missing certificate and nonzero exit still reject the run. */ }
                }
                // No raw driver error, SQL text, credential, context or payload reaches process output.
                System.exit(21);
            }
        }

        private static void deniedAfterWithdrawal(DataSource source, JdbcBulkDurableExecution kernel,
                BulkExecutionControl async, BulkExecutionControl sync, Path directory, boolean restart) throws Exception {
            // The native cause is independent of the SDK's deliberately redacted safe error.
            expectSqlState(() -> { try (var ignored = source.getConnection()) { throw new AssertionError("Origin accepted a new runtime connection"); } }, "55000");
            var admissions = new AtomicInteger();
            var callbacks = new AtomicInteger();
            for (var control : List.of(async, sync)) {
                expectKernelReason(() -> kernel.executeUnit(control, 1, unit -> {
                    admissions.incrementAndGet(); return BulkUnitAdmission.admit();
                }, unit -> { callbacks.incrementAndGet(); throw new AssertionError("Withdrawn runtime reached a domain callback"); }),
                        BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
            }
            require(admissions.get() == 0 && callbacks.get() == 0);
            emit(directory, "result", event(restart ? "RESTARTED_RUNTIME_DENIED_AFTER_WITHDRAWAL" : "ORIGINAL_RUNTIME_DENIED_AFTER_WITHDRAWAL")
                    .put("connectionSqlState", "55000").put("asyncReason", "RECONCILIATION_REQUIRED")
                    .put("syncReason", "RECONCILIATION_REQUIRED").put("admissions", 0).put("callbacks", 0));
        }

        private static DriverManagerDataSource runtimeSource(Properties p, String key) {
            var source = new DriverManagerDataSource(required(p, key), required(p, "runtimeUser"), p.getProperty("runtimePassword", ""));
            var options = new Properties();
            options.setProperty("ApplicationName", "withdrawal_runtime");
            options.setProperty("connectTimeout", "1"); // Same 1s native acquisition policy used in the resource proofs; no global setting.
            source.setConnectionProperties(options);
            return source;
        }

        private static ObjectNode writeDomain(BulkExecutionInfrastructure runtime, JdbcTemplate sql, int id) {
            return runtime.withConnection(connection -> {
                require(!connection.getAutoCommit());
                var physical = single(connection, "select pg_backend_pid() as pid,txid_current() as xid");
                int pid = ((Number) physical.get("pid")).intValue();
                long xid = ((Number) physical.get("xid")).longValue();
                var same = sql.queryForMap("select pg_backend_pid() as pid,txid_current() as xid");
                require(((Number) same.get("pid")).intValue() == pid && ((Number) same.get("xid")).longValue() == xid);
                require(sql.update("update occupancy_domain_witness set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current() where id=?", id) == 1);
                return event("DOMAIN_WRITE_INSIDE_BOUND_RUNTIME_TRANSACTION").put("backendPid", pid).put("transactionId", xid);
            });
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

        private static void expectKernelReason(Runnable action, BulkDurableExecutionException.Reason expected) {
            try { action.run(); throw new AssertionError("Expected protected runtime denial"); }
            catch (BulkDurableExecutionException failure) { require(failure.reason() == expected); }
        }

        private interface SqlAction { void run() throws SQLException; }
        private static void expectSqlState(SqlAction action, String expected) throws SQLException {
            try { action.run(); throw new AssertionError("Expected native PostgreSQL denial"); }
            catch (SQLException failure) { require(expected.equals(failure.getSQLState())); }
        }

        private static void await(Path directory, String signal, Duration budget) throws Exception {
            require(!budget.isNegative() && !budget.isZero());
            long stop = System.nanoTime() + budget.toNanos();
            while (!Files.exists(directory.resolve(signal))) {
                require(!Files.exists(directory.resolve("abort")) && System.nanoTime() < stop);
                Thread.sleep(10);
            }
            require(!Files.exists(directory.resolve("abort")));
        }
    }

    private static ObjectNode event(String phase) {
        require(phase.matches("[A-Z][A-Z0-9_]{0,100}"));
        return JSON.createObjectNode().put("phase", phase).put("osPid", ProcessHandle.current().pid())
                .put("logicalDatabase", "origin");
    }

    private static void emit(Path directory, String name, ObjectNode event) throws Exception {
        Path temporary = directory.resolve(name + ".json.tmp");
        Files.writeString(temporary, event.toPrettyString(), StandardCharsets.UTF_8);
        Files.move(temporary, directory.resolve(name + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private record Child(Path directory, Process process, boolean restart) { }

    /** Sole owner of task-local subprocesses, signals, reconciliation lease and sanitized proof. */
    private static final class Harness implements AutoCloseable {
        final Path directory = Files.createTempDirectory("praxis-withdrawal-private-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final ObjectNode manifest = JSON.createObjectNode().put("caseId", "controlled-origin-withdrawal")
                .put("harnessPid", ProcessHandle.current().pid()).put("barriersUsed", false)
                .put("scope", "ACCESSIBLE_ORIGIN_AND_ENUMERATED_RUNTIME_ONLY");
        final com.fasterxml.jackson.databind.node.ArrayNode events = manifest.putArray("events");
        final List<Child> children = new ArrayList<>();
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        Connection observer;
        Future<?> fence;
        boolean shutDown;
        boolean scopeClosed;

        Harness() throws Exception { }

        Child launch(Properties input, boolean restart) throws Exception {
            var properties = new Properties(); properties.putAll(input); properties.setProperty("restart", Boolean.toString(restart));
            String alias = restart ? "restarted-runtime" : "original-runtime";
            Path childDirectory = Files.createDirectory(directory.resolve(alias),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path configuration = Files.createFile(childDirectory.resolve("configuration.properties"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var output = Files.newOutputStream(configuration)) { properties.store(output, "Private fixed controlled-withdrawal test input"); }
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Process process = new ProcessBuilder(java, "-cp", classpath, RuntimeProcess.class.getName(), configuration.toString(), childDirectory.toString())
                    .redirectErrorStream(true).redirectOutput(childDirectory.resolve("private-output.log").toFile()).start();
            var child = new Child(childDirectory, process, restart);
            children.add(child);
            phase("RUNTIME_JVM_LAUNCHED").put("actor", alias).put("osPid", process.pid());
            return child;
        }

        void signal(Child child, String name) throws Exception {
            require(Set.of("unit-start", "callback-release", "after-fence", "after-block", "probe-release", "after-termination", "abort").contains(name));
            Files.writeString(child.directory().resolve(name), "go", StandardCharsets.UTF_8);
            if (name.equals("unit-start")) manifest.put("barriersUsed", true);
        }

        JsonNode await(Child child, String name, Duration budget) throws Exception {
            long stop = System.nanoTime() + budget.toNanos();
            Path path = child.directory().resolve(name + ".json");
            while (!Files.exists(path)) {
                require(child.process().isAlive() && System.nanoTime() < stop);
                if (Files.exists(child.directory().resolve("result.json"))) {
                    JsonNode premature = JSON.readTree(Files.readString(child.directory().resolve("result.json")));
                    require(!"FAILED".equals(premature.path("phase").asText()));
                }
                Thread.sleep(10);
            }
            var value = JSON.readTree(Files.readString(path));
            assertThat(value.path("osPid").asLong()).isEqualTo(child.process().pid());
            assertThat(value.path("logicalDatabase").asText()).isEqualTo("origin");
            require(!"FAILED".equals(value.path("phase").asText()));
            if (name.equals("ready")) {
                require(value.path("codeSourceMatchesParent").asBoolean());
                assertThat(value.path("kernelClassSha256").asText()).isEqualTo(classHash(JdbcBulkDurableExecution.class));
                assertThat(value.path("processClassSha256").asText()).isEqualTo(classHash(RuntimeProcess.class));
            }
            var certificate = value.deepCopy();
            ((ObjectNode) certificate).put("actor", child.restart() ? "restarted-runtime" : "original-runtime");
            events.add(certificate);
            return value;
        }

        void join(Child child) throws Exception {
            assertThat(child.process().waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(child.process().exitValue()).isZero();
            phase("RUNTIME_JVM_EXITED").put("osPid", child.process().pid()).put("exitCode", child.process().exitValue());
        }

        ObjectNode phase(String phase) { return events.addObject().put("phase", phase); }
        void session(String phase, Session session) {
            phase(phase).put("backendPid", session.pid()).put("databaseOid", session.databaseOid())
                    .put("roleAlias", "ordinary-runtime").put("backendStart", session.backendStart().toString());
        }

        void shutdown() throws Exception {
            if (shutDown) return;
            shutDown = true;
            Throwable failure = null;
            // Always release every held test transaction before any join/future/executor wait.
            for (var child : children) if (child.process().isAlive()) {
                try { signal(child, "callback-release"); signal(child, "probe-release"); signal(child, "abort"); }
                catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
            }
            if (fence != null) {
                try { fence.get(20, TimeUnit.SECONDS); }
                catch (Exception | Error cleanup) { fence.cancel(true); failure = add(failure, cleanup); }
            }
            for (var child : children) {
                try {
                    if (!child.process().waitFor(20, TimeUnit.SECONDS)) {
                        child.process().destroy();
                        if (!child.process().waitFor(3, TimeUnit.SECONDS)) child.process().destroyForcibly();
                        if (!child.process().waitFor(3, TimeUnit.SECONDS)) throw new IllegalStateException("Owned withdrawal runtime remains alive");
                    }
                    phase("OWNED_RUNTIME_CLEANUP").put("osPid", child.process().pid()).put("exitCode", child.process().exitValue());
                } catch (Exception | Error cleanup) {
                    child.process().destroyForcibly(); failure = add(failure, cleanup);
                    try {
                        if (!child.process().waitFor(3, TimeUnit.SECONDS)) throw new IllegalStateException("Owned withdrawal runtime remains alive after forced cleanup");
                    } catch (Exception | Error join) { failure = add(failure, join); }
                }
            }
            if (observer != null) {
                try { observer.rollback(); }
                catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
                try { observer.close(); observer = null; }
                catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
            }
            executor.shutdownNow();
            try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Owned fence executor remains alive"); }
            catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            try { shutdown(); } catch (Exception | Error cleanup) { failure = cleanup; }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
            manifest.put("subprocesses", children.size()).put("privateConfigurationRemoved", !Files.exists(directory))
                    .put("ownSharedScopeClosed", scopeClosed)
                    .put("caseOutcome", scopeClosed && failure == null ? "ASSERTIONS_COMPLETE" : "INCOMPLETE_OR_FAILED");
            try {
                String configured = System.getProperty("praxis.bulk.proof.directory");
                Path proofs = configured == null ? Files.createTempDirectory("praxis-withdrawal-proof-") : Path.of(configured);
                Files.createDirectories(proofs);
                Path path = proofs.resolve("controlled-origin-withdrawal.json");
                Files.writeString(path, manifest.toPrettyString(), StandardCharsets.UTF_8);
                if (configured == null) System.out.println("Controlled withdrawal manifest: " + path.toAbsolutePath());
            } catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
        }
        private static Throwable add(Throwable primary, Throwable next) {
            if (primary == null) return next;
            if (primary != next) primary.addSuppressed(next);
            return primary;
        }
    }
}
