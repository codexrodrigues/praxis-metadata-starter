package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.bulk.persistence.fixture.BulkDurableJpaDomainRow;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.JSON;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.bytes;

/** PostgreSQL proof of durable reservation, receipt atomicity, fencing and conservative recovery. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkDurableExecutionPostgresTest {
    private static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext(
            "tenant-a:production:payroll", "operator-a", "employees",
            new CanonicalOperationRef("admin", "employee-bulk-approve", "/employees/bulk/approve", "POST"),
            "schema-r1", ActionCollectionAtomicity.PER_ITEM);

    private EmbeddedPostgres postgres;
    private DataSource dataSource;
    private JdbcTemplate observer;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transactions;
    private JdbcBulkProposalStore proposals;
    private EntityManagerFactory entityManagerFactory;
    private JpaTransactionManager jpaManager;

    @BeforeAll
    void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
        dataSource = postgres.getPostgresDatabase();
        observer = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        proposals = new JdbcBulkProposalStore(new BulkExecutionInfrastructure(dataSource, manager, CONTEXT.namespaceId()));
        observer.execute("create table bulk_durable_domain(id bigint primary key, writes integer not null default 0)");
        observer.execute("create table bulk_durable_jpa_domain(id bigint primary key, writes integer not null default 0)");
        observer.execute("create role durable_runtime login");
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(BulkDurableJpaDomainRow.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.afterPropertiesSet();
        entityManagerFactory = factory.getObject();
        jpaManager = new JpaTransactionManager(entityManagerFactory);
        System.out.println("Durable execution proof PostgreSQL: "
                + observer.queryForObject("select version()", String.class));
    }

    @AfterAll
    void stop() throws Exception {
        try {
            if (entityManagerFactory != null) {
                entityManagerFactory.close();
            }
        } finally {
            if (postgres != null) {
                postgres.close();
            }
        }
    }

    @BeforeEach
    void reset() {
        observer.execute("drop schema if exists praxis_bulk cascade");
        observer.execute("truncate bulk_durable_domain, bulk_durable_jpa_domain");
        observer.update("insert into bulk_durable_domain(id) values (1), (2)");
        observer.update("insert into bulk_durable_jpa_domain(id) values (1), (2)");
        assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(3);
    }

    @Test
    void reservationHasOneWinnerForItsProposalAndIdempotencyScope() throws Exception {
        var kernel = kernel();
        var first = persist(twoTargetEvaluation());
        var reservation = reserve(kernel, first, "key-a", "owner-a");

        var same = reserve(kernel, first, "key-a", "owner-a");
        assertThat(same.replayed()).isTrue();
        assertThat(same.executionId()).isEqualTo(reservation.executionId());
        assertThat(same.control().epoch()).isEqualTo(reservation.control().epoch());

        assertThatThrownBy(() -> kernel.reserve(CONTEXT, first.proposal().id(), "key-a", "owner-a",
                "structural-r2", deadline()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CONFLICT));

        var anotherKey = reserve(kernel, first, "key-b", "owner-a");
        assertThat(anotherKey.executionId()).isEqualTo(reservation.executionId());
        assertThat(count("praxis_bulk_execution")).isEqualTo(1);

        var second = persist(twoTargetEvaluation());
        assertThatThrownBy(() -> kernel.reserve(CONTEXT, second.proposal().id(), "key-a", "owner-a",
                "structural-r1", deadline()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CONFLICT));
    }

    @Test
    void concurrentReservationsSerializeSameKeyDifferentBindingAndSecondKeyForOneProposal() throws Exception {
        var firstKernel = kernel();
        var secondManager = new DataSourceTransactionManager(dataSource);
        var secondKernel = new JdbcBulkDurableExecution(
                new BulkExecutionInfrastructure(dataSource, secondManager, CONTEXT.namespaceId()));
        var sameKeyProposal = persist(twoTargetEvaluation());
        var sameKey = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reserveAfterBarrier(firstKernel, sameKeyProposal, "same-key", "structural-r1", sameKey));
            var second = executor.submit(() -> reserveAfterBarrier(secondKernel, sameKeyProposal, "same-key", "structural-r1", sameKey));
            var left = first.get(10, TimeUnit.SECONDS);
            var right = second.get(10, TimeUnit.SECONDS);
            assertThat(left.executionId()).isEqualTo(right.executionId());
            assertThat(List.of(left.replayed(), right.replayed())).containsExactlyInAnyOrder(false, true);
        }

        var conflictingProposal = persist(twoTargetEvaluation());
        var conflictBarrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reserveAfterBarrier(firstKernel, conflictingProposal, "conflict-key", "structural-a", conflictBarrier));
            var second = executor.submit(() -> reserveAfterBarrier(secondKernel, conflictingProposal, "conflict-key", "structural-b", conflictBarrier));
            var outcomes = List.of(first, second).stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception error) {
                    return error;
                }
            }).toList();
            assertThat(outcomes.stream().filter(BulkExecutionReservation.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(Exception.class::isInstance)).hasSize(1);
            Throwable failure = ((Exception) outcomes.stream().filter(Exception.class::isInstance).findFirst().orElseThrow()).getCause();
            assertThat(failure).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                    error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CONFLICT));
        }

        var proposalKey = persist(twoTargetEvaluation());
        var proposalBarrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reserveAfterBarrier(firstKernel, proposalKey, "proposal-key-a", "structural-r1", proposalBarrier));
            var second = executor.submit(() -> reserveAfterBarrier(secondKernel, proposalKey, "proposal-key-b", "structural-r1", proposalBarrier));
            assertThat(first.get(10, TimeUnit.SECONDS).executionId())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).executionId());
        }
        assertThat(count("praxis_bulk_execution")).isEqualTo(3);
    }

    @Test
    void reservationRejectsSqlScopeThatDisagreesWithProtectedPayload() {
        var value = persist(twoTargetEvaluation());
        observer.execute("alter table praxis_bulk.praxis_bulk_proposal disable trigger praxis_bulk_proposal_reject_update");
        try {
            assertThat(observer.update("update praxis_bulk.praxis_bulk_proposal set subject_id='operator-b' where proposal_id=?",
                    value.proposal().id())).isEqualTo(1);
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_proposal enable trigger praxis_bulk_proposal_reject_update");
        }
        var otherScope = new BulkFingerprintContext(CONTEXT.namespaceId(), "operator-b", CONTEXT.resourceKey(),
                CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity());

        assertThatThrownBy(() -> kernel().reserve(otherScope, value.proposal().id(), "scope-tamper-key",
                "owner-a", "structural-r1", deadline()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
        assertThat(count("praxis_bulk_execution")).isZero();
    }

    @Test
    void v2UpgradePreservesEvaluationAndHistoryWithoutFabricatingExecutionOrReceipt() {
        observer.execute("drop schema if exists praxis_bulk cascade");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .baselineOnMigrate(false).cleanDisabled(true).target("2").load().migrate();
        var value = persist(twoTargetEvaluation());
        int v1Checksum = observer.queryForObject(
                "select checksum from praxis_bulk.praxis_bulk_schema_history where version='1'", Integer.class);
        int v2Checksum = observer.queryForObject(
                "select checksum from praxis_bulk.praxis_bulk_schema_history where version='2'", Integer.class);

        assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(1);
        assertThat(observer.queryForObject(
                "select checksum from praxis_bulk.praxis_bulk_schema_history where version='1'", Integer.class))
                .isEqualTo(v1Checksum);
        assertThat(observer.queryForObject(
                "select checksum from praxis_bulk.praxis_bulk_schema_history where version='2'", Integer.class))
                .isEqualTo(v2Checksum);
        var recovered = transactions.execute(status -> proposals.findEvaluation(CONTEXT, value.proposal().id()).orElseThrow());
        assertThat(recovered.fingerprint()).isEqualTo(value.fingerprint());
        assertThat(count("praxis_bulk_execution")).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
    }

    @Test
    void v3ReceiptIsImmutableAndRuntimeRoleHasOnlyTheRequiredWriteSurface() throws Exception {
        var runtimeDataSource = new DriverManagerDataSource(postgres.getJdbcUrl("durable_runtime", "postgres"),
                "durable_runtime", "");
        observer.execute("grant usage on schema praxis_bulk to durable_runtime");
        observer.execute("grant select on praxis_bulk.praxis_bulk_proposal, praxis_bulk.praxis_bulk_evaluation to durable_runtime");
        observer.execute("grant select, insert, update on praxis_bulk.praxis_bulk_execution to durable_runtime");
        observer.execute("grant select, insert on praxis_bulk.praxis_bulk_item_receipt to durable_runtime");
        var runtimeManager = new DataSourceTransactionManager(runtimeDataSource);
        var runtimeKernel = new JdbcBulkDurableExecution(
                new BulkExecutionInfrastructure(runtimeDataSource, runtimeManager, CONTEXT.namespaceId()));
        var reservation = reserve(runtimeKernel, persist(twoTargetEvaluation()), "runtime-key", "runtime-owner");
        runtimeKernel.executeUnit(reservation.control(), 0, unit -> BulkUnitMutationResult.confirmed());

        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
        assertThatThrownBy(() -> observer.execute(
                "update praxis_bulk.praxis_bulk_item_receipt set outcome='UNCHANGED'"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> observer.execute("delete from praxis_bulk.praxis_bulk_item_receipt"))
                .isInstanceOf(RuntimeException.class);
        var restricted = new JdbcTemplate(runtimeDataSource);
        for (String statement : List.of(
                "update praxis_bulk.praxis_bulk_item_receipt set outcome='UNCHANGED'",
                "delete from praxis_bulk.praxis_bulk_item_receipt",
                "update praxis_bulk.praxis_bulk_evaluation set payload=payload",
                "delete from praxis_bulk.praxis_bulk_proposal",
                "create table praxis_bulk.runtime_must_not_create(id integer)")) {
            assertThatThrownBy(() -> restricted.execute(statement)).as(statement).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void jdbcDomainAndReceiptCommitTogetherAndRetryOfADoesNotDispatchB() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "key-a", "owner-a");
        var callsA = new AtomicInteger();
        var callsB = new AtomicInteger();
        BulkUnitMutationCallback callback = unit -> {
            if (unit.ordinal() == 0) {
                callsA.incrementAndGet();
            } else {
                callsB.incrementAndGet();
            }
            observer.update("update bulk_durable_domain set writes=writes+1 where id=?", unit.ordinal() + 1L);
            assertIndependentObserverPreservesCommittedPrefix(unit.ordinal());
            return BulkUnitMutationResult.confirmed();
        };

        var first = kernel.executeUnit(reservation.control(), 0, callback);
        assertThat(first.replayed()).isFalse();
        assertThat(first.outcome()).isEqualTo(BulkUnitOutcome.CONFIRMED);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(writes("bulk_durable_domain", 2)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);

        var retryA = kernel.executeUnit(first.control(), 0, callback);
        assertThat(retryA.replayed()).isTrue();
        assertThat(retryA.ordinal()).isZero();
        assertThat(callsA).hasValue(1);
        assertThat(callsB).hasValue(0);

        var explicitB = kernel.executeUnit(retryA.control(), 1, callback);
        assertThat(explicitB.replayed()).isFalse();
        assertThat(callsB).hasValue(1);
        assertThat(writes("bulk_durable_domain", 2)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(2);
    }

    @Test
    void controlCannotBeUsedThroughAnotherOperationalNamespace() throws Exception {
        var reservation = reserve(kernel(), persist(twoTargetEvaluation()), "namespace-key", "owner-a");
        var otherContext = new BulkFingerprintContext("tenant-b:production:payroll", CONTEXT.subjectId(), CONTEXT.resourceKey(),
                CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity());
        var otherManager = new DataSourceTransactionManager(dataSource);
        var otherKernel = new JdbcBulkDurableExecution(
                new BulkExecutionInfrastructure(dataSource, otherManager, otherContext.namespaceId()));
        var called = new AtomicInteger();
        assertThatThrownBy(() -> otherKernel.executeUnit(reservation.control(), 0, unit -> {
            called.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOf(BulkDurableExecutionException.class);
        assertThat(called).hasValue(0);
        assertThat(writes("bulk_durable_domain", 1)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
    }

    @Test
    void jpaDomainAndReceiptAreAtomicForCommitAndCallbackRollback() throws Exception {
        var infrastructure = new BulkExecutionInfrastructure(dataSource, jpaManager, CONTEXT.namespaceId());
        var kernel = new JdbcBulkDurableExecution(infrastructure);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "jpa-key", "owner-a");

        var committed = kernel.executeUnit(reservation.control(), 0, unit -> {
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            entityManager.find(BulkDurableJpaDomainRow.class, 1L).recordWrite();
            entityManager.flush();
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(committed.replayed()).isFalse();
        assertThat(writes("bulk_durable_jpa_domain", 1)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);

        var rollbackReservation = reserve(kernel, persist(twoTargetEvaluation()), "jpa-rollback", "owner-b");
        var rolledBack = kernel.executeUnit(rollbackReservation.control(), 0, unit -> {
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            entityManager.find(BulkDurableJpaDomainRow.class, 1L).recordWrite();
            entityManager.flush();
            throw new IllegalStateException("fixture rollback");
        });
        assertThat(rolledBack.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(writes("bulk_durable_jpa_domain", 1)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
    }

    @Test
    void knownRollbackAndUnknownCommitKeepLaterOrdinalBlockedUntilRecovery() throws Exception {
        var faults = new BulkCommitFaultDataSource(dataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager, CONTEXT.namespaceId()));
        var faultJdbc = new JdbcTemplate(faults);

        var knownRollback = reserve(kernel, persist(twoTargetEvaluation()), "rollback-key", "owner-a");
        var rollbackCalls = new AtomicInteger();
        var rollback = kernel.executeUnit(knownRollback.control(), 0, unit -> {
            rollbackCalls.incrementAndGet();
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.ROLLBACK_THEN_FAIL);
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(rollback.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(rollbackCalls).hasValue(1);
        assertThat(writes("bulk_durable_domain", 1)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThatThrownBy(() -> kernel.executeUnit(rollback.control(), 1, unit -> {
            fail("ordinal B must remain blocked after known rollback");
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOf(BulkDurableExecutionException.class);

        var unknown = reserve(kernel, persist(twoTargetEvaluation()), "unknown-key", "owner-b");
        var unknownCalls = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(unknown.control(), 0, unit -> {
            unknownCalls.incrementAndGet();
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(unknownCalls).hasValue(1);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
        assertThatThrownBy(() -> kernel.executeUnit(unknown.control(), 1, unit -> {
            fail("ordinal B must not run while commit B is uncertain");
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));

        var recovery = kernel.recover(CONTEXT, unknown.executionId(), "recovery-owner");
        assertThat(recovery.receiptCount()).isEqualTo(1);
        assertThatThrownBy(() -> kernel.executeUnit(unknown.control(), 1, unit -> BulkUnitMutationResult.confirmed()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
    }

    @Test
    void ackLostAfterItsOwnCommitStillReplaysAAndNeverDispatchesBImplicitly() throws Exception {
        var faults = new BulkCommitFaultDataSource(dataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager, CONTEXT.namespaceId()));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "ack-key", "owner-a");
        var callsA = new AtomicInteger();
        var callsB = new AtomicInteger();

        var first = kernel.executeUnit(reservation.control(), 0, unit -> {
            callsA.incrementAndGet();
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
                }
            });
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(first.ordinal()).isZero();
        assertThat(first.replayed()).isTrue();
        assertThat(first.execution().nextOrdinal()).isEqualTo(1);
        assertThat(callsA).hasValue(1);
        assertThat(callsB).hasValue(0);

        var retryA = kernel.executeUnit(first.control(), 0, unit -> {
            callsB.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(retryA.replayed()).isTrue();
        assertThat(callsB).hasValue(0);

        kernel.executeUnit(retryA.control(), 1, unit -> {
            callsB.incrementAndGet();
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=2");
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(callsB).hasValue(1);
        assertThat(writes("bulk_durable_domain", 2)).isEqualTo(1);
    }

    @Test
    void replayOfAAfterBCompletesAndDeadlinePassesNeverDispatchesACallbackAgain() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "completed-replay-key", "owner-a");
        var callsA = new AtomicInteger();
        var callsB = new AtomicInteger();

        var first = kernel.executeUnit(reservation.control(), 0, unit -> {
            callsA.incrementAndGet();
            observer.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        var completed = kernel.executeUnit(first.control(), 1, unit -> {
            callsB.incrementAndGet();
            observer.update("update bulk_durable_domain set writes=writes+1 where id=2");
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(completed.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);

        var replayAfterCompletion = kernel.executeUnit(completed.control(), 0, ignored -> {
            fail("receipt A must replay after B completed; it must not invoke the callback");
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(replayAfterCompletion.replayed()).isTrue();
        assertThat(replayAfterCompletion.ordinal()).isZero();
        assertThat(callsA).hasValue(1);
        assertThat(callsB).hasValue(1);

        expireDeadlineAsFixtureOwner(reservation.executionId());
        assertThat(observer.queryForObject("""
                select deadline_at < clock_timestamp()
                from praxis_bulk.praxis_bulk_execution where execution_id=?
                """, Boolean.class, reservation.executionId())).isTrue();
        var replayAfterDeadline = kernel.executeUnit(replayAfterCompletion.control(), 0, ignored -> {
            fail("durable receipt A must replay even when the execution deadline is past");
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(replayAfterDeadline.replayed()).isTrue();
        assertThat(replayAfterDeadline.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertThat(callsA).hasValue(1);
        assertThat(callsB).hasValue(1);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(writes("bulk_durable_domain", 2)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(2);
    }

    @Test
    void recoveryClosesWhenPendingReceiptEpochDoesNotMatchTheActiveAttempt() throws Exception {
        var faults = new BulkCommitFaultDataSource(dataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager, CONTEXT.namespaceId()));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "corrupt-recovery-key", "owner-a");

        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 0, unit -> {
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);

        corruptPendingReceiptEpochAsFixtureOwner(reservation.executionId(), 0);
        var recovery = kernel.recover(CONTEXT, reservation.executionId(), "recovery-owner");
        assertThat(recovery.status()).isEqualTo(BulkDurableExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(recovery.receiptCount()).isEqualTo(1);
        assertThat(recovery.control().epoch()).isGreaterThan(reservation.control().epoch());
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(recovery.control(), 1, ignored -> {
            callbacks.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(callbacks).hasValue(0);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
    }

    @Test
    void pendingReceiptWithDifferentAttemptIdCannotBeAcknowledgedByFallbackReadback() throws Exception {
        var faults = new BulkCommitFaultDataSource(dataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager, CONTEXT.namespaceId()));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "corrupt-attempt-key", "owner-a");
        var first = kernel.executeUnit(reservation.control(), 0, unit -> {
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        assertThatThrownBy(() -> kernel.executeUnit(first.control(), 1, unit -> {
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=2");
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(2);

        corruptPendingReceiptAttemptIdAsFixtureOwner(reservation.executionId(), 1);
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(first.control(), 1, ignored -> {
            callbacks.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(callbacks).hasValue(0);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(writes("bulk_durable_domain", 2)).isEqualTo(1);
    }

    @Test
    void intactReceiptAReplaysWithoutMutationWhenReceiptBMakesTheAggregateReconciliationRequired() throws Exception {
        var faults = new BulkCommitFaultDataSource(dataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager, CONTEXT.namespaceId()));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "prefix-recovery-key", "owner-a");
        var first = kernel.executeUnit(reservation.control(), 0, unit -> {
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        assertThatThrownBy(() -> kernel.executeUnit(first.control(), 1, unit -> {
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=2");
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        corruptPendingReceiptEpochAsFixtureOwner(reservation.executionId(), 1);

        var recovery = kernel.recover(CONTEXT, reservation.executionId(), "recovery-owner");
        assertThat(recovery.status()).isEqualTo(BulkDurableExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(recovery.execution().nextOrdinal()).isEqualTo(1);
        assertThat(recovery.receiptCount()).isEqualTo(2);
        var callbacks = new AtomicInteger();
        var replayedA = kernel.executeUnit(recovery.control(), 0, ignored -> {
            callbacks.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(replayedA.replayed()).isTrue();
        assertThat(replayedA.ordinal()).isZero();
        assertThat(callbacks).hasValue(0);
        assertThat(replayedA.status()).isEqualTo(BulkDurableExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(replayedA.execution().nextOrdinal()).isEqualTo(1);
        assertThat(replayedA.execution().receiptCount()).isEqualTo(2);
        assertThat(replayedA.control().executionId()).isEqualTo(recovery.control().executionId());
        assertThat(replayedA.control().ownerId()).isEqualTo(recovery.control().ownerId());
        assertThat(replayedA.control().epoch()).isEqualTo(recovery.control().epoch());
        assertThatThrownBy(() -> kernel.executeUnit(recovery.control(), 1, ignored -> {
            callbacks.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(callbacks).hasValue(0);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(writes("bulk_durable_domain", 2)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(2);
    }

    @Test
    void recoveryWaitsForUnitLockThenFencesTheFormerOwnerWithoutCallingB() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "recovery-key", "owner-a");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callsB = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var unit = executor.submit(() -> kernel.executeUnit(reservation.control(), 0, ignored -> {
                observer.update("update bulk_durable_domain set writes=writes+1 where id=1");
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release the held unit transaction");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("unit callback interrupted", error);
                }
                return BulkUnitMutationResult.confirmed();
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var recovering = executor.submit(() -> kernel.recover(CONTEXT, reservation.executionId(), "recovery-owner"));
            assertThat(recovering.isDone()).isFalse();
            release.countDown();
            var completedUnit = unit.get(10, TimeUnit.SECONDS);
            assertThat(completedUnit.ordinal()).isZero();
            assertThat(completedUnit.receiptPresent()).isTrue();
            assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
            assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
            var recovery = recovering.get(10, TimeUnit.SECONDS);
            assertThat(recovery.control().epoch()).isGreaterThan(reservation.control().epoch());
            assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 1, ignored -> {
                callsB.incrementAndGet();
                return BulkUnitMutationResult.confirmed();
            })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                    error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            assertThat(callsB).hasValue(0);
        }
    }

    private JdbcBulkDurableExecution kernel() {
        return new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(dataSource, manager, CONTEXT.namespaceId()));
    }

    private BulkEvaluationSnapshot twoTargetEvaluation() {
        var reader = new BulkProtocolReader<>(BulkIdentityCodecs.strings());
        var request = reader.<com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.JsonNode>readCommand(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[
                  {"id":"1","expectedVersion":"v1"},{"id":"2","expectedVersion":"v2"}]},"parameters":{"reason":"fixture"}}
                """), com.fasterxml.jackson.databind.JsonNode::deepCopy, com.fasterxml.jackson.databind.JsonNode::deepCopy);
        Instant created = Instant.now().minusSeconds(5);
        var snapshot = BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                com.fasterxml.jackson.databind.JsonNode::deepCopy, com.fasterxml.jackson.databind.JsonNode::deepCopy);
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(600), snapshot);
        var evidence = new ArrayList<BulkTargetEvidence<?>>();
        evidence.add(new BulkTargetEvidence<>(new BulkTarget<>("1", "v1"), "observed-v1", JSON.objectNode(), JSON.objectNode()));
        evidence.add(new BulkTargetEvidence<>(new BulkTarget<>("2", "v2"), "observed-v2", JSON.objectNode(), JSON.objectNode()));
        Instant evaluatedAt = created.plusSeconds(1);
        var governance = new BulkEvaluationGovernance("test-evaluator-r1", "test-grants-r1", List.of(
                new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                        "resource:approve", "NEVER_APPLIED", "test-policy-r1", created.plusMillis(500))));
        return new BulkEvaluationSnapshot(proposal, evaluatedAt, evidence, governance);
    }

    private BulkEvaluationSnapshot persist(BulkEvaluationSnapshot value) {
        transactions.executeWithoutResult(status -> proposals.insertEvaluated(value));
        return value;
    }

    private BulkExecutionReservation reserve(JdbcBulkDurableExecution kernel, BulkEvaluationSnapshot value,
            String key, String owner) {
        return kernel.reserve(CONTEXT, value.proposal().id(), key, owner, "structural-r1", deadline());
    }

    private BulkExecutionReservation reserveAfterBarrier(JdbcBulkDurableExecution kernel, BulkEvaluationSnapshot value,
            String key, String revision, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return kernel.reserve(CONTEXT, value.proposal().id(), key, "owner-a", revision, deadline());
    }

    private static Instant deadline() {
        return Instant.now().plusSeconds(120);
    }

    private int count(String table) {
        return observer.queryForObject("select count(*) from praxis_bulk." + table, Integer.class);
    }

    private int writes(String table, long id) {
        return observer.queryForObject("select writes from " + table + " where id=?", Integer.class, id);
    }

    private void expireDeadlineAsFixtureOwner(UUID executionId) {
        observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_protect_binding");
        try {
            assertThat(observer.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set deadline_at=(select max(confirmed_at) + interval '1 microsecond'
                                     from praxis_bulk.praxis_bulk_item_receipt
                                     where execution_id=praxis_bulk_execution.execution_id)
                    where execution_id=?
                    """, executionId)).isEqualTo(1);
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_protect_binding");
        }
    }

    private void corruptPendingReceiptEpochAsFixtureOwner(UUID executionId, int ordinal) {
        // Test-only owner corruption: production roles cannot mutate a receipt and this trigger
        // is restored before recovery observes the incompatible durable state.
        observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                + "disable trigger praxis_bulk_item_receipt_reject_mutation");
        try {
            assertThat(observer.update("""
                    update praxis_bulk.praxis_bulk_item_receipt
                    set owner_epoch=owner_epoch + 1
                    where execution_id=? and unit_ordinal=?
                    """, executionId, ordinal)).isEqualTo(1);
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                    + "enable trigger praxis_bulk_item_receipt_reject_mutation");
        }
    }

    private void corruptPendingReceiptAttemptIdAsFixtureOwner(UUID executionId, int ordinal) {
        // Test-only owner corruption: production roles cannot mutate a receipt and this trigger
        // is restored before a retry attempts to acknowledge the pending control state.
        observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                + "disable trigger praxis_bulk_item_receipt_reject_mutation");
        try {
            assertThat(observer.update("""
                    update praxis_bulk.praxis_bulk_item_receipt
                    set attempt_id=?
                    where execution_id=? and unit_ordinal=?
                    """, UUID.randomUUID(), executionId, ordinal)).isEqualTo(1);
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                    + "enable trigger praxis_bulk_item_receipt_reject_mutation");
        }
    }

    private void assertIndependentObserverPreservesCommittedPrefix(int committedOrdinalCount) {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        select (select count(*) from bulk_durable_domain where writes > 0)
                             + (select count(*) from praxis_bulk.praxis_bulk_item_receipt)
                        """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(committedOrdinalCount * 2);
        } catch (SQLException error) {
            throw new AssertionError("independent PostgreSQL observer failed", error);
        }
    }
}
