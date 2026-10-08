package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * C1b partial: an administered cold copy of an owned cluster remains externally quarantined.
 * The copied system identifier, OIDs and bindings certify copied data, not continuity. This
 * does not prove authority recovery, journal anti-rollback, HA, power-loss safety or promotion.
 * The six-minute budget is for this private cold-copy fixture, not a larger unit/lock budget.
 */
class BulkCapacityTwoClusterQuarantinePostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGIN = "capacity_local_1";
    private static final String RUNTIME = "bulk_runtime_test";
    private static final List<String> LOGINS = List.of("postgres", "occupancy_provisioner",
            "occupancy_allocator", "occupancy_reader", RUNTIME, "durable_runtime");
    private static final Class<?> AGENT = BulkCapacityProvisioningInterlockPostgresTest.ProvisioningAgent.class;
    private static final Class<?> RUNTIME_PROCESS = BulkCapacityExternalQuarantinePostgresTest.RuntimeProcess.class;

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void coldWholeClusterCopyRemainsQuarantinedAndRetirementSurvivesAdminAndRuntimeRestart() throws Exception {
        try (var proof = new Proof()) {
            proof.writeHba(proof.originHba, bootstrapHba());
            var builder = proof.builder(proof.originData, proof.originHba, proof.firstLog, false);
            JdbcBulkCapacityInstallation.ExpectedBinding expected;
            BulkFingerprintContext context;
            BulkExecutionControl control;
            Map<String, List<String>> rows;
            Map<String, List<String>> schema;
            Map<String, List<String>> roles;
            Map<String, List<String>> databases;
            Map<String, List<String>> authority;
            Map<String, List<String>> authoritySchema;
            Map<String, String> settings;
            String systemId;
            Postmaster stopped;
            try (var scope = new BulkCapacityOccupancyPostgresFixture.SharedScope(builder, proof.passwords, connection -> {
                proof.closeBootstrapTrust(connection); return null;
            })) {
                var local = scope.local("two-cluster-quarantine-source", 1);
                var admin = sql(proof.source(scope.postgres, "postgres", "postgres"));
                attestHba(admin, proof.originHba, hba(true, true));
                local.activate();
                var queue = local.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
                var active = local.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
                var enqueued = local.enqueue(local.persist(), "two-cluster-key", queue);
                var claimed = local.kernel.claim(local.context, enqueued.executionId(), "two-cluster-worker", active).orElseThrow();
                var physical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
                var unit = local.kernel.executeUnit(claimed.control(), 0, ignored -> BulkUnitAdmission.admit(), ignored -> {
                    physical.set(local.writeDomain(ignored)); return BulkUnitMutationResult.confirmed();
                });
                require(unit.receiptPresent() && unit.execution().nextOrdinal() == 1
                        && unit.execution().status() == BulkDurableExecutionStatus.RUNNING && claimed.control().epoch() == 2);
                local.assertPhysicalCommit(claimed.executionId(), physical.get());
                proof.phase("ORIGINAL_DOMAIN_RECEIPT_PHYSICAL_COMMIT").put("backendPid", physical.get().backendPid())
                        .put("transactionId", physical.get().transactionId());
                expected = local.expected; context = local.context; control = claimed.control();
                rows = fullRows(local.observer); schema = catalog(local.ownerSource);
                roles = clusterRoles(admin); databases = databaseEnvelope(admin);
                authority = authorityRows(scope.authorityObserver); settings = settings(admin);
                authoritySchema = catalog(scope.source("postgres", "capacity_global"));
                systemId = admin.queryForObject("select system_identifier::text from pg_control_system()", String.class);
                require(systemId != null && !systemId.isBlank());
                new BulkCapacityProvisioningInterlockPostgresTest.Journal(proof.directory, expected).initializeByFixtureOwner();
                require(admin.queryForObject("select count(*) from pg_stat_activity where backend_type='client backend' and pid<>pg_backend_pid()", Long.class) == 0);
                stopped = postmaster(scope.postgres, proof.originData, admin);
                proof.firstStopOffset = Files.size(proof.firstLog);
                local.assertionsComplete();
            }
            // close() logs stop failures internally: a normal return alone is never a success oracle.
            String cleanStop = assertStopped(stopped, proof.originData, proof.firstLog, proof.firstStopOffset);
            proof.phase("OFFICIAL_CLOSE_NATIVE_CLEAN_STOP_AND_ACTUAL_POSTMASTER_EXIT")
                    .put("postmasterPid", stopped.pid()).put("stopExitStatusExposed", false).put("nativeRecord", cleanStop);
            coldCopy(proof.originData, proof.cloneData);
            proof.phase("WHOLE_PGDATA_COPY_BYTES_AND_PERMISSIONS_VERIFIED");
            proof.writeHba(proof.cloneHba, hba(false, false));
            // Existing postgresql.conf prevents initdb. Neither restored startup has trust rules.
            try (var origin = proof.builder(proof.originData, proof.originHba, proof.originLog, true).start();
                    var clone = proof.builder(proof.cloneData, proof.cloneHba, proof.cloneLog, true).start();
                    var childCleanup = (AutoCloseable) proof::stopChildren) {
                var admin = sql(proof.source(origin, "postgres", "postgres"));
                var cloneAdmin = sql(proof.source(clone, "postgres", "postgres"));
                var observer = sql(proof.source(origin, "postgres", ORIGIN));
                var copyObserver = sql(proof.source(clone, "postgres", ORIGIN));
                var originMaster = postmaster(origin, proof.originData, admin);
                var cloneMaster = postmaster(clone, proof.cloneData, cloneAdmin);
                proof.restartedMasters.add(originMaster); proof.restartedMasters.add(cloneMaster);
                require(originMaster.pid() != stopped.pid() && originMaster.pid() != cloneMaster.pid());
                require(origin.getPort() != clone.getPort());
                require(!proof.originData.toRealPath().equals(proof.cloneData.toRealPath()));
                proof.phase("ORIGIN_RESTART_NATIVE_CLEAN_STARTUP").put("postmasterPid", originMaster.pid())
                        .put("launcherExitCode", 0).set("nativeRecords", JSON.valueToTree(assertCleanStartup(origin, proof.originLog, originMaster)));
                proof.phase("CLONE_NATIVE_CLEAN_STARTUP_UNDER_DENY").put("postmasterPid", cloneMaster.pid())
                        .put("launcherExitCode", 0).set("nativeRecords", JSON.valueToTree(assertCleanStartup(clone, proof.cloneLog, cloneMaster)));
                attestHba(admin, proof.originHba, hba(true, true));
                attestHba(cloneAdmin, proof.cloneHba, hba(false, false));
                require(systemId.equals(admin.queryForObject("select system_identifier::text from pg_control_system()", String.class)));
                require(systemId.equals(cloneAdmin.queryForObject("select system_identifier::text from pg_control_system()", String.class)));
                for (var pair : List.of(new Cluster(origin, admin, observer), new Cluster(clone, cloneAdmin, copyObserver))) {
                    unchanged(fullRows(pair.observer()), rows);
                    unchanged(catalog(proof.source(pair.postgres(), "postgres", ORIGIN)), schema);
                    unchanged(clusterRoles(pair.admin()), roles); unchanged(databaseEnvelope(pair.admin()), databases);
                    unchanged(authorityRows(sql(proof.source(pair.postgres(), "postgres", "capacity_global"))), authority);
                    unchanged(catalog(proof.source(pair.postgres(), "postgres", "capacity_global")), authoritySchema);
                    unchanged(settings(pair.admin()), settings);
                    BulkExecutionMigrator.validate(proof.source(pair.postgres(), "postgres", ORIGIN), BulkPostgresTestSupport.testRoleConfiguration());
                    require("ACTIVE".equals(pair.observer().queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)));
                    require(pair.observer().queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_slot where current_execution_id=? and current_owner_epoch=2", Long.class, control.executionId()) == 1);
                    try (var connection = proof.source(pair.postgres(), "postgres", ORIGIN).getConnection()) {
                        var runtimeSource = proof.source(pair.postgres(), RUNTIME, ORIGIN);
                        var runtime = new BulkExecutionInfrastructure(runtimeSource,
                                new org.springframework.jdbc.datasource.DataSourceTransactionManager(runtimeSource),
                                context.namespaceId(), expected.deploymentId(), BulkPostgresTestSupport.testRoleConfiguration());
                        JdbcBulkCapacityOccupancy.requireBinding(connection, runtime, expected, true);
                    }
                }
                for (String role : LOGINS) if (!role.equals("postgres")) {
                    denied(proof.source(clone, role, ORIGIN)); denied(proof.source(clone, role, "capacity_global"));
                }
                proof.phase("TWO_ACTUAL_POSTMASTERS_COPIED_OIDS_AND_FULL_BINDING_QUARANTINED")
                        .put("originPostmasterPid", originMaster.pid()).put("clonePostmasterPid", cloneMaster.pid())
                        .put("databaseOid", databaseOid(admin)).put("coveredNonAdminLogins", LOGINS.size() - 1)
                        .put("nativeSqlState", "28000").put("copiedSystemIdentifierEqual", true);
                var runtimeConfig = runtimeConfiguration(proof, origin, clone, expected, context, control);
                var old = proof.launch(RUNTIME_PROCESS, runtimeConfig, "old-runtime");
                var ready = proof.await(old, "ready", "ORIGINAL_JVM_CERTIFIED", Duration.ofSeconds(20));
                require(ready.path("positiveReceiptReplay").asBoolean());
                var held = session(admin, ready.path("heldPid").asInt());
                require(held.databaseOid() == databaseOid(admin) && held.role().equals(RUNTIME)
                        && held.applicationName().equals("external_quarantine_runtime"));
                unchanged(fullRows(observer), rows); unchanged(fullRows(copyObserver), rows);
                String prefixDigest = ledgerDigest(observer);
                String authorityDigest = authorityDigest(sql(proof.source(origin, "postgres", "capacity_global")));
                var agentConfig = agentConfiguration(proof, origin, expected, context, held, prefixDigest, authorityDigest);
                var agent = proof.launch(AGENT, agentConfig, "retiring-admin");
                proof.await(agent, "ready", "AGENT_CODE_SOURCE_VERIFIED", Duration.ofSeconds(20));
                proof.await(agent, "barrier", "DENY_EFFECTIVE", Duration.ofSeconds(20));
                require(JSON.readTree(Files.readAllBytes(proof.directory.resolve("provisioning.json")))
                        .path("payload").path("state").asText().equals("INTENT"));
                // HBA denies reconnect but the old session can still write. Roll back that witness
                // before release: the reused agent will fence and exclude its exact tuple next.
                require(session(admin, held.pid()).equals(held));
                proof.signal(old, "retire");
                var probe = proof.await(old, "probe", "RETAINED_SESSION_REAL_UPDATE_UNCOMMITTED", Duration.ofSeconds(2));
                require(probe.path("backendPid").asInt() == held.pid() && probe.path("writesInsideTransaction").asInt() == 1);
                require(observer.queryForObject("select writes from occupancy_domain_witness where id=2", Integer.class) == 0);
                require(admin.queryForObject("select count(*) from pg_stat_activity where pid=? and datid=? and backend_xid::text::bigint=? and state='idle in transaction'", Long.class,
                        held.pid(), held.databaseOid(), probe.path("transactionId").asLong()) == 1);
                proof.signal(old, "rollback");
                proof.await(old, "rolled-back", "RETAINED_UPDATE_ROLLBACK_CONFIRMED", Duration.ofSeconds(3));
                unchanged(fullRows(observer), rows); require(session(admin, held.pid()).equals(held));
                proof.signal(agent, "release");
                var excluded = proof.await(agent, "excluded", "EXCLUSION_READBACK", Duration.ofSeconds(20));
                require(excluded.path("backendPid").asInt() == held.pid() && excluded.path("remainingWriters").asInt() == 0);
                require(admin.queryForObject("select count(*) from pg_stat_activity where pid=? and datid=? and usename=? and backend_start=? and application_name=?", Long.class,
                        held.pid(), held.databaseOid(), held.role(), OffsetDateTime.ofInstant(held.start(), java.time.ZoneOffset.UTC), held.applicationName()) == 0);
                proof.await(agent, "result", "RETIRED", Duration.ofSeconds(20)); proof.join(agent);
                require(prefixDigest.equals(ledgerDigest(observer)));
                require(authorityDigest.equals(authorityDigest(sql(proof.source(origin, "postgres", "capacity_global")))));
                require("FENCED".equals(observer.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)));
                unchanged(fullRows(copyObserver), rows); unchanged(clusterRoles(cloneAdmin), roles);
                proof.signal(old, "terminated");
                proof.await(old, "result", "ORIGINAL_RUNTIME_BOTH_DATABASES_DENIED", Duration.ofSeconds(20)); proof.join(old);
                runtimeConfig.setProperty("restart", "true");
                var restarted = proof.launch(RUNTIME_PROCESS, runtimeConfig, "restarted-runtime");
                proof.await(restarted, "ready", "RESTARTED_JVM_CERTIFIED", Duration.ofSeconds(20));
                proof.await(restarted, "result", "RESTARTED_RUNTIME_BOTH_DATABASES_DENIED", Duration.ofSeconds(20)); proof.join(restarted);
                agentConfig.setProperty("mode", "BOOT"); agentConfig.setProperty("barrier", "NONE");
                agentConfig.setProperty("expectedSequence", "2");
                var boot = proof.launch(AGENT, agentConfig, "restarted-admin");
                proof.await(boot, "ready", "AGENT_CODE_SOURCE_VERIFIED", Duration.ofSeconds(20));
                var bootResult = proof.await(boot, "result", "START_DENIED", Duration.ofSeconds(20)); proof.join(boot);
                require(bootResult.path("administrativeActions").asInt() == 0 && bootResult.path("sequence").asLong() == 2);
                require(prefixDigest.equals(ledgerDigest(observer)));
                require(authorityDigest.equals(authorityDigest(sql(proof.source(origin, "postgres", "capacity_global")))));
                unchanged(fullRows(copyObserver), rows); unchanged(clusterRoles(admin), roles);
                unchanged(catalog(proof.source(origin, "postgres", ORIGIN)), schema);
                unchanged(catalog(proof.source(clone, "postgres", ORIGIN)), schema);
                unchanged(catalog(proof.source(origin, "postgres", "capacity_global")), authoritySchema);
                unchanged(catalog(proof.source(clone, "postgres", "capacity_global")), authoritySchema);
                unchanged(databaseEnvelope(admin), databases); unchanged(databaseEnvelope(cloneAdmin), databases);
                attestHba(admin, proof.originHba, hba(false, true)); attestHba(cloneAdmin, proof.cloneHba, hba(false, false));
                for (String role : LOGINS) if (!role.equals("postgres")) {
                    denied(proof.source(origin, role, ORIGIN)); denied(proof.source(clone, role, ORIGIN));
                    denied(proof.source(clone, role, "capacity_global"));
                }
                require(admin.queryForObject("select count(*) from pg_stat_activity where datname=?", Long.class, ORIGIN) == 0);
                require(cloneAdmin.queryForObject("select count(*) from pg_stat_activity where datname in (?,?)", Long.class, ORIGIN, "capacity_global") == 0);
                proof.phase("RETIRED_INTENT_CONFIRMED_AFTER_EXCLUSION_WITH_PREFIX_AND_AUTHORITY_PRESERVED");
                proof.originStopOffset = Files.size(proof.originLog); proof.cloneStopOffset = Files.size(proof.cloneLog);
            }
            proof.phase("ORIGIN_FINAL_NATIVE_STOP_CONFIRMED").put("nativeRecord",
                    assertStopped(proof.restartedMasters.get(0), proof.originData, proof.originLog, proof.originStopOffset));
            proof.phase("CLONE_FINAL_NATIVE_STOP_CONFIRMED").put("nativeRecord",
                    assertStopped(proof.restartedMasters.get(1), proof.cloneData, proof.cloneLog, proof.cloneStopOffset));
            proof.clustersClosed = true;
            proof.complete = true;
        }
    }

    private record Cluster(EmbeddedPostgres postgres, JdbcTemplate admin, JdbcTemplate observer) { }
    private record Session(int pid, long databaseOid, String role, Instant start, String applicationName) { }
    private record Postmaster(long pid, Instant start, String command, Instant postgresStart) { }

    private static Session session(JdbcTemplate admin, int pid) {
        var rows = admin.query("select pid,datid::bigint,usename,backend_start,application_name from pg_stat_activity where pid=?",
                (r, n) -> new Session(r.getInt(1), r.getLong(2), r.getString(3), r.getObject(4, OffsetDateTime.class).toInstant(), r.getString(5)), pid);
        require(rows.size() == 1); return rows.getFirst();
    }

    private static long databaseOid(JdbcTemplate admin) {
        return admin.queryForObject("select oid::bigint from pg_database where datname=?", Long.class, ORIGIN);
    }

    /** The launcher is pg_ctl start, whereas postmaster.pid identifies the actual PostgreSQL process. */
    private static Postmaster postmaster(EmbeddedPostgres postgres, Path data, JdbcTemplate admin) throws Exception {
        var lines = Files.readAllLines(data.resolve("postmaster.pid")); require(lines.size() >= 3);
        long pid = Long.parseLong(lines.get(0)); require(pid > 0);
        require(Path.of(lines.get(1)).toRealPath().equals(data.toRealPath()));
        require(Path.of(admin.queryForObject("show data_directory", String.class)).toRealPath().equals(data.toRealPath()));
        require(Path.of(admin.queryForObject("show config_file", String.class)).toRealPath().equals(data.resolve("postgresql.conf").toRealPath()));
        require(Path.of(admin.queryForObject("show ident_file", String.class)).toRealPath().equals(data.resolve("pg_ident.conf").toRealPath()));
        require(Integer.toString(postgres.getPort()).equals(admin.queryForObject("show port", String.class)));
        var handle = ProcessHandle.of(pid).orElseThrow(); require(handle.isAlive());
        Instant start = handle.info().startInstant().orElseThrow();
        String command = handle.info().command().orElseThrow();
        require(Path.of(command).getFileName().toString().equals("postgres"));
        require(Arrays.asList(handle.info().arguments().orElseThrow()).contains(data.toString()));
        Instant postgresStart = admin.queryForObject("select pg_postmaster_start_time()", OffsetDateTime.class).toInstant();
        require(Math.abs(Duration.between(start, postgresStart).toMillis()) < 2_000);
        require(Math.abs(Instant.ofEpochSecond(Long.parseLong(lines.get(2))).toEpochMilli() - postgresStart.toEpochMilli()) < 2_000);
        require(postgres.getProcess().pid() != pid);
        return new Postmaster(pid, start, command, postgresStart);
    }

    private static String assertStopped(Postmaster prior, Path data, Path log, long offset) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            var current = ProcessHandle.of(prior.pid());
            boolean same = false;
            if (current.isPresent() && current.get().isAlive()) {
                require(current.get().info().startInstant().isPresent());
                same = current.get().info().startInstant().orElseThrow().equals(prior.start());
            }
            if (!same && !Files.exists(data.resolve("postmaster.pid"))) break;
            require(System.nanoTime() < end); Thread.sleep(10);
        }
        byte[] bytes = Files.readAllBytes(log); require(offset >= 0 && bytes.length > offset);
        String stop = new String(Arrays.copyOfRange(bytes, Math.toIntExact(offset), bytes.length), StandardCharsets.UTF_8);
        var records = stop.lines().filter(line -> line.contains("[" + prior.pid() + "]") && line.contains("database system is shut down")).toList();
        require(records.size() == 1);
        require(!stop.contains("PANIC:") && !stop.contains("could not shut down"));
        return records.getFirst();
    }

    private static List<String> assertCleanStartup(EmbeddedPostgres postgres, Path log, Postmaster actual) throws Exception {
        require(postgres.getProcess().waitFor(20, TimeUnit.SECONDS) && postgres.getProcess().exitValue() == 0);
        String startup = Files.readString(log);
        require(startup.contains("database system was shut down") && startup.contains("database system is ready to accept connections"));
        require(startup.lines().anyMatch(line -> line.contains("[" + actual.pid() + "]") && line.contains("database system is ready to accept connections")));
        require(!startup.contains("was interrupted") && !startup.contains("automatic recovery")
                && !startup.contains("redo starts") && !startup.contains("PANIC:"));
        // Only these fixed native status records are exported, never the private log as a whole.
        var records = startup.lines().filter(line -> line.contains("database system was shut down")
                || (line.contains("[" + actual.pid() + "]") && line.contains("database system is ready to accept connections"))).toList();
        require(records.size() == 2); return records;
    }

    /** No symlink, tablespace mount, omitted WAL directory or logical database substitution is allowed. */
    private static void coldCopy(Path from, Path to) throws Exception {
        require(!Files.exists(to) && Files.isDirectory(from) && !Files.exists(from.resolve("postmaster.pid")));
        require(Files.isDirectory(from.resolve("pg_wal")) && Files.isDirectory(from.resolve("pg_xact")));
        try (var walk = Files.walk(from)) {
            for (Path path : walk.sorted().toList()) {
                require(!Files.isSymbolicLink(path));
                Path target = to.resolve(from.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(target, PosixFilePermissions.asFileAttribute(Files.getPosixFilePermissions(path)));
                    require(Files.getPosixFilePermissions(path).equals(Files.getPosixFilePermissions(target)));
                } else {
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                    require(Files.mismatch(path, target) == -1);
                    require(Files.getPosixFilePermissions(path).equals(Files.getPosixFilePermissions(target)));
                }
            }
        }
        try (var left = Files.walk(from); var right = Files.walk(to)) {
            require(left.map(from::relativize).sorted().toList().equals(right.map(to::relativize).sorted().toList()));
        }
    }

    private static String bootstrapHba() {
        return "host all postgres 127.0.0.1/32 trust\nhost all all 127.0.0.1/32 reject\nhost all all ::1/128 reject\nlocal all all reject\n";
    }
    private static String hba(boolean runtime, boolean authority) {
        return "host all postgres 127.0.0.1/32 scram-sha-256\n"
                + (authority ? "host capacity_global occupancy_provisioner,occupancy_allocator,occupancy_reader 127.0.0.1/32 scram-sha-256\n" : "")
                + (runtime ? "host capacity_local_1 bulk_runtime_test 127.0.0.1/32 scram-sha-256\n" : "")
                + "host all all 127.0.0.1/32 reject\nhost all all ::1/128 reject\nlocal all all reject\n";
    }
    private static void attestHba(JdbcTemplate admin, Path path, String expected) throws Exception {
        require(Files.readString(path).equals(expected));
        require(Path.of(admin.queryForObject("show hba_file", String.class)).toRealPath().equals(path.toRealPath()));
        require(!path.toRealPath().startsWith(Path.of(admin.queryForObject("show data_directory", String.class)).toRealPath()));
        require("127.0.0.1".equals(admin.queryForObject("show listen_addresses", String.class)));
        require("".equals(admin.queryForObject("show unix_socket_directories", String.class)));
        require(admin.queryForObject("select count(*) from pg_hba_file_rules where error is not null", Long.class) == 0);
        var methods = admin.queryForList("select auth_method from pg_hba_file_rules order by line_number", String.class);
        var exact = expected.lines().map(line -> line.substring(line.lastIndexOf(' ') + 1)).toList();
        require(methods.equals(exact));
        require(admin.queryForList("select rolname from pg_roles where rolcanlogin order by 1", String.class).equals(LOGINS.stream().sorted().toList()));
    }
    private static void denied(DataSource source) throws SQLException {
        try (var ignored = source.getConnection()) { throw new AssertionError("Private quarantine admitted a non-administrative connection"); }
        catch (SQLException denied) { require("28000".equals(denied.getSQLState())); }
    }
    private static JdbcTemplate sql(DataSource source) { var sql = new JdbcTemplate(source); sql.setQueryTimeout(3); return sql; }
    private static void require(boolean condition) { if (!condition) throw new AssertionError("Private two-cluster proof invariant failed"); }
    private static void unchanged(Object actual, Object expected) { require(actual.equals(expected)); }

    /** Entire database envelopes/settings and private authentication rows are never printed. */
    private static Map<String, List<String>> databaseEnvelope(JdbcTemplate admin) {
        return Map.of("databases", admin.queryForList("select to_jsonb(d)::text from pg_database d order by 1", String.class),
                "settings", admin.queryForList("select to_jsonb(s)::text from pg_db_role_setting s order by 1", String.class),
                "tablespaces", admin.queryForList("select to_jsonb(t)::text from pg_tablespace t order by 1", String.class));
    }

    /** Only the five launch-location values differ; each is independently attested to its own cluster. */
    private static Map<String, String> settings(JdbcTemplate admin) {
        var settings = new LinkedHashMap<String, String>();
        admin.query("select name,setting from pg_settings order by name", row -> {
            String name = row.getString(1);
            if (!Set.of("port", "data_directory", "hba_file", "config_file", "ident_file").contains(name))
                settings.put(name, row.getString(2));
        });
        return settings;
    }

    private static Map<String, List<String>> authorityRows(JdbcTemplate observer) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : observer.queryForList("select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk_capacity' and c.relkind='r' order by 1", String.class)) {
            require(table.matches("[a-z_][a-z0-9_]*"));
            result.put(table, observer.queryForList("select to_jsonb(t)::text from (select *,xmin::text as row_xmin from praxis_bulk_capacity."
                    + table + ") t order by 1", String.class));
        }
        require(!result.isEmpty()); return result;
    }

    /** This projection is deliberately identical to the reused C003 agent's readback projection. */
    private static String ledgerDigest(JdbcTemplate observer) throws Exception {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : observer.queryForList("select n.nspname||'.'||c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where c.relkind='r' and (n.nspname='praxis_bulk' or (n.nspname='public' and c.relname='occupancy_domain_witness')) order by 1", String.class)) {
            require(table.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*"));
            if (table.equals("praxis_bulk.praxis_bulk_capacity_marker")) continue;
            result.put(table, observer.queryForList("select to_jsonb(t)::text from (select *,xmin::text as row_xmin from " + table + ") t order by 1", String.class));
        }
        require(result.containsKey("praxis_bulk.praxis_bulk_item_receipt"));
        return digest(JSON.writeValueAsBytes(result));
    }
    private static String authorityDigest(JdbcTemplate observer) throws Exception { return digest(JSON.writeValueAsBytes(authorityRows(observer))); }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String classHash(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            require(input != null); return digest(input.readAllBytes());
        }
    }
    private static String codeSource(Class<?> type) { return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm(); }
    private static ObjectNode binding(JdbcBulkCapacityInstallation.ExpectedBinding e) {
        return JSON.createObjectNode().put("deployment", e.deploymentId()).put("tenant", e.tenantId()).put("environment", e.environment())
                .put("binding", e.bindingId()).put("generation", e.generation()).put("databaseId", e.databaseId().toString())
                .put("attestationId", e.attestationId().toString()).put("authorityId", e.authorityId().toString()).put("authorityEpoch", e.authorityEpoch());
    }

    private static Properties runtimeConfiguration(Proof proof, EmbeddedPostgres origin, EmbeddedPostgres clone,
            JdbcBulkCapacityInstallation.ExpectedBinding e, BulkFingerprintContext c, BulkExecutionControl control) throws Exception {
        var p = new Properties(); p.setProperty("originUrl", origin.getJdbcUrl(RUNTIME, ORIGIN));
        p.setProperty("copyUrl", clone.getJdbcUrl(RUNTIME, ORIGIN)); p.setProperty("runtimePassword", proof.passwords.get(RUNTIME));
        p.setProperty("runtimeRoles", String.join(",", BulkPostgresTestSupport.testRoleConfiguration().runtimeGranteeRoles()));
        p.setProperty("ownerRole", "postgres");
        p.setProperty("deployment", e.deploymentId()); p.setProperty("tenant", e.tenantId()); p.setProperty("environment", e.environment());
        p.setProperty("binding", e.bindingId()); p.setProperty("generation", Long.toString(e.generation()));
        p.setProperty("databaseId", e.databaseId().toString()); p.setProperty("attestationId", e.attestationId().toString());
        p.setProperty("authorityId", e.authorityId().toString()); p.setProperty("authorityEpoch", Long.toString(e.authorityEpoch()));
        p.setProperty("async.namespace", c.namespaceId()); p.setProperty("async.subject", c.subjectId()); p.setProperty("async.resource", c.resourceKey());
        p.setProperty("async.revision", c.schemaRevision()); p.setProperty("async.group", c.operationRef().group());
        p.setProperty("async.operation", c.operationRef().operationId()); p.setProperty("async.path", c.operationRef().path()); p.setProperty("async.method", c.operationRef().method());
        p.setProperty("async.executionId", control.executionId().toString()); p.setProperty("async.ownerId", control.ownerId()); p.setProperty("async.epoch", Long.toString(control.epoch()));
        p.setProperty("restart", "false"); return p;
    }

    private static Properties agentConfiguration(Proof proof, EmbeddedPostgres origin,
            JdbcBulkCapacityInstallation.ExpectedBinding expected, BulkFingerprintContext context,
            Session held, String prefix, String authority) throws Exception {
        var p = new Properties(); p.setProperty("bindingJson", JSON.writeValueAsString(binding(expected)));
        p.setProperty("namespace", context.namespaceId()); p.setProperty("adminUrl", origin.getJdbcUrl("postgres", "postgres"));
        p.setProperty("ownerUrl", origin.getJdbcUrl("postgres", ORIGIN)); p.setProperty("authorityUrl", origin.getJdbcUrl("postgres", "capacity_global"));
        p.setProperty("runtimeUrl", origin.getJdbcUrl(RUNTIME, ORIGIN));
        for (String role : LOGINS) {
            p.setProperty("password." + role, proof.passwords.get(role));
            p.setProperty("originUrl." + role, origin.getJdbcUrl(role, ORIGIN)); p.setProperty("authorityUrl." + role, origin.getJdbcUrl(role, "capacity_global"));
        }
        p.setProperty("prefixDigest", prefix); p.setProperty("authorityDigest", authority);
        p.setProperty("held.pid", Integer.toString(held.pid())); p.setProperty("held.databaseOid", Long.toString(held.databaseOid()));
        p.setProperty("held.role", held.role()); p.setProperty("held.start", held.start().toString());
        p.setProperty("journalDirectory", proof.directory.toString()); p.setProperty("hbaFile", proof.originHba.toString());
        p.setProperty("mode", "RETIRE"); p.setProperty("barrier", "DENY_EFFECTIVE"); p.setProperty("expectedSequence", "0");
        return p;
    }

    private record Child(Path directory, Process process, Class<?> type) { }

    /** All ADMIN custody, native logs and handoffs are private and removed after owned cleanup. */
    private static final class Proof implements AutoCloseable {
        final Path directory = Files.createTempDirectory("praxis-two-cluster-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final Path originData = directory.resolve("origin-pgdata");
        final Path cloneData = directory.resolve("clone-pgdata");
        final Path originHba = privateFile("origin-hba.conf");
        final Path cloneHba = privateFile("clone-hba.conf");
        final Path firstLog = privateFile("initial-native.log");
        final Path originLog = privateFile("origin-native.log");
        final Path cloneLog = privateFile("clone-native.log");
        final List<Child> children = new ArrayList<>();
        final List<Postmaster> restartedMasters = new ArrayList<>();
        final Map<String, String> passwords;
        final ObjectNode manifest = JSON.createObjectNode().put("caseId", "two-cluster-quarantine")
                .put("harnessPid", ProcessHandle.current().pid()).put("scope", "ADMINISTERED_COLD_CLUSTER_COPY_ONLY")
                .put("barriersUsed", true);
        long firstStopOffset;
        long originStopOffset;
        long cloneStopOffset;
        boolean complete;
        boolean clustersClosed;
        boolean childrenStopped;

        Proof() throws Exception {
            var map = new LinkedHashMap<String, String>(); for (String role : LOGINS) map.put(role, UUID.randomUUID().toString());
            passwords = Map.copyOf(map); privateFile("provisioning.lock"); manifest.putArray("events");
        }
        Path privateFile(String name) throws Exception {
            return Files.createFile(directory.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).toAbsolutePath();
        }
        ObjectNode phase(String phase) { require(phase.matches("[A-Z][A-Z0-9_]{0,100}")); return manifest.withArray("events").addObject().put("phase", phase); }
        EmbeddedPostgres.Builder builder(Path data, Path hba, Path log, boolean restored) {
            var builder = EmbeddedPostgres.builder().setDataDirectory(data).setCleanDataDirectory(false).setRegisterShutdownHook(false)
                    .setPGStartupWait(Duration.ofSeconds(30)).setServerConfig("hba_file", hba.toString())
                    .setServerConfig("listen_addresses", "127.0.0.1").setServerConfig("unix_socket_directories", "")
                    .setServerConfig("lc_messages", "C").setServerConfig("log_statement", "none")
                    .setServerConfig("log_line_prefix", "%m[%p]")
                    .setOutputRedirector(ProcessBuilder.Redirect.appendTo(log.toFile())).setErrorRedirector(ProcessBuilder.Redirect.appendTo(log.toFile()));
            if (restored) builder.setConnectConfig("password", passwords.get("postgres"));
            return builder;
        }
        DriverManagerDataSource source(EmbeddedPostgres postgres, String role, String db) {
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(role, db), role, passwords.get(role));
            var options = new Properties(); options.setProperty("connectTimeout", "1"); options.setProperty("socketTimeout", "3");
            options.setProperty("ApplicationName", "private_two_cluster_admin"); source.setConnectionProperties(options); return source;
        }
        void closeBootstrapTrust(Connection connection) throws SQLException {
            require(connection.getAutoCommit()); char[] password = passwords.get("postgres").toCharArray();
            try { connection.unwrap(org.postgresql.PGConnection.class).alterUserPassword("postgres", password, "scram-sha-256"); }
            finally { Arrays.fill(password, '\0'); }
            try { writeHba(originHba, hba(true, true)); }
            catch (Exception failure) { throw new IllegalStateException("Private authenticated bootstrap replacement failed"); }
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                try (var row = statement.executeQuery("select pg_reload_conf()")) { require(row.next() && row.getBoolean(1) && !row.next()); }
            }
            var wrong = new DriverManagerDataSource(connection.getMetaData().getURL(), "postgres", passwords.get(RUNTIME));
            var options = new Properties(); options.setProperty("connectTimeout", "1"); wrong.setConnectionProperties(options);
            long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (true) {
                try (var ignored = wrong.getConnection()) { require(System.nanoTime() < stop); }
                catch (SQLException rejected) { require("28P01".equals(rejected.getSQLState())); break; }
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Private authenticated bootstrap interrupted"); }
            }
            phase("BOOTSTRAP_TRUST_CLOSED_BEFORE_AUTHORITY_DOMAIN_OR_RUNTIME_SETUP");
        }
        void writeHba(Path target, String value) throws Exception {
            Path staged = Files.createTempFile(directory, "hba-stage-", ".conf", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
                }
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
                require(Arrays.equals(Files.readAllBytes(target), bytes));
            } finally { Files.deleteIfExists(staged); }
        }
        Child launch(Class<?> type, Properties base, String name) throws Exception {
            var p = new Properties(); p.putAll(base);
            p.setProperty("processHash", classHash(type)); p.setProperty("processCodeSource", codeSource(type));
            p.setProperty("kernelHash", classHash(JdbcBulkDurableExecution.class)); p.setProperty("kernelCodeSource", codeSource(JdbcBulkDurableExecution.class));
            Path output = Files.createDirectory(directory.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path config = Files.createFile(output.resolve("private-configuration.properties"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var sink = Files.newOutputStream(config)) { p.store(sink, "Private fixed test process configuration"); }
            Path log = Files.createFile(output.resolve("private-output.log"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            var process = new ProcessBuilder(java, "-cp", classpath, type.getName(), config.toString(), output.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            var child = new Child(output, process, type); children.add(child);
            require(children.stream().map(value -> value.process().pid()).distinct().count() == children.size());
            phase(type == AGENT ? "ADMIN_AGENT_JVM_LAUNCHED" : "RUNTIME_JVM_LAUNCHED").put("osPid", process.pid()); return child;
        }
        JsonNode await(Child child, String name, String phase, Duration budget) throws Exception {
            long stop = System.nanoTime() + budget.toNanos(); Path path = child.directory().resolve(name + ".json");
            while (!Files.exists(path)) {
                Path result = child.directory().resolve("result.json");
                if (Files.exists(result)) require(!JSON.readTree(Files.readAllBytes(result)).path("phase").asText().equals("FAILED"));
                require(child.process().isAlive() && System.nanoTime() < stop); Thread.sleep(10);
            }
            var event = JSON.readTree(Files.readAllBytes(path));
            require(event.path("osPid").asLong() == child.process().pid() && event.path("phase").asText().equals(phase));
            if (name.equals("ready")) {
                require(event.path("kernelClassSha256").asText().equals(classHash(JdbcBulkDurableExecution.class)));
                require(event.path("processClassSha256").asText().equals(classHash(child.type())) && event.path("codeSourceMatchesParent").asBoolean());
            }
            manifest.withArray("events").add(event.deepCopy()); return event;
        }
        void signal(Child child, String name) throws Exception {
            require(Set.of("retire", "rollback", "terminated", "release", "abort").contains(name));
            Files.writeString(child.directory().resolve(name), "go");
        }
        void join(Child child) throws Exception {
            require(child.process().waitFor(20, TimeUnit.SECONDS) && child.process().exitValue() == 0);
            phase("OWNED_JVM_EXIT_CONFIRMED").put("osPid", child.process().pid()).put("exitCode", 0);
        }
        void stopChildren() throws Exception {
            if (childrenStopped) return; childrenStopped = true; Throwable failure = null;
            // Abort and release every barrier before any wait, even when a protected assertion fails.
            for (var child : children) if (child.process().isAlive()) {
                for (String name : List.of("abort", "rollback", "release", "terminated", "retire")) {
                    try { signal(child, name); } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
                }
            }
            for (var child : children) {
                try {
                    if (!child.process().waitFor(3, TimeUnit.SECONDS)) child.process().destroyForcibly();
                    require(child.process().waitFor(3, TimeUnit.SECONDS));
                    phase("OWNED_JVM_CLEANUP").put("osPid", child.process().pid()).put("exitCode", child.process().exitValue());
                } catch (Exception | Error cleanup) {
                    child.process().destroyForcibly(); failure = append(failure, cleanup);
                    try { require(child.process().waitFor(3, TimeUnit.SECONDS)); }
                    catch (Exception | Error join) { failure = append(failure, join); }
                }
            }
            rethrow(failure);
        }

        /** Fixed classification codes only: native text, paths and protected values stay private. */
        void incompleteNativeDiagnostics() throws Exception {
            var logs = Map.of("INITIAL_START", firstLog, "ORIGIN_RESTART", originLog, "CLONE_START", cloneLog);
            for (String scope : logs.keySet().stream().sorted().toList()) {
                Path log = logs.get(scope);
                require(Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS));
                require(Files.getPosixFilePermissions(log).equals(PosixFilePermissions.fromString("rw-------")));
                var counts = new LinkedHashMap<String, Integer>();
                int lines = 0;
                try (var records = Files.lines(log, StandardCharsets.UTF_8)) {
                    for (var iterator = records.iterator(); iterator.hasNext();) {
                        String record = iterator.next(); lines++;
                        String code = null;
                        if (record.trim().equals("postgres: invalid argument: \"[%p]\"")) code = "INVALID_LOG_PREFIX_ARGUMENT";
                        else if (record.contains("unrecognized configuration parameter")) code = "UNRECOGNIZED_CONFIGURATION_PARAMETER";
                        else if (record.contains("invalid value for parameter")) code = "INVALID_CONFIGURATION_VALUE";
                        else if (record.contains("could not start server")) code = "PG_CTL_COULD_NOT_START_SERVER";
                        else if (record.contains("PANIC:")) code = "NATIVE_PANIC";
                        else if (record.contains("FATAL:")) code = "NATIVE_FATAL";
                        if (code != null) counts.merge(code, 1, Integer::sum);
                    }
                }
                var diagnostic = phase("INCOMPLETE_PRIVATE_NATIVE_DIAGNOSTIC").put("logicalScope", scope)
                        .put("nativeLogBytes", Files.size(log)).put("nativeRecordCount", lines)
                        .put("rawPreservedPrivately", true).put("rawPermissions0600", true);
                var codes = diagnostic.putArray("classifications");
                counts.forEach((code, count) -> codes.addObject().put("code", code).put("count", count));
                if (counts.isEmpty()) codes.addObject().put("code", lines == 0 ? "NO_NATIVE_OUTPUT" : "UNCLASSIFIED_NATIVE_OUTPUT");
            }
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            try { stopChildren(); } catch (Exception | Error cleanup) { failure = cleanup; }
            // Refuse to erase a live owned PGDATA; never terminate a process by PID alone.
            for (Path data : List.of(originData, cloneData)) {
                if (Files.exists(data.resolve("postmaster.pid"))) failure = append(failure, new AssertionError("Owned PostgreSQL shutdown remains unconfirmed"));
            }
            boolean erasePrivateDirectory = complete && clustersClosed && failure == null;
            // Remove every child credential handoff even when raw diagnostics must remain private.
            for (var child : children) {
                try { Files.deleteIfExists(child.directory().resolve("private-configuration.properties")); }
                catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            }
            if (!erasePrivateDirectory || failure != null) {
                erasePrivateDirectory = false;
                try { incompleteNativeDiagnostics(); }
                catch (Exception | Error diagnostic) { failure = append(failure, diagnostic); }
            }
            manifest.put("assertionsComplete", complete && failure == null).put("clustersClosed", clustersClosed).put("childrenStopped", childrenStopped);
            String output = System.getProperty("praxis.bulk.proof.directory");
            if (output != null && !output.isBlank()) {
                try {
                    Path target = Path.of(output); Files.createDirectories(target);
                    Path file = target.resolve("two-cluster-quarantine-cold-copy.json"); require(!Files.exists(file));
                    Files.writeString(file, manifest.toPrettyString());
                } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            }
            if (erasePrivateDirectory && failure == null) {
                try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
            }
            rethrow(failure);
        }
    }

    private static Throwable append(Throwable original, Throwable next) {
        if (original == null) return next; original.addSuppressed(next); return original;
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    /** All bulk/Flyway rows and the domain fixture, including bytea content and copied xmin. */
    private static Map<String, List<String>> fullRows(JdbcTemplate sql) {
        var result = new LinkedHashMap<String, List<String>>();
        var tables = sql.queryForList("""
                select n.nspname||'.'||c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where c.relkind='r' and (n.nspname='praxis_bulk'
                    or (n.nspname='public' and c.relname='occupancy_domain_witness')) order by 1
                """, String.class);
        require(tables.contains("public.occupancy_domain_witness") && tables.contains("praxis_bulk.praxis_bulk_capacity_marker")
                && tables.contains("praxis_bulk." + BulkExecutionMigrator.HISTORY_TABLE));
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
                        from pg_namespace where nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("relations", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,c.relkind,pg_get_userbyid(c.relowner),c.relacl,c.reloptions)::text
                        from pg_class c join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("columns", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod),
                            a.attnotnull,a.attidentity,a.attgenerated,a.attacl,pg_get_expr(d.adbin,d.adrelid))::text
                        from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace
                        left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') and a.attnum>0 and not a.attisdropped order by 1
                        """));
                result.put("constraints", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,k.conname,k.contype,k.convalidated,k.condeferrable,
                            k.condeferred,pg_get_constraintdef(k.oid))::text
                        from pg_constraint k join pg_class c on c.oid=k.conrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("indexes", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,i.indisvalid,i.indisready,pg_get_indexdef(i.indexrelid))::text
                        from pg_index i join pg_class c on c.oid=i.indrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("functions", strings(connection, """
                        select jsonb_build_array(n.nspname,p.proname,pg_get_userbyid(p.proowner),p.proacl,
                            p.prosecdef,p.provolatile,p.proconfig,pg_get_functiondef(p.oid))::text
                        from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("triggers", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,t.tgname,t.tgenabled,t.tgisinternal,pg_get_triggerdef(t.oid))::text
                        from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
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

}
