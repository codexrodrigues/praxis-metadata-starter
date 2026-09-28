package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.governance;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.CONTEXT;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.proposal;

/**
 * Opt-in characterization of the V13 validation and bounded RS2 reader under a small JVM heap.
 *
 * <p>Run in a dedicated fork with {@code -Xmx256m -Dpraxis.bulk.capacity=true}. Measurements are
 * descriptive evidence for this embedded PostgreSQL fixture. They are not an SLA, a production
 * heap/RSS bound or a cold-start benchmark.</p>
 */
@EnabledIfSystemProperty(named = "praxis.bulk.capacity", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class BulkReadCapacityPostgresTest {
    private static final int PAGE_SIZE = 200;
    private static final String PROJECTOR_REVISION = "capacity-preview/1";

    private EmbeddedPostgres postgres;
    private DataSource owner;
    private JdbcTemplate sql;
    private TransactionTemplate transactions;
    private JdbcBulkProposalStore store;
    private BulkPreviewPageReader reader;

    @BeforeAll
    void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        DataSource runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        sql = new JdbcTemplate(owner);
        var manager = new DataSourceTransactionManager(runtime);
        transactions = new TransactionTemplate(manager);
        var infrastructure = new BulkExecutionInfrastructure(runtime, manager, CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration());
        store = new JdbcBulkProposalStore(infrastructure);
        reader = new BulkPreviewPageReader(infrastructure);
    }

    @AfterAll
    void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void reset() {
        sql.execute("drop schema if exists praxis_bulk cascade");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(13);
        BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(),
                CONTEXT.operationRef().operationId());
        assertV13Complete();
    }

    @Test
    void readsAValidHeavyTwoHundredItemProjectionInsideTheBoundedPage() {
        Fixture fixture = fixture(PAGE_SIZE, 16, "M".repeat(512));
        persist(fixture);
        SqlFootprint footprint = footprint(fixture.evaluation().proposal().id());

        PhaseMeasurement<BulkPreviewPageReader.Page> measured = measure("rs2-heavy-200", () ->
                page(fixture.evaluation().proposal().id(), -1, PAGE_SIZE, PAGE_SIZE));
        BulkPreviewPageReader.Page page = measured.value();

        assertCompletePage(page, 0, 199, 16, "M".repeat(512));
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextOrdinal()).isEqualTo(199);
        assertValidFootprint(footprint);
        assertThat(footprint.previewDiagnosticsBytes()).isGreaterThan(1_500_000L);
        report("heavy-200", footprint, List.of(measured), null);
    }

    @Test
    void readsFirstLastAndWarmPagesThenValidatesTenThousandItemsOnV13() {
        Fixture fixture = fixture(10_000, 1, "D".repeat(512));
        persist(fixture);
        UUID proposalId = fixture.evaluation().proposal().id();
        SqlFootprint footprint = footprint(proposalId);

        List<PhaseMeasurement<BulkPreviewPageReader.Page>> reads = new ArrayList<>();
        reads.add(measure("rs2-first-200", () -> page(proposalId, -1, 10_000, PAGE_SIZE)));
        reads.add(measure("rs2-last-200", () -> page(proposalId, 9_799, 10_000, PAGE_SIZE)));
        for (int repetition = 1; repetition <= 3; repetition++) {
            int sample = repetition;
            reads.add(measure("rs2-warm-first-200-" + sample,
                    () -> page(proposalId, -1, 10_000, PAGE_SIZE)));
        }

        BulkPreviewPageReader.Page first = reads.getFirst().value();
        assertCompletePage(first, 0, 199, 1, "D".repeat(512));
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextOrdinal()).isEqualTo(199);
        BulkPreviewPageReader.Page last = reads.get(1).value();
        assertCompletePage(last, 9_800, 9_999, 1, "D".repeat(512));
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextOrdinal()).isEqualTo(9_999);
        for (PhaseMeasurement<BulkPreviewPageReader.Page> warm : reads.subList(2, reads.size())) {
            assertCompletePage(warm.value(), 0, 199, 1, "D".repeat(512));
        }

        PhaseMeasurement<Void> validation = measure("migrator-validate-v13", () -> {
            BulkExecutionMigrator.validate(owner, BulkPostgresTestSupport.testRoleConfiguration());
            return null;
        });
        assertV13Complete();
        assertThat(footprint.manifestRows()).isEqualTo(10_000);
        assertThat(footprint.previewRows()).isEqualTo(10_000);
        assertThat(footprint.integrityRows()).isEqualTo(10_000);
        assertValidFootprint(footprint);
        assertThat(footprint.previewDiagnosticsBytes()).isGreaterThan(5_000_000L);
        report("ten-thousand", footprint, reads, validation);
    }

    private Fixture fixture(int targetCount, int diagnosticCount, String publicMessage) {
        List<ResourceCommandMessage> protectedDiagnostics = new ArrayList<>(diagnosticCount);
        List<BulkPreviewProjection.PublicDiagnostic> allowlist = new ArrayList<>(diagnosticCount);
        for (int index = 0; index < diagnosticCount; index++) {
            String code = "CAPACITY_" + index;
            protectedDiagnostics.add(new ResourceCommandMessage(
                    ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                    code, "protected test message", null, Map.of()));
            allowlist.add(new BulkPreviewProjection.PublicDiagnostic(
                    ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                    code, publicMessage));
        }
        BulkTargetEligibility eligibility = BulkTargetEligibility.blocked(protectedDiagnostics);
        List<BulkTarget<String>> selected = new ArrayList<>(targetCount);
        List<BulkTargetEvidence<?>> evidence = new ArrayList<>(targetCount);
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        for (int ordinal = 0; ordinal < targetCount; ordinal++) {
            var target = new BulkTarget<>("target-%05d".formatted(ordinal), "v1");
            selected.add(target);
            evidence.add(new BulkTargetEvidence<>(target, "observed-v1", empty, empty, eligibility));
        }
        var request = new BulkCommandEvaluationRequest<JsonNode, String, JsonNode>(
                BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, selected, null, null), empty);
        var stored = proposal(BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                JsonNode::deepCopy, JsonNode::deepCopy));
        var evaluation = new BulkEvaluationSnapshot(stored, stored.createdAt().plusSeconds(1),
                evidence, governance());
        return new Fixture(evaluation,
                new BulkPreviewProjection(evaluation, PROJECTOR_REVISION, allowlist));
    }

    private void persist(Fixture fixture) {
        transactions.executeWithoutResult(status ->
                store.insertEvaluated(fixture.evaluation(), fixture.projection()));
    }

    private BulkPreviewPageReader.Page page(UUID proposalId, int lastOrdinal,
            int watermarkExclusive, int size) {
        return reader.read(CONTEXT, proposalId, lastOrdinal, watermarkExclusive, size);
    }

    private static void assertCompletePage(BulkPreviewPageReader.Page page,
            int firstOrdinal, int lastOrdinal, int diagnosticsPerItem, String publicMessage) {
        assertThat(page.kind()).isEqualTo(BulkPreviewPageReader.Kind.COMPLETE);
        assertThat(page.items()).hasSize(PAGE_SIZE);
        assertThat(page.items().getFirst().ordinal()).isEqualTo(firstOrdinal);
        assertThat(page.items().getLast().ordinal()).isEqualTo(lastOrdinal);
        assertThat(page.items().getFirst().wireIdentity())
                .isEqualTo("target-%05d".formatted(firstOrdinal));
        assertThat(page.items().getLast().wireIdentity())
                .isEqualTo("target-%05d".formatted(lastOrdinal));
        for (BulkPreviewPageReader.Item item : page.items()) {
            assertThat(item.decision()).isEqualTo(BulkTargetEligibility.Decision.BLOCKED);
            assertThat(item.diagnostics()).hasSize(diagnosticsPerItem).allSatisfy(diagnostic -> {
                assertThat(diagnostic.message()).isEqualTo(publicMessage);
                assertThat(diagnostic.target()).isNull();
                assertThat(diagnostic.metadata()).isEmpty();
            });
        }
    }

    private SqlFootprint footprint(UUID proposalId) {
        return new SqlFootprint(
                scalar("select octet_length(payload) from praxis_bulk.praxis_bulk_proposal where proposal_id=?", proposalId),
                scalar("select octet_length(payload) from praxis_bulk.praxis_bulk_evaluation where proposal_id=?", proposalId),
                scalar("select coalesce(sum(pg_column_size(m)),0) from praxis_bulk.praxis_bulk_target_manifest m where proposal_id=?", proposalId),
                scalar("select coalesce(sum(octet_length(wire_identity)+octet_length(expected_version)),0) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?", proposalId),
                scalar("select pg_column_size(s) from praxis_bulk.praxis_bulk_preview_state s where proposal_id=?", proposalId),
                scalar("select octet_length(public_allowlist) from praxis_bulk.praxis_bulk_preview_state where proposal_id=?", proposalId),
                scalar("select coalesce(sum(pg_column_size(p)),0) from praxis_bulk.praxis_bulk_target_preview p where proposal_id=?", proposalId),
                scalar("select coalesce(sum(octet_length(diagnostics)),0) from praxis_bulk.praxis_bulk_target_preview where proposal_id=?", proposalId),
                scalar("select coalesce(max(octet_length(diagnostics)),0) from praxis_bulk.praxis_bulk_target_preview where proposal_id=?", proposalId),
                scalar("select coalesce(sum(pg_column_size(i)),0) from praxis_bulk.praxis_bulk_preview_item_integrity i where proposal_id=?", proposalId),
                scalar("select count(*) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?", proposalId),
                scalar("select count(*) from praxis_bulk.praxis_bulk_target_preview where proposal_id=?", proposalId),
                scalar("select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?", proposalId));
    }

    private static void assertValidFootprint(SqlFootprint footprint) {
        assertThat(footprint.proposalPayloadBytes()).isBetween(1L, 8L * 1024 * 1024);
        assertThat(footprint.evaluationPayloadBytes()).isBetween(1L, 8L * 1024 * 1024);
        assertThat(footprint.publicAllowlistBytes()).isBetween(2L, 65_536L);
        assertThat(footprint.maxPreviewDiagnosticsBytes()).isBetween(2L, 65_536L);
    }

    private long scalar(String query, UUID proposalId) {
        Number value = sql.queryForObject(query, Number.class, proposalId);
        assertThat(value).isNotNull();
        return value.longValue();
    }

    private void assertV13Complete() {
        assertThat(sql.queryForObject("""
                select version from praxis_bulk.praxis_bulk_schema_history
                 where success order by installed_rank desc limit 1
                """, String.class)).isEqualTo("13");
        for (String marker : List.of("praxis_bulk_manifest_bootstrap",
                "praxis_bulk_preview_bootstrap", "praxis_bulk_preview_integrity_bootstrap",
                "praxis_bulk_preview_reader_bootstrap")) {
            assertThat(sql.queryForObject("select count(*) from praxis_bulk." + marker
                    + " where phase='COMPLETE'", Integer.class)).isEqualTo(1);
        }
    }

    private static <T> PhaseMeasurement<T> measure(String name, Supplier<T> action) {
        HeapSnapshot before = HeapSnapshot.captureAndResetPeaks();
        long started = System.nanoTime();
        T value = action.get();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        HeapSnapshot after = HeapSnapshot.capture();
        return new PhaseMeasurement<>(name, elapsed, before, after, value);
    }

    private static void report(String fixture, SqlFootprint footprint,
            List<? extends PhaseMeasurement<?>> reads, PhaseMeasurement<?> validation) {
        System.out.printf("BULK_READ_CAPACITY fixture=%s maxHeapBytes=%d sql=%s%n",
                fixture, Runtime.getRuntime().maxMemory(), footprint);
        for (PhaseMeasurement<?> read : reads) System.out.println("BULK_READ_CAPACITY " + read.summary());
        if (validation != null) System.out.println("BULK_READ_CAPACITY " + validation.summary());
        System.out.println("BULK_READ_CAPACITY note=heapPeakSumIsConservativeNotSimultaneous;"
                + "embeddedPostgresIsNotProduction;fixtureSetupExcluded;warmIsNotColdStart;"
                + "measurementsAreNotSlaOrRss");
    }

    private record Fixture(BulkEvaluationSnapshot evaluation, BulkPreviewProjection projection) { }

    private record SqlFootprint(long proposalPayloadBytes, long evaluationPayloadBytes,
            long manifestRowBytes, long manifestValueBytes, long previewStateRowBytes,
            long publicAllowlistBytes, long previewRowBytes, long previewDiagnosticsBytes,
            long maxPreviewDiagnosticsBytes, long integrityRowBytes, long manifestRows,
            long previewRows, long integrityRows) { }

    private record PhaseMeasurement<T>(String name, Duration elapsed,
            HeapSnapshot before, HeapSnapshot after, T value) {
        String summary() {
            return "phase=" + name + ";elapsedMillis=" + elapsed.toMillis()
                    + ";heapBeforeBytes=" + before.usedBytes()
                    + ";heapAfterBytes=" + after.usedBytes()
                    + ";heapPeakSumBytes=" + after.peakHeapPoolBytes()
                    + ";gcCountDelta=" + (after.gcCount() - before.gcCount())
                    + ";gcMillisDelta=" + (after.gcMillis() - before.gcMillis());
        }
    }

    private record HeapSnapshot(long usedBytes, long peakHeapPoolBytes, long gcCount, long gcMillis) {
        static HeapSnapshot captureAndResetPeaks() {
            ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == MemoryType.HEAP)
                    .forEach(pool -> pool.resetPeakUsage());
            return capture();
        }

        static HeapSnapshot capture() {
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long peak = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == MemoryType.HEAP)
                    .mapToLong(pool -> Math.max(0L, pool.getPeakUsage().getUsed())).sum();
            long count = ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(gc -> Math.max(0L, gc.getCollectionCount())).sum();
            long millis = ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(gc -> Math.max(0L, gc.getCollectionTime())).sum();
            return new HeapSnapshot(used, peak, count, millis);
        }
    }
}
