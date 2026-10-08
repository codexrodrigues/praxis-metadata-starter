package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.CONTEXT;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.DEPLOYMENT;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.NAMESPACE;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.SYNC_CONTEXT;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.SYNC_OPERATION;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.SYNC_REVISION;

/**
 * Published rc.154 code is isolated from candidate classes/resources. A protected reader denial
 * and an old executor's live catalog denial are distinct gates. These private, serial fixtures
 * do not demonstrate rolling compatibility, fleet shutdown, public ASYNC or distributed workers.
 */
class BulkCapacityOccupancyCutoverPostgresTest {
    private static final Path OLD_JAR = Path.of(System.getProperty("praxis.bulk.historical.rc154.jar",
            Path.of(System.getProperty("basedir", System.getProperty("user.dir")), "target",
                    "historical-bulk-sdk", "metadata-v17-rc154.jar").toString()));
    private static final String OLD_SHA = "e2c98ca0a551eb4911400e7fc76247e5ce230c33ae42d187df7e2ba929698a61";
    private static final String OLD_GAV = "io.github.codexrodrigues:praxis-metadata-starter:8.0.0-rc.154";
    private static final String BULK = "org.praxisplatform.uischema.bulk.";

    @Test
    void syncUnitsRemainExecutableWithoutMarkerAndAfterFence() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("cutover-sync-marker-neutral")) {
            assertThat(fixture.observer.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_capacity_marker", Long.class)).isZero();
            var input = fixture.persistSync();
            var reservation = fixture.kernel.reserve(SYNC_CONTEXT, input.proposal().id(), "sync-before-marker",
                    "sync-owner", SYNC_REVISION, Instant.now().plusSeconds(120));
            assertThat(reservation.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
            assertSyncAllocation(fixture.observer, reservation.executionId(), "ACTIVE");
            assertNeutralStatement(fixture);
            var first = fixture.kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                var physical = fixture.writeDomain(unit);
                assertThat(physical.ordinal()).isZero();
                return BulkUnitMutationResult.confirmed();
            });
            assertThat(first.receiptPresent()).isTrue();
            assertPhysical(fixture.observer, reservation.executionId(), physical(fixture.observer, 0), 1);
            fixture.observe("sync-no-marker-committed", reservation.executionId());

            fixture.activate();
            fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            fixture.installation.fence();
            assertThat(fixture.observer.queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)).isEqualTo("FENCED");
            var capacityBefore = capacityRows(fixture.observer);
            assertNeutralStatement(fixture);
            var second = fixture.kernel.executeUnit(first.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
                fixture.writeDomain(unit);
                return BulkUnitMutationResult.confirmed();
            });
            assertThat(second.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
            assertPhysical(fixture.observer, reservation.executionId(), physical(fixture.observer, 1), 1);
            assertSyncAllocation(fixture.observer, reservation.executionId(), "RELEASED");
            assertThat(fixture.count("item_receipt")).isEqualTo(2);
            assertThat(fixture.count("capacity_occupation")).isZero();
            // Fresh public SYNC storage/reservation also remains available after the capacity fence.
            var fresh = fixture.persistSync();
            var afterFence = fixture.kernel.reserve(SYNC_CONTEXT, fresh.proposal().id(), "sync-after-fence",
                    "sync-owner", SYNC_REVISION, Instant.now().plusSeconds(120));
            assertThat(afterFence.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
            assertSyncAllocation(fixture.observer, afterFence.executionId(), "ACTIVE");
            fixture.kernel.requestCancel(SYNC_CONTEXT, afterFence.executionId());
            assertSyncAllocation(fixture.observer, afterFence.executionId(), "RELEASED");
            assertThat(capacityBefore.equals(capacityRows(fixture.observer)))
                    .as("SYNC never consumes an installed capacity slot or sequence").isTrue();
            assertThat(fixture.observer.queryForObject("""
                    select count(*) from praxis_bulk.praxis_bulk_execution
                     where execution_mode<>'SYNC' or queue_token_id is not null or active_token_id is not null
                    """, Long.class)).isZero();
            fixture.observe("sync-fenced-marker-completed", reservation.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void publishedRc154ProtectedReaderAcceptsSyncAndRejectsAsyncQueuedAndClaimed() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("cutover-old-reader")) {
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var sync = fixture.persistSync();
            var async = fixture.persist();
            var queued = fixture.enqueue(async, "old-reader-key", queue);
            try (var old = new PublishedSdk(fixture::artifactCertificate)) {
                old.withContext(() -> {
                    try (var connection = fixture.runtimeSource.getConnection()) {
                        old.assertSyncReaders(connection, sync);
                        fixture.artifactCertificate("RC154_SYNC_READERS_ACCEPTED", 0, 0);
                        old.assertAsyncCodecStillDecodes(async);
                        var before = fullRows(fixture.observer);
                        old.assertAsyncReadersCorrupt(connection, async);
                        unchanged(fixture.observer, before);
                        fixture.artifactCertificate("RC154_QUEUED_READERS_CORRUPT", 0, 0);
                    }
                    return null;
                });
                var claimed = fixture.kernel.claim(CONTEXT, queued.executionId(), "reader-worker", active).orElseThrow();
                assertThat(claimed.control().epoch()).isEqualTo(2);
                assertThat(claimed.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
                old.withContext(() -> {
                    try (var connection = fixture.runtimeSource.getConnection()) {
                        old.assertSyncReaders(connection, sync);
                        var before = fullRows(fixture.observer);
                        old.assertAsyncReadersCorrupt(connection, async);
                        unchanged(fixture.observer, before);
                        fixture.artifactCertificate("RC154_CLAIMED_READERS_CORRUPT", 0, 0);
                    }
                    return null;
                });
            }
            fixture.observe("old-reader-denied-claimed-async", queued.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void publishedRc154ExecutorDeniesClaimedAsyncBeforeAdmissionOrMutation() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("cutover-old-executor")) {
            try (var old = new PublishedSdk(fixture::artifactCertificate);
                    var historical = new HistoricalStorage(old)) {
                // Same published code and callback bridge must first make real V17 effects/receipts.
                old.withContext(() -> {
                    historical.completeSyncExecution();
                    return null;
                });
                fixture.artifactCertificate("RC154_V17_EXECUTOR_PHYSICAL_POSITIVE", 0, 0);
                fixture.activate();
                var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
                var active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
                var claimed = fixture.kernel.claim(CONTEXT,
                        fixture.enqueue(fixture.persist(), "old-executor-key", queue).executionId(),
                        "old-boundary-worker", active).orElseThrow();
                assertThat(claimed.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
                assertThat(claimed.control().epoch()).isEqualTo(2);
                var before = fullRows(fixture.observer);
                old.withContext(() -> {
                    var source = JdkSource.from(fixture.runtimeSource);
                    var binding = old.binding(source, BulkPostgresTestSupport.testRoleConfiguration());
                    old.assertLiveBodyRejected(source, binding.roles());
                    fixture.artifactCertificate("RC154_LIVE_VERSIONED_BODY_REJECTED", 0, 0);
                    var admissions = new AtomicInteger();
                    var mutations = new AtomicInteger();
                    Object control = old.control(claimed.control());
                    old.expectReason(() -> old.execute(binding, control, 0, admissions, mutations, fixture.observer),
                            "BulkDurableExecutionException", "RECONCILIATION_REQUIRED");
                    assertThat(admissions.get()).isZero();
                    assertThat(mutations.get()).isZero();
                    unchanged(fixture.observer, before);
                    fixture.artifactCertificate("RC154_EXECUTOR_DENIED_BEFORE_CALLBACK", 0, 0);
                    return null;
                });
            }
            fixture.observe("old-executor-catalog-boundary", fixture.observer.queryForObject(
                    "select execution_id from praxis_bulk.praxis_bulk_execution", java.util.UUID.class));
            fixture.assertionsComplete();
        }
    }

    @Test
    void genuineRc154HistoryCutoverPreservesSyncBytesAndSuspendsServingAuthority() throws Exception {
        try (var proof = new ArtifactProof("cutover-historical-sync");
                var old = new PublishedSdk(proof::event);
                var historical = new HistoricalStorage(old)) {
            old.withContext(() -> {
                historical.completeSyncExecution();
                return null;
            });
            // This serial fixture drains its actual callback transactions, not an external fleet.
            assertThat(historical.observer.queryForObject("""
                    select count(*) from pg_stat_activity where datname=current_database()
                      and usename='occupancy_old_runtime' and xact_start is not null
                    """, Long.class)).isZero();
            assertThat(historical.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution "
                    + "where status<>'COMPLETED' or active_attempt_id is not null", Long.class)).isZero();
            assertThat(historical.observer.queryForObject(
                    "select protocol_version from praxis_bulk.praxis_bulk_proposal", Integer.class)).isEqualTo(2);
            assertThat(historical.observer.queryForObject(
                    "select protocol_version from praxis_bulk.praxis_bulk_execution", Integer.class)).isEqualTo(2);
            historical.suspend();
            assertSuspended(historical.observer);
            proof.event("HISTORICAL_CALLBACK_TX_DRAINED_AND_CAS_SUSPENDED", 0, 0);
            var before = historicalRows(historical.observer);
            assertThat(before.get(BulkExecutionMigrator.HISTORY_TABLE)).hasSize(18); // SCHEMA plus V1..V17.
            assertThat(BulkExecutionMigrator.migrate(historical.owner, Map.of(NAMESPACE, DEPLOYMENT),
                    historical.roles)).isEqualTo(2);
            historicalUnchanged(historical.observer, before);
            assertSuspended(historical.observer);
            assertThat(historical.observer.queryForObject("select max(version::integer) from "
                    + "praxis_bulk.praxis_bulk_schema_history where version is not null", Integer.class)).isEqualTo(19);
            assertThat(historical.observer.queryForObject("""
                    select count(*) from praxis_bulk.praxis_bulk_schema_history
                     where version in ('18','19') and success
                    """, Long.class)).isEqualTo(2);
            assertThat(historical.observer.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(historical.observer.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(historical.observer.queryForObject("""
                    select count(*) from praxis_bulk.praxis_bulk_proposal where execution_mode<>'SYNC'
                    """, Long.class)).isZero();
            assertThat(historical.observer.queryForObject("""
                    select count(*) from praxis_bulk.praxis_bulk_execution
                    where execution_mode<>'SYNC' or queue_token_id is not null or active_token_id is not null
                    """, Long.class)).isZero();
            assertSyncAllocation(historical.observer, historical.executionId, "RELEASED");
            assertThat(historical.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_slot",
                    Long.class)).isZero();
            assertThat(historical.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_occupation",
                    Long.class)).isZero();
            BulkExecutionMigrator.validate(historical.owner, historical.roles);
            assertThat(BulkExecutionMigrator.migrate(historical.owner, Map.of(NAMESPACE, DEPLOYMENT), historical.roles)).isZero();
            historicalUnchanged(historical.observer, before);
            old.withContext(() -> {
                try (var connection = historical.runtimeSource.getConnection()) {
                    old.assertSyncReaders(connection, historical.evaluation);
                }
                return null;
            });
            proof.event("V17_TO_V19_SYNC_BYTES_FINGERPRINTS_AND_HISTORY_PRESERVED", 0, 0);
            proof.complete = true;
        }
    }

    private static void assertNeutralStatement(BulkCapacityOccupancyPostgresFixture fixture) {
        var before = fullRows(fixture.observer);
        assertThat(fixture.runtimeSql.update("update praxis_bulk.praxis_bulk_execution "
                + "set updated_at=updated_at where false")).isZero();
        unchanged(fixture.observer, before);
    }

    private static List<String> capacityRows(JdbcTemplate observer) {
        var rows = new ArrayList<String>();
        for (String table : List.of("capacity_marker", "capacity_installation", "capacity_slot", "capacity_occupation"))
            rows.addAll(observer.queryForList("select to_jsonb(t)::text from praxis_bulk.praxis_bulk_"
                    + table + " t order by 1", String.class));
        return rows;
    }

    private static void assertSyncAllocation(JdbcTemplate observer, java.util.UUID execution, String state) {
        var rows = observer.queryForList("select kind,state from praxis_bulk.praxis_bulk_allocation where execution_id=?",
                execution);
        assertThat(rows).containsExactly(Map.of("kind", "EXECUTION_ACTIVE", "state", state));
    }

    private record Physical(int ordinal, int pid, long xid) { }

    private static Physical physical(JdbcTemplate observer, int ordinal) {
        var row = observer.queryForMap("select last_pid,last_xid from occupancy_domain_witness where id=?", ordinal + 1);
        return new Physical(ordinal, ((Number) row.get("last_pid")).intValue(), ((Number) row.get("last_xid")).longValue());
    }

    private static void assertPhysical(JdbcTemplate observer, java.util.UUID execution, Physical physical, long epoch) {
        var row = observer.queryForMap("""
                select pg_backend_pid() as observer_pid,d.writes,d.last_pid,d.last_xid,d.xmin::text::bigint as write_xid,
                       r.owner_epoch,r.outcome,r.xmin::text::bigint as receipt_xid
                from occupancy_domain_witness d join praxis_bulk.praxis_bulk_item_receipt r
                  on r.execution_id=? and r.unit_ordinal=? where d.id=?
                """, execution, physical.ordinal(), physical.ordinal() + 1);
        assertThat(row).containsEntry("writes", 1).containsEntry("last_pid", physical.pid())
                .containsEntry("last_xid", physical.xid()).containsEntry("write_xid", physical.xid() & 0xffffffffL)
                .containsEntry("receipt_xid", physical.xid() & 0xffffffffL).containsEntry("owner_epoch", epoch)
                .containsEntry("outcome", "CONFIRMED");
        assertThat(((Number) row.get("observer_pid")).intValue()).isNotEqualTo(physical.pid());
    }

    /** Full-row, private memory snapshots; no protected values are included in failure output. */
    private static Map<String, List<String>> fullRows(JdbcTemplate observer) {
        return rows(observer, false);
    }

    /** V17 columns/history are compared byte for byte across an intentional V18/V19 extension. */
    private static Map<String, List<String>> historicalRows(JdbcTemplate observer) {
        return rows(observer, true);
    }

    private static Map<String, List<String>> rows(JdbcTemplate observer, boolean historicalProjection) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : observer.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk' and c.relkind='r' order by c.relname
                """, String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalStateException("Unsafe test relation");
            String document = "to_jsonb(t)";
            if (historicalProjection && table.equals("praxis_bulk_proposal"))
                document = "to_jsonb(t)-'execution_mode'";
            if (historicalProjection && table.equals("praxis_bulk_execution"))
                document = "to_jsonb(t)-array['execution_mode','queue_token_id','active_token_id']";
            String prefix = historicalProjection && table.equals(BulkExecutionMigrator.HISTORY_TABLE)
                    ? " where version is null or version::integer<=17" : "";
            result.put(table, observer.queryForList("select (" + document + ")::text from praxis_bulk."
                    + table + " t" + prefix + " order by 1", String.class));
        }
        result.put("domain-witness", observer.queryForList(
                "select to_jsonb(t)::text from occupancy_domain_witness t order by 1", String.class));
        return result;
    }

    private static void unchanged(JdbcTemplate observer, Map<String, List<String>> before) {
        var after = fullRows(observer);
        assertThat(before.keySet().equals(after.keySet())).as("private ledger relation set unchanged").isTrue();
        assertRowsUnchanged(before, after);
    }

    private static void historicalUnchanged(JdbcTemplate observer, Map<String, List<String>> before) {
        var after = historicalRows(observer);
        assertThat(after.keySet()).containsAll(before.keySet());
        assertRowsUnchanged(before, after);
    }

    private static void assertRowsUnchanged(Map<String, List<String>> before, Map<String, List<String>> after) {
        for (var entry : before.entrySet())
            assertThat(entry.getValue().equals(after.get(entry.getKey())))
                    .as("preexisting full row remains unchanged in %s", entry.getKey()).isTrue();
    }

    private static void assertSuspended(JdbcTemplate observer) {
        assertThat(observer.queryForObject("select state from praxis_bulk.praxis_bulk_operation_control "
                + "where namespace_id=? and operation_id=?", String.class, NAMESPACE, SYNC_OPERATION)).isEqualTo("SUSPENDED");
        assertThat(observer.queryForObject("select state from praxis_bulk.praxis_bulk_openapi_publication "
                + "where deployment_id=?", String.class, DEPLOYMENT)).isEqualTo("SUSPENDED");
    }

    @FunctionalInterface private interface Checked<T> { T run() throws Exception; }
    @FunctionalInterface private interface CertificateSink { void event(String phase, int pid, long xid); }

    /** Old Spring owns every operational holder; only JDK interfaces cross the isolated boundary. */
    private static final class PublishedSdk implements AutoCloseable {
        private final Path jar;
        private final URLClassLoader loader;
        private final CertificateSink certificates;

        PublishedSdk(CertificateSink certificates) throws Exception {
            this.certificates = certificates;
            jar = OLD_JAR.toRealPath();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))))
                    .as("genuine Central rc.154 checksum").isEqualTo(OLD_SHA);
            var dependencies = new LinkedHashSet<Path>();
            dependencies.add(jar);
            String classpath = System.getProperty("surefire.test.class.path");
            if (classpath == null || classpath.isBlank()) classpath = System.getProperty("java.class.path");
            for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                Path dependency = Path.of(entry);
                if (!Files.isRegularFile(dependency) || !entry.endsWith(".jar")) continue;
                dependency = dependency.toRealPath();
                try (var archive = new JarFile(dependency.toFile())) {
                    if (archive.getJarEntry("org/praxisplatform/uischema/bulk/BulkExecutionMigrator.class") != null
                            || archive.getJarEntry("org/praxisplatform/uischema/bulk/BulkStoredProposal.class") != null) continue;
                }
                dependencies.add(dependency);
            }
            URL[] urls = new URL[dependencies.size()];
            int index = 0;
            for (Path dependency : dependencies) urls[index++] = dependency.toUri().toURL();
            loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            try {
                assertThat(loader.getURLs()[0]).isEqualTo(jar.toUri().toURL());
                assertThat(loader.getParent()).isSameAs(ClassLoader.getPlatformClassLoader());
                assertThat(loader.getResource("db/praxis-bulk-migrations/V17__bulk_pending_quota_snapshot_fence.sql")).isNotNull();
                assertThat(loader.getResource("db/praxis-bulk-migrations/V18__bulk_capacity_installation.sql")).isNull();
                assertThat(loader.getResource("db/praxis-bulk-migrations/V19__bulk_capacity_occupancy.sql")).isNull();
                for (String type : List.of("BulkExecutionMigrator", "JdbcBulkProposalStore", "JdbcBulkDurableExecution",
                        "BulkStoredProposal", "BulkSnapshotStorageCodec", "BulkEvaluationStorageCodec",
                        "BulkExecutionInfrastructure", "BulkExecutionRoleConfiguration", "BulkFingerprintContext",
                        "BulkUnitAdmissionCallback", "BulkUnitMutationCallback")) type(type);
                certificates.event("RC154_SHA_CODESOURCES_AND_ISOLATION_ATTESTED", 0, 0);
            } catch (Exception | Error failure) { loader.close(); throw failure; }
        }

        Class<?> type(String name) throws Exception {
            Class<?> type = Class.forName(name.startsWith("org.") ? name : BULK + name, true, loader);
            assertSource(type);
            return type;
        }

        void assertSource(Class<?> type) throws Exception {
            assertThat(type.getClassLoader()).isSameAs(loader);
            assertThat(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath())
                    .as("every SDK type comes from published rc.154").isEqualTo(jar);
        }

        <T> T withContext(Checked<T> work) throws Exception {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            try { thread.setContextClassLoader(loader); return work.run(); }
            finally { thread.setContextClassLoader(previous); }
        }

        Object call(Object target, String name, Object... args) throws Exception {
            assertSource(target.getClass());
            return invoke(target.getClass().getMethod(name), target, args);
        }

        Object staticCall(String type, String name, Class<?>[] parameters, Object... args) throws Exception {
            Method method = type(type).getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return invoke(method, null, args);
        }

        Object scope(BulkFingerprintContext context) throws Exception {
            Class<?> referenceType = type("org.praxisplatform.uischema.openapi.CanonicalOperationRef");
            var ref = context.operationRef();
            Object reference = referenceType.getConstructor(String.class, String.class, String.class, String.class)
                    .newInstance(ref.group(), ref.operationId(), ref.path(), ref.method());
            Class<?> atomicity = type("org.praxisplatform.uischema.action.ActionCollectionAtomicity");
            Object perItem = atomicity.getMethod("valueOf", String.class).invoke(null, "PER_ITEM");
            return type("BulkFingerprintContext").getConstructor(String.class, String.class, String.class,
                    referenceType, String.class, atomicity).newInstance(context.namespaceId(), context.subjectId(),
                    context.resourceKey(), reference, context.schemaRevision(), perItem);
        }

        Object read(Connection connection, BulkEvaluationSnapshot input, boolean locate) throws Exception {
            if (locate) return staticCall("JdbcBulkProposalStore", "locateProposal",
                    new Class<?>[]{Connection.class, String.class, String.class, String.class, java.util.UUID.class},
                    connection, input.proposal().snapshot().context().namespaceId(),
                    input.proposal().snapshot().context().resourceKey(),
                    input.proposal().snapshot().context().operationRef().operationId(), input.proposal().id());
            return staticCall("JdbcBulkProposalStore", "readProposal",
                    new Class<?>[]{Connection.class, type("BulkFingerprintContext"), java.util.UUID.class},
                    connection, scope(input.proposal().snapshot().context()), input.proposal().id());
        }

        void assertSyncReaders(Connection connection, BulkEvaluationSnapshot input) throws Exception {
            for (boolean locate : List.of(false, true)) {
                Object proposal = ((Optional<?>) read(connection, input, locate)).orElseThrow();
                assertSource(proposal.getClass());
                Object snapshot = call(proposal, "snapshot");
                assertSource(snapshot.getClass());
                assertThat(call(proposal, "id")).isEqualTo(input.proposal().id());
                assertThat(call(snapshot, "fingerprint")).isEqualTo(input.proposal().snapshot().fingerprint());
                Object intent = call(snapshot, "intent");
                Object mode = invoke(intent.getClass().getMethod("path", String.class), intent, "executionMode");
                assertThat(invoke(mode.getClass().getMethod("asText"), mode)).isEqualTo("SYNC");
            }
        }

        void assertAsyncCodecStillDecodes(BulkEvaluationSnapshot input) throws Exception {
            Object decoded = staticCall("BulkSnapshotStorageCodec", "decode", new Class<?>[]{byte[].class, String.class},
                    BulkSnapshotStorageCodec.encode(input.proposal().snapshot()), input.proposal().snapshot().fingerprint());
            Object intent = call(decoded, "intent");
            Object mode = invoke(intent.getClass().getMethod("path", String.class), intent, "executionMode");
            assertThat(invoke(mode.getClass().getMethod("asText"), mode)).isEqualTo("ASYNC");
        }

        void assertAsyncReadersCorrupt(Connection connection, BulkEvaluationSnapshot input) throws Exception {
            for (boolean locate : List.of(false, true))
                expectReason(() -> read(connection, input, locate), "BulkProposalStorageException", "CORRUPT");
        }

        Object roles(BulkExecutionRoleConfiguration roles) throws Exception {
            return type("BulkExecutionRoleConfiguration").getConstructor(String.class, Set.class, Set.class, Set.class)
                    .newInstance(roles.expectedSchemaOwnerRole(), roles.runtimeGranteeRoles(),
                            roles.retentionExecutorMembers(), roles.controlPlaneGranteeRoles());
        }

        record Binding(DataSource source, Object roles, Object infrastructure, Object manager, Object kernel) { }

        Binding binding(DataSource source, BulkExecutionRoleConfiguration configuration) throws Exception {
            Class<?> managerType = Class.forName("org.springframework.jdbc.datasource.DataSourceTransactionManager", true, loader);
            Object manager = managerType.getConstructor(DataSource.class).newInstance(source);
            Class<?> platformManager = Class.forName("org.springframework.transaction.PlatformTransactionManager", true, loader);
            assertThat(manager.getClass().getClassLoader()).isSameAs(loader);
            Object roles = roles(configuration);
            Object infrastructure = type("BulkExecutionInfrastructure").getConstructor(DataSource.class,
                    platformManager, String.class, String.class, type("BulkExecutionRoleConfiguration"))
                    .newInstance(source, manager, NAMESPACE, DEPLOYMENT, roles);
            Object kernel = type("JdbcBulkDurableExecution").getConstructor(type("BulkExecutionInfrastructure"))
                    .newInstance(infrastructure);
            return new Binding(source, roles, infrastructure, manager, kernel);
        }

        void store(Binding binding, BulkEvaluationSnapshot input) throws Exception {
            Object snapshot = staticCall("BulkSnapshotStorageCodec", "decode", new Class<?>[]{byte[].class, String.class},
                    BulkSnapshotStorageCodec.encode(input.proposal().snapshot()), input.proposal().snapshot().fingerprint());
            Object expectation = type("BulkOperationControlExpectation").getConstructor(long.class, String.class, String.class)
                    .newInstance(input.proposal().controlExpectation().generation(),
                            input.proposal().controlExpectation().descriptorFingerprint(), SYNC_REVISION);
            Object proposal = type("BulkStoredProposal").getConstructor(java.util.UUID.class, Instant.class, Instant.class,
                    type("BulkIntentSnapshot"), type("BulkOperationControlExpectation"))
                    .newInstance(input.proposal().id(), input.proposal().createdAt(), input.proposal().expiresAt(), snapshot, expectation);
            Object evaluation = staticCall("BulkEvaluationStorageCodec", "decode",
                    new Class<?>[]{type("BulkStoredProposal"), byte[].class, String.class}, proposal,
                    BulkEvaluationStorageCodec.encode(input), input.fingerprint());
            Object preview = staticCall("BulkPreviewProjection", "unavailable", new Class<?>[]{type("BulkEvaluationSnapshot")}, evaluation);
            Object store = type("JdbcBulkProposalStore").getConstructor(type("BulkExecutionInfrastructure"))
                    .newInstance(binding.infrastructure());
            Class<?> transactionType = Class.forName("org.springframework.transaction.support.TransactionTemplate", true, loader);
            Class<?> managerType = Class.forName("org.springframework.transaction.PlatformTransactionManager", true, loader);
            Object transaction = transactionType.getConstructor(managerType).newInstance(binding.manager());
            Class<?> callbackType = Class.forName("org.springframework.transaction.support.TransactionCallback", true, loader);
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
                if (!method.getName().equals("doInTransaction")) throw new AssertionError("Unexpected old transaction callback");
                return invoke(store.getClass().getMethod("insertEvaluated", type("BulkEvaluationSnapshot"),
                        type("BulkPreviewProjection")), store, evaluation, preview);
            });
            invoke(transactionType.getMethod("execute", callbackType), transaction, callback);
        }

        Object reserve(Binding binding, BulkEvaluationSnapshot input) throws Exception {
            return invoke(type("JdbcBulkDurableExecution").getMethod("reserve", type("BulkFingerprintContext"),
                    java.util.UUID.class, String.class, String.class, String.class, Instant.class), binding.kernel(),
                    scope(SYNC_CONTEXT), input.proposal().id(), "old-sync-key", "old-sync-owner", SYNC_REVISION,
                    Instant.now().plusSeconds(120));
        }

        Object control(BulkExecutionControl control) throws Exception {
            Constructor<?> constructor = type("BulkExecutionControl").getDeclaredConstructor(java.util.UUID.class, String.class, long.class);
            constructor.setAccessible(true);
            Object copied = constructor.newInstance(control.executionId(), control.ownerId(), control.epoch());
            assertThat(call(copied, "executionId")).isEqualTo(control.executionId());
            assertThat(call(copied, "ownerId")).isEqualTo(control.ownerId());
            assertThat(call(copied, "epoch")).isEqualTo(control.epoch());
            return copied;
        }

        Object execute(Binding binding, Object control, int ordinal, AtomicInteger admissions,
                AtomicInteger mutations, JdbcTemplate observer) throws Exception {
            Class<?> admissionType = type("BulkUnitAdmissionCallback");
            Class<?> mutationType = type("BulkUnitMutationCallback");
            Object admission = Proxy.newProxyInstance(loader, new Class<?>[]{admissionType}, (proxy, method, args) -> {
                if (!method.getName().equals("admit")) throw new AssertionError("Unexpected old admission callback");
                admissions.incrementAndGet();
                return staticCall("BulkUnitAdmission", "admit", new Class<?>[]{});
            });
            Object mutation = Proxy.newProxyInstance(loader, new Class<?>[]{mutationType}, (proxy, method, args) -> {
                if (!method.getName().equals("apply")) throw new AssertionError("Unexpected old mutation callback");
                mutations.incrementAndGet();
                assertSource(args[0].getClass());
                java.util.UUID execution = (java.util.UUID) call(args[0], "executionId");
                int actualOrdinal = (Integer) call(args[0], "ordinal");
                assertThat(actualOrdinal).isEqualTo(ordinal);
                // Nested MANDATORY old infrastructure gives the exact old transactional Connection.
                Class<?> callbackType = Class.forName("org.springframework.jdbc.core.ConnectionCallback", true, loader);
                Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (p, m, values) -> {
                    if (!m.getName().equals("doInConnection")) throw new AssertionError("Unexpected old JDBC callback");
                    Connection connection = (Connection) values[0];
                    assertThat(connection.getAutoCommit()).isFalse();
                    Physical physical;
                    try (var statement = connection.createStatement();
                            var rows = statement.executeQuery("select pg_backend_pid(),txid_current()")) {
                        assertThat(rows.next()).isTrue();
                        physical = new Physical(ordinal, rows.getInt(1), rows.getLong(2));
                        assertThat(rows.next()).isFalse();
                    }
                    try (var statement = connection.prepareStatement("""
                            update occupancy_domain_witness
                            set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current() where id=?
                            """)) {
                        statement.setInt(1, ordinal + 1);
                        assertThat(statement.executeUpdate()).isEqualTo(1);
                    }
                    var visible = observer.queryForMap("""
                            select pg_backend_pid() as observer_pid,writes,
                                (select count(*) from praxis_bulk.praxis_bulk_item_receipt
                                  where execution_id=? and unit_ordinal=?) as receipts
                            from occupancy_domain_witness where id=?
                            """, execution, ordinal, ordinal + 1);
                    assertThat(visible).containsEntry("writes", 0).containsEntry("receipts", 0L);
                    assertThat(((Number) visible.get("observer_pid")).intValue()).isNotEqualTo(physical.pid());
                    certificates.event("RC154_DOMAIN_UNCOMMITTED_RECEIPT_ABSENT", physical.pid(), physical.xid());
                    return physical;
                });
                invoke(binding.infrastructure().getClass().getMethod("withConnection", callbackType),
                        binding.infrastructure(), callback);
                return staticCall("BulkUnitMutationResult", "confirmed", new Class<?>[]{});
            });
            return invoke(type("JdbcBulkDurableExecution").getMethod("executeUnit", type("BulkExecutionControl"),
                    int.class, admissionType, mutationType), binding.kernel(), control, ordinal, admission, mutation);
        }

        void assertLiveBodyRejected(DataSource source, Object roles) throws Exception {
            Method validator = type("BulkExecutionMigrator").getDeclaredMethod("validateLiveRuntimeRoleAccess",
                    Connection.class, type("BulkExecutionRoleConfiguration"));
            validator.setAccessible(true);
            Exception denied = null;
            try (var connection = source.getConnection()) {
                try { invoke(validator, null, connection, roles); }
                catch (Exception failure) { denied = failure; }
            }
            assertThat(denied != null).as("old catalog attestation specifically denies the V19 function body").isTrue();
            assertThat(denied.getClass()).isEqualTo(IllegalStateException.class);
            rejectSqlCause(denied);
            String reason = denied.getMessage();
            assertThat(reason).startsWith("governed lifecycle function body differs from ")
                    .contains(" expectation: ");
            assertThat(BulkCapacityOccupancyCatalog.REPLACED.keySet().stream()
                    .anyMatch(name -> reason.endsWith(name + "()") || reason.contains(name + "("))).isTrue();
        }

        void expectReason(Checked<?> action, String exceptionName, String reason) throws Exception {
            Exception denied = null;
            try { action.run(); } catch (Exception failure) { denied = failure; }
            assertThat(denied != null).as("old artifact reaches its specific safe denial").isTrue();
            rejectSqlCause(denied);
            assertThat(denied.getClass()).isEqualTo(type(exceptionName));
            Object actual = call(denied, "reason");
            assertSource(actual.getClass());
            assertThat(actual.toString()).isEqualTo(reason);
        }

        @Override public void close() throws Exception { loader.close(); }
    }

    private static void rejectSqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql)
                throw new AssertionError("SQL failure is not an accepted artifact gate: "
                        + (sql.getSQLState() != null && sql.getSQLState().matches("[0-9A-Z]{5}") ? sql.getSQLState() : "UNAVAILABLE"));
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw new AssertionError("Unexpected reflective invocation failure");
        }
    }

    /** JDBC data source with no Spring resource holders or current transaction manager. */
    private static final class JdkSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;
        JdkSource(String url, String user, String password) { this.url = url; this.user = user; this.password = password; }
        static JdkSource from(DataSource source) {
            if (!(source instanceof DriverManagerDataSource jdbc)) throw new IllegalArgumentException("Fixture JDBC source required");
            return new JdkSource(jdbc.getUrl(), jdbc.getUsername(), jdbc.getPassword());
        }
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, user, password); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger("occupancy.cutover.test"); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            if (type.isInstance(this)) return type.cast(this);
            throw new SQLException("Unsupported fixture wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
    }

    /** Genuine public V17 migration/storage/runtime; serving tuple is explicit trusted conformance setup. */
    private static final class HistoricalStorage implements AutoCloseable {
        private final EmbeddedPostgres postgres;
        private final PublishedSdk old;
        final DataSource owner;
        final DataSource runtimeSource;
        final JdbcTemplate observer;
        final BulkExecutionRoleConfiguration roles;
        final PublishedSdk.Binding binding;
        final BulkEvaluationSnapshot evaluation;
        java.util.UUID executionId;

        HistoricalStorage(PublishedSdk old) throws Exception {
            this.old = old;
            postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
            try {
                owner = postgres.getPostgresDatabase();
                observer = new JdbcTemplate(owner);
                old.withContext(() -> {
                    Object none = old.roles(new BulkExecutionRoleConfiguration("postgres", Set.of(), Set.of(), Set.of()));
                    assertThat(old.staticCall("BulkExecutionMigrator", "migrate",
                            new Class<?>[]{DataSource.class, Map.class, old.type("BulkExecutionRoleConfiguration")},
                            owner, Map.of(NAMESPACE, DEPLOYMENT), none)).isEqualTo(17);
                    return null;
                });
                roles = BulkPostgresTestSupport.grantRuntimeRole(owner, "occupancy_old_runtime");
                runtimeSource = new JdkSource(postgres.getJdbcUrl("occupancy_old_runtime", "postgres"), "occupancy_old_runtime", "");
                binding = old.withContext(() -> old.binding(runtimeSource, roles));
                old.withContext(() -> {
                    assertThat(old.staticCall("BulkExecutionMigrator", "migrate",
                            new Class<?>[]{DataSource.class, Map.class, old.type("BulkExecutionRoleConfiguration")},
                            owner, Map.of(NAMESPACE, DEPLOYMENT), binding.roles())).isEqualTo(0);
                    old.staticCall("BulkExecutionMigrator", "validate",
                            new Class<?>[]{DataSource.class, old.type("BulkExecutionRoleConfiguration")}, owner, binding.roles());
                    return null;
                });
                assertThat(observer.queryForObject("select max(version::integer) from "
                        + "praxis_bulk.praxis_bulk_schema_history where version is not null", Integer.class)).isEqualTo(17);
                observer.execute("create table occupancy_domain_witness(id integer primary key,writes integer not null,last_pid integer,last_xid bigint)");
                observer.update("insert into occupancy_domain_witness(id,writes) values(1,0),(2,0)");
                observer.execute("grant select,update on occupancy_domain_witness to occupancy_old_runtime");
                evaluation = BulkCapacityOccupancyPostgresFixture.syncEvaluation(composeSyncControl());
                old.withContext(() -> { old.store(binding, evaluation); return null; });
                try (var connection = runtimeSource.getConnection()) {
                    old.withContext(() -> { old.assertSyncReaders(connection, evaluation); return null; });
                }
            } catch (Exception | Error failure) { postgres.close(); throw failure; }
        }

        private BulkOperationControlExpectation composeSyncControl() throws Exception {
            String descriptor = BulkCanonicalJson.digest(JsonNodeFactory.instance.objectNode().put("operationId", SYNC_OPERATION)
                    .put("executionMode", "SYNC").put("structuralRevision", SYNC_REVISION)
                    .put("scope", "published-v17-protected-sync-conformance-only"));
            String document = BulkCanonicalJson.digest(JsonNodeFactory.instance.objectNode().put("fixturePublication", SYNC_OPERATION));
            observer.update("""
                    insert into praxis_bulk.praxis_bulk_operation_control
                    (namespace_id,operation_id,state,generation,descriptor_fingerprint,structural_revision,updated_at)
                    values (?,?,'UNCOMPOSED',0,null,null,clock_timestamp())
                    """, NAMESPACE, SYNC_OPERATION);
            try (var connection = owner.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    var current = JdbcBulkOpenApiPublication.lockForRead(connection, NAMESPACE, DEPLOYMENT);
                    var suspended = JdbcBulkOpenApiPublication.transition(connection, NAMESPACE, DEPLOYMENT,
                            current.generation(), JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
                    assertThat(suspended.applied()).isTrue();
                    var published = JdbcBulkOpenApiPublication.transition(connection, NAMESPACE, DEPLOYMENT,
                            suspended.generation(), JdbcBulkOpenApiPublication.Target.PUBLISHED, document);
                    assertThat(published.applied()).isTrue();
                    var control = JdbcBulkOperationControl.transition(connection, NAMESPACE, SYNC_OPERATION, 0,
                            JdbcBulkOperationControl.Target.READY, descriptor, SYNC_REVISION, published.generation(), document);
                    assertThat(control.applied()).isTrue();
                    connection.commit();
                    return new BulkOperationControlExpectation(control.generation(), descriptor, SYNC_REVISION);
                } catch (Exception | Error failure) { connection.rollback(); throw failure; }
            }
        }

        void completeSyncExecution() throws Exception {
            Object reservation = old.reserve(binding, evaluation);
            executionId = (java.util.UUID) old.call(reservation, "executionId");
            Object control = old.call(reservation, "control");
            var admissions = new AtomicInteger();
            var mutations = new AtomicInteger();
            for (int ordinal = 0; ordinal < 2; ordinal++) {
                Object result = old.execute(binding, control, ordinal, admissions, mutations, observer);
                assertThat(old.call(result, "receiptPresent")).isEqualTo(true);
                assertThat(old.call(result, "replayed")).isEqualTo(false);
                assertPhysical(observer, executionId, physical(observer, ordinal), 1);
                Physical physical = physical(observer, ordinal);
                old.certificates.event("RC154_DOMAIN_AND_RECEIPT_SAME_PHYSICAL_COMMIT", physical.pid(), physical.xid());
                control = old.call(result, "control");
            }
            assertThat(admissions.get()).isEqualTo(2);
            assertThat(mutations.get()).isEqualTo(2);
            assertThat(observer.queryForObject("select status from praxis_bulk.praxis_bulk_execution where execution_id=?",
                    String.class, executionId)).isEqualTo("COMPLETED");
            assertSyncAllocation(observer, executionId, "RELEASED");
        }

        void suspend() throws Exception {
            try (var connection = owner.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    var publication = JdbcBulkOpenApiPublication.lockForRead(connection, NAMESPACE, DEPLOYMENT);
                    var control = JdbcBulkOperationControl.lockForAdmission(connection, NAMESPACE, SYNC_OPERATION);
                    assertThat(control.ready()).isTrue();
                    assertThat(JdbcBulkOperationControl.transition(connection, NAMESPACE, SYNC_OPERATION,
                            control.generation(), JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null).applied()).isTrue();
                    assertThat(JdbcBulkOpenApiPublication.transition(connection, NAMESPACE, DEPLOYMENT,
                            publication.generation(), JdbcBulkOpenApiPublication.Target.SUSPENDED, null).applied()).isTrue();
                    connection.commit();
                } catch (Exception | Error failure) { connection.rollback(); throw failure; }
            }
        }

        @Override public void close() throws Exception { postgres.close(); }
    }

    /** Same sanitized manifest envelope as the occupancy fixture, for the private historical database. */
    private static final class ArtifactProof implements AutoCloseable {
        final String caseId;
        final List<com.fasterxml.jackson.databind.JsonNode> certificates = new ArrayList<>();
        boolean complete;
        ArtifactProof(String caseId) { this.caseId = caseId; }
        void event(String phase, int pid, long xid) {
            if (!phase.matches("[A-Z][A-Z0-9_]{0,80}") || pid < 0 || xid < 0) throw new IllegalArgumentException("Invalid proof phase");
            certificates.add(JsonNodeFactory.instance.objectNode().put("phase", phase).put("gav", OLD_GAV)
                    .put("sha256", OLD_SHA).put("backendPid", pid).put("transactionId", xid));
        }
        @Override public void close() throws Exception {
            String configured = System.getProperty("praxis.bulk.proof.directory");
            Path directory = configured == null ? Files.createTempDirectory("praxis-occupancy-cutover-proof-") : Path.of(configured);
            Files.createDirectories(directory);
            var manifest = JsonNodeFactory.instance.objectNode().put("caseId", caseId).put("logicalDatabase", "historical_local")
                    .put("harnessPid", ProcessHandle.current().pid()).put("subprocesses", 0).put("barriersUsed", false)
                    .put("processExit", "NOT_APPLICABLE_IN_PROCESS_JUNIT")
                    .put("caseOutcome", complete ? "ASSERTIONS_COMPLETE" : "INCOMPLETE_OR_FAILED");
            var events = manifest.putArray("artifactCertificates");
            certificates.forEach(event -> events.add(event.deepCopy()));
            Path file = directory.resolve(caseId + ".json");
            Files.writeString(file, manifest.toPrettyString(), StandardCharsets.UTF_8);
            if (configured == null) System.out.println("Occupancy proof manifest: " + file.toAbsolutePath());
        }
    }
}
