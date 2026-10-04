package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.preview;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.CONTEXT;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Timeout;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real operational PostgreSQL checks for callback capture in the proposal's owning transaction. */
class JdbcBulkProposalCapturePostgresTest {
    private static EmbeddedPostgres postgres;
    private static DataSource owner;
    private static DataSource runtime;
    private static JdbcTemplate sql;
    private static JdbcBulkProposalStore store;
    private static BulkExecutionInfrastructure infrastructure;
    private static TransactionTemplate repeatable;
    private static TransactionTemplate readCommitted;
    private static TransactionTemplate readOnly;

    @BeforeAll static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        sql = new JdbcTemplate(owner);
        var manager = new DataSourceTransactionManager(runtime);
        infrastructure = new BulkExecutionInfrastructure(runtime, manager, CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration());
        store = new JdbcBulkProposalStore(infrastructure);
        repeatable = transaction(manager, TransactionDefinition.ISOLATION_REPEATABLE_READ, false);
        readCommitted = transaction(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
        readOnly = transaction(manager, TransactionDefinition.ISOLATION_REPEATABLE_READ, true);
    }

    @AfterAll static void stop() throws Exception { if (postgres != null) postgres.close(); }

    @BeforeEach void reset() {
        sql.execute("drop schema if exists praxis_bulk cascade");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(17);
        BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
    }

