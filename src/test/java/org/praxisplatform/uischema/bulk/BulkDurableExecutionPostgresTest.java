package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManagerFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
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
        proposals = new JdbcBulkProposalStore(new BulkExecutionInfrastructure(dataSource, manager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
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
        assertThat(BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isEqualTo(7);
        BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
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
    void terminalRetentionClockCannotBeBackdatedByTheCaller() {
        var evaluation = persist(twoTargetEvaluation());
        var reservation = reserve(kernel(), evaluation, "terminal-clock", "owner-a");
        var terminal = kernel().executeUnit(reservation.control(), 0,
                unit -> BulkUnitAdmission.stop(BulkUnitReasonCode.AUTHORIZATION_REVOKED),
                unit -> BulkUnitMutationResult.confirmed());
        assertThat(terminal.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        Instant forgedTime = Instant.parse("2000-01-01T00:00:00Z");

        assertThatThrownBy(() -> observer.update("""
                update praxis_bulk.praxis_bulk_execution
                set status='STOPPED', terminal_reason_code='RECOVERY_STOPPED',
                    terminal_at=?, updated_at=clock_timestamp()
                where execution_id=?
                """, java.sql.Timestamp.from(forgedTime), reservation.executionId()))
                .isInstanceOf(RuntimeException.class);

        Instant stored = observer.queryForObject("""
                select terminal_at from praxis_bulk.praxis_bulk_execution where execution_id=?
                """, (row, index) -> row.getObject(1, java.time.OffsetDateTime.class).toInstant(),
                reservation.executionId());
        assertThat(stored).isAfter(forgedTime);
        BulkExecutionMigrator.validate(dataSource);
    }

    @Test
    void targetConflictIsDurableAndReplayNeverRunsAdmissionOrMutationAgain() {
        var kernel = kernel();
        var evaluation = persist(twoTargetEvaluation());
        var reservation = reserve(kernel, evaluation, "admission-conflict", "owner-a");
        var admissionCalls = new AtomicInteger();
        var mutations = new AtomicInteger();
        var conflict = kernel.executeUnit(reservation.control(), 0, unit -> {
            admissionCalls.incrementAndGet();
            assertThat(unit.originalIntent()).isNotNull();
            assertThat(unit.governance()).isNotNull();
            assertThat(unit.targetEvidence().eligibility()).isPresent();
            return BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
        }, unit -> {
            mutations.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(conflict.itemStatus()).isEqualTo(BulkItemStatus.CONFLICT);
        assertThat(conflict.durableResultPresent()).isTrue();
        assertThat(conflict.reasonCode()).isEqualTo(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
        assertThat(conflict.control().epoch()).isEqualTo(reservation.control().epoch());
        assertThat(conflict.execution().nextOrdinal()).isEqualTo(1);
        assertThat(conflict.execution().admissionCount()).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(admissionCalls).hasValue(1);
        assertThat(mutations).hasValue(0);

        var replay = kernel.executeUnit(conflict.control(), 0, unit -> {
            admissionCalls.incrementAndGet(); return BulkUnitAdmission.admit();
        }, unit -> {
            mutations.incrementAndGet(); return BulkUnitMutationResult.confirmed();
        });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.itemStatus()).isEqualTo(BulkItemStatus.CONFLICT);
        assertThat(admissionCalls).hasValue(1);
        assertThat(mutations).hasValue(0);

        var finalUnit = kernel.executeUnit(replay.control(), 1, unit -> BulkUnitAdmission.admit(),
                unit -> BulkUnitMutationResult.confirmed());
        assertThat(finalUnit.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS);
        assertThat(finalUnit.execution().receiptCount()).isEqualTo(1);
        assertThat(finalUnit.execution().admissionCount()).isEqualTo(1);
    }

    @Test
    void suspendedDescriptorStillReplaysReceiptButCannotStartAnotherUnit() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "suspend-fence", "owner-a");
        var writes = new AtomicInteger();
        var first = kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            writes.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(first.receiptPresent()).isTrue();
        assertThat(writes).hasValue(1);

        try (var connection = dataSource.getConnection()) {
            var transition = JdbcBulkOperationControl.transition(connection, CONTEXT.namespaceId(),
                    CONTEXT.operationRef().operationId(), 1, JdbcBulkOperationControl.Target.SUSPENDED,
                    null, null);
            assertThat(transition.applied()).isTrue();
            assertThat(transition.generation()).isEqualTo(2);
        }

        var replay = kernel.executeUnit(reservation.control(), 0, unit -> {
            writes.incrementAndGet();
            return BulkUnitAdmission.admit();
        }, unit -> {
            writes.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receiptPresent()).isTrue();
        assertThat(writes).hasValue(1);

        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 1, unit -> {
            writes.incrementAndGet();
            return BulkUnitAdmission.admit();
        }, unit -> {
            writes.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
        assertThat(writes).hasValue(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
    }

    @Test
    void suspensionThatWinsBetweenPrepareAndApplyStopsBeforeDomainCallback() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "suspend-in-flight", "owner-a");
        observer.execute("""
                create function praxis_bulk.test_gate_unit_prepare() returns trigger
                language plpgsql as $$
                begin
                    if old.status = 'RUNNING' and new.status = 'UNIT_IN_FLIGHT' then
                        perform pg_advisory_xact_lock(91021101);
                    end if;
                    return new;
                end;
                $$
                """);
        observer.execute("""
                create trigger test_gate_unit_prepare before update on praxis_bulk.praxis_bulk_execution
                for each row execute function praxis_bulk.test_gate_unit_prepare()
                """);

        var callbackCalls = new AtomicInteger();
        try (var gate = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            try (var statement = gate.createStatement()) { statement.execute("select pg_advisory_lock(91021101)"); }

            var unit = executor.submit(() -> kernel.executeUnit(reservation.control(), 0,
                    admission -> { callbackCalls.incrementAndGet(); return BulkUnitAdmission.admit(); },
                    mutation -> { callbackCalls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); }));
            assertDatabaseWait("advisory", "%update praxis_bulk.praxis_bulk_execution e%");

            var suspend = executor.submit(() -> {
                try (var connection = dataSource.getConnection()) {
                    return JdbcBulkOperationControl.transition(connection, CONTEXT.namespaceId(),
                            CONTEXT.operationRef().operationId(), 1, JdbcBulkOperationControl.Target.SUSPENDED,
                            null, null);
                }
            });
            assertDatabaseWait("transactionid", "%transition_operation_control%");

            try (var statement = gate.createStatement()) { statement.execute("select pg_advisory_unlock(91021101)"); }
            assertThat(suspend.get(3, TimeUnit.SECONDS).applied()).isTrue();
            var result = unit.get(5, TimeUnit.SECONDS);
            assertThat(result.itemStatus()).isEqualTo(BulkItemStatus.NOT_PROCESSED);
            assertThat(result.receiptPresent()).isFalse();
            assertThat(result.execution().status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        }
        assertThat(callbackCalls).hasValue(0);
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(count("praxis_bulk_admission")).isZero();
    }

    @Test
    void suspensionWaitsForAdmittedMutationAndReceiptToCommitTogether() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "suspend-after-admission", "owner-a");
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var writes = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var unit = executor.submit(() -> kernel.executeUnit(reservation.control(), 0,
                    admission -> BulkUnitAdmission.admit(), mutation -> {
                        callbackEntered.countDown();
                        try {
                            if (!releaseCallback.await(3, TimeUnit.SECONDS))
                                throw new AssertionError("domain callback was not released");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                        writes.incrementAndGet();
                        return BulkUnitMutationResult.confirmed();
                    }));
            assertThat(callbackEntered.await(2, TimeUnit.SECONDS)).isTrue();

            var suspend = executor.submit(() -> {
                try (var connection = dataSource.getConnection()) {
                    return JdbcBulkOperationControl.transition(connection, CONTEXT.namespaceId(),
                            CONTEXT.operationRef().operationId(), 1, JdbcBulkOperationControl.Target.SUSPENDED,
                            null, null);
                }
            });
            assertDatabaseWait("transactionid", "%transition_operation_control%");
            assertThat(suspend.isDone()).as("CAS waits while the admitted unit holds the control fence").isFalse();

            releaseCallback.countDown();
            var committed = unit.get(3, TimeUnit.SECONDS);
            assertThat(committed.receiptPresent()).isTrue();
            assertThat(committed.itemStatus()).isEqualTo(BulkItemStatus.CONFIRMED);
            assertThat(suspend.get(3, TimeUnit.SECONDS).applied()).isTrue();
        } finally {
            releaseCallback.countDown();
        }
        assertThat(writes).hasValue(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
        assertThat(count("praxis_bulk_admission")).isZero();
    }

    @Test
    void recompositionAtANewGenerationCannotContinueAnOldExecution() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "recomposed-generation", "owner-a");
        try (var connection = dataSource.getConnection()) {
            var suspended = JdbcBulkOperationControl.transition(connection, CONTEXT.namespaceId(),
                    CONTEXT.operationRef().operationId(), 1, JdbcBulkOperationControl.Target.SUSPENDED, null, null);
            assertThat(suspended.generation()).isEqualTo(2);
            var republished = JdbcBulkOperationControl.transition(connection, CONTEXT.namespaceId(),
                    CONTEXT.operationRef().operationId(), 2, JdbcBulkOperationControl.Target.READY,
                    "sha256:" + "0".repeat(64), "structural-r1");
            assertThat(republished.applied()).isTrue();
            assertThat(republished.generation()).isEqualTo(3);
        }

        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 0, unit -> {
            callbacks.incrementAndGet(); return BulkUnitAdmission.admit();
        }, unit -> {
            callbacks.incrementAndGet(); return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
        assertThat(callbacks).hasValue(0);
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(count("praxis_bulk_admission")).isZero();
    }

    @Test
    void concurrentAdmissionAndReceiptWritersSerializeAtTheExecutionControl() throws Exception {
        var firstKernel = kernel();
        var secondKernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(dataSource,
                new DataSourceTransactionManager(dataSource), CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
        var reservation = reserve(firstKernel, persist(twoTargetEvaluation()), "cross-result-race", "owner-a");
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondAdmissionCalls = new AtomicInteger();
        var secondMutationCalls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> firstKernel.executeUnit(reservation.control(), 0, unit -> {
                callbackEntered.countDown();
                try {
                    if (!releaseCallback.await(8, TimeUnit.SECONDS)) throw new AssertionError("admission callback not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new AssertionError(error);
                }
                return BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED);
            }, unit -> { fail("denied first writer must never mutate"); return BulkUnitMutationResult.confirmed(); }));
            assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return secondKernel.executeUnit(reservation.control(), 0, unit -> {
                secondAdmissionCalls.incrementAndGet(); return BulkUnitAdmission.admit();
                }, unit -> { secondMutationCalls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var lockWaitDeadline = Instant.now().plusSeconds(4);
            boolean lockObserved = false;
            while (Instant.now().isBefore(lockWaitDeadline) && !lockObserved) {
                lockObserved = observer.queryForObject("""
                        select exists(select 1 from pg_stat_activity
                          where pid <> pg_backend_pid() and wait_event_type='Lock'
                            and query ilike '%praxis_bulk_execution%')
                        """, Boolean.class);
                if (!lockObserved) Thread.sleep(25);
            }
            assertThat(lockObserved).as("second writer waits on the durable execution row").isTrue();
            releaseCallback.countDown();
            var committed = first.get(5, TimeUnit.SECONDS);
            assertThat(committed.itemStatus()).isEqualTo(BulkItemStatus.DENIED);
            var serialized = second.get(5, TimeUnit.SECONDS);
            assertThat(serialized.replayed()).isTrue();
            assertThat(serialized.itemStatus()).isEqualTo(BulkItemStatus.DENIED);
        } finally {
            releaseCallback.countDown();
        }
        assertThat(secondAdmissionCalls).hasValue(0);
        assertThat(secondMutationCalls).hasValue(0);
        assertThat(count("praxis_bulk_admission")).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isZero();
    }

    @Test
    void commonStopPersistsSafeReasonAndLeavesCurrentAndSuffixUnprocessed() {
        var kernel = kernel();
        var evaluation = persist(twoTargetEvaluation());
        var reservation = reserve(kernel, evaluation, "common-stop", "owner-a");
        var mutations = new AtomicInteger();
        var stopped = kernel.executeUnit(reservation.control(), 0,
                unit -> BulkUnitAdmission.stop(BulkUnitReasonCode.AUTHORIZATION_REVOKED), unit -> {
                    mutations.incrementAndGet(); return BulkUnitMutationResult.confirmed();
                });
        assertThat(stopped.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(stopped.execution().nextOrdinal()).isZero();
        assertThat(stopped.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.AUTHORIZATION_REVOKED);
        assertThat(stopped.itemStatus()).isEqualTo(BulkItemStatus.NOT_PROCESSED);
        assertThat(stopped.durableResultPresent()).isFalse();
        assertThat(count("praxis_bulk_admission")).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(mutations).hasValue(0);

        var readback = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
        assertThat(readback.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(readback.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.AUTHORIZATION_REVOKED);
        assertThat(kernel.recover(CONTEXT, reservation.executionId(), "recovery-owner").execution().terminalReasonCode())
                .isEqualTo(BulkUnitReasonCode.AUTHORIZATION_REVOKED);
        assertThat(mutations).hasValue(0);
    }

    @Test
    void targetLockThatExceedsOneSecondStopsAndClearsAttempt() throws Exception {
        var kernel = kernel();
        var targetLocked = new CountDownLatch(1);
        var releaseTarget = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var blocker = executor.submit(() -> transactions.executeWithoutResult(status -> {
                observer.update("update bulk_durable_domain set writes=writes+1 where id=1");
                targetLocked.countDown();
                try {
                    if (!releaseTarget.await(5, TimeUnit.SECONDS)) throw new AssertionError("target lock not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new AssertionError(error);
                }
            }));
            assertThat(targetLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var evaluation = persist(twoTargetEvaluation());
            long started = System.nanoTime();
            var reservation = kernel.reserve(CONTEXT, evaluation.proposal().id(), "deadline-target-lock", "owner-a",
                    "structural-r1", Instant.now().plusSeconds(10));
            try {
                var stopped = kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                    observer.queryForObject("select writes from bulk_durable_domain where id=1 for update", Integer.class);
                    return BulkUnitMutationResult.confirmed();
                });
                assertThat(stopped.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
                assertThat(stopped.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        .as("the active target lock is capped at one second")
                        .isLessThan(3_000);
                assertThat(stopped.itemStatus()).isEqualTo(BulkItemStatus.NOT_PROCESSED);
                assertThat(observer.queryForObject("select active_attempt_id is null from praxis_bulk.praxis_bulk_execution where execution_id=?",
                        Boolean.class, reservation.executionId())).isTrue();
                assertThat(count("praxis_bulk_item_receipt")).isZero();
            } finally {
                releaseTarget.countDown();
                blocker.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void durableAdmissionReplayRemainsReadableAfterExecutionDeadline() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "admission-after-deadline", "owner-a");
        var admissionCalls = new AtomicInteger();
        var denied = kernel.executeUnit(reservation.control(), 0, unit -> {
            admissionCalls.incrementAndGet(); return BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED);
        }, unit -> { fail("denied unit must not mutate"); return BulkUnitMutationResult.confirmed(); });
        expireExecutionDeadlineAsFixtureOwner(reservation.executionId());
        var replay = kernel.executeUnit(denied.control(), 0, unit -> {
            admissionCalls.incrementAndGet(); return BulkUnitAdmission.admit();
        }, unit -> { fail("replay after deadline must not mutate"); return BulkUnitMutationResult.confirmed(); });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.itemStatus()).isEqualTo(BulkItemStatus.DENIED);
        assertThat(admissionCalls).hasValue(1);
    }

    @Test
    void refusesToMutateAfterARecordedOrdinalGap() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "ordinal-gap", "owner-a");
        observer.update("update praxis_bulk.praxis_bulk_execution set next_ordinal=1 where execution_id=?",
                reservation.executionId());
        var admissions = new AtomicInteger();
        var mutations = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 1, unit -> {
            admissions.incrementAndGet(); return BulkUnitAdmission.admit();
        }, unit -> {
            mutations.incrementAndGet(); return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(admissions).hasValue(0);
        assertThat(mutations).hasValue(0);
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(count("praxis_bulk_admission")).isZero();
    }

    @Test
    void recoversPendingReceiptByOrdinalAfterPriorAdmissionWithoutRedispatch() throws Exception {
        var faults = new BulkCommitFaultDataSource(dataSource);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults,
                new DataSourceTransactionManager(faults), CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
        var domain = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "mixed-prefix-recovery", "owner-a");
        var admissionCalls = new AtomicInteger();
        var mutationCalls = new AtomicInteger();
        var conflict = kernel.executeUnit(reservation.control(), 0, unit -> {
            admissionCalls.incrementAndGet(); return BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
        }, unit -> { fail("conflicted ordinal must not mutate"); return BulkUnitMutationResult.confirmed(); });
        assertThat(conflict.execution().nextOrdinal()).isEqualTo(1);

        assertThatThrownBy(() -> kernel.executeUnit(conflict.control(), 1, unit -> {
            admissionCalls.incrementAndGet(); return BulkUnitAdmission.admit();
        }, unit -> {
            mutationCalls.incrementAndGet();
            domain.update("update bulk_durable_domain set writes=writes+1 where id=2");
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(writes("bulk_durable_domain", 2)).isEqualTo(1);
        var recovered = kernel.recover(CONTEXT, reservation.executionId(), "mixed-recovery-owner");
        assertThat(recovered.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS);
        assertThat(recovered.execution().nextOrdinal()).isEqualTo(2);
        assertThat(recovered.execution().admissionCount()).isEqualTo(1);
        assertThat(recovered.execution().receiptCount()).isEqualTo(1);
        assertThat(admissionCalls).hasValue(2);
        assertThat(mutationCalls).hasValue(1);
    }

    @Test
    void concurrentReservationsSerializeSameKeyDifferentBindingAndSecondKeyForOneProposal() throws Exception {
        var firstKernel = kernel();
        var secondManager = new DataSourceTransactionManager(dataSource);
        var secondKernel = new JdbcBulkDurableExecution(
                new BulkExecutionInfrastructure(dataSource, secondManager, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
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
        firstKernel.reserve(conflictingProposal.proposal().snapshot().context(), conflictingProposal.proposal().id(),
                "conflict-key", "owner-a", "structural-r1", deadline());
        assertThatThrownBy(() -> secondKernel.reserve(conflictingProposal.proposal().snapshot().context(),
                conflictingProposal.proposal().id(), "conflict-key", "owner-a", "structural-r2", deadline()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CONFLICT));

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
    void activeQuotaSerializesRacesAllowsReplayAndReleasesOnlyOnTerminalCommit() throws Exception {
        var kernel = kernel();
        var reservations = new ArrayList<BulkExecutionReservation>();
        for (int i = 0; i < BulkQuotaLedger.MAX_ACTIVE_DEPLOYMENT - 1; i++) {
            var scope = quotaSubject("active-fill-" + (i % 12));
            var evaluation = persist(twoTargetEvaluation(scope));
            reservations.add(kernel.reserve(scope, evaluation.proposal().id(), "active-fill-key-" + i,
                    "owner-fill-" + i, "structural-r1", deadline()));
        }
        assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                + "where kind='EXECUTION_ACTIVE' and state='ACTIVE'", Integer.class))
                .isEqualTo(BulkQuotaLedger.MAX_ACTIVE_DEPLOYMENT - 1);

        var candidateA = persist(twoTargetEvaluation(quotaSubject("active-race-a")));
        var candidateB = persist(twoTargetEvaluation(quotaSubject("active-race-b")));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reserveAfterBarrier(kernel(), candidateA,
                    "active-race-key-a", "structural-r1", barrier));
            var second = executor.submit(() -> reserveAfterBarrier(kernel(), candidateB,
                    "active-race-key-b", "structural-r1", barrier));
            BulkExecutionReservation winner = null;
            BulkEvaluationSnapshot winningEvaluation = null;
            String winningKey = null;
            try {
                winner = first.get(15, TimeUnit.SECONDS);
                winningEvaluation = candidateA;
                winningKey = "active-race-key-a";
            } catch (java.util.concurrent.ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CAPACITY));
            }
            try {
                var other = second.get(15, TimeUnit.SECONDS);
                assertThat(winner).as("the deployment active limit must serialize both reservations").isNull();
                winner = other;
                winningEvaluation = candidateB;
                winningKey = "active-race-key-b";
            } catch (java.util.concurrent.ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CAPACITY));
            }
            assertThat(winner).isNotNull();
            var replay = kernel.reserve(winningEvaluation.proposal().snapshot().context(),
                    winningEvaluation.proposal().id(), winningKey,
                    "replay-owner", "structural-r1", deadline());
            assertThat(replay.replayed()).isTrue();
            assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                    + "where kind='EXECUTION_ACTIVE' and state='ACTIVE'", Integer.class))
                    .isEqualTo(BulkQuotaLedger.MAX_ACTIVE_DEPLOYMENT);

            var stop = kernel().executeUnit(winner.control(), 0,
                    unit -> BulkUnitAdmission.stop(BulkUnitReasonCode.AUTHORIZATION_REVOKED),
                    unit -> { fail("stop must not execute a domain mutation"); return BulkUnitMutationResult.confirmed(); });
            assertThat(stop.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(observer.queryForObject("select state from praxis_bulk.praxis_bulk_allocation "
                    + "where execution_id=? and kind='EXECUTION_ACTIVE'", String.class, winner.executionId()))
                    .isEqualTo("RELEASED");

            var afterRelease = persist(twoTargetEvaluation(quotaSubject("active-after-release")));
            var replacement = kernel.reserve(afterRelease.proposal().snapshot().context(), afterRelease.proposal().id(),
                    "active-after-release-key", "replacement-owner", "structural-r1", deadline());
            assertThat(replacement.replayed()).isFalse();
            assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                    + "where kind='EXECUTION_ACTIVE' and state='ACTIVE'", Integer.class))
                    .isEqualTo(BulkQuotaLedger.MAX_ACTIVE_DEPLOYMENT);
        }
    }

    @Test
    void terminalQuotaReleaseIsInvisibleUntilCommitAndRollsBackWithExecution() throws Exception {
        var kernel = kernel();
        var evaluation = persist(twoTargetEvaluation(quotaSubject("active-rollback")));
        var reservation = kernel.reserve(evaluation.proposal().snapshot().context(), evaluation.proposal().id(),
                "active-rollback-key", "active-rollback-owner", "structural-r1", deadline());

        transactions.executeWithoutResult(status -> {
            observer.update("update praxis_bulk.praxis_bulk_execution "
                    + "set status='STOPPED', terminal_reason_code='AUTHORIZATION_REVOKED', "
                    + "owner_epoch=owner_epoch+1, terminal_at=clock_timestamp() where execution_id=?",
                    reservation.executionId());
            assertThat(readFromIndependentConnection("select status from praxis_bulk.praxis_bulk_execution "
                    + "where execution_id=?", reservation.executionId())).isEqualTo("RUNNING");
            assertThat(readFromIndependentConnection("select state from praxis_bulk.praxis_bulk_allocation "
                    + "where execution_id=? and kind='EXECUTION_ACTIVE'", reservation.executionId()))
                    .isEqualTo("ACTIVE");
            status.setRollbackOnly();
        });

        assertThat(readFromIndependentConnection("select status from praxis_bulk.praxis_bulk_execution "
                + "where execution_id=?", reservation.executionId())).isEqualTo("RUNNING");
        assertThat(readFromIndependentConnection("select state from praxis_bulk.praxis_bulk_allocation "
                + "where execution_id=? and kind='EXECUTION_ACTIVE'", reservation.executionId()))
                .isEqualTo("ACTIVE");
    }

    private String readFromIndependentConnection(String query, UUID executionId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(query)) {
            statement.setObject(1, executionId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        } catch (java.sql.SQLException error) {
            throw new AssertionError(error);
        }
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
        var value = twoTargetEvaluation();
        BulkPostgresTestSupport.insertLegacyInput(observer, value);
        int v1Checksum = observer.queryForObject(
                "select checksum from praxis_bulk.praxis_bulk_schema_history where version='1'", Integer.class);
        int v2Checksum = observer.queryForObject(
                "select checksum from praxis_bulk.praxis_bulk_schema_history where version='2'", Integer.class);

        assertThat(BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isEqualTo(5);
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
    void v3LegacyEvaluationReceiptStillReplaysAfterV4ButCannotStartAnotherUnit() {
        observer.execute("drop schema if exists praxis_bulk cascade");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .baselineOnMigrate(false).cleanDisabled(true).target("3").load().migrate();

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var expiredFixture = legacyV3Fixture(now.minusSeconds(40), now.minusSeconds(35), now.minusSeconds(20));
        var proposal = expiredFixture.proposal();
        var legacy = expiredFixture.evaluation();
        BulkPostgresTestSupport.insertLegacyInput(observer, legacy);
        UUID executionId = UUID.randomUUID();
        seedV3Execution(proposal, legacy, "legacy-key", executionId, now.minusSeconds(25), now.minusSeconds(10),
                now.minusSeconds(15), true);

        // A second, still-live legacy execution proves that typed eligibility is required
        // before a new unit starts, independently of replay/deadline behavior above.
        var activeFixture = legacyV3Fixture(now.minusSeconds(5), now.minusSeconds(4), now.plusSeconds(60));
        var activeProposal = activeFixture.proposal();
        var activeLegacy = activeFixture.evaluation();
        BulkPostgresTestSupport.insertLegacyInput(observer, activeLegacy);
        UUID activeExecutionId = UUID.randomUUID();
        seedV3Execution(activeProposal, activeLegacy, "legacy-key-active", activeExecutionId, now.minusSeconds(3), now.plusSeconds(30),
                null, false);

        assertThat(BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isEqualTo(4);
        var kernel = kernel();
        var replayReservation = kernel.reserve(CONTEXT, proposal.id(), "legacy-key", "owner-a", "structural-r1",
                now.plusSeconds(60));
        assertThat(replayReservation.replayed()).isTrue();
        var callbacks = new AtomicInteger();
        var receipt = kernel.executeUnit(replayReservation.control(), 0, unit -> {
            callbacks.incrementAndGet(); return BulkUnitAdmission.admit();
        }, unit -> { callbacks.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
        assertThat(receipt.replayed()).isTrue();
        assertThat(receipt.receiptPresent()).isTrue();
        assertThat(receipt.itemStatus()).isEqualTo(BulkItemStatus.CONFIRMED);
        assertThat(callbacks).hasValue(0);
        var recovered = kernel.recover(CONTEXT, executionId, "legacy-recovery-owner");
        assertThat(recovered.receiptCount()).isEqualTo(1);
        assertThat(recovered.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(callbacks).hasValue(0);

        var activeReplay = kernel.reserve(CONTEXT, activeProposal.id(), "legacy-key-active", "owner-a",
                "structural-r1", now.plusSeconds(60));
        assertThat(activeReplay.replayed()).isTrue();
        assertThat(observer.queryForObject(
                "select next_ordinal from praxis_bulk.praxis_bulk_execution where execution_id=?", Integer.class,
                activeExecutionId)).isZero();
        assertThat(observer.queryForObject(
                "select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Integer.class,
                activeExecutionId)).isZero();
        assertThatThrownBy(() -> kernel.executeUnit(activeReplay.control(), 0,
                unit -> { fail("legacy evidence has no new admission decision"); return BulkUnitAdmission.admit(); },
                unit -> { fail("legacy evidence cannot start a new mutation"); return BulkUnitMutationResult.confirmed(); }))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
        assertThat(callbacks).hasValue(0);
    }

    private LegacyV3Fixture legacyV3Fixture(Instant createdAt, Instant evaluatedAt, Instant expiresAt) {
        var modernFixture = twoTargetEvaluation();
        var proposal = new BulkStoredProposal(modernFixture.proposal().id(), createdAt, expiresAt,
                modernFixture.proposal().snapshot());
        var governance = new BulkEvaluationGovernance("legacy-evaluator-r1", "legacy-grants-r1", List.of(
                new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                        "resource:approve", "NEVER_APPLIED", "legacy-policy-r1", createdAt.plusSeconds(1))));
        List<BulkTargetEvidence<?>> legacyTargets = new ArrayList<>();
        modernFixture.targets().forEach(target -> legacyTargets.add(legacyEvidence(target)));
        return new LegacyV3Fixture(proposal,
                BulkEvaluationSnapshot.legacy(proposal, evaluatedAt, legacyTargets, governance));
    }

    private void seedV3Execution(BulkStoredProposal proposal, BulkEvaluationSnapshot legacy, String rawKey, UUID executionId,
            Instant executionCreatedAt, Instant executionDeadline, Instant receiptAt, boolean withReceipt) {
        String keyDigest = testFramedDigest("praxis.bulk.idempotency/1", rawKey);
        String binding = testFramedDigest("praxis.bulk.reservation/1", proposal.id().toString(),
                proposal.snapshot().fingerprint(), legacy.fingerprint(), CONTEXT.namespaceId(), CONTEXT.subjectId(),
                CONTEXT.resourceKey(), CONTEXT.operationRef().operationId(), "structural-r1");
        observer.update("""
                insert into praxis_bulk.praxis_bulk_execution
                (execution_id, proposal_id, namespace_id, subject_id, resource_key, operation_id,
                 idempotency_key_digest, reservation_fingerprint, input_fingerprint, evaluation_fingerprint,
                 structural_revision, owner_id, owner_epoch, status, next_ordinal, target_count, deadline_at,
                 active_attempt_id, active_attempt_ordinal, active_target_digest, active_attempt_epoch,
                 created_at, updated_at, terminal_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'RUNNING', ?, 2, ?, null, null, null, null, ?, ?, null)
                """, executionId, proposal.id(), CONTEXT.namespaceId(), CONTEXT.subjectId(), CONTEXT.resourceKey(),
                CONTEXT.operationRef().operationId(), keyDigest, binding, proposal.snapshot().fingerprint(),
                legacy.fingerprint(), "structural-r1", "owner-a", withReceipt ? 1 : 0,
                executionDeadline.atOffset(java.time.ZoneOffset.UTC),
                executionCreatedAt.atOffset(java.time.ZoneOffset.UTC),
                (withReceipt ? receiptAt : executionCreatedAt).atOffset(java.time.ZoneOffset.UTC));
        if (withReceipt) {
            UUID attemptId = UUID.randomUUID();
            String digest = testFramedDigest("praxis.bulk.unit/1", legacy.fingerprint(), "0", "string", "1", "v1");
            observer.update("""
                insert into praxis_bulk.praxis_bulk_item_receipt
                (execution_id, unit_ordinal, target_digest, expected_version, attempt_id, owner_epoch, outcome, confirmed_at)
                values (?, 0, ?, 'v1', ?, 1, 'CONFIRMED', ?)
                """, executionId, digest, attemptId, receiptAt.atOffset(java.time.ZoneOffset.UTC));
        }
    }

    private record LegacyV3Fixture(BulkStoredProposal proposal, BulkEvaluationSnapshot evaluation) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BulkTargetEvidence<?> legacyEvidence(BulkTargetEvidence<?> target) {
        return BulkTargetEvidence.legacy(target.target(), target.observedVersion(), target.facts(), target.plan());
    }

    private static String testFramedDigest(String framing, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            testFrame(digest, framing);
            for (String value : values) testFrame(digest, value);
            return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private static void testFrame(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    @Test
    void v3ReceiptIsImmutableAndRuntimeRoleHasOnlyTheRequiredWriteSurface() throws Exception {
        var runtimeDataSource = new DriverManagerDataSource(postgres.getJdbcUrl("durable_runtime", "postgres"),
                "durable_runtime", "");
        observer.execute("grant usage on schema praxis_bulk to durable_runtime");
        observer.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to durable_runtime");
        observer.execute("grant update (deployment_id) on praxis_bulk.praxis_bulk_namespace_binding to durable_runtime");
        observer.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to durable_runtime");
        observer.execute("grant select on praxis_bulk.praxis_bulk_deployment_bucket to durable_runtime");
        observer.execute("grant update (deployment_id) on praxis_bulk.praxis_bulk_deployment_bucket to durable_runtime");
        observer.execute("grant select, insert on praxis_bulk.praxis_bulk_subject_bucket to durable_runtime");
        observer.execute("grant update (deployment_id) on praxis_bulk.praxis_bulk_subject_bucket to durable_runtime");
        observer.execute("grant select, insert on praxis_bulk.praxis_bulk_allocation to durable_runtime");
        observer.execute("grant update (state) on praxis_bulk.praxis_bulk_allocation to durable_runtime");
        observer.execute("grant select on praxis_bulk.praxis_bulk_proposal, praxis_bulk.praxis_bulk_evaluation to durable_runtime");
        observer.execute("grant update (proposal_id) on praxis_bulk.praxis_bulk_proposal to durable_runtime");
        observer.execute("grant select, insert, update on praxis_bulk.praxis_bulk_execution to durable_runtime");
        observer.execute("grant select, insert on praxis_bulk.praxis_bulk_item_receipt to durable_runtime");
        observer.execute("grant select, insert on praxis_bulk.praxis_bulk_admission to durable_runtime");
        observer.execute("grant select on praxis_bulk.praxis_bulk_tombstone to durable_runtime");
        var runtimeManager = new DataSourceTransactionManager(runtimeDataSource);
        var runtimeKernel = new JdbcBulkDurableExecution(
                new BulkExecutionInfrastructure(runtimeDataSource, runtimeManager, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
        var reservation = reserve(runtimeKernel, persist(twoTargetEvaluation()), "runtime-key", "runtime-owner");
        runtimeKernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> BulkUnitMutationResult.confirmed());

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

        var first = kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), callback);
        assertThat(first.replayed()).isFalse();
        assertThat(first.outcome()).isEqualTo(BulkUnitOutcome.CONFIRMED);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(writes("bulk_durable_domain", 2)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);

        var retryA = kernel.executeUnit(first.control(), 0, unit -> BulkUnitAdmission.admit(), callback);
        assertThat(retryA.replayed()).isTrue();
        assertThat(retryA.ordinal()).isZero();
        assertThat(callsA).hasValue(1);
        assertThat(callsB).hasValue(0);

        var explicitB = kernel.executeUnit(retryA.control(), 1, unit -> BulkUnitAdmission.admit(), callback);
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
                new BulkExecutionInfrastructure(dataSource, otherManager, otherContext.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
        var called = new AtomicInteger();
        assertThatThrownBy(() -> otherKernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            called.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOf(BulkDurableExecutionException.class);
        assertThat(called).hasValue(0);
        assertThat(writes("bulk_durable_domain", 1)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
    }

    @Test
    void jpaDomainAndReceiptAreAtomicForCommitAndCallbackRollback() throws Exception {
        var infrastructure = new BulkExecutionInfrastructure(dataSource, jpaManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID);
        var kernel = new JdbcBulkDurableExecution(infrastructure);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "jpa-key", "owner-a");

        var committed = kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            entityManager.find(BulkDurableJpaDomainRow.class, 1L).recordWrite();
            entityManager.flush();
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(committed.replayed()).isFalse();
        assertThat(writes("bulk_durable_jpa_domain", 1)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);

        var rollbackReservation = reserve(kernel, persist(twoTargetEvaluation()), "jpa-rollback", "owner-b");
        var rolledBack = kernel.executeUnit(rollbackReservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
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
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
        var faultJdbc = new JdbcTemplate(faults);

        var knownRollback = reserve(kernel, persist(twoTargetEvaluation()), "rollback-key", "owner-a");
        var rollbackCalls = new AtomicInteger();
        var rollback = kernel.executeUnit(knownRollback.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            rollbackCalls.incrementAndGet();
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.ROLLBACK_THEN_FAIL);
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(rollback.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(rollback.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
        assertThat(observer.queryForObject("""
                select active_attempt_id is null and active_attempt_ordinal is null
                   and active_target_digest is null and active_attempt_epoch is null
                from praxis_bulk.praxis_bulk_execution where execution_id=?
                """, Boolean.class, knownRollback.executionId())).isTrue();
        assertThat(rollbackCalls).hasValue(1);
        assertThat(writes("bulk_durable_domain", 1)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThatThrownBy(() -> kernel.executeUnit(rollback.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
            fail("ordinal B must remain blocked after known rollback");
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOf(BulkDurableExecutionException.class);

        var unknown = reserve(kernel, persist(twoTargetEvaluation()), "unknown-key", "owner-b");
        var unknownCalls = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(unknown.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            unknownCalls.incrementAndGet();
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(unknownCalls).hasValue(1);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
        assertThatThrownBy(() -> kernel.executeUnit(unknown.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
            fail("ordinal B must not run while commit B is uncertain");
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));

        var recovery = kernel.recover(CONTEXT, unknown.executionId(), "recovery-owner");
        assertThat(recovery.receiptCount()).isEqualTo(1);
        assertThatThrownBy(() -> kernel.executeUnit(unknown.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> BulkUnitMutationResult.confirmed()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
    }

    @Test
    void ackLostAfterItsOwnCommitStillReplaysAAndNeverDispatchesBImplicitly() throws Exception {
        var faults = new BulkCommitFaultDataSource(dataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "ack-key", "owner-a");
        var callsA = new AtomicInteger();
        var callsB = new AtomicInteger();

        var first = kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
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

        var lockAcquired = new CountDownLatch(1);
        var releaseLock = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var blocker = executor.submit(() -> transactions.executeWithoutResult(status -> {
                observer.queryForObject("select execution_id from praxis_bulk.praxis_bulk_execution where execution_id=? for update",
                        UUID.class, reservation.executionId());
                lockAcquired.countDown();
                try {
                    if (!releaseLock.await(4, TimeUnit.SECONDS)) throw new AssertionError("ACK row lock not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("ACK row lock interrupted", error);
                }
            }));
            assertThat(lockAcquired.await(2, TimeUnit.SECONDS)).isTrue();
            long startedNanos = System.nanoTime();
            assertThatThrownBy(() -> kernel.executeUnit(first.control(), 0,
                    unit -> { callsB.incrementAndGet(); return BulkUnitAdmission.admit(); },
                    unit -> { callsB.incrementAndGet(); return BulkUnitMutationResult.confirmed(); }))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
            assertThat(Duration.ofNanos(System.nanoTime() - startedNanos)).isLessThan(Duration.ofSeconds(3));
            assertThat(callsA).hasValue(1);
            assertThat(callsB).hasValue(0);
            assertThat(countForExecution("praxis_bulk_item_receipt", reservation.executionId())).isEqualTo(1);
            releaseLock.countDown();
            blocker.get(4, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
        }

        var retryA = kernel.executeUnit(first.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            callsB.incrementAndGet();
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(retryA.replayed()).isTrue();
        assertThat(callsB).hasValue(0);
        assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=? and unit_ordinal=0 and unit_deadline_at > confirmed_at",
                Integer.class, reservation.executionId())).isEqualTo(1);

        kernel.executeUnit(retryA.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
            callsB.incrementAndGet();
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=2");
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(callsB).hasValue(1);
        assertThat(writes("bulk_durable_domain", 2)).isEqualTo(1);
    }

    @Test
    void admissionCannotExhaustItsDurableBudgetAndStillInvokeDomainMutation() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "unit-budget-key", "owner-budget");
        var mutations = new AtomicInteger();
        long startedNanos = System.nanoTime();
        var outcome = kernel.executeUnit(reservation.control(), 0, unit -> {
            assertThat(unit.remainingBudget()).isPositive();
            // Model the host's independent Config/pool wait while Metadata's API transaction is idle.
            try (Connection external = dataSource.getConnection();
                    var slowRead = external.prepareStatement("select pg_sleep(5.2)")) {
                slowRead.execute();
            } catch (SQLException unavailable) {
                throw new IllegalStateException("simulated external admission read failed", unavailable);
            }
            return BulkUnitAdmission.admit();
        }, unit -> {
            mutations.incrementAndGet();
            observer.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(Duration.ofNanos(System.nanoTime() - startedNanos)).isLessThan(Duration.ofSeconds(8));
        assertThat(outcome.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(outcome.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.DEADLINE_EXCEEDED);
        assertThat(mutations).hasValue(0);
        assertThat(writes("bulk_durable_domain", 1)).isZero();
        assertThat(countForExecution("praxis_bulk_item_receipt", reservation.executionId())).isZero();
        assertThat(countForExecution("praxis_bulk_admission", reservation.executionId())).isZero();
    }

    @Test
    void replayOfAAfterBCompletesAndDeadlinePassesNeverDispatchesACallbackAgain() {
        var kernel = kernel();
        var evaluation = persist(twoTargetEvaluation());
        var reservation = kernel.reserve(CONTEXT, evaluation.proposal().id(), "completed-replay-key",
                "owner-a", "structural-r1", Instant.now().plusSeconds(3));
        var callsA = new AtomicInteger();
        var callsB = new AtomicInteger();

        var first = kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            callsA.incrementAndGet();
            observer.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        var completed = kernel.executeUnit(first.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
            callsB.incrementAndGet();
            observer.update("update bulk_durable_domain set writes=writes+1 where id=2");
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(completed.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);

        var replayAfterCompletion = kernel.executeUnit(completed.control(), 0, unit -> BulkUnitAdmission.admit(), ignored -> {
            fail("receipt A must replay after B completed; it must not invoke the callback");
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(replayAfterCompletion.replayed()).isTrue();
        assertThat(replayAfterCompletion.ordinal()).isZero();
        assertThat(callsA).hasValue(1);
        assertThat(callsB).hasValue(1);

        try { Thread.sleep(3_100); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        assertThat(observer.queryForObject("""
                select deadline_at < clock_timestamp()
                from praxis_bulk.praxis_bulk_execution where execution_id=?
                """, Boolean.class, reservation.executionId())).isTrue();
        var replayAfterDeadline = kernel.executeUnit(replayAfterCompletion.control(), 0, unit -> BulkUnitAdmission.admit(), ignored -> {
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
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "corrupt-recovery-key", "owner-a");

        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
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
        assertThatThrownBy(() -> kernel.executeUnit(recovery.control(), 1, unit -> BulkUnitAdmission.admit(), ignored -> {
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
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "corrupt-attempt-key", "owner-a");
        var first = kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        assertThatThrownBy(() -> kernel.executeUnit(first.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=2");
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(2);

        corruptPendingReceiptAttemptIdAsFixtureOwner(reservation.executionId(), 1);
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(first.control(), 1, unit -> BulkUnitAdmission.admit(), ignored -> {
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
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "prefix-recovery-key", "owner-a");
        var first = kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        assertThatThrownBy(() -> kernel.executeUnit(first.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
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
        var replayedA = kernel.executeUnit(recovery.control(), 0, unit -> BulkUnitAdmission.admit(), ignored -> {
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
        assertThatThrownBy(() -> kernel.executeUnit(recovery.control(), 1, unit -> BulkUnitAdmission.admit(), ignored -> {
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
            var unit = executor.submit(() -> kernel.executeUnit(reservation.control(), 0, admissionContext -> BulkUnitAdmission.admit(), ignored -> {
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
            assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 1, admissionContext -> BulkUnitAdmission.admit(), ignored -> {
                callsB.incrementAndGet();
                return BulkUnitMutationResult.confirmed();
            })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                    error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            assertThat(callsB).hasValue(0);
        }
    }

    private JdbcBulkDurableExecution kernel() {
        return new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(dataSource, manager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
    }

    private BulkEvaluationSnapshot twoTargetEvaluation() {
        return twoTargetEvaluation(CONTEXT);
    }

    private BulkEvaluationSnapshot twoTargetEvaluation(BulkFingerprintContext context) {
        var reader = new BulkProtocolReader<>(BulkIdentityCodecs.strings());
        var request = reader.<com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.JsonNode>readCommand(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[
                  {"id":"1","expectedVersion":"v1"},{"id":"2","expectedVersion":"v2"}]},"parameters":{"reason":"fixture"}}
                """), com.fasterxml.jackson.databind.JsonNode::deepCopy, com.fasterxml.jackson.databind.JsonNode::deepCopy);
        Instant created = Instant.now().minusSeconds(5);
        var snapshot = BulkIntentSnapshot.command(context, BulkIdentityCodecs.strings(), request,
                com.fasterxml.jackson.databind.JsonNode::deepCopy, com.fasterxml.jackson.databind.JsonNode::deepCopy);
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(600), snapshot,
                BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
        var evidence = new ArrayList<BulkTargetEvidence<?>>();
        evidence.add(new BulkTargetEvidence<>(new BulkTarget<>("1", "v1"), "observed-v1", JSON.objectNode(), JSON.objectNode(), BulkTargetEligibility.executable()));
        evidence.add(new BulkTargetEvidence<>(new BulkTarget<>("2", "v2"), "observed-v2", JSON.objectNode(), JSON.objectNode(), BulkTargetEligibility.executable()));
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
        return kernel.reserve(value.proposal().snapshot().context(), value.proposal().id(), key,
                owner, "structural-r1", deadline());
    }

    private BulkExecutionReservation reserveAfterBarrier(JdbcBulkDurableExecution kernel, BulkEvaluationSnapshot value,
            String key, String revision, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return kernel.reserve(value.proposal().snapshot().context(), value.proposal().id(), key,
                "owner-a", revision, deadline());
    }

    private BulkFingerprintContext quotaSubject(String subject) {
        return new BulkFingerprintContext(CONTEXT.namespaceId(), subject, CONTEXT.resourceKey(),
                CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity());
    }

    private static Instant deadline() {
        return Instant.now().plusSeconds(120);
    }

    private int count(String table) {
        return observer.queryForObject("select count(*) from praxis_bulk." + table, Integer.class);
    }

    private void assertDatabaseWait(String waitEvent, String queryPattern) throws InterruptedException {
        var deadline = Instant.now().plusSeconds(3);
        boolean observed = false;
        while (Instant.now().isBefore(deadline) && !observed) {
            observed = observer.queryForObject("""
                    select exists(select 1 from pg_stat_activity
                      where pid <> pg_backend_pid() and wait_event_type='Lock' and wait_event=?
                        and query ilike ?)
                    """, Boolean.class, waitEvent, queryPattern);
            if (!observed) Thread.sleep(20);
        }
        assertThat(observed).as("database lock wait %s for %s", waitEvent, queryPattern).isTrue();
    }

    private int countForExecution(String table, UUID executionId) {
        if (!List.of("praxis_bulk_item_receipt", "praxis_bulk_admission").contains(table))
            throw new IllegalArgumentException("Unsupported durable execution table");
        return observer.queryForObject("select count(*) from praxis_bulk." + table + " where execution_id=?",
                Integer.class, executionId);
    }

    private int writes(String table, long id) {
        return observer.queryForObject("select writes from " + table + " where id=?", Integer.class, id);
    }

    private void expireDeadlineAsFixtureOwner(UUID executionId) {
        observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_protect_binding");
        observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                + "disable trigger praxis_bulk_item_receipt_reject_mutation");
        try {
            assertThat(observer.update("""
                    update praxis_bulk.praxis_bulk_item_receipt
                    set unit_deadline_at=(select max(confirmed_at) + interval '1 microsecond'
                                          from praxis_bulk.praxis_bulk_item_receipt
                                          where execution_id=?)
                    where execution_id=?
                    """, executionId, executionId)).isGreaterThan(0);
            assertThat(observer.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set deadline_at=(select max(confirmed_at) + interval '1 microsecond'
                                     from praxis_bulk.praxis_bulk_item_receipt
                                     where execution_id=praxis_bulk_execution.execution_id)
                    where execution_id=?
            """, executionId)).isEqualTo(1);
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                    + "enable trigger praxis_bulk_item_receipt_reject_mutation");
            observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_protect_binding");
        }
    }

    private void expireExecutionDeadlineAsFixtureOwner(UUID executionId) {
        observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_protect_binding");
        try {
            assertThat(observer.update("update praxis_bulk.praxis_bulk_execution "
                    + "set deadline_at=(select max(recorded_at) + interval '1 microsecond' "
                    + "from praxis_bulk.praxis_bulk_admission where execution_id=praxis_bulk_execution.execution_id) "
                    + "where execution_id=?", executionId)).isEqualTo(1);
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
