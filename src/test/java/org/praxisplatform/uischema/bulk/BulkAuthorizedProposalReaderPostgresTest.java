package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.governance;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkAuthorizedProposalReaderPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:01:00Z");

    private EmbeddedPostgres postgres;
    private DataSource owner;
    private DataSource runtime;
    private JdbcTemplate sql;
    private TransactionTemplate tx;
    private JdbcBulkProposalStore store;
    private JdbcBulkDurableExecution executions;
    private BulkExecutionInfrastructure infrastructure;

    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        sql = new JdbcTemplate(owner);
        var manager = new DataSourceTransactionManager(runtime);
        tx = new TransactionTemplate(manager);
        infrastructure = new BulkExecutionInfrastructure(runtime, manager, CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration());
        store = new JdbcBulkProposalStore(infrastructure);
        executions = new JdbcBulkDurableExecution(infrastructure);
    }

    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }

    @BeforeEach void reset() {
        sql.execute("drop schema if exists praxis_bulk cascade");
        sql.execute("drop table if exists public.rs1_test_grant");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(19);
        BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
    }

    @Test void composesReadyProposalFromProtectedIntentAndCompleteSafeProjection() {
        BulkEvaluationSnapshot evaluation = evaluation(401, -1);
        persist(evaluation, projection(evaluation));
        var rawPage = new BulkPreviewPageReader(infrastructure).read(
                CONTEXT, evaluation.proposal().id(), -1, 401, 200);
        assertThat(rawPage.selectedBytes()).isPositive();
        assertThat(rawPage.hasMore()).isTrue();
        var authorization = new MutableAuthorizationProvider();
        var reader = reader(authorization, new MutableClock(NOW), projectionProvider());

        var result = reader.readProposal("delegated-reader", evaluation.proposal().id());

        assertThat(result.state()).isEqualTo(BulkAuthorizedProposalReader.State.COMPLETE);
        assertThat(result.proposal()).satisfies(value -> {
            assertThat(value.status()).isEqualTo(BulkProposalStatus.READY);
            assertThat(value.totals()).isEqualTo(new BulkProposalTotals(401, 401, 401, 0));
            assertThat(value.redactedIntent()).isEqualTo(JsonNodeFactory.instance.objectNode()
                    .put("projection", "safe").put("targetCount", 401));
            assertThat(value.diagnostics()).isEmpty();
            assertThat(value.evidence()).isEmpty();
            assertThat(value.toString()).doesNotContain("target-0000", "protected");
        });
        assertThat(authorization.targetCounts).containsExactly(401);
    }

    @Test void aggregatesOnlyPersistedAllowlistedDiagnosticsAndKeepsProtectedValuesOut() {
        BulkEvaluationSnapshot evaluation = evaluation(2, 1);
        persist(evaluation, projection(evaluation));

        var result = reader(new MutableAuthorizationProvider(), new MutableClock(NOW),
                projectionProvider()).readProposal("reader", evaluation.proposal().id());

        assertThat(result.state()).isEqualTo(BulkAuthorizedProposalReader.State.COMPLETE);
        assertThat(result.proposal().status()).isEqualTo(BulkProposalStatus.BLOCKED);
        assertThat(result.proposal().totals()).isEqualTo(new BulkProposalTotals(2, 2, 1, 1));
        assertThat(result.proposal().diagnostics()).singleElement().satisfies(message -> {
            assertThat(message.code()).isEqualTo("STATE_NOT_ALLOWED");
            assertThat(message.message()).isEqualTo("Public state restriction");
            assertThat(message.target()).isNull();
            assertThat(message.metadata()).isEmpty();
        });
        assertThat(result.proposal().toString()).doesNotContain(
                "private payroll value", "target-0001", "protected");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .valueToTree(result.proposal()).toString())
                .doesNotContain("private payroll value", "target-0001", "protected");
    }

    @Test void scansDistinctBlockedPagesAndDeduplicatesOnlyThePersistedSafeMessage() {
        BulkEvaluationSnapshot evaluation = evaluation(401, 0, 200, 400);
        persist(evaluation, projection(evaluation));

        var result = reader(new MutableAuthorizationProvider(), new MutableClock(NOW),
                projectionProvider()).readProposal("reader", evaluation.proposal().id());

        assertThat(result.state()).isEqualTo(BulkAuthorizedProposalReader.State.COMPLETE);
        assertThat(result.proposal().totals()).isEqualTo(new BulkProposalTotals(401, 401, 398, 3));
        assertThat(result.proposal().diagnostics()).singleElement()
                .extracting(ResourceCommandMessage::message)
                .isEqualTo("Public state restriction");
    }

    @Test void appliesGlobalAndFullSetAuthorizationWithoutEnumeratingProtectedStorage() {
        BulkEvaluationSnapshot evaluation = evaluation(2, -1);
        persist(evaluation, projection(evaluation));
        var authorization = new MutableAuthorizationProvider();
        var reader = reader(authorization, new MutableClock(NOW), projectionProvider());

        authorization.global = BulkReadAuthorizationProvider.GlobalDecision.DENIED;
        assertThat(reader.readProposal("reader", UUID.randomUUID()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.GLOBAL_DENIED);
        assertThat(authorization.authorizeCalls).isZero();

        authorization.global = BulkReadAuthorizationProvider.GlobalDecision.ALLOWED;
        authorization.scoped = BulkReadAuthorizationProvider.ScopeDecision.denied();
        assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);
        assertThat(reader.readProposal("reader", UUID.randomUUID()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);
        assertThat(authorization.targetCounts).containsExactly(2);

        authorization.scoped = BulkReadAuthorizationProvider.ScopeDecision.unavailable();
        assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);

        BulkEvaluationSnapshot corrupt = evaluation(1, -1);
        persist(corrupt, projection(corrupt));
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_evaluation set payload=? where proposal_id=?",
                    new byte[]{1, 2, 3}, corrupt.proposal().id());
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_evaluation enable trigger user");
        }
        int authorizeCalls = authorization.authorizeCalls;
        authorization.scoped = BulkReadAuthorizationProvider.ScopeDecision.authorized(new byte[32]);
        assertThat(reader.readProposal("reader", corrupt.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);
        assertThat(authorization.authorizeCalls).isEqualTo(authorizeCalls);
    }

    @Test void returnsGoneOnlyAfterFullAuthorizationAndRemovedProposalBecomesNonEnumerating() {
        BulkEvaluationSnapshot evaluation = evaluation(1, -1);
        persist(evaluation, projection(evaluation));
        var authorization = new MutableAuthorizationProvider();
        var reader = reader(authorization,
                new MutableClock(evaluation.proposal().expiresAt()), projectionProvider());

        assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.GONE);
        assertThat(authorization.authorizeCalls).isOne();

        sql.execute("grant praxis_bulk_retention_executor to postgres");
        try {
            var ownerTx = new TransactionTemplate(new DataSourceTransactionManager(owner));
            Boolean purged = ownerTx.execute(status -> {
                sql.execute("set local role praxis_bulk_retention_executor");
                return sql.queryForObject("select praxis_bulk.expire_unconsumed_proposal(?)",
                        Boolean.class, evaluation.proposal().id());
            });
            assertThat(purged).isTrue();
        } finally {
            sql.execute("revoke praxis_bulk_retention_executor from postgres");
        }
        assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);
    }

    @Test void returnsGoneForCreatorOnlyWhenAnExecutionTombstoneRetainsTheProposalId() {
        BulkFingerprintContext executionContext = new BulkFingerprintContext(CONTEXT.namespaceId(),
                CONTEXT.subjectId(), CONTEXT.resourceKey(), CONTEXT.operationRef(),
                CONTEXT.schemaRevision(), ActionCollectionAtomicity.PER_ITEM);
        BulkEvaluationSnapshot stale = evaluation(executionContext, 1, -1);
        Instant created = Instant.now().minusSeconds(5);
        BulkStoredProposal freshProposal = new BulkStoredProposal(UUID.randomUUID(), created,
                created.plusSeconds(600), stale.proposal().snapshot(),
                stale.proposal().controlExpectation());
        BulkEvaluationGovernance freshGovernance = new BulkEvaluationGovernance(
                "test-evaluator-r1", "test-grants-r1", List.of(new BulkPolicyObservation(
                "tenant", "test", "approval_policy", "resource-action-approval",
                "resource:approve", "NEVER_APPLIED", "test-policy-r1", created.plusMillis(500))));
        BulkEvaluationSnapshot evaluation = new BulkEvaluationSnapshot(freshProposal,
                created.plusSeconds(1), stale.targets(), freshGovernance);
        persist(evaluation, projection(evaluation));
        BulkExecutionReservation reservation = executions.reserve(executionContext, freshProposal.id(),
                "proposal-purge-key", "owner", freshProposal.controlExpectation().structuralRevision(),
                Instant.now().plusSeconds(120));
        executions.requestCancel(CONTEXT, reservation.executionId());
        ageTerminalForRetention(reservation.executionId());
        purge(reservation.executionId());

        var authorization = new MutableAuthorizationProvider();
        var reader = reader(authorization, Clock.systemUTC(), projectionProvider());
        assertThat(reader.readProposal(executionContext.subjectId(), freshProposal.id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.TOMBSTONED);
        assertThat(authorization.authorizeCalls).isZero();
        assertThat(reader.readProposal("another-subject", freshProposal.id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);
        assertThat(reader.readProposal(CONTEXT.subjectId(), UUID.randomUUID()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);

        sql.execute("alter table praxis_bulk.praxis_bulk_tombstone "
                + "drop constraint praxis_bulk_tombstone_terminal_check");
        sql.execute("alter table praxis_bulk.praxis_bulk_tombstone disable trigger user");
        try {
            assertThat(sql.update("update praxis_bulk.praxis_bulk_tombstone set terminal_status=? where proposal_id=?",
                    "RUNNING", freshProposal.id())).isEqualTo(1);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_tombstone enable trigger user");
        }
        assertThat(reader.readProposal(executionContext.subjectId(), freshProposal.id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);

        authorization.global = BulkReadAuthorizationProvider.GlobalDecision.DENIED;
        assertThat(reader.readProposal(executionContext.subjectId(), freshProposal.id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.GLOBAL_DENIED);
    }

    private void ageTerminalForRetention(UUID executionId) {
        sql.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_guard_terminal");
        sql.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_protect_cancel");
        sql.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_protect_binding");
        sql.execute("alter table praxis_bulk.praxis_bulk_allocation "
                + "disable trigger praxis_bulk_allocation_protect_transition");
        try {
            assertThat(sql.update("""
                    with aged as materialized (select clock_timestamp() - interval '31 days' as terminal)
                    update praxis_bulk.praxis_bulk_execution
                    set created_at=aged.terminal - interval '2 seconds',
                        cancel_requested_at=aged.terminal - interval '1 second',
                        terminal_at=aged.terminal, updated_at=aged.terminal
                    from aged where execution_id=?
                    """, executionId)).isEqualTo(1);
            assertThat(sql.update("""
                    update praxis_bulk.praxis_bulk_allocation a
                    set created_at=e.created_at, released_at=e.terminal_at
                    from praxis_bulk.praxis_bulk_execution e
                    where a.execution_id=e.execution_id and e.execution_id=?
                    """, executionId)).isEqualTo(1);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_allocation "
                    + "enable trigger praxis_bulk_allocation_protect_transition");
            sql.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_protect_binding");
            sql.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_protect_cancel");
            sql.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_guard_terminal");
        }
    }

    private void purge(UUID executionId) {
        sql.execute("grant praxis_bulk_retention_executor to postgres");
        try {
            var ownerTx = new TransactionTemplate(new DataSourceTransactionManager(owner));
            Boolean purged = ownerTx.execute(status -> {
                sql.execute("set local role praxis_bulk_retention_executor");
                return sql.queryForObject("select praxis_bulk.purge_terminal_execution(?)",
                        Boolean.class, executionId);
            });
            assertThat(purged).isTrue();
        } finally {
            sql.execute("revoke praxis_bulk_retention_executor from postgres");
        }
    }

    @Test void mapsUnavailablePreviewProjectorDriftAndPostAuthorizationCorruptionToUnavailable() {
        BulkEvaluationSnapshot unavailable = evaluation(1, -1);
        persist(unavailable, BulkPreviewProjection.unavailable(unavailable));
        var authorization = new MutableAuthorizationProvider();
        var reader = reader(authorization, new MutableClock(NOW), projectionProvider());
        assertThat(reader.readProposal("reader", unavailable.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);

        BulkEvaluationSnapshot corrupt = evaluation(401, -1);
        persist(corrupt, projection(corrupt));
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_target_preview set decision='BLOCKED' "
                            + "where proposal_id=? and ordinal=400",
                    corrupt.proposal().id());
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        }
        assertThat(reader.readProposal("reader", corrupt.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);

        BulkEvaluationSnapshot coherentMismatch = evaluation(401, 400);
        assertThat(coherentMismatch.targets().get(400).eligibility().orElseThrow().decision())
                .isEqualTo(BulkTargetEligibility.Decision.BLOCKED);
        persist(coherentMismatch, projection(coherentMismatch));
        replaceLastBlockedItemWithIntegrityValidExecutable(coherentMismatch);
        var coherentPage = new BulkPreviewPageReader(infrastructure).read(
                CONTEXT, coherentMismatch.proposal().id(), 399, 401, 1);
        assertThat(coherentPage.kind()).isEqualTo(BulkPreviewPageReader.Kind.COMPLETE);
        assertThat(coherentPage.items()).singleElement().satisfies(item -> {
            assertThat(item.ordinal()).isEqualTo(400);
            assertThat(item.decision()).isEqualTo(BulkTargetEligibility.Decision.EXECUTABLE);
        });
        assertThat(reader.readProposal("reader", coherentMismatch.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);

        BulkEvaluationSnapshot healthy = evaluation(1, -1);
        persist(healthy, projection(healthy));
        var emptyProjection = new FixedProjectionProvider(
                CONTEXT.resourceKey(), CONTEXT.operationRef().operationId()) {
            @Override public JsonNode projectRedactedIntent(BulkEvaluationSnapshot ignored) {
                return JsonNodeFactory.instance.objectNode();
            }
        };
        assertThat(reader(authorization, new MutableClock(NOW), emptyProjection)
                .readProposal("reader", healthy.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);

        var mutableProjection = new MutableProjectionProvider();
        var driftReader = reader(authorization, new MutableClock(NOW), mutableProjection);
        mutableProjection.revision = "proposal-intent/2";
        assertThat(driftReader.readProposal("reader", healthy.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);
    }

    @Test void treatsUpgradedLegacyEvaluationAsUnavailableOnlyAfterFullAuthorization() {
        sql.execute("drop schema if exists praxis_bulk cascade");
        Flyway.configure().dataSource(owner).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target("2").load().migrate();
        BulkEvaluationSnapshot evaluation = evaluation(1, -1);
        BulkPostgresTestSupport.insertLegacyInput(sql, evaluation);
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(17);
        var authorization = new MutableAuthorizationProvider();

        assertThat(reader(authorization, new MutableClock(NOW), projectionProvider())
                .readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);
        assertThat(authorization.authorizeCalls).isOne();
        authorization.scoped = BulkReadAuthorizationProvider.ScopeDecision.denied();
        assertThat(reader(authorization, new MutableClock(NOW), projectionProvider())
                .readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);
    }

    @Test void oneRepeatableReadSnapshotSurvivesConcurrentRealExpiryAndNextReadIsAbsent() {
        BulkEvaluationSnapshot evaluation = evaluation(1, -1);
        persist(evaluation, projection(evaluation));
        var authorization = new MutableAuthorizationProvider();
        try {
            authorization.onAuthorize = () -> {
                sql.execute("grant praxis_bulk_retention_executor to postgres");
                try {
                    assertThat(expireOnIndependentThread(evaluation.proposal().id())).isTrue();
                } finally {
                    sql.execute("revoke praxis_bulk_retention_executor from postgres");
                }
            };
            var reader = reader(authorization, new MutableClock(NOW), projectionProvider());
            assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.COMPLETE);
            authorization.onAuthorize = () -> { };
            assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);
        } finally {
            if (Boolean.TRUE.equals(sql.queryForObject(
                    "select pg_has_role('postgres', 'praxis_bulk_retention_executor', 'member')",
                    Boolean.class)))
                sql.execute("revoke praxis_bulk_retention_executor from postgres");
        }
    }

    @Test void persistedGrantRevocationUsesTheSamePhysicalSnapshotForBothAuthorizationPhases() {
        BulkEvaluationSnapshot evaluation = evaluation(1, -1);
        persist(evaluation, projection(evaluation));
        sql.execute("create table public.rs1_test_grant(subject_id text primary key, allowed boolean not null)");
        sql.update("insert into public.rs1_test_grant values ('reader', true)");
        sql.execute("grant select on public.rs1_test_grant to bulk_runtime_test");
        var authorization = new DatabaseAuthorizationProvider();
        authorization.afterGlobal = () -> sql.update(
                "update public.rs1_test_grant set allowed=false where subject_id='reader'");
        var reader = new BulkAuthorizedProposalReader(infrastructure, CONTEXT.resourceKey(),
                authorization, projectionProvider(), new MutableClock(NOW));

        assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.COMPLETE);
        assertThat(new HashSet<>(authorization.backendPids)).hasSize(1);
        assertThat(authorization.transactionModes).containsOnly("repeatable read/on");

        authorization.afterGlobal = () -> { };
        assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.GLOBAL_DENIED);
    }

    @Test void rechecksExpiryAndDeadlineAfterTheReadOnlySnapshotCompletes() {
        BulkEvaluationSnapshot evaluation = evaluation(1, -1);
        persist(evaluation, projection(evaluation));
        var authorization = new MutableAuthorizationProvider();
        var clock = new MutableClock(NOW);
        var reader = reader(authorization, clock, projectionProvider());
        authorization.onAuthorize = () -> TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        clock.set(evaluation.proposal().expiresAt());
                    }
                });

        assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.GONE);

        authorization.onAuthorize = () -> { throw new IllegalStateException("authority unavailable"); };
        assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.NOT_FOUND_OR_DENIED);

        clock.set(NOW);
        authorization.onAuthorize = () -> TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        pause(Duration.ofMillis(3_100));
                    }
                });
        assertThat(reader.readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);
    }

    @Test void rejectsAValidProjectionWhoseConservativeAggregateExceedsTwentyMiB() {
        BulkEvaluationSnapshot evaluation = heavyEvaluation(2_350, 16);
        BulkPreviewProjection projection = heavyProjection(evaluation, 16);
        persist(evaluation, projection);
        Long persistedBytes = sql.queryForObject("""
                select sum(octet_length(diagnostics))
                  from praxis_bulk.praxis_bulk_target_preview where proposal_id=?
                """, Long.class, evaluation.proposal().id());
        assertThat(persistedBytes).isGreaterThan(20L * 1024 * 1024);

        assertThat(reader(new MutableAuthorizationProvider(), new MutableClock(NOW), projectionProvider())
                .readProposal("reader", evaluation.proposal().id()).state())
                .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);
    }

    @Test void rejectsMalformedOrMismatchedServerBindingsWithoutOpeningStorage() {
        var authorization = new MutableAuthorizationProvider();
        assertThatThrownBy(() -> new BulkAuthorizedProposalReader(infrastructure,
                CONTEXT.resourceKey() + "\ud800", authorization, projectionProvider()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkAuthorizedProposalReader(infrastructure,
                CONTEXT.resourceKey(), authorization,
                new FixedProjectionProvider("other", CONTEXT.operationRef().operationId())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkAuthorizedProposalReader(infrastructure,
                CONTEXT.resourceKey(), authorization,
                new FixedProjectionProvider(CONTEXT.resourceKey(), "other-operation")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BulkAuthorizedProposalReader reader(MutableAuthorizationProvider authorization,
            Clock clock, BulkProposalProjectionProvider projection) {
        return new BulkAuthorizedProposalReader(
                infrastructure, CONTEXT.resourceKey(), authorization, projection, clock);
    }

    private void persist(BulkEvaluationSnapshot evaluation, BulkPreviewProjection projection) {
        tx.executeWithoutResult(status -> store.insertEvaluated(evaluation, projection));
    }

    private boolean expireOnIndependentThread(UUID proposalId) {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> {
                var ownerTx = new TransactionTemplate(new DataSourceTransactionManager(owner));
                ownerTx.setTimeout(5);
                return ownerTx.execute(status -> {
                    var ownerSql = new JdbcTemplate(owner);
                    ownerSql.setQueryTimeout(5);
                    ownerSql.execute("set local role praxis_bulk_retention_executor");
                    return ownerSql.queryForObject("select praxis_bulk.expire_unconsumed_proposal(?)",
                            Boolean.class, proposalId);
                });
            });
            return Boolean.TRUE.equals(result.get(6, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while expiring the test proposal");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Could not expire the test proposal");
        }
    }

    private static BulkEvaluationSnapshot evaluation(int count, int... blockedOrdinals) {
        return evaluation(CONTEXT, count, blockedOrdinals);
    }

    private static BulkEvaluationSnapshot evaluation(BulkFingerprintContext context, int count,
            int... blockedOrdinals) {
        boolean[] blocked = new boolean[count];
        for (int ordinal : blockedOrdinals) {
            if (ordinal >= 0) blocked[ordinal] = true;
        }
        List<BulkTarget<String>> selected = new ArrayList<>(count);
        List<BulkTargetEvidence<?>> evidence = new ArrayList<>(count);
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            var target = new BulkTarget<>("target-%04d".formatted(ordinal), "v1");
            selected.add(target);
            BulkTargetEligibility eligibility = blocked[ordinal]
                    ? BulkTargetEligibility.blocked(List.of(new ResourceCommandMessage(
                            ResourceCommandErrorCategory.VALIDATION, "STATE_NOT_ALLOWED",
                            "private payroll value", "target-%04d".formatted(ordinal),
                            Map.of())))
                    : BulkTargetEligibility.executable();
            evidence.add(new BulkTargetEvidence<>(target, "observed", empty, empty, eligibility));
        }
        var request = new BulkCommandEvaluationRequest<JsonNode, String, JsonNode>(
                BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, selected, null, null),
                JsonNodeFactory.instance.objectNode().put("private", "protected"));
        var stored = proposal(BulkIntentSnapshot.command(context, BulkIdentityCodecs.strings(),
                request, JsonNode::deepCopy, JsonNode::deepCopy));
        return new BulkEvaluationSnapshot(stored, stored.createdAt().plusSeconds(1),
                evidence, governance());
    }

    private void replaceLastBlockedItemWithIntegrityValidExecutable(BulkEvaluationSnapshot evaluation) {
        UUID proposalId = evaluation.proposal().id();
        Map<String, Object> row = sql.queryForMap("""
                select s.evaluation_fingerprint, s.projector_revision, s.target_count,
                       s.public_allowlist, s.projection_digest,
                       m.wire_identity, m.wire_identity_digest, m.expected_version, m.target_digest
                  from praxis_bulk.praxis_bulk_preview_state s
                  join praxis_bulk.praxis_bulk_target_manifest m on m.proposal_id=s.proposal_id
                 where s.proposal_id=? and m.ordinal=400
                """, proposalId);
        byte[] diagnostics = BulkPreviewStorage.diagnostics(List.of());
        byte[] context = BulkPreviewItemIntegrity.contextDigest(proposalId,
                (String) row.get("evaluation_fingerprint"), (String) row.get("projector_revision"),
                (Integer) row.get("target_count"), (byte[]) row.get("public_allowlist"),
                (String) row.get("projection_digest"));
        String digest = BulkPreviewItemIntegrity.itemDigest(context, 400,
                (byte[]) row.get("wire_identity"), (String) row.get("wire_identity_digest"),
                (byte[]) row.get("expected_version"), (String) row.get("target_digest"),
                "EXECUTABLE", diagnostics);
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity disable trigger user");
        try {
            sql.update("""
                    update praxis_bulk.praxis_bulk_target_preview
                       set decision='EXECUTABLE', diagnostics=? where proposal_id=? and ordinal=400
                    """, diagnostics, proposalId);
            sql.update("""
                    update praxis_bulk.praxis_bulk_preview_item_integrity
                       set item_digest=? where proposal_id=? and ordinal=400
                    """, digest, proposalId);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity enable trigger user");
            sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        }
    }

    private static BulkPreviewProjection projection(BulkEvaluationSnapshot evaluation) {
        boolean blocked = evaluation.targets().stream()
                .anyMatch(target -> !target.eligibility().orElseThrow().isExecutable());
        List<BulkPreviewProjection.PublicDiagnostic> allowlist = blocked
                ? List.of(new BulkPreviewProjection.PublicDiagnostic(
                        ResourceCommandErrorCategory.VALIDATION, "STATE_NOT_ALLOWED",
                        "Public state restriction"))
                : List.of();
        return new BulkPreviewProjection(evaluation, "safe-preview/1", allowlist);
    }

    private static BulkEvaluationSnapshot heavyEvaluation(int count, int diagnosticCount) {
        List<ResourceCommandMessage> privateDiagnostics = new ArrayList<>(diagnosticCount);
        for (int index = 0; index < diagnosticCount; index++)
            privateDiagnostics.add(new ResourceCommandMessage(ResourceCommandErrorCategory.VALIDATION,
                    "SAFE_" + index, "x", null, Map.of()));
        List<BulkTarget<String>> selected = new ArrayList<>(count);
        List<BulkTargetEvidence<?>> evidence = new ArrayList<>(count);
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            var target = new BulkTarget<>("heavy-%04d".formatted(ordinal), "v1");
            selected.add(target);
            evidence.add(new BulkTargetEvidence<>(target, "observed", empty, empty,
                    BulkTargetEligibility.blocked(privateDiagnostics)));
        }
        var request = new BulkCommandEvaluationRequest<JsonNode, String, JsonNode>(
                BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, selected, null, null),
                JsonNodeFactory.instance.objectNode().put("private", "protected"));
        var stored = proposal(BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(),
                request, JsonNode::deepCopy, JsonNode::deepCopy));
        return new BulkEvaluationSnapshot(stored, stored.createdAt().plusSeconds(1),
                evidence, governance());
    }

    private static BulkPreviewProjection heavyProjection(BulkEvaluationSnapshot evaluation,
            int diagnosticCount) {
        List<BulkPreviewProjection.PublicDiagnostic> allowlist = new ArrayList<>(diagnosticCount);
        for (int index = 0; index < diagnosticCount; index++)
            allowlist.add(new BulkPreviewProjection.PublicDiagnostic(
                    ResourceCommandErrorCategory.VALIDATION, "SAFE_" + index, "P".repeat(512)));
        return new BulkPreviewProjection(evaluation, "safe-preview/large", allowlist);
    }

    private static BulkProposalProjectionProvider projectionProvider() {
        return new FixedProjectionProvider(CONTEXT.resourceKey(), CONTEXT.operationRef().operationId());
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while testing the bounded read deadline");
        }
    }

    private static class FixedProjectionProvider implements BulkProposalProjectionProvider {
        private final String resource;
        private final String operation;
        private FixedProjectionProvider(String resource, String operation) {
            this.resource = resource;
            this.operation = operation;
        }
        @Override public String resourceKey() { return resource; }
        @Override public String confirmationOperationId() { return operation; }
        @Override public String projectionRevision() { return "proposal-intent/1"; }
        @Override public JsonNode projectRedactedIntent(BulkEvaluationSnapshot evaluation) {
            return JsonNodeFactory.instance.objectNode().put("projection", "safe")
                    .put("targetCount", evaluation.targets().size());
        }
    }

    private static final class MutableProjectionProvider extends FixedProjectionProvider {
        private String revision = "proposal-intent/1";
        private MutableProjectionProvider() {
            super(CONTEXT.resourceKey(), CONTEXT.operationRef().operationId());
        }
        @Override public String projectionRevision() { return revision; }
    }

    private final class DatabaseAuthorizationProvider implements BulkReadAuthorizationProvider {
        private final List<Integer> backendPids = new ArrayList<>();
        private final List<String> transactionModes = new ArrayList<>();
        private Runnable afterGlobal = () -> { };

        @Override public String confirmationOperationId() { return CONTEXT.operationRef().operationId(); }
        @Override public BulkExecutionInfrastructure executionInfrastructure() { return infrastructure; }
        @Override public GlobalDecision preAuthorize(Context context, Duration remainingBudget) {
            boolean allowed = allowed(context.requesterSubjectId());
            afterGlobal.run();
            return allowed ? GlobalDecision.ALLOWED : GlobalDecision.DENIED;
        }
        @Override public ScopeDecision authorize(Context context, String creatorSubjectId,
                List<Target> fullTargetSet, Duration remainingBudget) {
            return allowed(context.requesterSubjectId())
                    ? ScopeDecision.authorized(new byte[32]) : ScopeDecision.denied();
        }

        private boolean allowed(String subjectId) {
            Connection connection = DataSourceUtils.getConnection(runtime);
            try {
                if (!DataSourceUtils.isConnectionTransactional(
                        DataSourceUtils.getTargetConnection(connection), runtime))
                    throw new IllegalStateException("Authorization must use the bound operational connection");
                try (var statement = connection.prepareStatement("""
                        select allowed, pg_backend_pid(), current_setting('transaction_isolation'),
                               current_setting('transaction_read_only')
                          from public.rs1_test_grant where subject_id=?
                        """)) {
                    statement.setString(1, subjectId);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) return false;
                        boolean result = rows.getBoolean(1);
                        backendPids.add(rows.getInt(2));
                        transactionModes.add(rows.getString(3) + "/" + rows.getString(4));
                        if (rows.next()) throw new IllegalStateException("Duplicate authorization row");
                        return result;
                    }
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Authorization query failed");
            } finally {
                DataSourceUtils.releaseConnection(connection, runtime);
            }
        }
    }

    private final class MutableAuthorizationProvider implements BulkReadAuthorizationProvider {
        private GlobalDecision global = GlobalDecision.ALLOWED;
        private ScopeDecision scoped = ScopeDecision.authorized(new byte[32]);
        private int authorizeCalls;
        private final List<Integer> targetCounts = new ArrayList<>();
        private Runnable onAuthorize = () -> { };
        @Override public String confirmationOperationId() { return CONTEXT.operationRef().operationId(); }
        @Override public BulkExecutionInfrastructure executionInfrastructure() { return infrastructure; }
        @Override public GlobalDecision preAuthorize(Context context, Duration remainingBudget) {
            return global;
        }
        @Override public ScopeDecision authorize(Context context, String creatorSubjectId,
                List<Target> fullTargetSet, Duration remainingBudget) {
            authorizeCalls++;
            targetCounts.add(fullTargetSet.size());
            onAuthorize.run();
            return scoped;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        void set(Instant value) { this.value = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