    @Test void callbackUsesSameWritableRepeatableReadConnectionAndRollsBackAllRows() {
        var input = freshProposal();
        var expected = freshEvaluation(input);
        var callbacks = new AtomicInteger();
        var supplies = new AtomicInteger();
        repeatable.executeWithoutResult(status -> {
            var actual = store.captureAndInsertEvaluated(input, (connection, budget) -> {
                callbacks.incrementAndGet();
                assertThat(sqlValue(connection, "select current_setting('transaction_isolation')"))
                        .isEqualTo("repeatable read");
                assertThat(sqlValue(connection, "select current_setting('transaction_read_only')"))
                        .isEqualTo("off");
                assertThat(connectionPid(connection)).isEqualTo(
                        new JdbcTemplate(runtime).queryForObject("select pg_backend_pid()", Integer.class));
                assertThat(sqlValue(connection, "select txid_current()::text"))
                        .isEqualTo(new JdbcTemplate(runtime).queryForObject("select txid_current()::text", String.class));
                assertThat(budget.get().compareTo(Duration.ZERO)).isPositive();
                assertThat(budget.get().compareTo(Duration.ofSeconds(2))).isLessThanOrEqualTo(0);
                return expected;
            }, BulkEvaluationSnapshotTest::preview,
                    () -> supplies.incrementAndGet() == 1 ? Duration.ofSeconds(5) : Duration.ofSeconds(2));
            assertThat(actual.fingerprint()).isEqualTo(expected.fingerprint());
            assertThat(new JdbcTemplate(runtime).queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_proposal", Integer.class)).isEqualTo(1);
            assertThat(new JdbcTemplate(runtime).queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_evaluation", Integer.class)).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(callbacks.get()).isEqualTo(1);
        assertAllProtectedRowsAbsent();
    }

    @Test void callerDeadlineStartsBeforeTheTransactionAndIsNotRenewed() {
        var input = freshProposal();
        long started = System.nanoTime();
        java.util.function.Supplier<Duration> remaining = () ->
                Duration.ofSeconds(10).minusNanos(System.nanoTime() - started);
        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
        assertThat(Duration.ofSeconds(10).minus(remaining.get()).toMillis()).isGreaterThanOrEqualTo(200);
        repeatable.executeWithoutResult(status -> {
            store.captureAndInsertEvaluated(input, (connection, budget) -> {
                assertThat(budget.get().compareTo(Duration.ZERO)).isPositive();
                assertThat(budget.get()).isLessThan(Duration.ofMillis(9_800));
                return freshEvaluation(input);
            }, BulkEvaluationSnapshotTest::preview, remaining);
            status.setRollbackOnly();
        });
        assertAllProtectedRowsAbsent();
    }

    @Test void perItemUpdateKeepsItsCanonicalItemsShapeAndPersistsFullEvidence() throws Exception {
        var input = freshProposal(BulkSnapshotStorageCodecTest.snapshot(BulkMode.PER_ITEM_UPDATE,
                BulkIdentityCodecs.strings(), "\"101\"", "1.0"));
        var expected = freshEvaluation(input);
        repeatable.executeWithoutResult(status -> store.captureAndInsertEvaluated(input,
                (connection, budget) -> expected, BulkEvaluationSnapshotTest::preview,
                () -> Duration.ofSeconds(10)));
        var recovered = repeatable.execute(status -> store.findEvaluation(CONTEXT, input.id()).orElseThrow());
        assertThat(recovered.fingerprint()).isEqualTo(expected.fingerprint());
        assertThat(recovered.proposal().snapshot().mode()).isEqualTo(BulkMode.PER_ITEM_UPDATE);
        for (String table : List.of("praxis_bulk_proposal", "praxis_bulk_evaluation",
                "praxis_bulk_target_manifest", "praxis_bulk_target_preview", "praxis_bulk_allocation"))
            assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table, Integer.class))
                    .as(table).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_target_manifest m
                  join praxis_bulk.praxis_bulk_evaluation e
                    on e.proposal_id=m.proposal_id and e.evaluation_fingerprint=m.evaluation_fingerprint
                 where m.proposal_id=? and m.ordinal=0 and m.target_count=1
                   and m.expected_version=convert_to('v1','UTF8')
                   and m.wire_identity=convert_to('"101"','UTF8')
                """, Integer.class, input.id())).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_preview_state s
                  join praxis_bulk.praxis_bulk_target_preview p
                    on p.proposal_id=s.proposal_id and p.evaluation_fingerprint=s.evaluation_fingerprint
                 where s.proposal_id=? and s.projection_state='COMPLETE' and s.target_count=1
                   and p.ordinal=0 and p.decision='EXECUTABLE'
                """, Integer.class, input.id())).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_allocation
                 where proposal_id=? and namespace_id=? and kind='PROPOSAL_PENDING' and state='PENDING'
                """, Integer.class, input.id(), CONTEXT.namespaceId())).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity
                 where proposal_id=? and ordinal=0 and digest_version=1
                """, Integer.class, input.id())).isEqualTo(1);
        assertThat(input.snapshot().intent().path("items").size()).isEqualTo(1);
        assertThat(input.snapshot().fingerprint()).isEqualTo(recovered.proposal().snapshot().fingerprint());
        try (var connection = owner.getConnection()) {
            BulkOrdinalManifest.validateOne(connection, recovered);
        }
    }

    @Test @Timeout(45)
    void subjectPendingNineToTenFencesAnOlderRepeatableReadSnapshot() throws Exception {
        pendingQuotaRace(9, false, false);
    }

    @Test @Timeout(90)
    void deploymentPendingNinetyNineToHundredFencesAnOlderRepeatableReadSnapshot() throws Exception {
        pendingQuotaRace(99, true, false);
    }

    @Test @Timeout(45)
    void ledgerDirectlyObservesSerializationFailureAfterOlderSubjectSnapshot() throws Exception {
        pendingQuotaRace(9, false, true);
    }

    private void pendingQuotaRace(int initialCount, boolean distinctSubjects, boolean rawLedger) throws Exception {
        for (int index = 0; index < initialCount; index++) {
            var seed = freshProposalForSubject(distinctSubjects ? "seed-" + index : CONTEXT.subjectId());
            var evaluation = freshEvaluation(seed);
            readCommitted.executeWithoutResult(status -> store.insertEvaluated(evaluation, preview(evaluation)));
        }
        var first = freshProposalForSubject(distinctSubjects ? "winner" : CONTEXT.subjectId());
        var second = freshProposalForSubject(distinctSubjects ? "loser" : CONTEXT.subjectId());
        int before = sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation", Integer.class);
        assertThat(before).isEqualTo(initialCount);
        var deploymentBefore = sql.queryForList("""
                select to_jsonb(b)::text from praxis_bulk.praxis_bulk_deployment_bucket b order by deployment_id
                """, String.class);
        var subjectsBefore = sql.queryForList("""
                select to_jsonb(b)::text from praxis_bulk.praxis_bulk_subject_bucket b
                 order by deployment_id, subject_scope_digest_version, subject_scope_digest
                """, String.class);
        var snapshotFixed = new CountDownLatch(1);
        var allowSecond = new CountDownLatch(1);
        var firstAtCallback = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstPid = new AtomicInteger();
        var secondPid = new AtomicInteger();
        var firstCallbacks = new AtomicInteger();
        var secondCallbacks = new AtomicInteger();
        var rawState = new java.util.concurrent.atomic.AtomicReference<String>();
        var executor = Executors.newFixedThreadPool(2);
        Future<?> firstTask = null;
        Future<Throwable> secondTask = null;
        try {
            secondTask = executor.submit(() -> {
                try {
                    repeatable.executeWithoutResult(status -> {
                        secondPid.set(new JdbcTemplate(runtime).queryForObject("select pg_backend_pid()", Integer.class));
                        // This read fixes B's RR snapshot before A creates the final allocation.
                        new JdbcTemplate(runtime).queryForObject(
                                "select count(*) from praxis_bulk.praxis_bulk_allocation", Integer.class);
                        snapshotFixed.countDown();
                        await(allowSecond);
                        if (rawLedger) {
                            Connection bound = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(runtime);
                            try {
                                BulkQuotaLedger.lockProposal(bound, infrastructure, second, false, true);
                                throw new AssertionError("stale quota snapshot was admitted");
                            } catch (SQLException failure) {
                                rawState.set(failure.getSQLState());
                                throw new IllegalStateException("test-only raw ledger failure", failure);
                            }
                        } else {
                            store.captureAndInsertEvaluated(second, (connection, remaining) -> {
                                secondCallbacks.incrementAndGet(); return freshEvaluation(second);
                            }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(25));
                        }
                    });
                    return null;
                } catch (Throwable failure) { return failure; }
            });
            assertThat(snapshotFixed.await(3, TimeUnit.SECONDS)).isTrue();
            firstTask = executor.submit(() -> repeatable.executeWithoutResult(status -> {
                firstPid.set(new JdbcTemplate(runtime).queryForObject("select pg_backend_pid()", Integer.class));
                store.captureAndInsertEvaluated(first, (connection, remaining) -> {
                    firstCallbacks.incrementAndGet();
                    firstAtCallback.countDown();
                    await(releaseFirst);
                    return freshEvaluation(first);
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(25));
            }));
            assertThat(firstAtCallback.await(5, TimeUnit.SECONDS)).isTrue();
            allowSecond.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean directBlocker = false;
            while (System.nanoTime() < until) {
                directBlocker = Boolean.TRUE.equals(sql.queryForObject("""
                        select a.wait_event_type='Lock' and a.wait_event='transactionid'
                               and ? = any(pg_blocking_pids(a.pid))
                               and ltrim(lower(a.query)) like 'update praxis_bulk.praxis_bulk_deployment_bucket%'
                          from pg_stat_activity a where a.pid=?
                        """, Boolean.class, firstPid.get(), secondPid.get()));
                if (directBlocker) break;
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            assertThat(firstPid.get()).isPositive().isNotEqualTo(secondPid.get());
            assertThat(directBlocker).as("B's actual deployment MVCC touch waits for A").isTrue();
            assertThat(secondCallbacks.get()).isZero();
            releaseFirst.countDown();
            firstTask.get(10, TimeUnit.SECONDS);
            Throwable secondFailure = secondTask.get(10, TimeUnit.SECONDS);
            if (rawLedger) {
                assertThat(rawState.get()).isEqualTo("40001");
                assertThat(secondFailure).isInstanceOf(IllegalStateException.class)
                        .hasCauseInstanceOf(SQLException.class);
            } else {
                assertThat(secondFailure).isInstanceOf(BulkProposalStorageException.class);
                assertThat(((BulkProposalStorageException) secondFailure).reason())
                        .isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
                assertThat(secondFailure).hasNoCause();
                assertThat(secondFailure.getMessage()).doesNotContain("loser", "seed-", "winner");
            }
            assertThat(firstCallbacks.get()).isEqualTo(1);
            assertThat(secondCallbacks.get()).isZero();
            for (String table : List.of("praxis_bulk_proposal", "praxis_bulk_evaluation",
                    "praxis_bulk_target_manifest", "praxis_bulk_preview_state", "praxis_bulk_target_preview",
                    "praxis_bulk_preview_item_integrity", "praxis_bulk_allocation")) {
                assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table,
                        Integer.class)).as(table + " total").isEqualTo(initialCount + 1);
                assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table
                        + " where proposal_id=?", Integer.class, first.id())).as(table + " winner").isEqualTo(1);
                assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table
                        + " where proposal_id=?", Integer.class, second.id())).as(table).isZero();
            }
            assertThat(sql.queryForList("""
                    select to_jsonb(b)::text from praxis_bulk.praxis_bulk_deployment_bucket b order by deployment_id
                    """, String.class)).containsExactlyElementsOf(deploymentBefore);
            var subjectsAfter = sql.queryForList("""
                    select to_jsonb(b)::text from praxis_bulk.praxis_bulk_subject_bucket b
                     order by deployment_id, subject_scope_digest_version, subject_scope_digest
                    """, String.class);
            if (distinctSubjects) {
                String winnerDigest = BulkScopeDigests.subjectQuotaDigest(
                        BulkPostgresTestSupport.DEPLOYMENT_ID, "winner");
                String winnerRow = sql.queryForObject("""
                        select to_jsonb(b)::text from praxis_bulk.praxis_bulk_subject_bucket b
                         where deployment_id=? and subject_scope_digest=?
                        """, String.class, BulkPostgresTestSupport.DEPLOYMENT_ID, winnerDigest);
                assertThat(subjectsAfter).hasSize(subjectsBefore.size() + 1);
                assertThat(subjectsAfter).contains(winnerRow);
                var withoutWinner = new java.util.ArrayList<>(subjectsAfter);
                assertThat(withoutWinner.remove(winnerRow)).isTrue();
                assertThat(withoutWinner).containsExactlyElementsOf(subjectsBefore);
            } else assertThat(subjectsAfter).containsExactlyElementsOf(subjectsBefore);
            var fresh = freshProposalForSubject(distinctSubjects ? "fresh" : CONTEXT.subjectId());
            var freshCallbacks = new AtomicInteger();
            assertThatThrownBy(() -> repeatable.executeWithoutResult(status ->
                    store.captureAndInsertEvaluated(fresh, (connection, remaining) -> {
                        freshCallbacks.incrementAndGet(); return freshEvaluation(fresh);
                    }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10))))
                    .isInstanceOf(BulkProposalStorageException.class)
                    .satisfies(failure -> assertThat(((BulkProposalStorageException) failure).reason())
                            .isEqualTo(BulkProposalStorageException.Reason.CAPACITY));
            assertThat(freshCallbacks.get()).isZero();
            for (String table : List.of("praxis_bulk_proposal", "praxis_bulk_evaluation",
                    "praxis_bulk_target_manifest", "praxis_bulk_preview_state", "praxis_bulk_target_preview",
                    "praxis_bulk_preview_item_integrity", "praxis_bulk_allocation")) {
                assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table
                        + " where proposal_id=?", Integer.class, fresh.id())).as(table + " fresh").isZero();
                assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table,
                        Integer.class)).as(table + " post-capacity").isEqualTo(initialCount + 1);
            }
            assertThat(sql.queryForList("""
                    select to_jsonb(b)::text from praxis_bulk.praxis_bulk_deployment_bucket b order by deployment_id
                    """, String.class)).containsExactlyElementsOf(deploymentBefore);
            assertThat(sql.queryForList("""
                    select to_jsonb(b)::text from praxis_bulk.praxis_bulk_subject_bucket b
                     order by deployment_id, subject_scope_digest_version, subject_scope_digest
                    """, String.class)).containsExactlyElementsOf(subjectsAfter);
        } finally {
            allowSecond.countDown();
            releaseFirst.countDown();
            if (firstTask != null) try { firstTask.get(5, TimeUnit.SECONDS); } catch (Exception ignored) { }
            if (secondTask != null) try { secondTask.get(5, TimeUnit.SECONDS); } catch (Exception ignored) { }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("quota race did not progress"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static BulkStoredProposal freshProposalForSubject(String subject) {
        var context = new BulkFingerprintContext(CONTEXT.namespaceId(), subject, CONTEXT.resourceKey(),
                CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity());
        return freshProposal(BulkSnapshotStorageCodecTest.snapshot(context, BulkMode.DOMAIN_COMMAND,
                BulkIdentityCodecs.strings(), "\"101\"", "1.0"));
    }

    @Test void expirationObservedByPostgresAfterCallbackRollsBackAllStorage() {
        Instant created = Instant.now();
        var input = new BulkStoredProposal(java.util.UUID.randomUUID(), created, created.plusSeconds(1),
                BulkSnapshotStorageCodecTest.proposal().snapshot(), BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
        var original = freshEvaluation(freshProposal());
        var governance = new BulkEvaluationGovernance("test-evaluator-r1", "test-grants-r1", List.of(
                new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                        "resource:approve", "NEVER_APPLIED", "test-policy-r1", input.createdAt())));
        var valid = new BulkEvaluationSnapshot(input, input.createdAt().plusMillis(100),
                original.targets(), governance);
        var entered = new AtomicBoolean();
        var projected = new AtomicBoolean();
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status -> {
            try {
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    entered.set(true);
                    return valid;
                }, evaluation -> {
                    projected.set(true);
                    var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(runtime);
                    try (var query = connection.prepareStatement("select clock_timestamp() >= ?")) {
                        query.setObject(1, java.time.OffsetDateTime.ofInstant(input.expiresAt(), java.time.ZoneOffset.UTC));
                        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                        boolean expired = false;
                        while (System.nanoTime() < until) {
                            try (var rows = query.executeQuery()) {
                                assertThat(rows.next()).isTrue();
                                expired = rows.getBoolean(1);
                            }
                            if (expired) break;
                            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                        }
                        assertThat(expired).isTrue();
                    } catch (SQLException error) { throw new AssertionError(error); }
                    return preview(evaluation);
                }, () -> Duration.ofSeconds(10));
            } catch (BulkProposalStorageException denied) {
                assertThat(denied.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
                assertThat(denied).hasNoCause();
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(entered.get()).isTrue();
        assertThat(projected.get()).isTrue();
        assertAllProtectedRowsAbsent();
    }

    @Test void invalidTransactionNeverInvokesCallback() {
        var input = freshProposal();
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> store.captureAndInsertEvaluated(input, (connection, budget) -> {
            callbacks.incrementAndGet(); return freshEvaluation(input);
        }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> readCommitted.executeWithoutResult(status ->
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    callbacks.incrementAndGet(); return freshEvaluation(input);
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status ->
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    callbacks.incrementAndGet(); return freshEvaluation(input);
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10))))
                .isInstanceOf(RuntimeException.class);
        assertThat(callbacks.get()).isZero();
        assertAllProtectedRowsAbsent();
    }

    @Test void callerSupplierFailureIsSafeAndCaughtOuterTransactionRollsBack() {
        var input = freshProposal();
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status -> {
            try {
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    callbacks.incrementAndGet(); return freshEvaluation(input);
                }, BulkEvaluationSnapshotTest::preview,
                        () -> { throw new IllegalStateException("private caller token 123"); });
            } catch (BulkProposalStorageException safe) {
                assertThat(safe.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
                assertThat(safe).hasNoCause();
                assertThat(safe.getMessage()).doesNotContain("private caller token");
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(callbacks.get()).isZero();
        assertAllProtectedRowsAbsent();
    }

    @Test void finalAllocationSqlFailureRollsBackAllFiveProtectedArtifactsWhenCaught() {
        var input = freshProposal();
        try {
            sql.execute("""
                create function praxis_bulk.reject_capture_allocation() returns trigger language plpgsql as $$
                begin
                  if new.kind='PROPOSAL_PENDING' then
                    raise exception 'test-only final allocation failure' using errcode='57014';
                  end if;
                  return new;
                end $$
                """);
            sql.execute("""
                create trigger reject_capture_allocation before insert on praxis_bulk.praxis_bulk_allocation
                for each row execute function praxis_bulk.reject_capture_allocation()
                """);
            assertThatThrownBy(() -> repeatable.executeWithoutResult(status -> {
                try {
                    store.captureAndInsertEvaluated(input, (connection, budget) -> freshEvaluation(input),
                            BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10));
                } catch (BulkProposalStorageException failure) {
                    assertThat(failure.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
                }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertAllProtectedRowsAbsent();
        } finally {
            try { sql.execute("drop trigger if exists reject_capture_allocation on praxis_bulk.praxis_bulk_allocation"); }
            finally { sql.execute("drop function if exists praxis_bulk.reject_capture_allocation()"); }
        }
    }

    @Test void expiredCallerBudgetInsideParticipationMarksCaughtOuterTransactionRollbackOnly() {
        var input = freshProposal();
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status -> {
            try {
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    callbacks.incrementAndGet(); return freshEvaluation(input);
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ZERO);
            } catch (BulkProposalStorageException denied) {
                assertThat(denied.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(callbacks.get()).isZero();
        assertAllProtectedRowsAbsent();
    }

    @Test void callbackAndProjectionFailuresMakeCaughtOuterTransactionRollbackOnly() {
        var input = freshProposal();
        var expected = freshEvaluation(input);
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status -> {
            try {
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    throw new IllegalStateException("capture failed");
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10));
            } catch (BulkProposalStorageException expectedFailure) {
                assertThat(expectedFailure.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
                assertThat(expectedFailure).hasNoCause();
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertAllProtectedRowsAbsent();
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status -> {
            try {
                store.captureAndInsertEvaluated(input, (connection, budget) -> expected,
                        captured -> { throw new IllegalStateException("projection failed"); },
                        () -> Duration.ofSeconds(10));
            } catch (BulkProposalStorageException expectedFailure) {
                assertThat(expectedFailure.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
                assertThat(expectedFailure).hasNoCause();
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertAllProtectedRowsAbsent();
    }

    @Test @Timeout(30)
    void controlLockIsHeldBeforeTheCaptureCallback() throws Exception {
        var input = freshProposal();
        var callbacks = new AtomicInteger();
        var workerPid = new AtomicInteger();
        var started = new CountDownLatch(1);
        var ownerManager = new DataSourceTransactionManager(owner);
        var ownerTransaction = new TransactionTemplate(ownerManager);
        var executor = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> worker = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            var ownerPid = new AtomicInteger();
            ownerTransaction.executeWithoutResult(status -> {
                ownerPid.set(sql.queryForObject("select pg_backend_pid()", Integer.class));
                sql.queryForObject("""
                        select generation from praxis_bulk.praxis_bulk_operation_control
                         where namespace_id=? and operation_id=? for update
                        """, Long.class, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
                var future = executor.submit(() -> repeatable.executeWithoutResult(inner -> {
                    workerPid.set(new JdbcTemplate(runtime).queryForObject("select pg_backend_pid()", Integer.class));
                    started.countDown();
                    store.captureAndInsertEvaluated(input, (connection, budget) -> {
                        callbacks.incrementAndGet(); return freshEvaluation(input);
                    }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10));
                }));
                worker.set(future);
                try {
                    assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    boolean blocked = false;
                    while (System.nanoTime() < until) {
                        blocked = Boolean.TRUE.equals(sql.queryForObject("""
                                select wait_event_type='Lock' and ? = any(pg_blocking_pids(?))
                                  from pg_stat_activity where pid=?
                                """, Boolean.class, ownerPid.get(), workerPid.get(), workerPid.get()));
                        if (blocked) break;
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                    }
                    assertThat(blocked).as("actual PostgreSQL control-row blocker").isTrue();
                    assertThat(sql.queryForObject("""
                            select coalesce(bool_or(mode='RowShareLock' and granted),false)
                              from pg_locks where pid=?
                               and relation='praxis_bulk.praxis_bulk_operation_control'::regclass
                            """, Boolean.class, workerPid.get())).isTrue();
                    assertThat(callbacks.get()).as("capture cannot precede control/quota locks").isZero();
                    assertThat(future.isDone()).isFalse();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                }
                status.setRollbackOnly();
            });
            // The released control row allows the same worker transaction to capture and commit.
            worker.get().get(5, TimeUnit.SECONDS);
            assertThat(callbacks.get()).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_proposal", Integer.class))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test @Timeout(30)
    void deploymentBucketLockIsHeldBeforeTheCaptureCallback() throws Exception {
        var input = freshProposal();
        var callbacks = new AtomicInteger();
        var workerPid = new AtomicInteger();
        var started = new CountDownLatch(1);
        var ownerManager = new DataSourceTransactionManager(owner);
        var ownerTransaction = new TransactionTemplate(ownerManager);
        var executor = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> worker = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            var ownerPid = new AtomicInteger();
            ownerTransaction.executeWithoutResult(status -> {
                ownerPid.set(sql.queryForObject("select pg_backend_pid()", Integer.class));
                sql.queryForObject("""
                        select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket
                         where deployment_id=? for update
                        """, String.class, BulkPostgresTestSupport.DEPLOYMENT_ID);
                var future = executor.submit(() -> repeatable.executeWithoutResult(inner -> {
                    workerPid.set(new JdbcTemplate(runtime).queryForObject("select pg_backend_pid()", Integer.class));
                    started.countDown();
                    store.captureAndInsertEvaluated(input, (connection, budget) -> {
                        callbacks.incrementAndGet(); return freshEvaluation(input);
                    }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10));
                }));
                worker.set(future);
                try {
                    assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    boolean blocked = false;
                    while (System.nanoTime() < until) {
                        blocked = Boolean.TRUE.equals(sql.queryForObject("""
                                select wait_event_type='Lock' and ? = any(pg_blocking_pids(?))
                                  from pg_stat_activity where pid=?
                                """, Boolean.class, ownerPid.get(), workerPid.get(), workerPid.get()));
                        if (blocked) break;
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                    }
                    assertThat(blocked).as("actual PostgreSQL deployment-bucket blocker").isTrue();
                    assertThat(sql.queryForObject("""
                            select coalesce(bool_or(mode='RowExclusiveLock' and granted),false)
                              from pg_locks where pid=?
                               and relation='praxis_bulk.praxis_bulk_deployment_bucket'::regclass
                            """, Boolean.class, workerPid.get())).isTrue();
                    assertThat(callbacks.get()).as("capture cannot precede deployment quota lock").isZero();
                    assertThat(future.isDone()).isFalse();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                }
                status.setRollbackOnly();
            });
            // The released control row allows the same worker transaction to capture and commit.
            worker.get().get(5, TimeUnit.SECONDS);
            assertThat(callbacks.get()).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_proposal", Integer.class))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void fullSubjectQuotaRejectsBeforeCallbackAndLeavesNoNewAllocation() {
        for (int index = 0; index < BulkQuotaLedger.MAX_PENDING_SUBJECT; index++) {
            var pending = freshProposal();
            repeatable.executeWithoutResult(status -> store.insert(pending));
        }
        int before = sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation", Integer.class);
        assertThat(before).isEqualTo(BulkQuotaLedger.MAX_PENDING_SUBJECT);
        var callbacks = new AtomicInteger();
        var input = freshProposal();
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status ->
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    callbacks.incrementAndGet(); return freshEvaluation(input);
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10))))
                .isInstanceOf(BulkProposalStorageException.class);
        assertThat(callbacks.get()).isZero();
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation", Integer.class))
                .isEqualTo(before);
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_proposal", Integer.class))
                .isEqualTo(before);
    }

    @Test void suspendedControlAndExpiredCallerBudgetDoNotInvokeCapture() {
        var input = freshProposal();
        var callbacks = new AtomicInteger();
        try (var connection = owner.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertThat(JdbcBulkOperationControl.transition(connection, CONTEXT.namespaceId(),
                        CONTEXT.operationRef().operationId(), 1,
                        JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null).applied()).isTrue();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status ->
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    callbacks.incrementAndGet(); return freshEvaluation(input);
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10))))
                .isInstanceOf(RuntimeException.class);
        assertThat(callbacks.get()).isZero();
        assertAllProtectedRowsAbsent();
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status ->
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    callbacks.incrementAndGet(); return freshEvaluation(input);
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ZERO)))
                .isInstanceOf(BulkProposalStorageException.class);
        assertThat(callbacks.get()).isZero();
        assertAllProtectedRowsAbsent();
    }

    @Test void mismatchedCapturedBindingAndRealStatementTimeoutRollbackAllStorage() {
        var input = freshProposal();
        var different = freshEvaluation(freshProposal());
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status ->
                store.captureAndInsertEvaluated(input, (connection, budget) -> different,
                        BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(10))))
                .isInstanceOf(RuntimeException.class);
        assertAllProtectedRowsAbsent();
        var entered = new AtomicInteger();
        var sqlState = new java.util.concurrent.atomic.AtomicReference<String>();
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status ->
                store.captureAndInsertEvaluated(input, (connection, budget) -> {
                    entered.incrementAndGet();
                    try (var statement = connection.createStatement()) {
                        statement.execute("select pg_sleep(5)");
                    } catch (SQLException timeout) {
                        sqlState.set(timeout.getSQLState());
                        throw new IllegalStateException("bounded PostgreSQL statement", timeout);
                    }
                    return freshEvaluation(input);
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(2))))
                .isInstanceOf(BulkProposalStorageException.class).hasNoCause();
        assertThat(entered.get()).isEqualTo(1);
        assertThat(sqlState.get()).as("actual PostgreSQL statement_timeout after callback entry")
                .isEqualTo("57014");
        assertAllProtectedRowsAbsent();
    }

    @Test void localTimeoutsNeverWidenAndRestoreTheCallersLimits() {
        var first = freshProposal();
        repeatable.executeWithoutResult(status -> {
            var jdbc = new JdbcTemplate(runtime);
            jdbc.queryForObject("select set_config('statement_timeout','250ms',true)", String.class);
            jdbc.queryForObject("select set_config('lock_timeout','250ms',true)", String.class);
            store.captureAndInsertEvaluated(first, (connection, budget) -> {
                assertThat(sqlValue(connection, "select current_setting('statement_timeout')"))
                        .isEqualTo("250ms");
                assertThat(sqlValue(connection, "select current_setting('lock_timeout')"))
                        .isEqualTo("250ms");
                return freshEvaluation(first);
            }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(2));
            assertThat(jdbc.queryForObject("select current_setting('statement_timeout')", String.class))
                    .isEqualTo("250ms");
            assertThat(jdbc.queryForObject("select current_setting('lock_timeout')", String.class))
                    .isEqualTo("250ms");
            status.setRollbackOnly();
        });
        assertAllProtectedRowsAbsent();
        var second = freshProposal();
        repeatable.executeWithoutResult(status -> {
            var jdbc = new JdbcTemplate(runtime);
            jdbc.queryForObject("select set_config('statement_timeout','5s',true)", String.class);
            jdbc.queryForObject("select set_config('lock_timeout','4s',true)", String.class);
            store.captureAndInsertEvaluated(second, (connection, budget) -> {
                assertThat(sqlValue(connection, "select case when current_setting('statement_timeout')::interval <= interval '2 seconds' then 'yes' else 'no' end"))
                        .isEqualTo("yes");
                assertThat(sqlValue(connection, "select case when current_setting('lock_timeout')::interval <= interval '2 seconds' then 'yes' else 'no' end"))
                        .isEqualTo("yes");
                return freshEvaluation(second);
            }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(2));
            assertThat(jdbc.queryForObject("select current_setting('statement_timeout')", String.class))
                    .isEqualTo("5s");
            assertThat(jdbc.queryForObject("select current_setting('lock_timeout')", String.class))
                    .isEqualTo("4s");
            status.setRollbackOnly();
        });
        assertAllProtectedRowsAbsent();
    }

    @Test void deadlineExhaustedAfterProjectionRollsBackEvenWhenOuterCatches() {
        var input = freshProposal();
        var projected = new AtomicBoolean();
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status -> {
            try {
                store.captureAndInsertEvaluated(input, (connection, budget) -> freshEvaluation(input),
                        evaluation -> {
                            projected.set(true);
                            return preview(evaluation);
                        }, () -> projected.get() ? Duration.ZERO : Duration.ofSeconds(10));
            } catch (BulkProposalStorageException expected) {
                assertThat(projected.get()).isTrue();
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertAllProtectedRowsAbsent();
    }

    private static BulkStoredProposal freshProposal() {
        return freshProposal(BulkSnapshotStorageCodecTest.proposal().snapshot());
    }

    private static BulkStoredProposal freshProposal(BulkIntentSnapshot snapshot) {
        Instant created = Instant.now();
        return new BulkStoredProposal(java.util.UUID.randomUUID(), created, created.plusSeconds(900),
                snapshot, BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
    }

    private static BulkEvaluationSnapshot freshEvaluation(BulkStoredProposal input) {
        var example = BulkEvaluationSnapshotTest.evaluation(BulkSnapshotStorageCodecTest.proposal());
        var governance = new BulkEvaluationGovernance("test-evaluator-r1", "test-grants-r1", List.of(
                new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                        "resource:approve", "NEVER_APPLIED", "test-policy-r1", input.createdAt())));
        return new BulkEvaluationSnapshot(input, input.createdAt().plusSeconds(1), example.targets(), governance);
    }

    private static TransactionTemplate transaction(DataSourceTransactionManager manager, int isolation, boolean ro) {
        var template = new TransactionTemplate(manager);
        template.setIsolationLevel(isolation);
        template.setReadOnly(ro);
        return template;
    }

    private static int connectionPid(Connection connection) {
        return Integer.parseInt(sqlValue(connection, "select pg_backend_pid()"));
    }

    private static String sqlValue(Connection connection, String query) {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            String value = rows.getString(1);
            assertThat(rows.next()).isFalse();
            return value;
        } catch (SQLException error) { throw new AssertionError(error); }
    }

    private static void assertAllProtectedRowsAbsent() {
        for (String table : java.util.List.of("praxis_bulk_proposal", "praxis_bulk_evaluation",
                "praxis_bulk_target_manifest", "praxis_bulk_preview_state", "praxis_bulk_target_preview",
                "praxis_bulk_preview_item_integrity", "praxis_bulk_allocation"))
            assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table, Integer.class))
                    .as(table).isZero();
    }
}
