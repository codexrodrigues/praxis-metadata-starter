package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.governance;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.preview;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.CONTEXT;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.proposal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkAuthorizedProposalResultsContinuationPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final byte[] OLD_KEY = bytes(7);
    private static final byte[] NEW_KEY = bytes(19);

    private EmbeddedPostgres postgres;
    private DataSource owner;
    private DataSource runtime;
    private TransactionTemplate tx;
    private JdbcBulkProposalStore store;
    private BulkExecutionInfrastructure infrastructure;

    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        var manager = new DataSourceTransactionManager(runtime);
        tx = new TransactionTemplate(manager);
        infrastructure = new BulkExecutionInfrastructure(runtime, manager, CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration());
        store = new JdbcBulkProposalStore(infrastructure);
    }

    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }

    @BeforeEach void reset() {
        new JdbcTemplate(owner).execute("drop schema if exists praxis_bulk cascade");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(17);
        BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
    }

    @Test void chainsFixedWindowAcrossPagesAndReauthorizesTheEntireSet() {
        BulkEvaluationSnapshot evaluation = evaluation(5);
        UUID id = persist(evaluation);
        var provider = new MutableAuthorizationProvider(bytes(3));
        var clock = new MutableClock(NOW);
        var configuration = configuration("old", Map.of("old", key(OLD_KEY)), Duration.ofMinutes(5));
        var reader = reader(provider, configuration, clock);

        var first = reader.readProposalResults("reader-a", id, 2);
        assertThat(first.state()).isEqualTo(BulkAuthorizedProposalResultsReader.State.COMPLETE);
        assertThat(first.page().content()).extracting(BulkProposalItemResult::id)
                .containsExactly("target-0", "target-1");
        assertThat(first.page().next()).isNotBlank();
        assertThat(first.page().prev()).isNull();
        assertThat(first.page().size()).isEqualTo(2);
        assertThatThrownBy(() -> first.page().content().add(first.page().content().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);

        var second = reader.readProposalResults("reader-a", id, 2, first.page().next());
        assertThat(second.page().content()).extracting(BulkProposalItemResult::id)
                .containsExactly("target-2", "target-3");
        var last = reader.readProposalResults("reader-a", id, 2, second.page().next());
        assertThat(last.page().content()).extracting(BulkProposalItemResult::id)
                .containsExactly("target-4");
        assertThat(last.page().next()).isNull();
        assertThat(provider.targetCounts).containsExactly(5, 5, 5);

        assertThat(reader.readProposalResults("reader-b", id, 2, first.page().next()).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);
        provider.fingerprint = bytes(4);
        assertThat(reader.readProposalResults("reader-a", id, 2, first.page().next()).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);
        provider.fingerprint = bytes(3);
        assertThat(reader.readProposalResults("reader-a", id, 1, first.page().next()).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.PRECONDITION_FAILED);

        BulkReadCursorCodec codec = new BulkReadCursorCodec(configuration.keySet(),
                new java.security.SecureRandom(), clock);
        BulkReadCursorCodec.Claims original = codec.decode(first.page().next(),
                BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS).claims();
        String changedWatermark = codec.encode(copy(original, original.lastOrdinalExclusive(),
                4, original.projectorRevision()));
        assertThat(reader.readProposalResults("reader-a", id, 2, changedWatermark).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.PRECONDITION_FAILED);
        String changedProjector = codec.encode(copy(original, original.lastOrdinalExclusive(),
                original.watermarkExclusive(), "different-projector/1"));
        assertThat(reader.readProposalResults("reader-a", id, 2, changedProjector).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.PRECONDITION_FAILED);
        String afterLast = codec.encode(copy(original, original.watermarkExclusive() - 1,
                original.watermarkExclusive(), original.projectorRevision()));
        assertThat(reader.readProposalResults("reader-a", id, 2, afterLast).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);
    }

    @Test void authenticatesBeforeCursorPreconditionsAndPreservesOriginalLifetimeAcrossRotation() {
        BulkEvaluationSnapshot evaluation = evaluation(5);
        UUID id = persist(evaluation);
        var provider = new MutableAuthorizationProvider(bytes(5));
        var clock = new MutableClock(NOW);
        var originalConfiguration = configuration("old", Map.of("old", key(OLD_KEY)), Duration.ofSeconds(30));
        var issuer = reader(provider, originalConfiguration, clock);
        String firstToken = issuer.readProposalResults("reader", id, 2).page().next();
        BulkReadCursorCodec oldCodec = new BulkReadCursorCodec(originalConfiguration.keySet(),
                new java.security.SecureRandom(), clock);
        var originalClaims = oldCodec.decode(firstToken, BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS).claims();

        var rotatedConfiguration = configuration("new",
                Map.of("new", key(NEW_KEY), "old", key(OLD_KEY)), Duration.ofMinutes(10));
        var restarted = reader(provider, rotatedConfiguration, clock);
        var second = restarted.readProposalResults("reader", id, 2, firstToken);
        assertThat(second.state()).isEqualTo(BulkAuthorizedProposalResultsReader.State.COMPLETE);
        String lastToken = second.page().next();
        var rotatedCodec = new BulkReadCursorCodec(rotatedConfiguration.keySet(),
                new java.security.SecureRandom(), clock);
        var continuedClaims = rotatedCodec.decode(second.page().next(),
                BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS).claims();
        assertThat(continuedClaims.issuedAt()).isEqualTo(originalClaims.issuedAt());
        assertThat(continuedClaims.expiresAt()).isEqualTo(originalClaims.expiresAt());

        var retired = reader(provider,
                configuration("new", Map.of("new", key(NEW_KEY)), Duration.ofMinutes(5)), clock);
        assertThat(retired.readProposalResults("reader", id, 2, firstToken).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.INVALID_CURSOR);

        clock.advance(Duration.ofSeconds(29));
        provider.onAuthorize = () -> clock.advance(Duration.ofSeconds(2));
        assertThat(restarted.readProposalResults("reader", id, 2, firstToken).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.PRECONDITION_FAILED);
        clock.set(NOW.plusSeconds(29));
        provider.onAuthorize = () -> TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        clock.advance(Duration.ofSeconds(2));
                    }
                });
        assertThat(restarted.readProposalResults("reader", id, 2, lastToken).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.PRECONDITION_FAILED);
        provider.onAuthorize = () -> { };
        provider.scopedState = BulkReadAuthorizationProvider.State.DENIED_OR_REDUCED;
        assertThat(restarted.readProposalResults("reader", id, 2, firstToken).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);
        provider.scopedState = BulkReadAuthorizationProvider.State.AUTHORIZED_ALL;
        assertThat(restarted.readProposalResults("reader", UUID.randomUUID(), 2, firstToken).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);
        assertThat(restarted.readProposalResults("reader", id, 2, firstToken).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.PRECONDITION_FAILED);
    }

    @Test void rejectsInvalidCursorBeforeOpeningAuthorizationOrStorage() {
        var provider = new MutableAuthorizationProvider(bytes(6));
        var reader = reader(provider,
                configuration("old", Map.of("old", key(OLD_KEY)), Duration.ofMinutes(5)),
                new MutableClock(NOW));

        var result = reader.readProposalResults("reader", UUID.randomUUID(), 10, "not-a-cursor");

        assertThat(result.state()).isEqualTo(BulkAuthorizedProposalResultsReader.State.INVALID_CURSOR);
        assertThat(result.page()).isNull();
        assertThat(provider.preAuthorizeCalls).isZero();
        assertThat(provider.authorizeCalls).isZero();
    }

    private BulkAuthorizedProposalResultsReader reader(MutableAuthorizationProvider provider,
            BulkReadCursorConfiguration configuration, Clock clock) {
        return new BulkAuthorizedProposalResultsReader(
                infrastructure, CONTEXT.resourceKey(), provider, configuration, clock);
    }

    private UUID persist(BulkEvaluationSnapshot evaluation) {
        tx.executeWithoutResult(status -> store.insertEvaluated(evaluation, preview(evaluation)));
        return evaluation.proposal().id();
    }

    private static BulkEvaluationSnapshot evaluation(int count) {
        List<BulkTarget<String>> selected = new ArrayList<>(count);
        List<BulkTargetEvidence<?>> evidence = new ArrayList<>(count);
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            var target = new BulkTarget<>("target-" + ordinal, "v1");
            selected.add(target);
            evidence.add(new BulkTargetEvidence<>(target, "observed", empty, empty,
                    BulkTargetEligibility.executable()));
        }
        var request = new BulkCommandEvaluationRequest<JsonNode, String, JsonNode>(
                BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, selected, null, null), empty);
        var stored = proposal(BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                JsonNode::deepCopy, JsonNode::deepCopy));
        return new BulkEvaluationSnapshot(stored, stored.createdAt().plusSeconds(1), evidence, governance());
    }

    private static BulkReadCursorCodec.Claims copy(BulkReadCursorCodec.Claims source,
            int lastOrdinal, int watermark, String projectorRevision) {
        return new BulkReadCursorCodec.Claims(source.purpose(), source.proposalId(), source.executionId(),
                source.scope(), source.authorizationScopeFingerprint(), source.direction(), lastOrdinal,
                source.pageSize(), watermark, projectorRevision, source.issuedAt(), source.expiresAt());
    }

    private static BulkReadCursorConfiguration configuration(String active,
            Map<String, javax.crypto.SecretKey> keys, Duration ttl) {
        return new BulkReadCursorConfiguration(active, keys, ttl);
    }

    private static SecretKeySpec key(byte[] value) { return new SecretKeySpec(value, "AES"); }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private final class MutableAuthorizationProvider implements BulkReadAuthorizationProvider {
        private byte[] fingerprint;
        private State scopedState = State.AUTHORIZED_ALL;
        private int preAuthorizeCalls;
        private int authorizeCalls;
        private final List<Integer> targetCounts = new ArrayList<>();
        private Runnable onAuthorize = () -> { };

        private MutableAuthorizationProvider(byte[] fingerprint) { this.fingerprint = fingerprint.clone(); }

        @Override public String confirmationOperationId() { return CONTEXT.operationRef().operationId(); }
        @Override public BulkExecutionInfrastructure executionInfrastructure() { return infrastructure; }
        @Override public GlobalDecision preAuthorize(Context context, Duration remainingBudget) {
            preAuthorizeCalls++;
            return GlobalDecision.ALLOWED;
        }
        @Override public ScopeDecision authorize(Context context, String creatorSubjectId,
                List<Target> fullTargetSet, Duration remainingBudget) {
            authorizeCalls++;
            targetCounts.add(fullTargetSet.size());
            onAuthorize.run();
            return switch (scopedState) {
                case AUTHORIZED_ALL -> ScopeDecision.authorized(fingerprint);
                case DENIED_OR_REDUCED -> ScopeDecision.denied();
                case AUTHORITY_UNAVAILABLE -> ScopeDecision.unavailable();
            };
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        void set(Instant value) { instant = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
