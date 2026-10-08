package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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
import java.time.ZoneOffset;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A private Quickstart-provisioning reference agent, not a product journal or continuity API.
 * Its cooperative lock and forced local files certify process-crash ordering on the observed
 * filesystem. They do not detect a valid old journal restored with its custodian, establish
 * isolation between processes with the same UID, certify power-loss durability or fence units.
 * PostgreSQL and all credentials belong exclusively to each test's authenticated SharedScope.
 */
class BulkCapacityProvisioningInterlockPostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String ORIGIN = "capacity_local_1";
    private static final String RUNTIME = "bulk_runtime_test";
    private static final List<String> LOGINS = List.of("postgres", "occupancy_provisioner",
            "occupancy_allocator", "occupancy_reader", RUNTIME, "durable_runtime");

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void durableRetirementSurvivesAgentRestartAndNeverReopensTheOrigin() throws Exception {
        try (var proof = new Proof("interlock-retirement"); var environment = new Environment(proof)) {
            Object lockIdentity = Files.readAttributes(proof.lockFile, "basic:fileKey").get("fileKey");
            assertThat(lockIdentity).isNotNull();
            var retiring = proof.launch(environment.configuration(), "RETIRE", "NONE", 0);
            proof.await(retiring, "result", "RETIRED"); proof.join(retiring);
            environment.assertRetiredAndPreserved();
            var restarted = proof.launch(environment.configuration(), "BOOT", "NONE", 2);
            var result = proof.await(restarted, "result", "START_DENIED"); proof.join(restarted);
            assertThat(result.path("state").asText()).isEqualTo(State.RETIRED.name());
            assertThat(result.path("administrativeActions").asInt()).isZero();
            assertThat(restarted.process().pid()).isNotEqualTo(retiring.process().pid());
            byte[] terminal = Files.readAllBytes(proof.dataFile);
            var reopen = proof.launch(environment.configuration(), "CAS_INTENT", "NONE", 2);
            var refused = proof.await(reopen, "result", "SOURCE_DENIED"); proof.join(reopen);
            assertThat(refused.path("reason").asText()).isEqualTo(Denial.TERMINAL.name());
            assertThat(refused.path("administrativeActions").asInt()).isZero();
            check(Arrays.equals(terminal, Files.readAllBytes(proof.dataFile)));
            assertThat(Files.readAttributes(proof.lockFile, "basic:fileKey").get("fileKey")).isEqualTo(lockIdentity);
            environment.assertRetiredAndPreserved(); proof.complete = true;
        }
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void threeProcessCrashesConserveIntentAndRequireFreshExclusionBeforeConfirmation() throws Exception {
        // Separate scenarios; every barrier is outside a domain/unit transaction.
        for (String barrier : List.of("INTENT_FORCED", "DENY_EFFECTIVE", "EXCLUSION_READBACK")) {
            try (var proof = new Proof("interlock-crash-" + barrier.toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
                    var environment = new Environment(proof)) {
                var dying = proof.launch(environment.configuration(), "RETIRE", barrier, 0);
                proof.await(dying, "barrier", barrier);
                // Observation after the forced-write barrier, not a transition decision outside the lock.
                assertThat(proof.journal(environment.local.expected).read().state()).isEqualTo(State.INTENT);
                assertThat(proof.journal(environment.local.expected).read().sequence()).isEqualTo(1);
                proof.kill(dying);
                var boot = proof.launch(environment.configuration(), "BOOT", "NONE", 1);
                var denied = proof.await(boot, "result", "START_DENIED"); proof.join(boot);
                assertThat(denied.path("state").asText()).isEqualTo(State.INTENT.name());
                assertThat(denied.path("administrativeActions").asInt()).isZero();
                environment.assertPrefixAndAuthority();
                var recovering = proof.launch(environment.configuration(), "RETIRE", "NONE", 1);
                var result = proof.await(recovering, "result", "RETIRED"); proof.join(recovering);
                assertThat(result.path("freshExclusionReadback").asBoolean()).isTrue();
                assertThat(result.path("sequence").asLong()).isEqualTo(2);
                environment.assertRetiredAndPreserved(); proof.complete = true;
            }
        }
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void twoAgentJvmsContendOnOneStableLockAndRereadCasAfterHolderDeath() throws Exception {
        try (var proof = new Proof("interlock-two-agents"); var environment = new Environment(proof)) {
            var identity = Files.readAttributes(proof.lockFile, "basic:fileKey").get("fileKey");
            assertThat(identity).isNotNull();
            var holder = proof.launch(environment.configuration(), "INTENT_HOLD", "INTENT_FORCED", 0);
            proof.await(holder, "barrier", "INTENT_FORCED");
            var waiter = proof.launch(environment.configuration(), "CONTEND_THEN_CAS", "NONE", 0);
            proof.await(waiter, "contended", "LOCK_CONTENDED");
            proof.await(waiter, "timeout", "LOCK_TIMEOUT");
            assertThat(holder.process().pid()).isNotEqualTo(waiter.process().pid());
            proof.kill(holder); // Actual death releases the OS lock, not the durable intent.
            proof.signal(waiter, "retry");
            var stale = proof.await(waiter, "result", "SOURCE_DENIED"); proof.join(waiter);
            assertThat(stale.path("reason").asText()).isEqualTo(Denial.STALE_CAS.name());
            assertThat(stale.path("administrativeActions").asInt()).isZero();
            var row = proof.journal(environment.local.expected).readLocked();
            assertThat(row.sequence()).isEqualTo(1); assertThat(row.state()).isEqualTo(State.INTENT);
            assertThat(Files.readAttributes(proof.lockFile, "basic:fileKey").get("fileKey")).isEqualTo(identity);
            assertThat(Files.readString(proof.hba)).isEqualTo(hba(true));
            assertThat(environment.local.observer.queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)).isEqualTo("ACTIVE");
            environment.assertPrefixAndAuthority(); proof.complete = true;
        }
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void invalidSourceAndStaleCasDenyBootWithoutSqlOrHealing() throws Exception {
        try (var proof = new Proof("interlock-invalid-source"); var environment = new Environment(proof)) {
            var knownBoot = proof.launch(environment.configuration(), "BOOT", "NONE", 0);
            proof.await(knownBoot, "result", "START_ALLOWED"); proof.join(knownBoot);
            byte[] known = Files.readAllBytes(proof.dataFile);
            for (Denial fault : List.of(Denial.MISSING_SOURCE, Denial.CORRUPT_SOURCE,
                    Denial.UNSUPPORTED_FORMAT, Denial.BINDING_DIFFERS)) {
                Files.write(proof.dataFile, known);
                if (fault == Denial.MISSING_SOURCE) Files.delete(proof.dataFile);
                else if (fault == Denial.CORRUPT_SOURCE) Files.writeString(proof.dataFile, "{\"payload\":");
                else {
                    ObjectNode envelope = (ObjectNode) JSON.readTree(known);
                    var payload = (ObjectNode) envelope.get("payload");
                    if (fault == Denial.UNSUPPORTED_FORMAT) payload.put("format", 99);
                    else ((ObjectNode) payload.get("binding")).put("generation", environment.local.expected.generation() + 1);
                    envelope.put("digest", digest(JSON.writeValueAsBytes(payload)));
                    Files.write(proof.dataFile, JSON.writeValueAsBytes(envelope));
                }
                byte[] before = Files.exists(proof.dataFile) ? Files.readAllBytes(proof.dataFile) : null;
                var child = proof.launch(environment.configuration(), "BOOT", "NONE", 0);
                var result = proof.await(child, "result", "SOURCE_DENIED"); proof.join(child);
                assertThat(result.path("reason").asText()).isEqualTo(fault.name());
                assertThat(result.path("administrativeActions").asInt()).isZero();
                check(Arrays.equals(before, Files.exists(proof.dataFile) ? Files.readAllBytes(proof.dataFile) : null));
                environment.assertPrefixAndAuthority();
                check("ACTIVE".equals(environment.local.observer.queryForObject(
                        "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)));
                check(Files.readString(proof.hba).equals(hba(true)));
            }
            // A version that wraps to 1 through intValue must remain unsupported, with a valid digest.
            var overflow = (ObjectNode) JSON.readTree(known);
            var overflowPayload = (ObjectNode) overflow.get("payload"); overflowPayload.put("format", 4_294_967_297L);
            overflow.put("digest", digest(JSON.writeValueAsBytes(overflowPayload)));
            byte[] oversizedVersion = JSON.writeValueAsBytes(overflow); Files.write(proof.dataFile, oversizedVersion);
            var overflowBoot = proof.launch(environment.configuration(), "BOOT", "NONE", 0);
            var overflowResult = proof.await(overflowBoot, "result", "SOURCE_DENIED"); proof.join(overflowBoot);
            assertThat(overflowResult.path("reason").asText()).isEqualTo(Denial.UNSUPPORTED_FORMAT.name());
            assertThat(overflowResult.path("administrativeActions").asInt()).isZero();
            check(Arrays.equals(oversizedVersion, Files.readAllBytes(proof.dataFile)));
            check("ACTIVE".equals(environment.local.observer.queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)));
            check(Files.readString(proof.hba).equals(hba(true))); environment.assertPrefixAndAuthority();
            Files.write(proof.dataFile, known); // Privileged test-fixture restoration, not agent recovery.
            var stale = proof.launch(environment.configuration(), "CAS_INTENT", "NONE", 7);
            var denied = proof.await(stale, "result", "SOURCE_DENIED"); proof.join(stale);
            assertThat(denied.path("reason").asText()).isEqualTo(Denial.STALE_CAS.name());
            check(Arrays.equals(known, Files.readAllBytes(proof.dataFile)));
            // A valid envelope with a wrong checksum is a separate corruption oracle.
            var corrupt = (ObjectNode) JSON.readTree(known); corrupt.put("digest", "00".repeat(32));
            Files.write(proof.dataFile, JSON.writeValueAsBytes(corrupt));
            byte[] broken = Files.readAllBytes(proof.dataFile);
            var checksum = proof.launch(environment.configuration(), "BOOT", "NONE", 0);
            var checksumResult = proof.await(checksum, "result", "SOURCE_DENIED"); proof.join(checksum);
            assertThat(checksumResult.path("reason").asText()).isEqualTo(Denial.CORRUPT_SOURCE.name());
            check(Arrays.equals(broken, Files.readAllBytes(proof.dataFile)));
            check(Files.readString(proof.hba).equals(hba(true)));
            check("ACTIVE".equals(environment.local.observer.queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)));
            environment.assertPrefixAndAuthority(); proof.complete = true;
        }
    }

    private enum State { KNOWN, INTENT, RETIRED }
    private enum Denial { MISSING_SOURCE, CORRUPT_SOURCE, UNSUPPORTED_FORMAT, BINDING_DIFFERS, STALE_CAS, LOCK_TIMEOUT, TERMINAL }
    private static final class SourceDenied extends Exception {
        final Denial reason;
        SourceDenied(Denial reason) { super(reason.name()); this.reason = reason; }
    }
    private record JournalRow(long sequence, State state) { }

    /** One cooperative lock inode lasts across every replacement of the data file. */
    private static final class Journal {
        final Path directory;
        final Path lockFile;
        final Path dataFile;
        final JdbcBulkCapacityInstallation.ExpectedBinding expected;

        Journal(Path directory, JdbcBulkCapacityInstallation.ExpectedBinding expected) {
            this.directory = directory; this.lockFile = directory.resolve("provisioning.lock");
            this.dataFile = directory.resolve("provisioning.json"); this.expected = expected;
        }

        Lease acquire(Duration budget, Runnable contention) throws Exception {
            check(Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS));
            var channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
            boolean returned = false;
            try {
                long stop = System.nanoTime() + budget.toNanos(); boolean witnessed = false;
                while (true) {
                    FileLock lock = channel.tryLock();
                    if (lock != null) { returned = true; return new Lease(channel, lock); }
                    if (!witnessed) { contention.run(); witnessed = true; }
                    if (System.nanoTime() >= stop) throw new SourceDenied(Denial.LOCK_TIMEOUT);
                    Thread.sleep(10);
                }
            } finally { if (!returned) channel.close(); }
        }

        JournalRow readLocked() throws Exception {
            try (var ignored = acquire(Duration.ofSeconds(3), () -> { })) { return read(); }
        }

        JournalRow read() throws Exception {
            if (!Files.exists(dataFile, LinkOption.NOFOLLOW_LINKS)) throw new SourceDenied(Denial.MISSING_SOURCE);
            if (!Files.isRegularFile(dataFile, LinkOption.NOFOLLOW_LINKS) || Files.size(dataFile) > 16_384)
                throw new SourceDenied(Denial.CORRUPT_SOURCE);
            JsonNode envelope;
            try { envelope = JSON.readTree(Files.readAllBytes(dataFile)); }
            catch (com.fasterxml.jackson.core.JacksonException invalid) { throw new SourceDenied(Denial.CORRUPT_SOURCE); }
            if (envelope == null || !keys(envelope, Set.of("payload", "digest"))) throw new SourceDenied(Denial.CORRUPT_SOURCE);
            JsonNode payload = envelope.get("payload");
            if (!keys(payload, Set.of("format", "binding", "sequence", "state"))
                    || !envelope.path("digest").isTextual()
                    || !digest(JSON.writeValueAsBytes(payload)).equals(envelope.path("digest").textValue()))
                throw new SourceDenied(Denial.CORRUPT_SOURCE);
            if (!payload.path("format").isIntegralNumber() || !payload.path("format").canConvertToInt()
                    || payload.path("format").intValue() != 1)
                throw new SourceDenied(Denial.UNSUPPORTED_FORMAT);
            if (!binding(expected).equals(payload.get("binding"))) throw new SourceDenied(Denial.BINDING_DIFFERS);
            if (!payload.path("sequence").isIntegralNumber() || !payload.path("sequence").canConvertToLong())
                throw new SourceDenied(Denial.CORRUPT_SOURCE);
            long sequence = payload.path("sequence").longValue();
            State state;
            try { state = State.valueOf(payload.path("state").textValue()); }
            catch (IllegalArgumentException | NullPointerException badState) { throw new SourceDenied(Denial.CORRUPT_SOURCE); }
            if ((state == State.KNOWN && sequence != 0) || (state == State.INTENT && sequence != 1)
                    || (state == State.RETIRED && sequence != 2)) throw new SourceDenied(Denial.CORRUPT_SOURCE);
            return new JournalRow(sequence, state);
        }

        JournalRow transition(long expectedSequence, State target) throws Exception {
            JournalRow current = read();
            if (current.sequence() != expectedSequence) throw new SourceDenied(Denial.STALE_CAS);
            if (current.state() == State.RETIRED) throw new SourceDenied(Denial.TERMINAL);
            check((current.state() == State.KNOWN && target == State.INTENT)
                    || (current.state() == State.INTENT && target == State.RETIRED));
            var next = new JournalRow(current.sequence() + 1, target);
            persist(next); check(next.equals(read())); return next;
        }

        void initializeByFixtureOwner() throws Exception {
            check(!Files.exists(dataFile));
            try (var ignored = acquire(Duration.ofSeconds(3), () -> { })) {
                persist(new JournalRow(0, State.KNOWN));
                // Preflight exercises ATOMIC_MOVE replacement of an existing destination.
                persist(new JournalRow(0, State.KNOWN)); check(read().state() == State.KNOWN);
            }
        }

        void persist(JournalRow row) throws Exception {
            ObjectNode payload = JSON.createObjectNode().put("format", 1);
            payload.set("binding", binding(expected)); payload.put("sequence", row.sequence()).put("state", row.state().name());
            ObjectNode envelope = JSON.createObjectNode(); envelope.set("payload", payload);
            envelope.put("digest", digest(JSON.writeValueAsBytes(payload)));
            byte[] bytes = JSON.writeValueAsBytes(envelope);
            Path temporary = Files.createTempFile(directory, "journal-stage-", ".json", privateFile());
            try {
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(temporary, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                // Unsupported directory force or atomic replacement is an error, never a fallback.
                try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
                check(Arrays.equals(bytes, Files.readAllBytes(dataFile)));
                try (var paths = Files.list(directory)) { check(paths.anyMatch(path -> path.equals(dataFile))); }
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    private record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws Exception { try { lock.release(); } finally { channel.close(); } }
    }

    private static ObjectNode binding(JdbcBulkCapacityInstallation.ExpectedBinding e) {
        return JSON.createObjectNode().put("deployment", e.deploymentId()).put("tenant", e.tenantId())
                .put("environment", e.environment()).put("binding", e.bindingId()).put("generation", e.generation())
                .put("databaseId", e.databaseId().toString()).put("attestationId", e.attestationId().toString())
                .put("authorityId", e.authorityId().toString()).put("authorityEpoch", e.authorityEpoch());
    }

    private static boolean keys(JsonNode object, Set<String> expected) {
        if (object == null || !object.isObject()) return false;
        var actual = new java.util.HashSet<String>(); object.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static java.nio.file.attribute.FileAttribute<Set<java.nio.file.attribute.PosixFilePermission>> privateFile() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Private provisioning proof invariant failed"); }

    private static String hba(boolean allowOrigin) {
        return "host all postgres 127.0.0.1/32 scram-sha-256\n"
                + "host capacity_global occupancy_provisioner,occupancy_allocator,occupancy_reader 127.0.0.1/32 scram-sha-256\n"
                + (allowOrigin ? "host " + ORIGIN + " " + RUNTIME + " 127.0.0.1/32 scram-sha-256\n" : "")
                + "host all all 127.0.0.1/32 reject\nhost all all ::1/128 reject\nlocal all all reject\n";
    }

    private static void replaceHba(Path path, String content) throws Exception {
        Path staged = Files.createTempFile(path.getParent(), "hba-stage-", ".conf", privateFile());
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            check(Arrays.equals(bytes, Files.readAllBytes(path)));
        } finally { Files.deleteIfExists(staged); }
    }

    private static DriverManagerDataSource source(String url, String role, String password) {
        var source = new DriverManagerDataSource(url, role, password);
        var options = new Properties(); options.setProperty("connectTimeout", "1");
        options.setProperty("socketTimeout", "3"); options.setProperty("ApplicationName", "private_provisioning_interlock");
        source.setConnectionProperties(options); return source;
    }

    private static JdbcTemplate sql(javax.sql.DataSource source) {
        var sql = new JdbcTemplate(source); sql.setQueryTimeout(3); return sql;
    }

    private static void attestHba(JdbcTemplate admin, Path path, boolean allowed) throws Exception {
        check(Files.readString(path).equals(hba(allowed)));
        check(Path.of(admin.queryForObject("show hba_file", String.class)).toRealPath().equals(path.toRealPath()));
        check(!path.toRealPath().startsWith(Path.of(admin.queryForObject("show data_directory", String.class)).toRealPath()));
        check("127.0.0.1".equals(admin.queryForObject("show listen_addresses", String.class)));
        check("".equals(admin.queryForObject("show unix_socket_directories", String.class)));
        check(admin.queryForObject("select count(*) from pg_hba_file_rules where error is not null", Long.class) == 0);
        check(admin.queryForList("select auth_method from pg_hba_file_rules order by line_number", String.class)
                .equals(allowed ? List.of("scram-sha-256", "scram-sha-256", "scram-sha-256", "reject", "reject", "reject")
                        : List.of("scram-sha-256", "scram-sha-256", "reject", "reject", "reject")));
        // Only admin and the enumerated runtime are writers admitted to the original.
        check(admin.queryForList("select rolname from pg_roles where rolcanlogin order by 1", String.class)
                .equals(LOGINS.stream().sorted().toList()));
    }

    private static void denied(String url, String role, String password) throws Exception {
        long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (true) {
            try (var ignored = source(url, role, password).getConnection()) {
                check(System.nanoTime() < stop);
            } catch (SQLException failure) { check("28000".equals(failure.getSQLState())); return; }
            Thread.sleep(10);
        }
    }

    /** JSON projection makes bytea and all JDBC values comparable by content, never by byte[] identity. */
    private static String ledgerDigest(JdbcTemplate observer) throws Exception {
        var result = new LinkedHashMap<String, List<String>>();
        var tables = observer.queryForList("""
                select n.nspname||'.'||c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where c.relkind='r' and (n.nspname='praxis_bulk'
                  or (n.nspname='public' and c.relname='occupancy_domain_witness')) order by 1
                """, String.class);
        check(tables.contains("praxis_bulk.praxis_bulk_item_receipt") && tables.contains("public.occupancy_domain_witness"));
        for (String table : tables) {
            check(table.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*"));
            if (table.equals("praxis_bulk.praxis_bulk_capacity_marker")) continue; // Its sole permitted delta is separately attested.
            result.put(table, observer.queryForList("select to_jsonb(t)::text from (select *,xmin::text as row_xmin from "
                    + table + ") t order by 1", String.class));
        }
        return digest(JSON.writeValueAsBytes(result));
    }

    private static String authorityDigest(JdbcTemplate observer) throws Exception {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : observer.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and c.relkind='r' order by 1
                """, String.class)) {
            check(table.matches("[a-z_][a-z0-9_]*"));
            result.put(table, observer.queryForList("select to_jsonb(t)::text from (select *,xmin::text as row_xmin from praxis_bulk_capacity."
                    + table + ") t order by 1", String.class));
        }
        check(!result.isEmpty()); return digest(JSON.writeValueAsBytes(result));
    }

    private record Session(int pid, long databaseOid, String role, Instant start) { }

    private static Session session(JdbcTemplate admin, int pid) {
        var rows = admin.query("select pid,datid::bigint,usename,backend_start from pg_stat_activity where pid=?",
                (r, n) -> new Session(r.getInt(1), r.getLong(2), r.getString(3), r.getObject(4, OffsetDateTime.class).toInstant()), pid);
        check(rows.size() == 1); return rows.getFirst();
    }

    private static void excludeSession(JdbcTemplate admin, Session session) throws Exception {
        long matches = admin.queryForObject("""
                select count(*) from pg_stat_activity where pid=? and datid=? and usename=? and backend_start=?
                """, Long.class, session.pid(), session.databaseOid(), session.role(), OffsetDateTime.ofInstant(session.start(), ZoneOffset.UTC));
        if (matches == 1) {
            check(Boolean.TRUE.equals(admin.queryForObject("""
                    select pg_terminate_backend(pid,1000) from pg_stat_activity
                     where pid=? and datid=? and usename=? and backend_start=?
                    """, Boolean.class, session.pid(), session.databaseOid(), session.role(), OffsetDateTime.ofInstant(session.start(), ZoneOffset.UTC))));
        } else check(matches == 0); // Repeated exclusion after crash still requires fresh absence below.
        long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (admin.queryForObject("""
                select count(*) from pg_stat_activity where pid=? and datid=? and usename=? and backend_start=?
                """, Long.class, session.pid(), session.databaseOid(), session.role(), OffsetDateTime.ofInstant(session.start(), ZoneOffset.UTC)) != 0) {
            check(System.nanoTime() < stop); Thread.sleep(10);
        }
    }

    private static final class Environment implements AutoCloseable {
        final Proof proof;
        final BulkCapacityOccupancyPostgresFixture.SharedScope scope;
        final BulkCapacityOccupancyPostgresFixture local;
        final JdbcTemplate admin;
        final Connection held;
        final Session heldIdentity;
        final String prefix;
        final String authority;

        Environment(Proof proof) throws Exception {
            this.proof = proof;
            replaceHba(proof.hba, "host all postgres 127.0.0.1/32 trust\nhost all all 127.0.0.1/32 reject\nhost all all ::1/128 reject\nlocal all all reject\n");
            var builder = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false)
                    .setServerConfig("hba_file", proof.hba.toString()).setServerConfig("listen_addresses", "127.0.0.1")
                    .setServerConfig("unix_socket_directories", "");
            scope = new BulkCapacityOccupancyPostgresFixture.SharedScope(builder, proof.passwords, connection -> {
                char[] password = proof.passwords.get("postgres").toCharArray();
                try { connection.unwrap(org.postgresql.PGConnection.class).alterUserPassword("postgres", password, "scram-sha-256"); }
                finally { Arrays.fill(password, '\0'); }
                try { replaceHba(proof.hba, hba(true)); }
                catch (Exception failure) { throw new IllegalStateException("Private authenticated bootstrap failed"); }
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(3);
                    try (var rows = statement.executeQuery("select pg_reload_conf()")) { check(rows.next() && rows.getBoolean(1) && !rows.next()); }
                }
                // A real wrong-password response proves the temporary startup trust is no longer active.
                long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (true) {
                    try (var ignored = source(connection.getMetaData().getURL(), "postgres", proof.passwords.get(RUNTIME)).getConnection()) {
                        check(System.nanoTime() < stop);
                    } catch (SQLException rejected) { check("28P01".equals(rejected.getSQLState())); break; }
                }
                return null;
            });
            Connection opened = null;
            try {
                local = scope.local(proof.caseId, 1); admin = sql(scope.source("postgres", "postgres"));
                attestHba(admin, proof.hba, true);
                check(!proof.directory.toRealPath().startsWith(Path.of(admin.queryForObject("show data_directory", String.class)).toRealPath()));
                local.activate();
                var queue = local.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
                var active = local.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
                var enqueued = local.enqueue(local.persist(), "interlock-key", queue);
                var claimed = local.kernel.claim(local.context, enqueued.executionId(), "interlock-worker", active).orElseThrow();
                var physical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
                var first = local.kernel.executeUnit(claimed.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                    physical.set(local.writeDomain(unit)); return BulkUnitMutationResult.confirmed();
                });
                check(first.receiptPresent() && first.execution().nextOrdinal() == 1);
                local.assertPhysicalCommit(claimed.executionId(), physical.get());
                proof.event("ASYNC_PREFIX_PHYSICAL_COMMIT").put("backendPid", physical.get().backendPid()).put("transactionId", physical.get().transactionId());
                // SYNC is another real writer path. Its distinct witness avoids altering the ASYNC prefix.
                var syncInput = local.persistSync();
                var sync = local.kernel.reserve(local.syncContext, syncInput.proposal().id(), "interlock-sync-key",
                        "interlock-sync-owner", local.syncContext.schemaRevision(), Instant.now().plusSeconds(120));
                var syncPhysical = new AtomicReference<long[]>();
                var syncUnit = local.kernel.executeUnit(sync.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                    local.runtime.withConnection(connection -> {
                        check(!connection.getAutoCommit());
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery("select pg_backend_pid(),txid_current()")) {
                            check(rows.next()); syncPhysical.set(new long[] {rows.getInt(1), rows.getLong(2)}); check(!rows.next());
                        }
                        check(local.runtimeSql.update("update occupancy_domain_witness set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current() where id=2") == 1);
                        check(local.observer.queryForObject("select writes from occupancy_domain_witness where id=2", Integer.class) == 0);
                        check(local.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Long.class, sync.executionId()) == 0);
                        return null;
                    });
                    return BulkUnitMutationResult.confirmed();
                });
                check(syncUnit.receiptPresent());
                var syncWitness = local.observer.queryForMap("""
                        select d.last_pid,d.last_xid,d.xmin::text::bigint as domain_xid,r.xmin::text::bigint as receipt_xid
                        from occupancy_domain_witness d join praxis_bulk.praxis_bulk_item_receipt r
                          on r.execution_id=? and r.unit_ordinal=0 where d.id=2
                        """, sync.executionId());
                long xid = syncPhysical.get()[1] & 0xffffffffL;
                check(((Number) syncWitness.get("last_pid")).longValue() == syncPhysical.get()[0]);
                check(((Number) syncWitness.get("last_xid")).longValue() == syncPhysical.get()[1]);
                check(((Number) syncWitness.get("domain_xid")).longValue() == xid && ((Number) syncWitness.get("receipt_xid")).longValue() == xid);
                proof.event("SYNC_PREFIX_PHYSICAL_COMMIT").put("backendPid", syncPhysical.get()[0]).put("transactionId", syncPhysical.get()[1]);
                opened = source(scope.postgres.getJdbcUrl(RUNTIME, ORIGIN), RUNTIME, scope.password(RUNTIME)).getConnection();
                try (var statement = opened.createStatement(); var row = statement.executeQuery("select pg_backend_pid(),current_user")) {
                    check(row.next() && RUNTIME.equals(row.getString(2))); heldIdentity = session(admin, row.getInt(1)); check(!row.next());
                }
                held = opened;
                prefix = ledgerDigest(local.observer); authority = authorityDigest(scope.authorityObserver);
                proof.journal(local.expected).initializeByFixtureOwner();
                proof.event("FORCED_ATOMIC_REPLACE_DIRECTORY_READBACK_SUPPORTED")
                        .put("fileStoreType", Files.getFileStore(proof.directory).type()).put("javaVersion", System.getProperty("java.version"));
            } catch (Exception | Error failure) {
                if (opened != null) try { opened.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                try { scope.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        Properties configuration() throws Exception {
            var p = new Properties(); p.setProperty("bindingJson", JSON.writeValueAsString(binding(local.expected)));
            p.setProperty("namespace", local.context.namespaceId());
            p.setProperty("adminUrl", scope.postgres.getJdbcUrl("postgres", "postgres"));
            p.setProperty("ownerUrl", scope.postgres.getJdbcUrl("postgres", ORIGIN));
            p.setProperty("authorityUrl", scope.postgres.getJdbcUrl("postgres", "capacity_global"));
            p.setProperty("runtimeUrl", scope.postgres.getJdbcUrl(RUNTIME, ORIGIN));
            for (String role : LOGINS) {
                p.setProperty("password." + role, scope.password(role));
                // Zonky JDBC URLs carry user as well; preserve the actual role on each native probe.
                p.setProperty("originUrl." + role, scope.postgres.getJdbcUrl(role, ORIGIN));
                p.setProperty("authorityUrl." + role, scope.postgres.getJdbcUrl(role, "capacity_global"));
            }
            p.setProperty("prefixDigest", prefix); p.setProperty("authorityDigest", authority);
            p.setProperty("held.pid", Integer.toString(heldIdentity.pid())); p.setProperty("held.databaseOid", Long.toString(heldIdentity.databaseOid()));
            p.setProperty("held.role", heldIdentity.role()); p.setProperty("held.start", heldIdentity.start().toString());
            return p;
        }

        void assertPrefixAndAuthority() throws Exception {
            check(prefix.equals(ledgerDigest(local.observer))); check(authority.equals(authorityDigest(scope.authorityObserver)));
        }

        void assertRetiredAndPreserved() throws Exception {
            assertPrefixAndAuthority(); attestHba(admin, proof.hba, false);
            for (String role : LOGINS) if (!role.equals("postgres")) denied(scope.postgres.getJdbcUrl(role, ORIGIN), role, scope.password(role));
            check("FENCED".equals(local.observer.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)));
            check(admin.queryForObject("select count(*) from pg_stat_activity where datname=? and usename<>?", Long.class, ORIGIN, "postgres") == 0);
            check(proof.journal(local.expected).readLocked().equals(new JournalRow(2, State.RETIRED)));
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            try { proof.stopChildren(); } catch (Exception | Error cleanup) { failure = cleanup; }
            try { held.close(); } catch (SQLException cleanup) { failure = append(failure, cleanup); }
            if (proof.complete && failure == null) local.assertionsComplete();
            try { scope.close(); proof.scopeClosed = true; } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            rethrow(failure);
        }
    }

    /** Fixed private operations only. No SQL text or credentials are accepted in argv. */
    public static final class ProvisioningAgent {
        public static void main(String[] arguments) {
            int exit = 0; Path output = null; int actions = 0;
            try {
                check(arguments.length == 2);
                Path config = Path.of(arguments[0]); output = Path.of(arguments[1]);
                var p = new Properties(); try (var input = Files.newInputStream(config)) { p.load(input); }
                check(required(p, "processHash").equals(classHash(ProvisioningAgent.class)));
                check(required(p, "processCodeSource").equals(codeSource(ProvisioningAgent.class)));
                check(required(p, "kernelHash").equals(classHash(JdbcBulkDurableExecution.class)));
                check(required(p, "kernelCodeSource").equals(codeSource(JdbcBulkDurableExecution.class)));
                emit(output, "ready", event("AGENT_CODE_SOURCE_VERIFIED").put("processClassSha256", classHash(ProvisioningAgent.class))
                        .put("kernelClassSha256", classHash(JdbcBulkDurableExecution.class)).put("codeSourceMatchesParent", true));
                var expected = expected(p);
                var journal = new Journal(Path.of(required(p, "journalDirectory")), expected);
                String mode = required(p, "mode"); long expectedSequence = Long.parseLong(required(p, "expectedSequence"));
                check(Set.of("BOOT", "RETIRE", "INTENT_HOLD", "CAS_INTENT", "CONTEND_THEN_CAS").contains(mode));
                if (mode.equals("CONTEND_THEN_CAS")) {
                    Path sink = output;
                    try (var ignored = journal.acquire(Duration.ofMillis(250), () -> uncheckedEmit(sink, "contended", "LOCK_CONTENDED"))) {
                        throw new AssertionError("Expected real peer lock contention was absent");
                    } catch (SourceDenied timeout) {
                        check(timeout.reason == Denial.LOCK_TIMEOUT);
                        emit(output, "timeout", event("LOCK_TIMEOUT").put("reason", timeout.reason.name()));
                    }
                    awaitSignal(output, "retry", Duration.ofSeconds(30));
                }
                Path sink = output;
                try (var ignored = journal.acquire(Duration.ofSeconds(3), () -> uncheckedEmit(sink, "contended", "LOCK_CONTENDED"))) {
                    // All authoritative reads, validation and CAS are under this exclusive lock.
                    JournalRow current = journal.read();
                    if (mode.equals("BOOT")) {
                        emit(output, "result", event(current.state() == State.KNOWN ? "START_ALLOWED" : "START_DENIED")
                                .put("state", current.state().name()).put("sequence", current.sequence()).put("administrativeActions", 0));
                    } else {
                        if (current.sequence() != expectedSequence) throw new SourceDenied(Denial.STALE_CAS);
                        if (current.state() == State.RETIRED) throw new SourceDenied(Denial.TERMINAL);
                        if (current.state() == State.KNOWN) current = journal.transition(expectedSequence, State.INTENT);
                        if (mode.equals("CAS_INTENT") || mode.equals("CONTEND_THEN_CAS")) {
                            emit(output, "result", event("INTENT_RECORDED").put("sequence", current.sequence()).put("administrativeActions", 0));
                        } else {
                            barrier(p, output, "INTENT_FORCED");
                            if (mode.equals("INTENT_HOLD")) {
                                emit(output, "result", event("INTENT_RECORDED").put("sequence", current.sequence()).put("administrativeActions", 0));
                            } else {
                                actions++;
                                retire(p, output, expected);
                                JournalRow retired = journal.transition(current.sequence(), State.RETIRED);
                                emit(output, "result", event("RETIRED").put("sequence", retired.sequence())
                                        .put("freshExclusionReadback", true).put("administrativeActions", actions));
                            }
                        }
                    }
                }
            } catch (SourceDenied rejected) {
                try { emit(output, "result", event("SOURCE_DENIED").put("reason", rejected.reason.name()).put("administrativeActions", actions)); }
                catch (Exception failure) { exit = 1; }
            } catch (Throwable failure) {
                exit = 1;
                // No throwable messages, driver failing-row details, paths or protected records.
                if (output != null) try { emit(output, "result", event("FAILED").put("failureType", failure.getClass().getSimpleName())); }
                catch (Exception ignored) { }
            }
            System.exit(exit);
        }

        private static void retire(Properties p, Path output, JdbcBulkCapacityInstallation.ExpectedBinding expected) throws Exception {
            Path hba = Path.of(required(p, "hbaFile"));
            replaceHba(hba, BulkCapacityProvisioningInterlockPostgresTest.hba(false));
            var adminSource = source(required(p, "adminUrl"), "postgres", required(p, "password.postgres"));
            var admin = sql(adminSource);
            check(Boolean.TRUE.equals(admin.queryForObject("select pg_reload_conf()", Boolean.class)));
            attestHba(admin, hba, false);
            String ownerUrl = required(p, "ownerUrl");
            // Final HBA reject covers every non-administrative login and both writer APIs on the
            // shared runtime role; IPv6 and local sockets are verified disabled, not silently omitted.
            for (String role : LOGINS) if (!role.equals("postgres"))
                denied(required(p, "originUrl." + role), role, required(p, "password." + role));
            emit(output, "deny", event("DENY_EFFECTIVE").put("nativeSqlState", "28000").put("coveredLoginCount", LOGINS.size() - 1));
            barrier(p, output, "DENY_EFFECTIVE");

            var runtimeSource = source(required(p, "runtimeUrl"), RUNTIME, required(p, "password." + RUNTIME));
            var runtime = new BulkExecutionInfrastructure(runtimeSource, new DataSourceTransactionManager(runtimeSource),
                    required(p, "namespace"), expected.deploymentId(), BulkPostgresTestSupport.testRoleConfiguration());
            var authorityIdentity = new BulkCapacityAuthorityMigrator.Identity(expected.deploymentId(), expected.environment(),
                    expected.authorityId(), expected.authorityEpoch());
            var authorityRoles = new BulkCapacityAuthorityMigrator.RoleConfiguration("postgres",
                    "occupancy_provisioner", "occupancy_allocator", "occupancy_reader");
            var provisionerSource = source(required(p, "authorityUrl.occupancy_provisioner"), "occupancy_provisioner", required(p, "password.occupancy_provisioner"));
            var readingSource = source(required(p, "authorityUrl.occupancy_reader"), "occupancy_reader", required(p, "password.occupancy_reader"));
            var provisioner = new BulkCapacityAuthorityInfrastructure(provisionerSource, new DataSourceTransactionManager(provisionerSource),
                    authorityIdentity, BulkCapacityAuthorityInfrastructure.Access.PROVISIONER, authorityRoles);
            var reader = new JdbcBulkCapacityIssuer.CapacityReader(new BulkCapacityAuthorityInfrastructure(readingSource,
                    new DataSourceTransactionManager(readingSource), authorityIdentity, BulkCapacityAuthorityInfrastructure.Access.READER, authorityRoles));
            var owner = source(ownerUrl, "postgres", required(p, "password.postgres"));
            var installation = new JdbcBulkCapacityInstallation(expected, owner, new DataSourceTransactionManager(owner),
                    "postgres", runtime, provisioner, reader, Duration.ofSeconds(20), Duration.ofSeconds(3));
            installation.fence();
            var held = new Session(Integer.parseInt(required(p, "held.pid")), Long.parseLong(required(p, "held.databaseOid")),
                    required(p, "held.role"), Instant.parse(required(p, "held.start")));
            check(held.role().equals(RUNTIME));
            check(admin.queryForObject("select oid::bigint from pg_database where datname=?", Long.class, ORIGIN) == held.databaseOid());
            excludeSession(admin, held);
            // Unknown remaining writers block confirmation; no blanket termination or PID-only kill.
            check(admin.queryForObject("select count(*) from pg_stat_activity where datname=? and usename<>?", Long.class, ORIGIN, "postgres") == 0);
            var observer = sql(owner);
            try (var connection = owner.getConnection()) { JdbcBulkCapacityOccupancy.requireBinding(connection, runtime, expected, false); }
            check("FENCED".equals(observer.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)));
            check(required(p, "prefixDigest").equals(ledgerDigest(observer)));
            check(required(p, "authorityDigest").equals(authorityDigest(sql(source(required(p, "authorityUrl"), "postgres", required(p, "password.postgres"))))));
            emit(output, "excluded", event("EXCLUSION_READBACK").put("backendPid", held.pid()).put("databaseOid", held.databaseOid())
                    .put("remainingWriters", 0).put("prefixUnchanged", true).put("authorityUnchanged", true));
            barrier(p, output, "EXCLUSION_READBACK");
        }

        private static void barrier(Properties p, Path output, String phase) throws Exception {
            if (!required(p, "barrier").equals(phase)) return;
            emit(output, "barrier", event(phase).put("outsideUnitTransaction", true));
            awaitSignal(output, "release", Duration.ofSeconds(30));
        }
    }

    private static JdbcBulkCapacityInstallation.ExpectedBinding expected(Properties p) throws Exception {
        var b = JSON.readTree(required(p, "bindingJson"));
        return new JdbcBulkCapacityInstallation.ExpectedBinding(b.path("deployment").asText(), b.path("tenant").asText(),
                b.path("environment").asText(), b.path("binding").asText(), b.path("generation").longValue(),
                UUID.fromString(b.path("databaseId").asText()), UUID.fromString(b.path("attestationId").asText()),
                UUID.fromString(b.path("authorityId").asText()), b.path("authorityEpoch").longValue());
    }

    private static String required(Properties p, String name) {
        String value = p.getProperty(name); check(value != null && !value.isBlank()); return value;
    }
    private static void awaitSignal(Path directory, String signal, Duration budget) throws Exception {
        long stop = System.nanoTime() + budget.toNanos();
        while (!Files.exists(directory.resolve(signal))) {
            check(!Files.exists(directory.resolve("abort")) && System.nanoTime() < stop); Thread.sleep(10);
        }
    }
    private static ObjectNode event(String phase) {
        check(phase.matches("[A-Z][A-Z0-9_]{0,80}"));
        return JSON.createObjectNode().put("phase", phase).put("osPid", ProcessHandle.current().pid());
    }
    private static void emit(Path directory, String name, ObjectNode event) throws Exception {
        check(directory != null && Set.of("ready", "result", "barrier", "contended", "timeout", "deny", "excluded").contains(name));
        Path staged = Files.createTempFile(directory, "event-stage-", ".json", privateFile());
        try {
            Files.writeString(staged, event.toString());
            Files.move(staged, directory.resolve(name + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged); }
    }
    private static void uncheckedEmit(Path output, String name, String phase) {
        try { emit(output, name, event(phase)); } catch (Exception failure) { throw new IllegalStateException("Private process event could not be persisted"); }
    }
    private static String classHash(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            check(input != null); return digest(input.readAllBytes());
        }
    }
    private static String codeSource(Class<?> type) { return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm(); }

    private record Child(Path directory, Process process) { }

    /** Parent owns lifecycle and private configuration; proof output never exports journal or secrets. */
    private static final class Proof implements AutoCloseable {
        final String caseId;
        final Path directory;
        final Path hba;
        final Path lockFile;
        final Path dataFile;
        final Map<String, String> passwords;
        final List<Child> children = new ArrayList<>();
        final ObjectNode manifest;
        boolean stopped;
        boolean complete;
        boolean scopeClosed;

        Proof(String caseId) throws Exception {
            check(caseId.matches("[a-z][a-z0-9-]{0,100}")); this.caseId = caseId;
            directory = Files.createTempDirectory("praxis-provisioning-interlock-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            hba = Files.createFile(directory.resolve("external-hba.conf"), privateFile()).toAbsolutePath();
            lockFile = Files.createFile(directory.resolve("provisioning.lock"), privateFile());
            dataFile = directory.resolve("provisioning.json");
            var credentials = new LinkedHashMap<String, String>();
            for (String role : LOGINS) credentials.put(role, UUID.randomUUID().toString());
            passwords = Map.copyOf(credentials);
            manifest = JSON.createObjectNode().put("caseId", caseId).put("harnessPid", ProcessHandle.current().pid())
                    .put("scope", "COOPERATIVE_LOCAL_PROCESS_CRASH_REFERENCE_ONLY").put("barriersUsed", false);
            manifest.putArray("events");
        }

        Journal journal(JdbcBulkCapacityInstallation.ExpectedBinding expected) { return new Journal(directory, expected); }
        ObjectNode event(String phase) {
            return manifest.withArray("events").addObject().put("phase", phase);
        }

        Child launch(Properties base, String mode, String barrier, long expectedSequence) throws Exception {
            var p = new Properties(); p.putAll(base);
            p.setProperty("mode", mode); p.setProperty("barrier", barrier); p.setProperty("expectedSequence", Long.toString(expectedSequence));
            p.setProperty("journalDirectory", directory.toString()); p.setProperty("hbaFile", hba.toString());
            p.setProperty("processHash", classHash(ProvisioningAgent.class)); p.setProperty("processCodeSource", codeSource(ProvisioningAgent.class));
            p.setProperty("kernelHash", classHash(JdbcBulkDurableExecution.class)); p.setProperty("kernelCodeSource", codeSource(JdbcBulkDurableExecution.class));
            Path output = Files.createDirectory(directory.resolve("agent-" + children.size()),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path config = Files.createFile(output.resolve("private-configuration.properties"), privateFile());
            try (var sink = Files.newOutputStream(config)) { p.store(sink, "Private fixed provisioning test configuration"); }
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            var process = new ProcessBuilder(java, "-cp", classpath, ProvisioningAgent.class.getName(), config.toString(), output.toString())
                    .redirectErrorStream(true).redirectOutput(output.resolve("private-output.log").toFile()).start();
            var child = new Child(output, process); children.add(child);
            event("AGENT_LAUNCHED").put("osPid", process.pid());
            var ready = await(child, "ready", "AGENT_CODE_SOURCE_VERIFIED");
            check(ready.path("processClassSha256").asText().equals(classHash(ProvisioningAgent.class)));
            check(ready.path("kernelClassSha256").asText().equals(classHash(JdbcBulkDurableExecution.class)));
            check(ready.path("codeSourceMatchesParent").asBoolean()); return child;
        }

        JsonNode await(Child child, String name, String phase) throws Exception {
            long stop = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            Path file = child.directory().resolve(name + ".json");
            while (!Files.exists(file)) {
                Path result = child.directory().resolve("result.json");
                if (Files.exists(result)) check(!JSON.readTree(Files.readAllBytes(result)).path("phase").asText().equals("FAILED"));
                check(child.process().isAlive() && System.nanoTime() < stop); Thread.sleep(10);
            }
            JsonNode result = JSON.readTree(Files.readAllBytes(file));
            assertThat(result.path("osPid").asLong()).isEqualTo(child.process().pid());
            assertThat(result.path("phase").asText()).isEqualTo(phase);
            manifest.withArray("events").add(result.deepCopy());
            if (name.equals("barrier") || name.equals("contended")) manifest.put("barriersUsed", true);
            return result;
        }

        void signal(Child child, String name) throws Exception {
            check(Set.of("release", "retry", "abort").contains(name));
            Files.writeString(child.directory().resolve(name), "go");
        }
        void join(Child child) throws Exception {
            check(child.process().waitFor(20, TimeUnit.SECONDS)); assertThat(child.process().exitValue()).isZero();
            event("AGENT_EXIT_CONFIRMED").put("osPid", child.process().pid()).put("exitCode", child.process().exitValue());
        }
        void kill(Child child) throws Exception {
            check(child.process().isAlive()); child.process().destroyForcibly();
            check(child.process().waitFor(3, TimeUnit.SECONDS)); check(child.process().exitValue() != 0);
            event("AGENT_CRASH_EXIT_CONFIRMED").put("osPid", child.process().pid()).put("exitCode", child.process().exitValue());
        }
        void stopChildren() throws Exception {
            if (stopped) return; stopped = true; Throwable failure = null;
            // Every barrier is released/aborted before any join, including assertion-failure paths.
            for (Child child : children) if (child.process().isAlive()) {
                try { signal(child, "abort"); signal(child, "release"); signal(child, "retry"); }
                catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            }
            for (Child child : children) {
                try {
                    if (!child.process().waitFor(3, TimeUnit.SECONDS)) child.process().destroyForcibly();
                    check(child.process().waitFor(3, TimeUnit.SECONDS));
                    event("OWNED_AGENT_CLEANUP").put("osPid", child.process().pid()).put("exitCode", child.process().exitValue());
                } catch (Exception | Error cleanup) {
                    child.process().destroyForcibly(); failure = append(failure, cleanup);
                    try { check(child.process().waitFor(3, TimeUnit.SECONDS)); }
                    catch (Exception | Error join) { failure = append(failure, join); }
                }
            }
            rethrow(failure);
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            try { stopChildren(); } catch (Exception | Error cleanup) { failure = cleanup; }
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            manifest.put("privateConfigurationRemoved", !Files.exists(directory)).put("ownScopeClosed", scopeClosed)
                    .put("subprocesses", children.size()).put("caseOutcome", complete && failure == null ? "ASSERTIONS_COMPLETE" : "INCOMPLETE_OR_FAILED");
            String configured = System.getProperty("praxis.bulk.proof.directory");
            if (configured != null && !configured.isBlank()) {
                try {
                    Path output = Path.of(configured); Files.createDirectories(output);
                    Path destination = output.resolve(caseId + "-agent.json"); check(!Files.exists(destination));
                    Files.writeString(destination, manifest.toPrettyString());
                } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            }
            rethrow(failure);
        }
    }

    private static Throwable append(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary; primary.addSuppressed(secondary); return primary;
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
}
