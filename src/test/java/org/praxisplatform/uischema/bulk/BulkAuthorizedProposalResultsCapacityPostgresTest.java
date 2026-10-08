package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in composition proof for the published authorized RS2 reader, not an HTTP or execution proof.
 * Run this class alone with {@code -Xmx256m -Dpraxis.bulk.capacity=true}. The native read deadline
 * remains three seconds; fixture setup and assertions are outside that deadline. The reader still
 * materializes the protected evaluation and full target set: a page bound is not an O(page) heap
 * claim. Heap/GC/timing observations include the harness and are not an RSS bound or corporate SLO.
 * The grant adapter below proves this fixture's context/subject/operation/target scope, not arbitrary
 * corporate field/reference authorization. No protected documents, tokens or keys are reported.
 */
@EnabledIfSystemProperty(named = "praxis.bulk.capacity", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class BulkAuthorizedProposalResultsCapacityPostgresTest {
    private static final int TARGETS = 10_000;
    private static final int PAGE_SIZE = 200;
    private static final Duration READ_BUDGET = Duration.ofSeconds(3);
    private static final Duration CURSOR_TTL = Duration.ofMinutes(5);
    private static final String READER = "b5c-current-reader";
    private static final String PUBLIC_MESSAGE = "D".repeat(512);
    private static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext(
            "b5c-authorized-capacity", "b5c-creator", "b5c-targets",
            new CanonicalOperationRef("b5c", "b5cConfirm", "/b5c-targets/bulk/confirm", "POST"),
            "b5c-schema/1", ActionCollectionAtomicity.PER_ITEM);

    private EmbeddedPostgres postgres;
    private DataSource owner;
    private DataSource runtime;
    private JdbcTemplate sql;
    private ObservedTransactionManager manager;
    private TransactionTemplate transactions;
    private BulkExecutionInfrastructure infrastructure;
    private JdbcBulkProposalStore store;
    private GrantAuthorization authorization;
    private BulkAuthorizedProposalResultsReader reader;
    private BulkReadCursorCodec cursorInspector;

    @BeforeAll
    void start() throws Exception {
        assertThat(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax())
                .as("Dedicated capacity fork must use at most -Xmx256m")
                .isBetween(1L, 256L * 1024 * 1024);
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        sql = new JdbcTemplate(owner);
        manager = new ObservedTransactionManager(runtime);
        transactions = new TransactionTemplate(manager);
        infrastructure = new BulkExecutionInfrastructure(runtime, manager, CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration());
        store = new JdbcBulkProposalStore(infrastructure);
    }

    @AfterAll
    void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void reset() {
        sql.execute("drop schema if exists praxis_bulk cascade");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(20);
        // Official test-only structural publication; this does not publish an HTTP capability.
        BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
        sql.execute("drop table if exists public.b5c_target_grant");
        sql.execute("drop table if exists public.b5c_global_grant");
        sql.execute("""
                create table public.b5c_global_grant (
                    namespace_id text not null, resource_key text not null, operation_id text not null,
                    subject_id text not null, allowed boolean not null, revision bigint not null,
                    primary key(namespace_id, resource_key, operation_id, subject_id))
                """);
        sql.execute("""
                create table public.b5c_target_grant (
                    namespace_id text not null, resource_key text not null, operation_id text not null,
                    subject_id text not null, wire_identity text not null,
                    allowed boolean not null, revision bigint not null,
                    primary key(namespace_id, resource_key, operation_id, subject_id, wire_identity))
                """);
        sql.execute("revoke all on public.b5c_global_grant, public.b5c_target_grant from public");
        sql.execute("grant select on public.b5c_global_grant, public.b5c_target_grant to bulk_runtime_test");
        for (String table : List.of("b5c_global_grant", "b5c_target_grant")) {
            for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
                assertThat(sql.queryForObject("select has_table_privilege('bulk_runtime_test', ?, ?)",
                        Boolean.class, "public." + table, privilege)).isFalse();
            }
        }
        sql.update("insert into public.b5c_global_grant values (?, ?, ?, ?, true, 1)",
                CONTEXT.namespaceId(), CONTEXT.resourceKey(), CONTEXT.operationRef().operationId(), READER);
        sql.update("""
                insert into public.b5c_target_grant
                select ?, ?, ?, ?, 'target-' || lpad(n::text, 5, '0'), true, 1
                from generate_series(0, 9999) n
                """, CONTEXT.namespaceId(), CONTEXT.resourceKey(), CONTEXT.operationRef().operationId(), READER);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        var configuration = new BulkReadCursorConfiguration("b5c-test",
                Map.of("b5c-test", new SecretKeySpec(key, "AES")), CURSOR_TTL);
        authorization = new GrantAuthorization();
        // Use the public real-clock constructor, not a clock that can renew or pause the cursor.
        reader = new BulkAuthorizedProposalResultsReader(infrastructure, CONTEXT.resourceKey(),
                authorization, configuration);
        cursorInspector = new BulkReadCursorCodec(configuration.keySet(), new SecureRandom(), Clock.systemUTC());
    }

    @Test
    void chainsFiftyAuthorizedPagesAndChecks199200201BeforeOpeningAnInvalidRead() throws Exception {
        UUID proposalId = persistTenThousandTargetProjection();
        Map<String, String> before = durableState();
        long proposalBytes = scalar("select octet_length(payload) from praxis_bulk.praxis_bulk_proposal where proposal_id=?", proposalId);
        long evaluationBytes = scalar("select octet_length(payload) from praxis_bulk.praxis_bulk_evaluation where proposal_id=?", proposalId);
        long diagnosticsBytes = scalar("select sum(octet_length(diagnostics)) from praxis_bulk.praxis_bulk_target_preview where proposal_id=?", proposalId);
        assertThat(proposalBytes).isBetween(1L, 8L * 1024 * 1024);
        assertThat(evaluationBytes).isBetween(1L, 8L * 1024 * 1024);
        assertThat(diagnosticsBytes).isGreaterThan(5_000_000L);
        assertThat(scalar("select count(*) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?", proposalId)).isEqualTo(TARGETS);
        assertThat(scalar("select count(*) from praxis_bulk.praxis_bulk_target_preview where proposal_id=?", proposalId)).isEqualTo(TARGETS);

        for (int size : List.of(199, 200)) {
            assertPage(reader.readProposalResults(READER, proposalId, size), 0, size);
        }
        int globalBefore = authorization.globalCalls;
        int scopedBefore = authorization.scopeCalls;
        int beginsBefore = manager.begins;
        assertThatThrownBy(() -> reader.readProposalResults(READER, proposalId, 201))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(authorization.globalCalls).isEqualTo(globalBefore);
        assertThat(authorization.scopeCalls).isEqualTo(scopedBefore);
        assertThat(manager.begins).isEqualTo(beginsBefore);

        // Warm the entire composition, including full-set grants, rather than the page SQL alone.
        for (int repetition = 0; repetition < 3; repetition++) {
            assertPage(reader.readProposalResults(READER, proposalId, PAGE_SIZE), 0, PAGE_SIZE);
        }
        HeapObservation heapBefore = HeapObservation.captureAndResetPeaks();
        long maxReadNanos = 0;
        long totalReadNanos = 0;
        long maxPageBytes = 0;
        int measuredStart = authorization.scopeCalls;
        // This retained harness oracle is only 10,000 bits, not a second set of 10,000 identities.
        BitSet seen = new BitSet(TARGETS);
        String next = null;
        Instant issuedAt = null;
        Instant expiresAt = null;
        for (int pageIndex = 0; pageIndex < 50; pageIndex++) {
            long started = System.nanoTime();
            var observation = reader.readProposalResults(READER, proposalId, PAGE_SIZE, next);
            long elapsed = System.nanoTime() - started;
            maxReadNanos = Math.max(maxReadNanos, elapsed);
            totalReadNanos += elapsed;
            assertPage(observation, pageIndex * PAGE_SIZE, PAGE_SIZE);
            for (int index = 0; index < observation.page().content().size(); index++) {
                int ordinal = pageIndex * PAGE_SIZE + index;
                assertThat(seen.get(ordinal)).isFalse();
                seen.set(ordinal);
            }
            // Public JSON size is descriptive harness work after the SDK returns, not an HTTP gate.
            maxPageBytes = Math.max(maxPageBytes,
                    new ObjectMapper().writeValueAsBytes(observation.page()).length);
            next = observation.page().next();
            if (pageIndex == 49) {
                assertThat(next).isNull();
            } else {
                assertThat(next != null && !next.isBlank()).isTrue();
                var claims = cursorInspector.decode(next, BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS).claims();
                assertThat(claims.lastOrdinalExclusive()).isEqualTo((pageIndex + 1) * PAGE_SIZE - 1);
                assertThat(claims.watermarkExclusive()).isEqualTo(TARGETS);
                assertThat(claims.pageSize()).isEqualTo(PAGE_SIZE);
                Duration lifetime = Duration.between(claims.issuedAt(), claims.expiresAt());
                assertThat(lifetime.isPositive()).isTrue();
                assertThat(lifetime).isLessThanOrEqualTo(Duration.ofMinutes(15));
                assertThat(lifetime).isEqualTo(CURSOR_TTL);
                if (issuedAt == null) {
                    issuedAt = claims.issuedAt();
                    expiresAt = claims.expiresAt();
                } else {
                    assertThat(claims.issuedAt()).isEqualTo(issuedAt);
                    assertThat(claims.expiresAt()).isEqualTo(expiresAt);
                }
            }
        }
        HeapObservation heapAfter = HeapObservation.capture();
        assertThat(seen.cardinality()).isEqualTo(TARGETS);
        assertThat(authorization.scopeCalls - measuredStart).isEqualTo(50);
        assertThat(authorization.checkedTargets).hasSize(55).allMatch(count -> count == TARGETS);
        assertThat(authorization.checkedGrantRows).hasSize(55).allMatch(count -> count == TARGETS);
        assertThat(authorization.globalCalls).isEqualTo(55);
        assertThat(authorization.allowedGlobalCalls).isEqualTo(55);
        assertNoExecutionEffects();
        assertUnchanged(before);
        System.out.printf(java.util.Locale.ROOT,
                "b5c-authorized-capacity targets=%d pages=50 pageSize=%d fullSetChecks=%d "
                + "proposalBytes=%d evaluationBytes=%d diagnosticsBytes=%d maxPublicPageBytes=%d "
                + "totalReadMs=%.3f maxReadMs=%.3f budgetMs=%d "
                + "heapBefore=%d heapAfter=%d heapPoolPeakSum=%d heapMax=%d "
                + "gcCountDelta=%d gcTimeMsDelta=%d harnessSeenBits=%d%n",
                TARGETS, PAGE_SIZE, authorization.scopeCalls, proposalBytes, evaluationBytes,
                diagnosticsBytes, maxPageBytes, totalReadNanos / 1_000_000d, maxReadNanos / 1_000_000d,
                READ_BUDGET.toMillis(), heapBefore.used(), heapAfter.used(), heapAfter.peakSum(),
                heapAfter.max(), heapAfter.gcCount() - heapBefore.gcCount(),
                heapAfter.gcMillis() - heapBefore.gcMillis(), seen.size());
    }

    @Test
    void revokingAnOffPageTargetDeniesInitialAndExistingContinuationWithoutOutputOrEffects() {
        UUID proposalId = persistTenThousandTargetProjection();
        var first = reader.readProposalResults(READER, proposalId, PAGE_SIZE);
        assertPage(first, 0, PAGE_SIZE);
        String continuation = first.page().next();
        assertThat(continuation != null && !continuation.isBlank()).isTrue();
        Map<String, String> before = durableState();
        // Owner changes a real grant for ordinal 9,999, outside both pages 0 and 1. The independent
        // global grant stays ALLOWED at its original revision; no JWT or provider flag substitutes it.
        assertThat(sql.update("""
                update public.b5c_target_grant set allowed=false, revision=revision+1
                where namespace_id=? and resource_key=? and operation_id=? and subject_id=?
                    and wire_identity=?
                """, CONTEXT.namespaceId(), CONTEXT.resourceKey(), CONTEXT.operationRef().operationId(),
                READER, identity(TARGETS - 1))).isEqualTo(1);
        assertThat(sql.queryForObject("select allowed and revision=1 from public.b5c_global_grant",
                Boolean.class)).isTrue();
        assertThat(sql.queryForObject("select count(*) from public.b5c_target_grant where not allowed",
                Integer.class)).isEqualTo(1);
        for (String token : new String[] {null, continuation}) {
            var denied = reader.readProposalResults(READER, proposalId, PAGE_SIZE, token);
            assertThat(denied.state()).isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);
            assertThat(denied.page()).isNull(); // No content, partial page or continuation can escape.
        }
        assertThat(authorization.globalCalls).isEqualTo(3);
        assertThat(authorization.allowedGlobalCalls).isEqualTo(3);
        assertThat(authorization.scopeCalls).isEqualTo(3);
        assertThat(authorization.checkedTargets).containsExactly(TARGETS, TARGETS, TARGETS);
        assertThat(authorization.checkedGrantRows).containsExactly(TARGETS, TARGETS, TARGETS);
        assertThat(authorization.deniedTargetCounts).containsExactly(0, 1, 1);
        assertNoExecutionEffects();
        assertUnchanged(before);
        System.out.printf("b5c-off-page-revocation fullSetChecks=%d checkedTargetsPerRead=%d "
                + "globalAllowed=%d deniedReads=2 outputPages=0 durableStateUnchanged=true%n",
                authorization.scopeCalls, TARGETS, authorization.allowedGlobalCalls);
    }

    private UUID persistTenThousandTargetProjection() {
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        var protectedDiagnostic = new ResourceCommandMessage(ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                "B5C_CAPACITY", "protected test diagnostic", null, Map.of());
        var eligibility = BulkTargetEligibility.blocked(List.of(protectedDiagnostic));
        List<BulkTarget<String>> selected = new ArrayList<>(TARGETS);
        List<BulkTargetEvidence<?>> evidence = new ArrayList<>(TARGETS);
        for (int ordinal = 0; ordinal < TARGETS; ordinal++) {
            var target = new BulkTarget<>(identity(ordinal), "v1");
            selected.add(target);
            evidence.add(new BulkTargetEvidence<>(target, "observed-v1", empty, empty, eligibility));
        }
        var request = new BulkCommandEvaluationRequest<JsonNode, String, JsonNode>(BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, selected, null, null), empty);
        var intent = BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                JsonNode::deepCopy, JsonNode::deepCopy);
        Instant created = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plus(Duration.ofHours(1)),
                intent, new BulkOperationControlExpectation(1, "sha256:" + "0".repeat(64), "structural-r1"));
        // Evaluation provenance is not the current grant source. Current authorization comes only
        // from owner-seeded tables queried inside the reader's operational snapshot below.
        var governance = new BulkEvaluationGovernance("b5c-evaluator/1", "fixture-capture/1", List.of(
                new BulkPolicyObservation("b5c-tenant", "test", "approval_policy", "resource-action-approval",
                        "b5c:read", "NEVER_APPLIED", "fixture-policy/1", created.plusMillis(100))));
        var evaluation = new BulkEvaluationSnapshot(proposal, created.plusSeconds(1), evidence, governance);
        var projection = new BulkPreviewProjection(evaluation, "b5c-authorized-capacity-preview/1", List.of(
                new BulkPreviewProjection.PublicDiagnostic(ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                        "B5C_CAPACITY", PUBLIC_MESSAGE)));
        transactions.executeWithoutResult(status -> store.insertEvaluated(evaluation, projection));
        return proposal.id();
    }

    private static String identity(int ordinal) { return "target-%05d".formatted(ordinal); }

    private static void assertPage(BulkAuthorizedProposalResultsReader.Observation observation,
            int firstOrdinal, int size) {
        assertThat(observation.state()).isEqualTo(BulkAuthorizedProposalResultsReader.State.COMPLETE);
        assertThat(observation.page()).isNotNull();
        assertThat(observation.page().content()).hasSize(size);
        assertThat(observation.page().size()).isEqualTo(size);
        assertThat(observation.page().prev()).isNull();
        for (int index = 0; index < size; index++) {
            var item = observation.page().content().get(index);
            assertThat(item.id()).isEqualTo(identity(firstOrdinal + index));
            assertThat(item.decision()).isEqualTo(BulkProposalItemResult.Decision.BLOCKED);
            assertThat(item.diagnostics()).hasSize(1);
            assertThat(item.diagnostics().getFirst().message()).isEqualTo(PUBLIC_MESSAGE);
            assertThat(item.diagnostics().getFirst().target()).isNull();
            assertThat(item.diagnostics().getFirst().metadata()).isEmpty();
        }
    }

    private long scalar(String query, UUID proposalId) {
        return sql.queryForObject(query, Long.class, proposalId);
    }

    private Map<String, String> durableState() {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> tables = sql.queryForList("""
                select tablename from pg_tables where schemaname='praxis_bulk' order by tablename
                """, String.class);
        assertThat(tables).isNotEmpty();
        for (String table : tables) {
            assertThat(table.matches("[a-z_]+")).isTrue();
            // Compact equality oracle over every protected durable row, kept private in memory.
            // MD5 here is not an authorization/security fingerprint; the grant proof uses SHA-256.
            result.put(table, sql.queryForObject("select count(*)::text || ':' || "
                    + "md5(coalesce(string_agg(h, '' order by h), '')) from "
                    + "(select md5(to_jsonb(t)::text) h from praxis_bulk." + table + " t) rows", String.class));
        }
        return result;
    }

    private void assertUnchanged(Map<String, String> before) {
        // Do not print protected row signatures on assertion failure.
        assertThat(before.equals(durableState())).as("All protected durable tables unchanged by reads").isTrue();
    }

    private void assertNoExecutionEffects() {
        for (String table : List.of("praxis_bulk_execution", "praxis_bulk_admission",
                "praxis_bulk_item_receipt", "praxis_bulk_atomic_receipt", "praxis_bulk_atomic_item_result",
                "praxis_bulk_atomic_effect_ref", "praxis_bulk_atomic_rejection")) {
            assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table, Long.class))
                    .as("Read-only fixture must not create execution evidence").isZero();
        }
    }

    /** Observer only: the native official datasource, physical connections and TX semantics remain intact. */
    private static final class ObservedTransactionManager extends DataSourceTransactionManager {
        int begins;
        ObservedTransactionManager(DataSource source) { super(source); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins++;
            super.doBegin(transaction, definition);
        }
    }

    /** A real test-owned grant adapter, deliberately scoped to this fixture's explicit target IDs. */
    private final class GrantAuthorization implements BulkReadAuthorizationProvider {
        int globalCalls;
        int allowedGlobalCalls;
        int scopeCalls;
        final List<Integer> checkedTargets = new ArrayList<>();
        final List<Integer> checkedGrantRows = new ArrayList<>();
        final List<Integer> deniedTargetCounts = new ArrayList<>();
        private NativeSnapshot globalSnapshot;
        private long globalRevision;
        private Duration globalRemaining;

        @Override public String confirmationOperationId() { return CONTEXT.operationRef().operationId(); }
        @Override public BulkExecutionInfrastructure executionInfrastructure() { return infrastructure; }

        @Override public GlobalDecision preAuthorize(Context context, Duration remaining) {
            globalCalls++;
            assertContext(context);
            Budget budget = new Budget(remaining);
            globalRemaining = remaining;
            Connection connection = boundConnection();
            try {
                globalSnapshot = snapshot(connection, budget);
                try (PreparedStatement statement = connection.prepareStatement("""
                        select allowed, revision from public.b5c_global_grant
                        where namespace_id=? and resource_key=? and operation_id=? and subject_id=?
                        """)) {
                    bindContext(statement, 1, context);
                    constrain(statement, budget);
                    try (var rows = statement.executeQuery()) {
                        budget.requireRemaining();
                        if (!rows.next()) return GlobalDecision.DENIED;
                        boolean allowed = rows.getBoolean(1);
                        globalRevision = rows.getLong(2);
                        assertThat(rows.next()).isFalse();
                        budget.requireRemaining();
                        if (allowed) allowedGlobalCalls++;
                        return allowed ? GlobalDecision.ALLOWED : GlobalDecision.DENIED;
                    }
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Fixture global grant query failed", failure);
            } finally {
                DataSourceUtils.releaseConnection(connection, runtime);
            }
        }

        @Override public ScopeDecision authorize(Context context, String creator, List<Target> targets,
                Duration remaining) {
            scopeCalls++;
            assertContext(context);
            assertThat(creator).isEqualTo(CONTEXT.subjectId());
            assertThat(targets).hasSize(TARGETS);
            assertThat(remaining).isLessThanOrEqualTo(globalRemaining);
            Budget budget = new Budget(remaining);
            checkedTargets.add(targets.size());
            Connection connection = boundConnection();
            java.sql.Array identities = null;
            try {
                NativeSnapshot scopedSnapshot = snapshot(connection, budget);
                assertThat(scopedSnapshot.connection() == globalSnapshot.connection()).isTrue();
                assertThat(scopedSnapshot.pid()).isEqualTo(globalSnapshot.pid());
                assertThat(scopedSnapshot.snapshot()).isEqualTo(globalSnapshot.snapshot());
                String[] wireIds = new String[TARGETS];
                MessageDigest fingerprint = MessageDigest.getInstance("SHA-256");
                for (String coordinate : List.of(context.namespaceId(), context.resourceKey(), context.operationId(),
                        context.requesterSubjectId(), creator)) frame(fingerprint, coordinate);
                fingerprint.update(ByteBuffer.allocate(Long.BYTES).putLong(globalRevision).array());
                for (int index = 0; index < targets.size(); index++) {
                    budget.requireRemaining();
                    assertThat(targets.get(index).ordinal()).isEqualTo(index);
                    assertThat(targets.get(index).wireIdentity()).isEqualTo(identity(index));
                    wireIds[index] = (String) targets.get(index).wireIdentity();
                }
                identities = connection.createArrayOf("text", wireIds);
                int checked = 0;
                int denied = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        select requested.ordinal, requested.wire_identity, g.allowed, g.revision
                        from unnest(?::text[]) with ordinality requested(wire_identity, ordinal)
                        left join public.b5c_target_grant g on g.namespace_id=? and g.resource_key=?
                            and g.operation_id=? and g.subject_id=? and g.wire_identity=requested.wire_identity
                        order by requested.ordinal
                        """)) {
                    statement.setArray(1, identities);
                    bindContext(statement, 2, context);
                    statement.setFetchSize(PAGE_SIZE);
                    constrain(statement, budget);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            budget.requireRemaining();
                            assertThat(rows.getInt(1)).isEqualTo(checked + 1);
                            assertThat(rows.getString(2)).isEqualTo(wireIds[checked]);
                            boolean allowed = rows.getBoolean(3);
                            boolean missing = rows.wasNull();
                            long revision = rows.getLong(4);
                            if (missing || !allowed) denied++;
                            fingerprint.update(ByteBuffer.allocate(Integer.BYTES).putInt(checked).array());
                            frame(fingerprint, wireIds[checked]);
                            fingerprint.update(ByteBuffer.allocate(Long.BYTES).putLong(revision).array());
                            checked++;
                        }
                    }
                }
                budget.requireRemaining();
                assertThat(checked).isEqualTo(TARGETS);
                checkedGrantRows.add(checked);
                deniedTargetCounts.add(denied);
                return denied == 0 ? ScopeDecision.authorized(fingerprint.digest()) : ScopeDecision.denied();
            } catch (SQLException | java.security.NoSuchAlgorithmException failure) {
                throw new IllegalStateException("Fixture scoped grant query failed", failure);
            } finally {
                try {
                    if (identities != null) identities.free();
                } catch (SQLException failure) {
                    throw new IllegalStateException("Fixture grant array cleanup failed", failure);
                } finally {
                    DataSourceUtils.releaseConnection(connection, runtime);
                }
            }
        }

        private void assertContext(Context context) {
            assertThat(context.namespaceId()).isEqualTo(CONTEXT.namespaceId());
            assertThat(context.resourceKey()).isEqualTo(CONTEXT.resourceKey());
            assertThat(context.operationId()).isEqualTo(CONTEXT.operationRef().operationId());
            assertThat(context.requesterSubjectId()).isEqualTo(READER);
        }

        private Connection boundConnection() {
            // Verify binding BEFORE DataSourceUtils could obtain a separate connection.
            Object resource = TransactionSynchronizationManager.getResource(runtime);
            assertThat(resource).isInstanceOf(ConnectionHolder.class);
            Connection expected = ((ConnectionHolder) resource).getConnection();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            Connection actual = DataSourceUtils.getConnection(runtime);
            assertThat(DataSourceUtils.isConnectionTransactional(actual, runtime)).isTrue();
            assertThat(DataSourceUtils.getTargetConnection(actual) == DataSourceUtils.getTargetConnection(expected)).isTrue();
            return actual;
        }

        private NativeSnapshot snapshot(Connection connection, Budget budget) throws SQLException {
            assertThat(connection.getAutoCommit()).isFalse();
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(connection.isReadOnly()).isTrue();
            try (PreparedStatement statement = connection.prepareStatement("""
                    select pg_backend_pid(), txid_current_snapshot()::text,
                        current_setting('transaction_isolation'), current_setting('transaction_read_only')
                    """)) {
                constrain(statement, budget);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    budget.requireRemaining();
                    assertThat(rows.getString(3)).isEqualTo("repeatable read");
                    assertThat(rows.getString(4)).isEqualTo("on");
                    var snapshot = new NativeSnapshot(DataSourceUtils.getTargetConnection(connection),
                            rows.getInt(1), rows.getString(2));
                    assertThat(rows.next()).isFalse();
                    return snapshot;
                }
            }
        }

        private void constrain(PreparedStatement statement, Budget budget) throws SQLException {
            DataSourceUtils.applyTransactionTimeout(statement, runtime);
            int wholeRemainingSeconds = (int) TimeUnit.NANOSECONDS.toSeconds(budget.remainingNanos());
            assertThat(wholeRemainingSeconds).isGreaterThanOrEqualTo(1);
            int existing = statement.getQueryTimeout();
            statement.setQueryTimeout(existing > 0 ? Math.min(existing, wholeRemainingSeconds) : wholeRemainingSeconds);
        }

        private void bindContext(PreparedStatement statement, int start, Context context) throws SQLException {
            statement.setString(start, context.namespaceId());
            statement.setString(start + 1, context.resourceKey());
            statement.setString(start + 2, context.operationId());
            statement.setString(start + 3, context.requesterSubjectId());
        }
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private record NativeSnapshot(Connection connection, int pid, String snapshot) { }

    /** A provider-local monotonically decreasing slice supplied by the kernel, never a new 3s budget. */
    private static final class Budget {
        private final long deadline;
        Budget(Duration remaining) {
            assertThat(remaining.isPositive()).isTrue();
            assertThat(remaining).isLessThanOrEqualTo(READ_BUDGET);
            deadline = System.nanoTime() + remaining.toNanos();
        }
        long remainingNanos() {
            long left = deadline - System.nanoTime();
            assertThat(left).as("Provider must stay inside its supplied remaining read budget").isPositive();
            return left;
        }
        void requireRemaining() { remainingNanos(); }
    }

    private record HeapObservation(long used, long peakSum, long max, long gcCount, long gcMillis) {
        static HeapObservation captureAndResetPeaks() {
            ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP)
                    .forEach(pool -> pool.resetPeakUsage());
            return capture();
        }
        static HeapObservation capture() {
            var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long peaks = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == MemoryType.HEAP)
                    .mapToLong(pool -> Math.max(0, pool.getPeakUsage().getUsed())).sum();
            long count = ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum();
            long millis = ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum();
            // Pool peak sum is descriptive; individual peaks need not have occurred simultaneously.
            return new HeapObservation(memory.getUsed(), peaks, memory.getMax(), count, millis);
        }
    }
}
