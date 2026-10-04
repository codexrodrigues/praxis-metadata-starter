package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkAuthorizedExecutionResultsReaderPostgresTest {
    private static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext(
            "tenant-a:production:payroll", "creator-a", "employees",
            new CanonicalOperationRef("admin", "employee-bulk-approve",
                    "/employees/bulk/approve", "POST"),
            "schema-r1", ActionCollectionAtomicity.PER_ITEM);
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final byte[] OLD_KEY = bytes(7);
    private static final byte[] NEW_KEY = bytes(19);

    private EmbeddedPostgres postgres;
    private DataSource owner;
    private DataSource runtime;
    private JdbcTemplate admin;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private BulkExecutionInfrastructure infrastructure;
    private JdbcBulkProposalStore proposals;
    private JdbcBulkDurableExecution executions;

    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        admin = new JdbcTemplate(owner);
        manager = new DataSourceTransactionManager(runtime);
        tx = new TransactionTemplate(manager);
        infrastructure = new BulkExecutionInfrastructure(runtime, manager, CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration());
        proposals = new JdbcBulkProposalStore(infrastructure);
        executions = new JdbcBulkDurableExecution(infrastructure);
    }

    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }

    @BeforeEach void reset() {
        admin.execute("drop schema if exists praxis_bulk cascade");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(17);
        BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(),
                CONTEXT.operationRef().operationId());
        admin.execute("create table if not exists bulk_g3cb_authority "
                + "(subject_id text primary key, allowed boolean not null)");
        admin.execute("grant select on bulk_g3cb_authority to bulk_runtime_test");
        admin.execute("truncate bulk_g3cb_authority");
        admin.update("insert into bulk_g3cb_authority(subject_id,allowed) values ('delegate-a',true)");
    }

    @Test void chainsCertifiedOutcomesAndReauthorizesTheFullProtectedSet() {
        BulkExecutionReservation reservation = reserve(persist(evaluation("mixed", 5)), "mixed-results");
        BulkExecutionControl control = reservation.control();
        control = executions.executeUnit(control, 0, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed()).control();
        control = executions.executeUnit(control, 1, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.unchanged()).control();
        control = executions.executeUnit(control, 2,
                ignored -> BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED),
                ignored -> { throw new AssertionError("denied target must not mutate"); }).control();
        control = executions.executeUnit(control, 3,
                ignored -> BulkUnitAdmission.invalid(BulkUnitReasonCode.TARGET_INVALID),
                ignored -> { throw new AssertionError("invalid target must not mutate"); }).control();
        executions.executeUnit(control, 4,
                ignored -> BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_DEPENDENCY_CHANGED),
                ignored -> { throw new AssertionError("conflicted target must not mutate"); });

        MutableAuthorizationProvider provider = new MutableAuthorizationProvider(bytes(3));
        BulkAuthorizedExecutionResultsReader reader = reader(provider, configuration(
                "old", Map.of("old", key(OLD_KEY)), Duration.ofMinutes(5)), new MutableClock(NOW));
        var first = reader.readExecutionResults("delegate-a", reservation.executionId(), 2);
        assertThat(first.state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.COMPLETE);
        assertThat(first.page().content()).extracting(BulkItemResult::status)
                .containsExactly(BulkItemStatus.CONFIRMED, BulkItemStatus.UNCHANGED);
        assertThat(first.page().next()).isNotBlank();
        var second = reader.readExecutionResults("delegate-a", reservation.executionId(), 2,
                first.page().next());
        assertThat(second.page().content()).extracting(BulkItemResult::status)
                .containsExactly(BulkItemStatus.DENIED, BulkItemStatus.INVALID);
        assertSafeDiagnostic(second.page().content().getFirst(), "BULK_ITEM_DENIED");
        assertSafeDiagnostic(second.page().content().getLast(), "BULK_ITEM_INVALID");
        var last = reader.readExecutionResults("delegate-a", reservation.executionId(), 2,
                second.page().next());
        assertThat(last.page().content()).extracting(BulkItemResult::status)
                .containsExactly(BulkItemStatus.CONFLICT);
        assertSafeDiagnostic(last.page().content().getFirst(), "BULK_ITEM_CONFLICT");
        assertThat(last.page().next()).isNull();
        assertThat(provider.targetCounts).containsExactly(5, 5, 5);
        assertThat(last.toString()).doesNotContain("TARGET_DEPENDENCY_CHANGED", "creator-a");
    }

    @Test void supportsCanonicalIntegerWireIdentity() {
        BulkExecutionReservation reservation = reserve(persist(integerEvaluation()), "integer-results");
        executions.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed());
        var result = reader(new MutableAuthorizationProvider(bytes(4)), configuration(
                "old", Map.of("old", key(OLD_KEY)), Duration.ofMinutes(5)), new MutableClock(NOW))
                .readExecutionResults("delegate-a", reservation.executionId(), 10);
        assertThat(result.page().content()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(42);
            assertThat(item.status()).isEqualTo(BulkItemStatus.CONFIRMED);
            assertThat(item.diagnostics()).isEmpty();
        });
    }

    @Test void fixedWindowNeverExpandsAfterAckAndStop() {
        BulkExecutionReservation reservation = reserve(persist(evaluation("window", 4)), "fixed-window");
        BulkUnitExecutionResult firstUnit = executions.executeUnit(reservation.control(), 0,
                ignored -> BulkUnitAdmission.admit(), ignored -> BulkUnitMutationResult.confirmed());
        BulkUnitExecutionResult secondUnit = executions.executeUnit(firstUnit.control(), 1,
                ignored -> BulkUnitAdmission.admit(), ignored -> BulkUnitMutationResult.unchanged());
        var reader = reader(new MutableAuthorizationProvider(bytes(5)), configuration(
                "old", Map.of("old", key(OLD_KEY)), Duration.ofMinutes(5)), new MutableClock(NOW));
        var firstPage = reader.readExecutionResults("delegate-a", reservation.executionId(), 1);
        assertThat(firstPage.page().next()).isNotBlank();

        BulkUnitExecutionResult third = executions.executeUnit(secondUnit.control(), 2,
                ignored -> BulkUnitAdmission.admit(), ignored -> BulkUnitMutationResult.confirmed());
        executions.executeUnit(third.control(), 3,
                ignored -> BulkUnitAdmission.stop(BulkUnitReasonCode.AUTHORIZATION_REVOKED),
                ignored -> { throw new AssertionError("stopped unit must not mutate"); });

        var continued = reader.readExecutionResults("delegate-a", reservation.executionId(), 1,
                firstPage.page().next());
        assertThat(continued.page().content()).extracting(BulkItemResult::status)
                .containsExactly(BulkItemStatus.UNCHANGED);
        assertThat(continued.page().next()).isNull();
        var fresh = reader.readExecutionResults("delegate-a", reservation.executionId(), 4);
        assertThat(fresh.page().content()).extracting(BulkItemResult::status)
                .containsExactly(BulkItemStatus.CONFIRMED, BulkItemStatus.UNCHANGED,
                        BulkItemStatus.CONFIRMED, BulkItemStatus.NOT_PROCESSED);
        assertSafeDiagnostic(fresh.page().content().getLast(), "BULK_ITEM_NOT_PROCESSED");
    }

    @Test void emptyPendingAndReconcilingPrefixesPublishNoUnknownOutcome() {
        BulkExecutionReservation pending = reserve(persist(evaluation("pending", 2)), "pending-results");
        assertThat(reader(new MutableAuthorizationProvider(bytes(6)), configuration(
                "old", Map.of("old", key(OLD_KEY)), Duration.ofMinutes(5)), new MutableClock(NOW))
                .readExecutionResults("delegate-a", pending.executionId(), 10).page().content()).isEmpty();

        admin.execute("alter table praxis_bulk.praxis_bulk_execution disable trigger all");
        try {
            assertThat(admin.update("update praxis_bulk.praxis_bulk_execution "
                    + "set status='RECONCILIATION_REQUIRED',updated_at=clock_timestamp() "
                    + "where execution_id=?", pending.executionId())).isEqualTo(1);
        } finally {
            admin.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger all");
        }
        var reconciliation = reader(new MutableAuthorizationProvider(bytes(6)), configuration(
                "old", Map.of("old", key(OLD_KEY)), Duration.ofMinutes(5)), new MutableClock(NOW))
                .readExecutionResults("delegate-a", pending.executionId(), 10);
        assertThat(reconciliation.state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.COMPLETE);
        assertThat(reconciliation.page().content()).isEmpty();
        assertThat(reconciliation.page().next()).isNull();
    }

    @Test void hidesCorrelationDamageAndReportsItemCorruptionOnlyAfterFullAuthorization() {
        BulkEvaluationSnapshot original = persist(evaluation("correlation-a", 2));
        BulkExecutionReservation reservation = reserve(original, "correlation-results");
        BulkEvaluationSnapshot other = persist(evaluation("correlation-b", 1));
        admin.execute("alter table praxis_bulk.praxis_bulk_execution disable trigger all");
        try {
            assertThat(admin.update("update praxis_bulk.praxis_bulk_execution set proposal_id=? "
                    + "where execution_id=?", other.proposal().id(), reservation.executionId())).isOne();
        } finally {
            admin.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger all");
        }
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider(bytes(15));
        assertHidden(reader(provider, configuration("old", Map.of("old", key(OLD_KEY)),
                Duration.ofMinutes(5)), new MutableClock(NOW)).readExecutionResults(
                        "delegate-a", reservation.executionId(), 10));
        assertThat(provider.authorizeCalls).isZero();

        reset();
        BulkEvaluationSnapshot corrupt = persist(evaluation("item-corrupt", 2));
        BulkExecutionReservation corruptReservation = reserve(corrupt, "item-corrupt-results");
        executions.executeUnit(corruptReservation.control(), 0,
                ignored -> BulkUnitAdmission.admit(), ignored -> BulkUnitMutationResult.confirmed());
        provider = new MutableAuthorizationProvider(bytes(16));
        var corruptReader = reader(provider, configuration("old", Map.of("old", key(OLD_KEY)),
                Duration.ofMinutes(5)), new MutableClock(NOW));
        provider.scopedState = BulkReadAuthorizationProvider.State.DENIED_OR_REDUCED;
        assertHidden(corruptReader.readExecutionResults("delegate-a", corruptReservation.executionId(), 10));
        provider.scopedState = BulkReadAuthorizationProvider.State.AUTHORITY_UNAVAILABLE;
        assertHidden(corruptReader.readExecutionResults("delegate-a", corruptReservation.executionId(), 10));
        provider.global = BulkReadAuthorizationProvider.GlobalDecision.DENIED;
        assertThat(corruptReader.readExecutionResults("delegate-a", corruptReservation.executionId(), 10).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.GLOBAL_DENIED);
        provider.global = BulkReadAuthorizationProvider.GlobalDecision.ALLOWED;
        provider.scopedState = BulkReadAuthorizationProvider.State.AUTHORIZED_ALL;
        admin.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                + "disable trigger praxis_bulk_target_manifest_immutable");
        try {
            assertThat(admin.update("update praxis_bulk.praxis_bulk_target_manifest set target_digest=? "
                    + "where proposal_id=? and ordinal=0", "sha256:" + "f".repeat(64),
                    corrupt.proposal().id())).isOne();
        } finally {
            admin.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                    + "enable trigger praxis_bulk_target_manifest_immutable");
        }
        var corrupted = corruptReader.readExecutionResults(
                "delegate-a", corruptReservation.executionId(), 10);
        assertThat(corrupted.state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.UNAVAILABLE);
        assertThat(corrupted.page()).isNull();
        assertThat(provider.authorizeCalls).isGreaterThan(0);
    }

    @Test void authorizationUsesTheSamePhysicalSnapshotAcrossExternalRevocation() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DatabaseAuthorizationProvider provider = new DatabaseAuthorizationProvider(observed, release);
        BulkExecutionReservation reservation = completed("grant-mvcc", 2);
        MutableClock clock = new MutableClock(NOW);
        BulkReadCursorConfiguration cursorConfiguration = configuration(
                "old", Map.of("old", key(OLD_KEY)), Duration.ofSeconds(1));
        String cursor = new BulkAuthorizedExecutionResultsReader(infrastructure, CONTEXT.resourceKey(),
                new DatabaseAuthorizationProvider(null, null), cursorConfiguration, clock)
                .readExecutionResults("delegate-a", reservation.executionId(), 1).page().next();
        assertThat(cursor).isNotBlank();
        var reader = new BulkAuthorizedExecutionResultsReader(infrastructure, CONTEXT.resourceKey(), provider,
                cursorConfiguration, clock);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldSnapshot = executor.submit(() -> reader.readExecutionResults(
                    "delegate-a", reservation.executionId(), 1, cursor));
            try {
                assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(admin.update("update bulk_g3cb_authority set allowed=false "
                        + "where subject_id='delegate-a'")).isOne();
                release.countDown();
                assertThat(oldSnapshot.get(5, TimeUnit.SECONDS).state())
                        .isEqualTo(BulkAuthorizedExecutionResultsReader.State.COMPLETE);
            } finally {
                release.countDown();
            }
        }
        assertThat(provider.backendPids).hasSize(2)
                .allMatch(pid -> pid.equals(provider.backendPids.getFirst()));
        clock.advance(Duration.ofSeconds(2));
        var deniedProvider = new DatabaseAuthorizationProvider(null, null);
        var denied = new BulkAuthorizedExecutionResultsReader(infrastructure, CONTEXT.resourceKey(),
                deniedProvider, cursorConfiguration, clock)
                .readExecutionResults("delegate-a", reservation.executionId(), 2, cursor);
        assertThat(denied.state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.GLOBAL_DENIED);
        assertThat(denied.page()).isNull();
    }

    @Test void cursorBindsRequesterExecutionFingerprintSizeRevisionPurposeAndLifetime() {
        BulkExecutionReservation reservation = completed("cursor", 3);
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider(bytes(8));
        MutableClock clock = new MutableClock(NOW);
        BulkReadCursorConfiguration old = configuration("old", Map.of("old", key(OLD_KEY)),
                Duration.ofSeconds(30));
        var reader = reader(provider, old, clock);
        String token = reader.readExecutionResults("delegate-a", reservation.executionId(), 1)
                .page().next();
        provider.scopedState = BulkReadAuthorizationProvider.State.DENIED_OR_REDUCED;
        assertHidden(reader.readExecutionResults("delegate-a", reservation.executionId(), 2, token));
        provider.scopedState = BulkReadAuthorizationProvider.State.AUTHORIZED_ALL;
        assertThat(reader.readExecutionResults("delegate-b", reservation.executionId(), 1, token).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.NOT_FOUND_OR_DENIED);
        assertThat(reader.readExecutionResults("delegate-a", UUID.randomUUID(), 1, token).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.NOT_FOUND_OR_DENIED);
        BulkExecutionReservation otherExecution = completed("other-execution", 1);
        assertThat(reader.readExecutionResults("delegate-a", otherExecution.executionId(), 1, token).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.NOT_FOUND_OR_DENIED);
        provider.fingerprint = bytes(9);
        assertThat(reader.readExecutionResults("delegate-a", reservation.executionId(), 1, token).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.NOT_FOUND_OR_DENIED);
        provider.fingerprint = bytes(8);
        assertThat(reader.readExecutionResults("delegate-a", reservation.executionId(), 2, token).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.PRECONDITION_FAILED);

        BulkReadCursorCodec codec = new BulkReadCursorCodec(old.keySet(), new java.security.SecureRandom(), clock);
        var claims = codec.decode(token, BulkReadCursorCodec.Purpose.EXECUTION_RESULTS).claims();
        String wrongRevision = codec.encode(copy(claims, claims.proposalId(), claims.executionId(),
                claims.authorizationScopeFingerprint(), "other-revision"));
        assertThat(reader.readExecutionResults("delegate-a", reservation.executionId(), 1, wrongRevision).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.PRECONDITION_FAILED);
        String wrongProposal = codec.encode(copy(claims, UUID.randomUUID(), claims.executionId(),
                claims.authorizationScopeFingerprint(), claims.projectorRevision()));
        assertThat(reader.readExecutionResults("delegate-a", reservation.executionId(), 1, wrongProposal).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.NOT_FOUND_OR_DENIED);
        assertThatThrownBy(() -> codec.decode(token, BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS))
                .isInstanceOf(BulkReadCursorCodec.BulkReadCursorException.class);

        var rotated = reader(provider, configuration("new",
                Map.of("new", key(NEW_KEY), "old", key(OLD_KEY)), Duration.ofMinutes(10)), clock);
        String continued = rotated.readExecutionResults("delegate-a", reservation.executionId(), 1, token)
                .page().next();
        var continuedClaims = new BulkReadCursorCodec(configuration("new",
                Map.of("new", key(NEW_KEY), "old", key(OLD_KEY)), Duration.ofMinutes(10)).keySet(),
                new java.security.SecureRandom(), clock).decode(
                        continued, BulkReadCursorCodec.Purpose.EXECUTION_RESULTS).claims();
        assertThat(continuedClaims.issuedAt()).isEqualTo(claims.issuedAt());
        assertThat(continuedClaims.expiresAt()).isEqualTo(claims.expiresAt());
        clock.advance(Duration.ofSeconds(31));
        assertThat(rotated.readExecutionResults("delegate-a", reservation.executionId(), 1, token).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.PRECONDITION_FAILED);
    }

    @Test void creatorSeesGoneWhileDelegateAndCopiedCursorRemainHiddenAfterPurge() {
        BulkExecutionReservation reservation = reserve(persist(evaluation("purged", 3)), "purged-results");
        BulkUnitExecutionResult first = executions.executeUnit(reservation.control(), 0,
                ignored -> BulkUnitAdmission.admit(), ignored -> BulkUnitMutationResult.confirmed());
        BulkUnitExecutionResult second = executions.executeUnit(first.control(), 1,
                ignored -> BulkUnitAdmission.admit(), ignored -> BulkUnitMutationResult.unchanged());
        BulkReadCursorConfiguration cursorConfiguration = configuration(
                "old", Map.of("old", key(OLD_KEY)), Duration.ofMinutes(5));
        MutableClock cursorClock = new MutableClock(NOW);
        var reader = reader(new MutableAuthorizationProvider(bytes(10)), cursorConfiguration, cursorClock);
        String cursor = reader.readExecutionResults(CONTEXT.subjectId(), reservation.executionId(), 1)
                .page().next();
        assertThat(cursor).isNotBlank();
        BulkReadCursorCodec codec = new BulkReadCursorCodec(cursorConfiguration.keySet(),
                new java.security.SecureRandom(), cursorClock);
        BulkReadCursorCodec.Claims claims = codec.decode(
                cursor, BulkReadCursorCodec.Purpose.EXECUTION_RESULTS).claims();
        String wrongExecution = codec.encode(copy(claims, claims.proposalId(), UUID.randomUUID(),
                claims.authorizationScopeFingerprint(), claims.projectorRevision()));
        var wrongScopeClaims = new BulkReadCursorCodec.Claims(claims.purpose(), claims.proposalId(),
                claims.executionId(), new BulkReadCursorCodec.Scope(CONTEXT.namespaceId(),
                        "other-creator", CONTEXT.resourceKey(), CONTEXT.operationRef().operationId()),
                claims.authorizationScopeFingerprint(), claims.direction(),
                claims.lastOrdinalExclusive(), claims.pageSize(), claims.watermarkExclusive(),
                claims.projectorRevision(), claims.issuedAt(), claims.expiresAt());
        String wrongScope = codec.encode(wrongScopeClaims);
        executions.executeUnit(second.control(), 2,
                ignored -> BulkUnitAdmission.stop(BulkUnitReasonCode.AUTHORIZATION_REVOKED),
                ignored -> { throw new AssertionError("stopped unit must not mutate"); });
        ageTerminalForRetention(reservation.executionId());

        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MutableAuthorizationProvider paused = new MutableAuthorizationProvider(bytes(10));
        paused.onAuthorize = () -> { authorized.countDown(); await(release); };
        var pausedReader = reader(paused, configuration("old", Map.of("old", key(OLD_KEY)),
                Duration.ofMinutes(5)), new MutableClock(NOW));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldSnapshot = executor.submit(() -> pausedReader.readExecutionResults(
                    CONTEXT.subjectId(), reservation.executionId(), 1, cursor));
            try {
                await(authorized);
                purge(reservation.executionId());
                release.countDown();
                var retained = awaitResult(oldSnapshot);
                assertThat(retained.state())
                        .isEqualTo(BulkAuthorizedExecutionResultsReader.State.COMPLETE);
                assertThat(retained.page().content()).singleElement().satisfies(item ->
                        assertThat(item.status()).isEqualTo(BulkItemStatus.UNCHANGED));
                assertThat(retained.page().next()).isNull();
            } finally {
                release.countDown();
            }
        }

        assertThat(reader.readExecutionResults(CONTEXT.subjectId(), reservation.executionId(), 1).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.GONE);
        assertThat(reader.readExecutionResults(CONTEXT.subjectId(), reservation.executionId(), 1, cursor).state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.GONE);
        assertHidden(reader.readExecutionResults(
                CONTEXT.subjectId(), reservation.executionId(), 1, wrongExecution));
        assertHidden(reader.readExecutionResults(
                CONTEXT.subjectId(), reservation.executionId(), 1, wrongScope));
        assertHidden(reader.readExecutionResults("delegate-a", reservation.executionId(), 1));
        assertHidden(reader.readExecutionResults("delegate-a", reservation.executionId(), 1, cursor));
    }

    @Test void invalidCursorPrecedesSqlAndLateCompletionPublishesNoPage() {
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider(bytes(12));
        var reader = reader(provider, configuration("old", Map.of("old", key(OLD_KEY)),
                Duration.ofMinutes(5)), new MutableClock(NOW));
        var invalid = reader.readExecutionResults("delegate-a", UUID.randomUUID(), 10, "not-a-cursor");
        assertThat(invalid.state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.INVALID_CURSOR);
        assertThat(provider.preAuthorizeCalls).isZero();

        BulkExecutionReservation reservation = completed("late", 1);
        provider.onAuthorize = () -> TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        try { Thread.sleep(3_050); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                });
        var late = reader.readExecutionResults("delegate-a", reservation.executionId(), 10);
        assertThat(late.state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.UNAVAILABLE);
        assertThat(late.page()).isNull();
    }

    @Test void neverPublishesANewCursorThatExpiredDuringTransactionCompletion() {
        BulkExecutionReservation reservation = completed("initial-expiry", 2);
        MutableClock initialClock = new MutableClock(NOW);
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider(bytes(13));
        provider.onAuthorize = () -> TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        initialClock.advance(Duration.ofSeconds(2));
                    }
                });
        var result = reader(provider, configuration("old", Map.of("old", key(OLD_KEY)),
                Duration.ofSeconds(1)), initialClock).readExecutionResults(
                        "delegate-a", reservation.executionId(), 1);
        assertThat(result.state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.UNAVAILABLE);
        assertThat(result.page()).isNull();

        MutableClock continuationClock = new MutableClock(NOW);
        provider = new MutableAuthorizationProvider(bytes(14));
        var continuationReader = reader(provider, configuration("old", Map.of("old", key(OLD_KEY)),
                Duration.ofSeconds(1)), continuationClock);
        String cursor = continuationReader.readExecutionResults(
                "delegate-a", reservation.executionId(), 1).page().next();
        provider.onAuthorize = () -> TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        continuationClock.advance(Duration.ofSeconds(2));
                    }
                });
        var last = continuationReader.readExecutionResults(
                "delegate-a", reservation.executionId(), 1, cursor);
        assertThat(last.state()).isEqualTo(
                BulkAuthorizedExecutionResultsReader.State.PRECONDITION_FAILED);
        assertThat(last.page()).isNull();
    }

    private BulkAuthorizedExecutionResultsReader reader(MutableAuthorizationProvider provider,
            BulkReadCursorConfiguration configuration, Clock clock) {
        return new BulkAuthorizedExecutionResultsReader(
                infrastructure, CONTEXT.resourceKey(), provider, configuration, clock);
    }

    private BulkEvaluationSnapshot persist(BulkEvaluationSnapshot evaluation) {
        tx.executeWithoutResult(status -> proposals.insertEvaluated(
                evaluation, BulkEvaluationSnapshotTest.preview(evaluation)));
        return evaluation;
    }

    private BulkExecutionReservation reserve(BulkEvaluationSnapshot evaluation, String key) {
        return executions.reserve(CONTEXT, evaluation.proposal().id(), key, "owner-a",
                "structural-r1", Instant.now().plusSeconds(120));
    }

    private BulkExecutionReservation completed(String suffix, int count) {
        BulkExecutionReservation reservation = reserve(persist(evaluation(suffix, count)), suffix + "-key");
        BulkExecutionControl control = reservation.control();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            control = executions.executeUnit(control, ordinal, ignored -> BulkUnitAdmission.admit(),
                    ignored -> BulkUnitMutationResult.confirmed()).control();
        }
        return reservation;
    }

    private static BulkEvaluationSnapshot evaluation(String suffix, int count) {
        List<BulkTarget<String>> selected = new ArrayList<>(count);
        List<BulkTargetEvidence<?>> evidence = new ArrayList<>(count);
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            BulkTarget<String> target = new BulkTarget<>(suffix + "-target-" + ordinal, "v1");
            selected.add(target);
            evidence.add(new BulkTargetEvidence<>(target, "observed-v1", empty, empty,
                    BulkTargetEligibility.executable()));
        }
        var request = new BulkCommandEvaluationRequest<JsonNode, String, JsonNode>(
                BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, selected, null, null), empty);
        return evaluation(BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                JsonNode::deepCopy, JsonNode::deepCopy), evidence);
    }

    private static BulkEvaluationSnapshot integerEvaluation() {
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        BulkTarget<Integer> target = new BulkTarget<>(42, "1");
        var request = new BulkCommandEvaluationRequest<JsonNode, Integer, JsonNode>(
                BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, List.of(target), null, null), empty);
        BulkIntentSnapshot snapshot = BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.integers(),
                request, JsonNode::deepCopy, JsonNode::deepCopy);
        return evaluation(snapshot, List.of(new BulkTargetEvidence<>(target, "1",
                empty, empty, BulkTargetEligibility.executable())));
    }

    private static BulkEvaluationSnapshot evaluation(BulkIntentSnapshot snapshot,
            List<BulkTargetEvidence<?>> evidence) {
        Instant created = Instant.now().minusSeconds(5);
        BulkStoredProposal proposal = new BulkStoredProposal(UUID.randomUUID(), created,
                created.plusSeconds(600), snapshot, BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
        var governance = new BulkEvaluationGovernance("test-evaluator-r1", "test-grants-r1", List.of(
                new BulkPolicyObservation("tenant", "test", "approval_policy",
                        "resource-action-approval", "resource:approve", "NEVER_APPLIED",
                        "test-policy-r1", created.plusMillis(500))));
        return new BulkEvaluationSnapshot(proposal, created.plusSeconds(1), evidence, governance);
    }

    private void ageTerminalForRetention(UUID executionId) {
        for (String trigger : List.of("praxis_bulk_execution_guard_terminal",
                "praxis_bulk_execution_protect_cancel", "praxis_bulk_execution_protect_binding"))
            admin.execute("alter table praxis_bulk.praxis_bulk_execution disable trigger " + trigger);
        admin.execute("alter table praxis_bulk.praxis_bulk_allocation "
                + "disable trigger praxis_bulk_allocation_protect_transition");
        try {
            assertThat(admin.update("""
                    with aged as materialized (select clock_timestamp()-interval '31 days' as terminal)
                    update praxis_bulk.praxis_bulk_execution
                    set created_at=aged.terminal-interval '2 seconds',terminal_at=aged.terminal,
                        updated_at=aged.terminal from aged where execution_id=?
                    """, executionId)).isEqualTo(1);
            assertThat(admin.update("""
                    update praxis_bulk.praxis_bulk_allocation a
                    set created_at=e.created_at,released_at=e.terminal_at
                    from praxis_bulk.praxis_bulk_execution e
                    where a.execution_id=e.execution_id and e.execution_id=?
                    """, executionId)).isOne();
        } finally {
            admin.execute("alter table praxis_bulk.praxis_bulk_allocation "
                    + "enable trigger praxis_bulk_allocation_protect_transition");
            for (String trigger : List.of("praxis_bulk_execution_protect_binding",
                    "praxis_bulk_execution_protect_cancel", "praxis_bulk_execution_guard_terminal"))
                admin.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger " + trigger);
        }
    }

    private void purge(UUID executionId) {
        admin.execute("grant praxis_bulk_retention_executor to postgres");
        try {
            Boolean purged = new TransactionTemplate(new DataSourceTransactionManager(owner)).execute(status -> {
                admin.execute("set local role praxis_bulk_retention_executor");
                return admin.queryForObject("select praxis_bulk.purge_terminal_execution(?)",
                        Boolean.class, executionId);
            });
            assertThat(purged).isTrue();
        } finally {
            admin.execute("revoke praxis_bulk_retention_executor from postgres");
        }
    }

    private static void assertSafeDiagnostic(BulkItemResult<Object> item, String code) {
        assertThat(item.diagnostics()).singleElement().satisfies(message -> {
            assertThat(message.code()).isEqualTo(code);
            assertThat(message.target()).isNull();
            assertThat(message.metadata()).isEmpty();
            assertThat(message.toString()).doesNotContain("TARGET_", "creator-a");
        });
    }

    private static void assertHidden(BulkAuthorizedExecutionResultsReader.Observation observation) {
        assertThat(observation.state())
                .isEqualTo(BulkAuthorizedExecutionResultsReader.State.NOT_FOUND_OR_DENIED);
        assertThat(observation.page()).isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static <T> T awaitResult(java.util.concurrent.Future<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static BulkReadCursorCodec.Claims copy(BulkReadCursorCodec.Claims source,
            UUID proposalId, UUID executionId, byte[] fingerprint, String revision) {
        return new BulkReadCursorCodec.Claims(source.purpose(), proposalId, executionId,
                source.scope(), fingerprint, source.direction(), source.lastOrdinalExclusive(),
                source.pageSize(), source.watermarkExclusive(), revision,
                source.issuedAt(), source.expiresAt());
    }

    private static BulkReadCursorConfiguration configuration(String active,
            Map<String, javax.crypto.SecretKey> keys, Duration ttl) {
        return new BulkReadCursorConfiguration(active, keys, ttl);
    }
    private static SecretKeySpec key(byte[] value) { return new SecretKeySpec(value, "AES"); }
    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private final class MutableAuthorizationProvider implements BulkReadAuthorizationProvider {
        private byte[] fingerprint;
        private GlobalDecision global = GlobalDecision.ALLOWED;
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
            return global;
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

    private final class DatabaseAuthorizationProvider implements BulkReadAuthorizationProvider {
        private final CountDownLatch observed;
        private final CountDownLatch release;
        private final List<Integer> backendPids = new ArrayList<>();
        private DatabaseAuthorizationProvider(CountDownLatch observed, CountDownLatch release) {
            this.observed = observed;
            this.release = release;
        }
        @Override public String confirmationOperationId() { return CONTEXT.operationRef().operationId(); }
        @Override public BulkExecutionInfrastructure executionInfrastructure() { return infrastructure; }
        @Override public GlobalDecision preAuthorize(Context context, Duration remainingBudget) {
            boolean allowed = authority(context.requesterSubjectId());
            if (observed != null) { observed.countDown(); await(release); }
            return allowed ? GlobalDecision.ALLOWED : GlobalDecision.DENIED;
        }
        @Override public ScopeDecision authorize(Context context, String creatorSubjectId,
                List<Target> fullTargetSet, Duration remainingBudget) {
            return authority(context.requesterSubjectId())
                    ? ScopeDecision.authorized(bytes(17)) : ScopeDecision.denied();
        }
        private boolean authority(String subject) {
            Connection connection = DataSourceUtils.getConnection(runtime);
            try {
                assertThat(DataSourceUtils.isConnectionTransactional(
                        DataSourceUtils.getTargetConnection(connection), runtime)).isTrue();
                try (var query = connection.prepareStatement("""
                        select allowed,pg_backend_pid(),current_setting('transaction_isolation'),
                               current_setting('transaction_read_only')
                        from bulk_g3cb_authority where subject_id=?
                        """)) {
                    query.setString(1, subject);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) return false;
                        boolean allowed = rows.getBoolean(1);
                        backendPids.add(rows.getInt(2));
                        assertThat(rows.getString(3)).isEqualTo("repeatable read");
                        assertThat(rows.getString(4)).isEqualTo("on");
                        if (rows.next()) throw new IllegalStateException("duplicate authority");
                        return allowed;
                    }
                }
            } catch (SQLException error) {
                throw new IllegalStateException(error);
            } finally {
                DataSourceUtils.releaseConnection(connection, runtime);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
