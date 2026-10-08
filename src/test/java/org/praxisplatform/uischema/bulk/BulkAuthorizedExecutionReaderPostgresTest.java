package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class BulkAuthorizedExecutionReaderPostgresTest {
    private static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext(
            "tenant-a:production:payroll", "creator-a", "employees",
            new CanonicalOperationRef("admin", "employee-bulk-approve",
                    "/employees/bulk/approve", "POST"),
            "schema-r1", ActionCollectionAtomicity.PER_ITEM);

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

    @AfterAll void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach void reset() {
        admin.execute("drop schema if exists praxis_bulk cascade");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(20);
        BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(),
                CONTEXT.operationRef().operationId());
        admin.execute("create table if not exists bulk_g3c_authority "
                + "(subject_id text primary key, allowed boolean not null)");
        admin.execute("grant select on bulk_g3c_authority to bulk_runtime_test");
        admin.execute("truncate bulk_g3c_authority");
        admin.update("insert into bulk_g3c_authority(subject_id,allowed) values ('delegate-a',true)");
    }

    @Test void projectsRunningReplayStoppedAndCompletedWithoutProtectedDetails() {
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider();
        BulkAuthorizedExecutionReader reader = reader(provider);

        BulkExecutionReservation running = reserve(persist(evaluation("running", 2)), "read-running");
        BulkAuthorizedExecutionReader.Observation first = reader.readExecution(
                "delegate-a", running.executionId());
        assertThat(first.state()).isEqualTo(BulkAuthorizedExecutionReader.State.COMPLETE);
        assertThat(first.execution().status()).isEqualTo(BulkExecutionStatus.RUNNING);
        assertThat(first.execution().totals().pending()).isEqualTo(2);
        assertThat(first.execution().diagnostics()).isEmpty();
        assertThat(provider.targetCounts).containsExactly(2);
        Instant createdAt = first.execution().createdAt();

        BulkUnitExecutionResult confirmed = executions.executeUnit(running.control(), 0,
                ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed());
        BulkUnitExecutionResult replay = executions.executeUnit(confirmed.control(), 0,
                ignored -> { throw new AssertionError("replay must not admit again"); },
                ignored -> { throw new AssertionError("replay must not mutate again"); });
        assertThat(replay.replayed()).isTrue();
        BulkExecution afterReplay = reader.readExecution("delegate-a", running.executionId()).execution();
        assertThat(afterReplay.createdAt()).isEqualTo(createdAt);
        assertThat(afterReplay.totals().confirmed()).isOne();
        assertThat(afterReplay.totals().pending()).isOne();

        BulkUnitExecutionResult completed = executions.executeUnit(replay.control(), 1,
                ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.unchanged());
        assertThat(completed.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        BulkExecution complete = reader.readExecution("delegate-a", running.executionId()).execution();
        assertThat(complete.status()).isEqualTo(BulkExecutionStatus.COMPLETED);
        assertThat(complete.totals().confirmed()).isOne();
        assertThat(complete.totals().unchanged()).isOne();
        assertThat(complete.totals().pending()).isZero();

        BulkExecutionReservation stopping = reserve(persist(evaluation("stopped", 2)), "read-stopped");
        executions.executeUnit(stopping.control(), 0,
                ignored -> BulkUnitAdmission.stop(BulkUnitReasonCode.AUTHORIZATION_REVOKED),
                ignored -> { throw new AssertionError("stopped admission must not mutate"); });
        BulkExecution stopped = reader.readExecution("delegate-a", stopping.executionId()).execution();
        assertThat(stopped.status()).isEqualTo(BulkExecutionStatus.STOPPED);
        assertThat(stopped.totals().notProcessed()).isEqualTo(2);
        assertThat(stopped.diagnostics()).singleElement().satisfies(message -> {
            assertThat(message.code()).isEqualTo("BULK_EXECUTION_STOPPED");
            assertThat(message.target()).isNull();
            assertThat(message.metadata()).isEmpty();
            assertThat(message.toString()).doesNotContain("creator-a", "AUTHORIZATION_REVOKED");
        });
        assertThat(stopped.toString()).doesNotContain("creator-a", "employee-bulk-approve");
    }

    @Test void normalizesCorrelationAndProtectedCorruptionBeforeFullAuthorization() {
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider();
        BulkAuthorizedExecutionReader reader = reader(provider);
        BulkEvaluationSnapshot first = persist(evaluation("first", 2));
        BulkExecutionReservation reservation = reserve(first, "read-correlation");
        BulkEvaluationSnapshot other = persist(evaluation("other", 1));

        // Fixture-owner corruption bypasses both the immutable-binding trigger and its FK so the
        // reader must reject the mismatched protected correlation before invoking authorization.
        admin.execute("alter table praxis_bulk.praxis_bulk_execution disable trigger all");
        try {
            assertThat(admin.update("update praxis_bulk.praxis_bulk_execution set proposal_id=? "
                    + "where execution_id=?", other.proposal().id(), reservation.executionId()))
                    .isEqualTo(1);
        } finally {
            admin.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger all");
        }
        assertThat(reader.readExecution("delegate-a", reservation.executionId()).state())
                .isEqualTo(BulkAuthorizedExecutionReader.State.NOT_FOUND_OR_DENIED);
        assertThat(provider.authorizeCalls).isZero();
        assertThat(reader.readExecution("delegate-a", UUID.randomUUID()).state())
                .isEqualTo(BulkAuthorizedExecutionReader.State.NOT_FOUND_OR_DENIED);

        reset();
        provider = new MutableAuthorizationProvider();
        reader = reader(provider);
        BulkEvaluationSnapshot corrupt = persist(evaluation("corrupt", 2));
        reservation = reserve(corrupt, "read-corrupt-before");
        admin.execute("alter table praxis_bulk.praxis_bulk_evaluation "
                + "disable trigger praxis_bulk_evaluation_reject_update");
        try {
            assertThat(admin.update("update praxis_bulk.praxis_bulk_evaluation set payload=decode('00','hex') "
                    + "where proposal_id=?", corrupt.proposal().id())).isEqualTo(1);
        } finally {
            admin.execute("alter table praxis_bulk.praxis_bulk_evaluation "
                    + "enable trigger praxis_bulk_evaluation_reject_update");
        }
        assertThat(reader.readExecution("delegate-a", reservation.executionId()).state())
                .isEqualTo(BulkAuthorizedExecutionReader.State.NOT_FOUND_OR_DENIED);
        assertThat(provider.authorizeCalls).isZero();
    }

    @Test void corruptionAfterFullAuthorizationIsUnavailableAndGlobalGatesOpenNoProtectedRead() {
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider();
        BulkAuthorizedExecutionReader reader = reader(provider);
        BulkEvaluationSnapshot evaluation = persist(evaluation("corrupt-summary", 2));
        BulkExecutionReservation reservation = reserve(evaluation, "read-corrupt-summary");

        admin.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                + "disable trigger praxis_bulk_target_manifest_immutable");
        try {
            assertThat(admin.update("update praxis_bulk.praxis_bulk_target_manifest "
                    + "set target_digest=? where proposal_id=? and ordinal=0",
                    "sha256:" + "f".repeat(64), evaluation.proposal().id())).isEqualTo(1);
        } finally {
            admin.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                    + "enable trigger praxis_bulk_target_manifest_immutable");
        }
        assertThat(reader.readExecution("delegate-a", reservation.executionId()).state())
                .isEqualTo(BulkAuthorizedExecutionReader.State.UNAVAILABLE);
        assertThat(provider.authorizeCalls).isOne();

        provider.global = BulkReadAuthorizationProvider.GlobalDecision.DENIED;
        int authorizationCalls = provider.authorizeCalls;
        assertThat(reader.readExecution("delegate-a", reservation.executionId()).state())
                .isEqualTo(BulkAuthorizedExecutionReader.State.GLOBAL_DENIED);
        assertThat(provider.authorizeCalls).isEqualTo(authorizationCalls);
        provider.global = BulkReadAuthorizationProvider.GlobalDecision.UNAVAILABLE;
        assertThat(reader.readExecution("delegate-a", reservation.executionId()).state())
                .isEqualTo(BulkAuthorizedExecutionReader.State.GLOBAL_UNAVAILABLE);

        provider.global = BulkReadAuthorizationProvider.GlobalDecision.ALLOWED;
        provider.state = BulkReadAuthorizationProvider.State.DENIED_OR_REDUCED;
        assertHidden(reader.readExecution("delegate-a", reservation.executionId()));
        provider.state = BulkReadAuthorizationProvider.State.AUTHORITY_UNAVAILABLE;
        assertHidden(reader.readExecution("delegate-a", reservation.executionId()));
    }

    @Test void crossResourceOperationAndNamespaceAreIndistinguishableFromAbsence() {
        BulkFingerprintContext otherRoute = context(CONTEXT.namespaceId(), "payroll-adjustments",
                "payroll-adjustment-confirm");
        BulkPostgresTestSupport.ready(owner, otherRoute.namespaceId(),
                otherRoute.operationRef().operationId());
        BulkEvaluationSnapshot routed = persist(evaluation(otherRoute, "other-route", 1));
        BulkExecutionReservation routedReservation = executions.reserve(otherRoute,
                routed.proposal().id(), "other-route", "owner-a", "structural-r1",
                Instant.now().plusSeconds(120));
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider();
        assertHidden(reader(provider).readExecution("delegate-a", routedReservation.executionId()));
        assertThat(provider.authorizeCalls).isZero();

        String otherNamespace = "tenant-b:production:payroll";
        assertThat(BulkPostgresTestSupport.migrate(owner, Map.of(
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                otherNamespace, BulkPostgresTestSupport.DEPLOYMENT_ID))).isZero();
        BulkPostgresTestSupport.ready(owner, otherNamespace, CONTEXT.operationRef().operationId());
        BulkFingerprintContext otherScope = context(otherNamespace, CONTEXT.resourceKey(),
                CONTEXT.operationRef().operationId());
        BulkExecutionInfrastructure otherInfrastructure = new BulkExecutionInfrastructure(runtime,
                manager, otherNamespace, BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration());
        JdbcBulkProposalStore otherStore = new JdbcBulkProposalStore(otherInfrastructure);
        JdbcBulkDurableExecution otherExecutions = new JdbcBulkDurableExecution(otherInfrastructure);
        BulkEvaluationSnapshot otherEvaluation = evaluation(otherScope, "other-namespace", 1);
        tx.executeWithoutResult(status -> otherStore.insertEvaluated(
                otherEvaluation, BulkEvaluationSnapshotTest.preview(otherEvaluation)));
        BulkExecutionReservation otherReservation = otherExecutions.reserve(otherScope,
                otherEvaluation.proposal().id(), "other-namespace", "owner-a", "structural-r1",
                Instant.now().plusSeconds(120));
        assertHidden(reader(provider).readExecution("delegate-a", otherReservation.executionId()));
        assertThat(provider.authorizeCalls).isZero();
    }

    @Test void authorizationJoinsTheSamePhysicalSnapshotAcrossExternalRevocation() throws Exception {
        CountDownLatch observedPreAuthorization = new CountDownLatch(1);
        CountDownLatch continueAuthorization = new CountDownLatch(1);
        DatabaseAuthorizationProvider provider = new DatabaseAuthorizationProvider(
                observedPreAuthorization, continueAuthorization);
        BulkExecutionReservation reservation = reserve(persist(evaluation("mvcc-grant", 2)),
                "read-mvcc-grant");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldSnapshot = executor.submit(() -> reader(provider).readExecution(
                    "delegate-a", reservation.executionId()));
            try {
                assertThat(observedPreAuthorization.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(admin.update("update bulk_g3c_authority set allowed=false "
                        + "where subject_id='delegate-a'")).isEqualTo(1);
                continueAuthorization.countDown();
                assertThat(oldSnapshot.get(5, TimeUnit.SECONDS).state())
                        .isEqualTo(BulkAuthorizedExecutionReader.State.COMPLETE);
            } finally {
                continueAuthorization.countDown();
            }
        }
        assertThat(provider.backendPids).hasSize(2)
                .allMatch(pid -> pid.equals(provider.backendPids.getFirst()));
        assertThat(provider.physicalSnapshots).containsOnly("repeatable read:on");
        BulkAuthorizedExecutionReader.Observation revoked = reader(
                new DatabaseAuthorizationProvider(null, null)).readExecution(
                        "delegate-a", reservation.executionId());
        assertThat(revoked.state()).isEqualTo(BulkAuthorizedExecutionReader.State.GLOBAL_DENIED);
        assertThat(revoked.execution()).isNull();
    }

    @Test void pendingAckAndReconciliationNeverInventConfirmedOrNotProcessedOutcomes() {
        BulkExecutionReservation pending = reserve(persist(evaluation("pending-ack", 2)),
                "read-pending-ack");
        BulkCommitFaultDataSource faults = new BulkCommitFaultDataSource(runtime);
        JdbcBulkDurableExecution faultKernel = new JdbcBulkDurableExecution(
                new BulkExecutionInfrastructure(faults, new DataSourceTransactionManager(faults),
                        CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                        BulkPostgresTestSupport.testRoleConfiguration()));
        assertThatThrownBy(() -> faultKernel.executeUnit(pending.control(), 0,
                ignored -> BulkUnitAdmission.admit(), ignored -> {
                    faults.arm(Thread.currentThread(),
                            BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
                    return BulkUnitMutationResult.confirmed();
                })).isInstanceOf(BulkDurableExecutionException.class);
        assertThat(admin.queryForObject("select status from praxis_bulk.praxis_bulk_execution "
                + "where execution_id=?", String.class, pending.executionId()))
                .isEqualTo("UNIT_COMMITTED_PENDING_ACK");
        BulkExecution pendingProjection = reader(new MutableAuthorizationProvider())
                .readExecution("delegate-a", pending.executionId()).execution();
        assertThat(pendingProjection.status()).isEqualTo(BulkExecutionStatus.RUNNING);
        assertThat(pendingProjection.totals().confirmed()).isZero();
        assertThat(pendingProjection.totals().notProcessed()).isZero();
        assertThat(pendingProjection.totals().pending()).isEqualTo(2);

        BulkExecutionReservation reconciling = reserve(persist(evaluation("reconciling", 2)),
                "read-reconciling");
        admin.execute("alter table praxis_bulk.praxis_bulk_execution disable trigger all");
        try {
            assertThat(admin.update("update praxis_bulk.praxis_bulk_execution "
                    + "set status='RECONCILIATION_REQUIRED',updated_at=clock_timestamp() "
                    + "where execution_id=?", reconciling.executionId())).isEqualTo(1);
        } finally {
            admin.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger all");
        }
        BulkExecution reconciliation = reader(new MutableAuthorizationProvider())
                .readExecution("delegate-a", reconciling.executionId()).execution();
        assertThat(reconciliation.status()).isEqualTo(BulkExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(reconciliation.totals().unknown()).isEqualTo(2);
        assertThat(reconciliation.totals().confirmed()).isZero();
        assertThat(reconciliation.totals().notProcessed()).isZero();
        assertThat(reconciliation.diagnostics()).isEmpty();
    }

    @Test void creatorSeesGoneDelegateSeesNotFoundAndAnOlderSnapshotSurvivesRealPurge() throws Exception {
        BulkEvaluationSnapshot evaluation = persist(evaluation("purge", 2));
        BulkExecutionReservation reservation = reserve(evaluation, "read-purge");
        executions.requestCancel(CONTEXT, reservation.executionId());
        ageTerminalForRetention(reservation.executionId());

        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MutableAuthorizationProvider paused = new MutableAuthorizationProvider();
        paused.onAuthorize = () -> {
            authorized.countDown();
            await(release);
        };
        BulkAuthorizedExecutionReader reader = reader(paused);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldSnapshot = executor.submit(() ->
                    reader.readExecution(CONTEXT.subjectId(), reservation.executionId()));
            assertThat(authorized.await(5, TimeUnit.SECONDS)).isTrue();
            purge(reservation.executionId());
            release.countDown();
            BulkAuthorizedExecutionReader.Observation retained = oldSnapshot.get(5, TimeUnit.SECONDS);
            assertThat(retained.state()).isEqualTo(BulkAuthorizedExecutionReader.State.COMPLETE);
            assertThat(retained.execution().status()).isEqualTo(BulkExecutionStatus.CANCELLED);
        } finally {
            release.countDown();
        }

        assertThat(reader(new MutableAuthorizationProvider()).readExecution(
                CONTEXT.subjectId(), reservation.executionId()).state())
                .isEqualTo(BulkAuthorizedExecutionReader.State.GONE);
        assertThat(reader(new MutableAuthorizationProvider()).readExecution(
                "delegate-a", reservation.executionId()).state())
                .isEqualTo(BulkAuthorizedExecutionReader.State.NOT_FOUND_OR_DENIED);
    }

    @Test void discardsACompleteProjectionWhenTheAbsoluteBudgetExpiresDuringCompletion() {
        BulkExecutionReservation reservation = reserve(persist(evaluation("late", 1)), "read-late");
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider();
        provider.onAuthorize = () -> TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        try {
                            Thread.sleep(3_050);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                });

        BulkAuthorizedExecutionReader.Observation result = reader(provider).readExecution(
                "delegate-a", reservation.executionId());

        assertThat(result.state()).isEqualTo(BulkAuthorizedExecutionReader.State.UNAVAILABLE);
        assertThat(result.execution()).isNull();
    }

    @Test void constructorAndRequesterRejectMalformedUtf8WithoutAuthorizing() {
        MutableAuthorizationProvider provider = new MutableAuthorizationProvider();
        BulkAuthorizedExecutionReader reader = reader(provider);
        assertThatThrownBy(() -> reader.readExecution("bad\ud800subject", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid UTF-8");
        assertThat(provider.preAuthorizeCalls).isZero();
        assertThatThrownBy(() -> new BulkAuthorizedExecutionReader(infrastructure,
                "bad\u0000resource", provider)).isInstanceOf(IllegalArgumentException.class);
    }

    private BulkAuthorizedExecutionReader reader(BulkReadAuthorizationProvider provider) {
        return new BulkAuthorizedExecutionReader(infrastructure, CONTEXT.resourceKey(), provider);
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

    private static BulkEvaluationSnapshot evaluation(String suffix, int count) {
        return evaluation(CONTEXT, suffix, count);
    }

    private static BulkEvaluationSnapshot evaluation(BulkFingerprintContext context,
            String suffix, int count) {
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
        Instant created = Instant.now().minusSeconds(5);
        BulkIntentSnapshot snapshot = BulkIntentSnapshot.command(context,
                BulkIdentityCodecs.strings(), request, JsonNode::deepCopy, JsonNode::deepCopy);
        BulkStoredProposal proposal = new BulkStoredProposal(UUID.randomUUID(), created,
                created.plusSeconds(600), snapshot,
                BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
        BulkEvaluationGovernance governance = new BulkEvaluationGovernance(
                "test-evaluator-r1", "test-grants-r1", List.of(new BulkPolicyObservation(
                "tenant", "test", "approval_policy", "resource-action-approval",
                "resource:approve", "NEVER_APPLIED", "test-policy-r1",
                created.plusMillis(500))));
        return new BulkEvaluationSnapshot(proposal, created.plusSeconds(1), evidence, governance);
    }

    private void ageTerminalForRetention(UUID executionId) {
        admin.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_guard_terminal");
        admin.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_protect_cancel");
        admin.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_protect_binding");
        admin.execute("alter table praxis_bulk.praxis_bulk_allocation "
                + "disable trigger praxis_bulk_allocation_protect_transition");
        try {
            assertThat(admin.update("""
                    with aged as materialized (select clock_timestamp() - interval '31 days' as terminal)
                    update praxis_bulk.praxis_bulk_execution
                    set created_at=aged.terminal - interval '2 seconds',
                        cancel_requested_at=aged.terminal - interval '1 second',
                        terminal_at=aged.terminal, updated_at=aged.terminal
                    from aged where execution_id=?
                    """, executionId)).isEqualTo(1);
            assertThat(admin.update("""
                    update praxis_bulk.praxis_bulk_allocation a
                    set created_at=e.created_at, released_at=e.terminal_at
                    from praxis_bulk.praxis_bulk_execution e
                    where a.execution_id=e.execution_id and e.execution_id=?
                    """, executionId)).isEqualTo(1);
        } finally {
            admin.execute("alter table praxis_bulk.praxis_bulk_allocation "
                    + "enable trigger praxis_bulk_allocation_protect_transition");
            admin.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_protect_binding");
            admin.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_protect_cancel");
            admin.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_guard_terminal");
        }
    }

    private void purge(UUID executionId) {
        admin.execute("grant praxis_bulk_retention_executor to postgres");
        try {
            TransactionTemplate ownerTx = new TransactionTemplate(new DataSourceTransactionManager(owner));
            Boolean purged = ownerTx.execute(status -> {
                admin.execute("set local role praxis_bulk_retention_executor");
                return admin.queryForObject("select praxis_bulk.purge_terminal_execution(?)",
                        Boolean.class, executionId);
            });
            assertThat(purged).isTrue();
        } finally {
            admin.execute("revoke praxis_bulk_retention_executor from postgres");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static BulkFingerprintContext context(String namespace, String resource,
            String operationId) {
        return new BulkFingerprintContext(namespace, CONTEXT.subjectId(), resource,
                new CanonicalOperationRef("admin", operationId,
                        "/" + resource + "/bulk/confirm", "POST"),
                CONTEXT.schemaRevision(), CONTEXT.atomicity());
    }

    private static void assertHidden(BulkAuthorizedExecutionReader.Observation observation) {
        assertThat(observation.state())
                .isEqualTo(BulkAuthorizedExecutionReader.State.NOT_FOUND_OR_DENIED);
        assertThat(observation.execution()).isNull();
    }

    private final class MutableAuthorizationProvider implements BulkReadAuthorizationProvider {
        private GlobalDecision global = GlobalDecision.ALLOWED;
        private State state = State.AUTHORIZED_ALL;
        private int preAuthorizeCalls;
        private int authorizeCalls;
        private final List<Integer> targetCounts = new ArrayList<>();
        private Runnable onAuthorize = () -> { };

        @Override public String confirmationOperationId() {
            return CONTEXT.operationRef().operationId();
        }
        @Override public BulkExecutionInfrastructure executionInfrastructure() {
            return infrastructure;
        }
        @Override public GlobalDecision preAuthorize(Context context, Duration remainingBudget) {
            preAuthorizeCalls++;
            return global;
        }
        @Override public ScopeDecision authorize(Context context, String creatorSubjectId,
                List<Target> fullTargetSet, Duration remainingBudget) {
            authorizeCalls++;
            targetCounts.add(fullTargetSet.size());
            onAuthorize.run();
            return switch (state) {
                case AUTHORIZED_ALL -> ScopeDecision.authorized(fingerprint(7));
                case DENIED_OR_REDUCED -> ScopeDecision.denied();
                case AUTHORITY_UNAVAILABLE -> ScopeDecision.unavailable();
            };
        }
    }

    /** Proves both authorization phases reuse Metadata's bound physical RR/RO snapshot. */
    private final class DatabaseAuthorizationProvider implements BulkReadAuthorizationProvider {
        private final CountDownLatch observedPreAuthorization;
        private final CountDownLatch continueAuthorization;
        private final List<Integer> backendPids = new ArrayList<>();
        private final List<String> physicalSnapshots = new ArrayList<>();

        private DatabaseAuthorizationProvider(CountDownLatch observedPreAuthorization,
                CountDownLatch continueAuthorization) {
            this.observedPreAuthorization = observedPreAuthorization;
            this.continueAuthorization = continueAuthorization;
        }

        @Override public String confirmationOperationId() {
            return CONTEXT.operationRef().operationId();
        }

        @Override public BulkExecutionInfrastructure executionInfrastructure() {
            return infrastructure;
        }

        @Override public GlobalDecision preAuthorize(Context context, Duration remainingBudget) {
            boolean allowed = readAuthority(context.requesterSubjectId());
            if (observedPreAuthorization != null) {
                observedPreAuthorization.countDown();
                await(continueAuthorization);
            }
            return allowed ? GlobalDecision.ALLOWED : GlobalDecision.DENIED;
        }

        @Override public ScopeDecision authorize(Context context, String creatorSubjectId,
                List<Target> fullTargetSet, Duration remainingBudget) {
            return readAuthority(context.requesterSubjectId())
                    ? ScopeDecision.authorized(fingerprint(11)) : ScopeDecision.denied();
        }

        private boolean readAuthority(String subject) {
            Connection connection = DataSourceUtils.getConnection(runtime);
            try {
                assertThat(DataSourceUtils.isConnectionTransactional(
                        DataSourceUtils.getTargetConnection(connection), runtime)).isTrue();
                try (var statement = connection.prepareStatement("""
                        select allowed,pg_backend_pid(),current_setting('transaction_isolation'),
                               current_setting('transaction_read_only')
                        from bulk_g3c_authority where subject_id=?
                        """)) {
                    statement.setString(1, subject);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) return false;
                        boolean allowed = rows.getBoolean(1);
                        backendPids.add(rows.getInt(2));
                        physicalSnapshots.add(rows.getString(3) + ":" + rows.getString(4));
                        if (rows.next()) throw new IllegalStateException("duplicate authority row");
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

    private static byte[] fingerprint(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
