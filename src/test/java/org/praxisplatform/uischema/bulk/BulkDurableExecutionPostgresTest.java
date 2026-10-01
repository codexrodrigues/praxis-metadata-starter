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
    private DataSource runtimeDataSource;
    private JdbcTemplate observer;
    private JdbcTemplate runtimeJdbc;
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
        observer.execute("create role bulk_runtime_test login");
        runtimeDataSource = BulkPostgresTestSupport.runtimeDataSource(postgres);
        runtimeJdbc = new JdbcTemplate(runtimeDataSource);
        manager = new DataSourceTransactionManager(runtimeDataSource);
        transactions = new TransactionTemplate(manager);
        proposals = new JdbcBulkProposalStore(new BulkExecutionInfrastructure(runtimeDataSource, manager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        observer.execute("create table bulk_durable_domain(id bigint primary key, writes integer not null default 0)");
        observer.execute("create table bulk_durable_jpa_domain(id bigint primary key, writes integer not null default 0)");
        observer.execute("grant select, insert, update, delete on bulk_durable_domain, bulk_durable_jpa_domain to bulk_runtime_test");
        observer.execute("create role durable_runtime login");
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(runtimeDataSource);
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
        assertThat(BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isEqualTo(13);
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
    void advanceProcessesFreshReceiptAndAdmissionWithoutAnAmbientTransaction() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "advance-decisions", "owner-a");
        var admissions = new ArrayList<Integer>();
        var mutations = new ArrayList<Integer>();
        kernel.advance(reservation, unit -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            admissions.add(unit.ordinal());
            return unit.ordinal() == 0 ? BulkUnitAdmission.admit()
                    : BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
        }, unit -> {
            mutations.add(unit.ordinal());
            runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(admissions).containsExactly(0, 1);
        assertThat(mutations).containsExactly(0);
        var state = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
        assertThat(state.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS);
        assertThat(state.nextOrdinal()).isEqualTo(2);
        assertThat(state.receiptCount()).isEqualTo(1);
        assertThat(state.admissionCount()).isEqualTo(1);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void advanceReplaysTheReservedOrdinalWithoutSkippingToTheCurrentSuffix() {
        var kernel = kernel();
        var evaluation = persist(twoTargetEvaluation());
        var reservation = reserve(kernel, evaluation, "advance-prefix", "owner-a");
        kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(),
                unit -> BulkUnitMutationResult.confirmed());
        var calls = new AtomicInteger();
        BulkUnitAdmissionCallback admission = unit -> { calls.incrementAndGet(); return BulkUnitAdmission.admit(); };
        BulkUnitMutationCallback mutation = unit -> { calls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); };
        kernel.advance(reservation, admission, mutation);
        assertThat(calls).hasValue(0);
        assertThat(kernel.find(CONTEXT, reservation.executionId()).orElseThrow().nextOrdinal()).isEqualTo(1);
        kernel.advance(reserve(kernel, evaluation, "advance-prefix", "owner-a"), admission, mutation);
        assertThat(calls).hasValue(2);
        assertThat(kernel.find(CONTEXT, reservation.executionId()).orElseThrow().status())
                .isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        kernel.advance(reservation, admission, mutation);
        assertThat(calls).hasValue(2);
        assertThatThrownBy(() -> kernel.advance(reservation, null, mutation)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> kernel.advance(reservation, admission, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> kernel.advance(reservation, admission, mutation)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void advanceValidatesDurableBindingAndFenceBeforeTerminalNoOp() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "advance-binding", "owner-a");
        kernel.advance(reservation, unit -> BulkUnitAdmission.admit(), unit -> BulkUnitMutationResult.confirmed());
        var terminal = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
        BulkUnitAdmissionCallback admission = unit -> { throw new AssertionError("no admission expected"); };
        BulkUnitMutationCallback mutation = unit -> { throw new AssertionError("no mutation expected"); };
        for (var invalid : List.of(
                advanceHint(terminal, UUID.randomUUID(), terminal.targetCount(), terminal.nextOrdinal(), terminal.control()),
                advanceHint(terminal, terminal.proposalId(), terminal.targetCount() + 1, terminal.nextOrdinal(), terminal.control()),
                advanceHint(terminal, terminal.proposalId(), terminal.targetCount(), terminal.nextOrdinal() + 1, terminal.control()))) {
            assertThatThrownBy(() -> kernel.advance(invalid, admission, mutation))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CONFLICT));
        }
        var wrongOwner = new BulkExecutionControl(terminal.executionId(), "other-owner", terminal.control().epoch());
        assertThatThrownBy(() -> kernel.advance(advanceHint(terminal, terminal.proposalId(), terminal.targetCount(),
                terminal.nextOrdinal(), wrongOwner), admission, mutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
        var staleEpoch = new BulkExecutionControl(terminal.executionId(), terminal.control().ownerId(), terminal.control().epoch() + 1);
        assertThatThrownBy(() -> kernel.advance(advanceHint(terminal, terminal.proposalId(), terminal.targetCount(),
                terminal.nextOrdinal(), staleEpoch), admission, mutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
        var foreign = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(runtimeDataSource, manager,
                "another-namespace", BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        // An unprovisioned namespace fails infrastructure attestation before control lookup.
        assertThatThrownBy(() -> foreign.advance(reservation, admission, mutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.UNAVAILABLE));
        BulkPostgresTestSupport.migrate(dataSource, java.util.Map.of(
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                "another-namespace", BulkPostgresTestSupport.DEPLOYMENT_ID));
        // With a valid binding the foreign execution is absent, even though its ID is known.
        assertThatThrownBy(() -> foreign.advance(reservation, admission, mutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_FOUND));
    }

    @Test
    void advanceDoesNotTrustATerminalReservationHintOverRunningDurableControl() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "advance-status", "owner-a");
        var state = reservation.execution();
        var misleading = new BulkExecutionReservation(new BulkExecutionSnapshot(state.executionId(), state.proposalId(),
                BulkDurableExecutionStatus.STOPPED, state.nextOrdinal(), state.targetCount(), 0, 0,
                state.deadlineAt(), state.control(), BulkUnitReasonCode.RECOVERY_STOPPED, null), true);
        var calls = new AtomicInteger();
        kernel.advance(misleading, unit -> BulkUnitAdmission.admit(), unit -> {
            calls.incrementAndGet(); return BulkUnitMutationResult.confirmed();
        });
        assertThat(calls).hasValue(2);
        assertThat(kernel.find(CONTEXT, state.executionId()).orElseThrow().status())
                .isEqualTo(BulkDurableExecutionStatus.COMPLETED);
    }

    @Test
    void advanceStopsAfterKnownRollbackWithoutMutatingTheSuffix() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "advance-rollback", "owner-a");
        var calls = new AtomicInteger();
        kernel.advance(reservation, unit -> BulkUnitAdmission.admit(), unit -> {
            calls.incrementAndGet();
            runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            throw new IllegalStateException("injected domain rollback");
        });
        assertThat(calls).hasValue(1);
        var stopped = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(stopped.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
        assertThat(stopped.nextOrdinal()).isZero();
        assertThat(writes("bulk_durable_domain", 1)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
    }

    @Test
    void advanceReplaysAnOlderPrefixWhilePendingAckAndRequiresAnotherRequestForTheSuffix() {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        var evaluation = persist(threeTargetEvaluation());
        var reservation = reserve(kernel, evaluation, "advance-pending", "owner-a");
        var calls = new ArrayList<Integer>();
        BulkUnitMutationCallback mutation = unit -> {
            calls.add(unit.ordinal());
            if (unit.ordinal() == 1) faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return BulkUnitMutationResult.confirmed();
        };
        assertThatThrownBy(() -> kernel.advance(reservation, unit -> BulkUnitAdmission.admit(), mutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(calls).containsExactly(0, 1);
        kernel.advance(reservation, unit -> { throw new AssertionError("prefix admission"); }, mutation);
        assertThat(kernel.find(CONTEXT, reservation.executionId()).orElseThrow().nextOrdinal()).isEqualTo(1);
        kernel.advance(reserve(kernel, evaluation, "advance-pending", "owner-a"),
                unit -> { throw new AssertionError("pending ACK admission"); }, mutation);
        assertThat(calls).containsExactly(0, 1);
        assertThat(kernel.find(CONTEXT, reservation.executionId()).orElseThrow().nextOrdinal()).isEqualTo(2);
        kernel.advance(reserve(kernel, evaluation, "advance-pending", "owner-a"), unit -> BulkUnitAdmission.admit(), mutation);
        assertThat(calls).containsExactly(0, 1, 2);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(3);
    }

    @Test
    void advanceStopsAfterLostAckOfTheSecondUnitEvenWhenReadbackIsRunning() {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        var evaluation = persist(threeTargetEvaluation());
        var reservation = reserve(kernel, evaluation, "advance-ack", "owner-a");
        var calls = new ArrayList<Integer>();
        BulkUnitMutationCallback mutation = unit -> {
            calls.add(unit.ordinal());
            if (unit.ordinal() == 1) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
                }
            });
            return BulkUnitMutationResult.confirmed();
        };
        kernel.advance(reservation, unit -> BulkUnitAdmission.admit(), mutation);
        assertThat(calls).containsExactly(0, 1);
        var acknowledged = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
        assertThat(acknowledged.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
        assertThat(acknowledged.nextOrdinal()).isEqualTo(2);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(2);
        kernel.advance(reserve(kernel, evaluation, "advance-ack", "owner-a"), unit -> BulkUnitAdmission.admit(), mutation);
        assertThat(calls).containsExactly(0, 1, 2);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(3);
    }

    @Test
    void advancePreservesCommittedPrefixWhenCancellationWinsBeforeTheNextUnit() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "advance-cancel", "owner-a");
        var calls = new AtomicInteger();
        kernel.advance(reservation, unit -> BulkUnitAdmission.admit(), unit -> {
            calls.incrementAndGet();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    observer.update("update praxis_bulk.praxis_bulk_execution set cancel_requested_at=clock_timestamp() where execution_id=?",
                            reservation.executionId());
                }
            });
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(calls).hasValue(1);
        var state = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
        assertThat(state.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(state.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
        assertThat(state.nextOrdinal()).isEqualTo(1);
        assertThat(state.receiptCount()).isEqualTo(1);
    }

    @Test
    void advanceReplaysConfirmedPrefixWhileAnotherOrdinalIsInFlight() {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        var evaluation = persist(twoTargetEvaluation());
        var reservation = reserve(kernel, evaluation, "advance-in-flight", "owner-a");
        kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> BulkUnitMutationResult.confirmed());
        faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
        BulkUnitAdmissionCallback noAdmission = unit -> { throw new AssertionError("no admission expected"); };
        BulkUnitMutationCallback noMutation = unit -> { throw new AssertionError("no mutation expected"); };
        // The prepare marker commits, but its acknowledgement is lost before admission.
        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 1, noAdmission, noMutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(kernel.find(CONTEXT, reservation.executionId()).orElseThrow().status())
                .isEqualTo(BulkDurableExecutionStatus.UNIT_IN_FLIGHT);
        kernel.advance(reservation, noAdmission, noMutation);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
        assertThat(kernel.find(CONTEXT, reservation.executionId()).orElseThrow().nextOrdinal()).isEqualTo(1);
        assertThatThrownBy(() -> kernel.advance(reserve(kernel, evaluation, "advance-in-flight", "owner-a"), noAdmission, noMutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
    }

    @Test
    void advanceReplaysIntactPrefixWhenAnotherReceiptRequiresReconciliation() throws Exception {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        var evaluation = persist(threeTargetEvaluation());
        var reservation = reserve(kernel, evaluation, "advance-reconciliation", "owner-a");
        kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> BulkUnitMutationResult.confirmed());
        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return BulkUnitMutationResult.confirmed();
        })).isInstanceOf(BulkDurableExecutionException.class);
        corruptPendingReceiptEpochAsFixtureOwner(reservation.executionId(), 1);
        // The real cancellation transition detects the inconsistent pending receipt and keeps
        // the same owner/epoch while moving control into reconciliation.
        assertThat(kernel.requestCancel(CONTEXT, reservation.executionId()).status())
                .isEqualTo(BulkDurableExecutionStatus.RECONCILIATION_REQUIRED);
        BulkUnitAdmissionCallback noAdmission = unit -> { throw new AssertionError("no admission expected"); };
        BulkUnitMutationCallback noMutation = unit -> { throw new AssertionError("no mutation expected"); };
        kernel.advance(reservation, noAdmission, noMutation);
        assertThat(kernel.find(CONTEXT, reservation.executionId()).orElseThrow().nextOrdinal()).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(2);
        assertThatThrownBy(() -> kernel.advance(reserve(kernel, evaluation, "advance-reconciliation", "owner-a"), noAdmission, noMutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
    }

    @Test
    void advanceReplaysAfterSuspensionButDoesNotAdmitTheFreshSuffix() throws Exception {
        var kernel = kernel();
        var evaluation = persist(twoTargetEvaluation());
        var reservation = reserve(kernel, evaluation, "advance-suspended", "owner-a");
        kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> BulkUnitMutationResult.confirmed());
        try (var connection = dataSource.getConnection()) {
            assertThat(JdbcBulkOperationControl.transition(connection, CONTEXT.namespaceId(),
                    CONTEXT.operationRef().operationId(), 1, JdbcBulkOperationControl.Target.SUSPENDED, null, null).applied()).isTrue();
        }
        BulkUnitAdmissionCallback noAdmission = unit -> { throw new AssertionError("no admission expected"); };
        BulkUnitMutationCallback noMutation = unit -> { throw new AssertionError("no mutation expected"); };
        kernel.advance(reservation, noAdmission, noMutation);
        assertThatThrownBy(() -> kernel.advance(reserve(kernel, evaluation, "advance-suspended", "owner-a"), noAdmission, noMutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
        assertThat(kernel.find(CONTEXT, reservation.executionId()).orElseThrow().nextOrdinal()).isEqualTo(1);
    }

    @Test
    void advanceReplaysAfterDeadlineButStopsTheFreshSuffix() throws Exception {
        var kernel = kernel();
        var evaluation = persist(twoTargetEvaluation());
        var reservation = kernel.reserve(CONTEXT, evaluation.proposal().id(), "advance-expired", "owner-a",
                "structural-r1", Instant.now().plusSeconds(2));
        kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> BulkUnitMutationResult.confirmed());
        Thread.sleep(Math.max(1, Duration.between(Instant.now(), reservation.execution().deadlineAt()).toMillis() + 50));
        BulkUnitAdmissionCallback noAdmission = unit -> { throw new AssertionError("no admission expected"); };
        BulkUnitMutationCallback noMutation = unit -> { throw new AssertionError("no mutation expected"); };
        kernel.advance(reservation, noAdmission, noMutation);
        kernel.advance(reserve(kernel, evaluation, "advance-expired", "owner-a"), noAdmission, noMutation);
        var stopped = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(stopped.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.DEADLINE_EXCEEDED);
        assertThat(stopped.nextOrdinal()).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
    }

    private static BulkExecutionReservation advanceHint(BulkExecutionSnapshot state, UUID proposalId,
            int targetCount, int nextOrdinal, BulkExecutionControl control) {
        return new BulkExecutionReservation(new BulkExecutionSnapshot(state.executionId(), proposalId, state.status(),
                nextOrdinal, targetCount, state.receiptCount(), state.admissionCount(), state.deadlineAt(), control,
                state.terminalReasonCode(), state.cancelRequestedAt()), true);
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
        assertExecutionChronology(reservation.executionId());
        BulkExecutionMigrator.validate(dataSource, BulkPostgresTestSupport.testRoleConfiguration());
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
        var secondKernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(runtimeDataSource,
                new DataSourceTransactionManager(runtimeDataSource), CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
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
                runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
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
                    runtimeJdbc.queryForObject("select writes from bulk_durable_domain where id=1 for update", Integer.class);
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
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults,
                new DataSourceTransactionManager(faults), CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
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
        var secondManager = new DataSourceTransactionManager(runtimeDataSource);
        var secondKernel = new JdbcBulkDurableExecution(
                new BulkExecutionInfrastructure(runtimeDataSource, secondManager, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
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
            runtimeJdbc.update("update praxis_bulk.praxis_bulk_execution "
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

        assertThat(BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isEqualTo(11);
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

        assertThat(BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isEqualTo(10);
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
        observer.execute("grant select, insert, update, delete on bulk_durable_domain, bulk_durable_jpa_domain to durable_runtime");
        var runtimeRoles = new BulkExecutionRoleConfiguration("postgres",
                java.util.Set.of("bulk_runtime_test", "durable_runtime"), java.util.Set.of(), java.util.Set.of());
        BulkExecutionMigrator.validate(dataSource, runtimeRoles);
        var runtimeManager = new DataSourceTransactionManager(runtimeDataSource);
        var runtimeKernel = new JdbcBulkDurableExecution(
                new BulkExecutionInfrastructure(runtimeDataSource, runtimeManager, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, runtimeRoles));
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
            runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=?", unit.ordinal() + 1L);
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
        var otherManager = new DataSourceTransactionManager(runtimeDataSource);
        var otherKernel = new JdbcBulkDurableExecution(
                new BulkExecutionInfrastructure(runtimeDataSource, otherManager, otherContext.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
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
        var infrastructure = new BulkExecutionInfrastructure(runtimeDataSource, jpaManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration());
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
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
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
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
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
                runtimeJdbc.queryForObject("select execution_id from praxis_bulk.praxis_bulk_execution where execution_id=? for update",
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
            runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
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
            runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        var completed = kernel.executeUnit(first.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
            callsB.incrementAndGet();
            runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=2");
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
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
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
        var conservativeRead = kernel.inspectConsistent(CONTEXT, reservation.executionId());
        assertThat(conservativeRead.execution().receiptCount()).isZero();
        assertThat(conservativeRead.confirmed()).isZero();
        assertThat(conservativeRead.unknown()).isEqualTo(2);
        var summary = kernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(summary.status()).isEqualTo(BulkExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(summary.totals().unknown()).isEqualTo(2);
        assertThat(summary.totals().confirmed()).isZero();
        assertThat(summary.totals().notProcessed()).isZero();
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
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
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
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
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
        var conservativeRead = kernel.inspectConsistent(CONTEXT, reservation.executionId());
        assertThat(conservativeRead.execution().receiptCount()).isEqualTo(1);
        assertThat(conservativeRead.confirmed()).isEqualTo(1);
        assertThat(conservativeRead.unknown()).isEqualTo(1);
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
                runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
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

    @Test
    void cancellationBeforeFirstUnitIsDurableIdempotentScopedAndReleasesQuota() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-before-unit", "owner-a");
        var cancelled = kernel.requestCancel(CONTEXT, reservation.executionId());
        assertThat(cancelled.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(cancelled.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
        assertThat(cancelled.cancelRequestedAt()).isNotNull();
        assertThat(cancelled.nextOrdinal()).isZero();
        assertExecutionChronology(reservation.executionId());
        assertThat(observer.queryForObject("""
                select cancel_requested_at <= terminal_at and cancel_requested_at <= updated_at
                from praxis_bulk.praxis_bulk_execution where execution_id=?
                """, Boolean.class, reservation.executionId())).isTrue();
        assertThat(kernel.requestCancel(CONTEXT, reservation.executionId()).cancelRequestedAt())
                .isEqualTo(cancelled.cancelRequestedAt());
        assertThat(observer.queryForObject("select state from praxis_bulk.praxis_bulk_allocation where execution_id=?",
                String.class, reservation.executionId())).isEqualTo("RELEASED");

        var anotherSubject = quotaSubject("another-subject");
        assertThatThrownBy(() -> kernel.requestCancel(anotherSubject, reservation.executionId()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_FOUND));
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 0,
                unit -> { calls.incrementAndGet(); return BulkUnitAdmission.admit(); },
                unit -> { calls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); }))
                .isInstanceOf(BulkDurableExecutionException.class);
        assertThat(calls).hasValue(0);
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(count("praxis_bulk_admission")).isZero();
        BulkExecutionMigrator.validate(dataSource, BulkPostgresTestSupport.testRoleConfiguration());
    }

    @Test
    void internalConsistentReadValidatesOneLiveScopeAndHidesAnotherSubject() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-live", "owner-a");
        var live = kernel.inspectConsistent(CONTEXT, reservation.executionId());
        assertThat(live.kind()).isEqualTo(BulkConsistentExecutionRead.Kind.LIVE);
        assertThat(live.execution().status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
        assertThat(live.execution().nextOrdinal()).isZero();
        assertThat(live.createdAt()).isNotNull();
        assertThat(live.updatedAt()).isNotNull();
        assertThat(live.terminalAt()).isNull();
        assertThat(live.confirmed() + live.unchanged() + live.denied() + live.invalid() + live.conflict()).isZero();
        var foreign = kernel.inspectConsistent(quotaSubject("foreign-subject"), reservation.executionId());
        assertThat(foreign.kind()).isEqualTo(BulkConsistentExecutionRead.Kind.ABSENT);
        assertThat(foreign.execution()).isNull();
        assertThat(foreign.tombstoneTerminalStatus()).isNull();
    }

    @Test
    void internalSummaryProjectsCertifiedCountsAndTerminalSuffixFromPostgres() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-summary-counts", "owner-a");
        var initial = kernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(initial.status()).isEqualTo(BulkExecutionStatus.RUNNING);
        assertThat(initial.totals().pending()).isEqualTo(2);
        assertThat(initial.totals().confirmed()).isZero();
        assertThat(initial.totals().targetCount()).isEqualTo(2);

        kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed());
        var partial = kernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(partial.status()).isEqualTo(BulkExecutionStatus.RUNNING);
        assertThat(partial.totals().confirmed()).isEqualTo(1);
        assertThat(partial.totals().pending()).isEqualTo(1);
        assertThat(partial.terminalAt()).isNull();

        kernel.executeUnit(reservation.control(), 1,
                ignored -> BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED),
                ignored -> { fail("denied admission must not mutate"); return BulkUnitMutationResult.confirmed(); });
        var terminal = kernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(terminal.status()).isEqualTo(BulkExecutionStatus.COMPLETED_WITH_ERRORS);
        assertThat(terminal.totals().confirmed()).isEqualTo(1);
        assertThat(terminal.totals().denied()).isEqualTo(1);
        assertThat(terminal.totals().pending()).isZero();
        assertThat(terminal.terminalAt()).isNotNull();
        assertExecutionChronology(reservation.executionId());

        var cancelledReservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-summary-cancel", "owner-a");
        kernel.executeUnit(cancelledReservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.unchanged());
        kernel.requestCancel(CONTEXT, cancelledReservation.executionId());
        var cancelled = kernel.summarizeConsistent(CONTEXT, cancelledReservation.executionId());
        assertThat(cancelled.status()).isEqualTo(BulkExecutionStatus.CANCELLED);
        assertThat(cancelled.totals().unchanged()).isEqualTo(1);
        assertThat(cancelled.totals().notProcessed()).isEqualTo(1);
        assertThat(cancelled.totals().pending()).isZero();
        assertExecutionChronology(cancelledReservation.executionId());
        assertThat(countForExecution("praxis_bulk_item_receipt", cancelledReservation.executionId())).isEqualTo(1);
        assertThat(countForExecution("praxis_bulk_admission", cancelledReservation.executionId())).isZero();
        assertThat(kernel.summarizeConsistent(quotaSubject("foreign-subject"), cancelledReservation.executionId()).kind())
                .isEqualTo(BulkExecutionSummary.Kind.ABSENT);

        var stoppedReservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-summary-stop", "owner-a");
        kernel.executeUnit(stoppedReservation.control(), 0,
                ignored -> BulkUnitAdmission.stop(BulkUnitReasonCode.AUTHORIZATION_REVOKED),
                ignored -> { fail("stop must not mutate"); return BulkUnitMutationResult.confirmed(); });
        var stopped = kernel.summarizeConsistent(CONTEXT, stoppedReservation.executionId());
        assertThat(stopped.status()).isEqualTo(BulkExecutionStatus.STOPPED);
        assertThat(stopped.totals().notProcessed()).isEqualTo(2);
        assertExecutionChronology(stoppedReservation.executionId());

        var completedReservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-summary-complete", "owner-a");
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            kernel.executeUnit(completedReservation.control(), ordinal, ignored -> BulkUnitAdmission.admit(),
                    ignored -> BulkUnitMutationResult.confirmed());
        }
        var completed = kernel.summarizeConsistent(CONTEXT, completedReservation.executionId());
        assertThat(completed.status()).isEqualTo(BulkExecutionStatus.COMPLETED);
        assertThat(completed.totals().confirmed()).isEqualTo(2);
        assertThat(completed.totals().pending()).isZero();
    }

    @Test
    void internalSummaryRejectsStoppedSuffixWithPhysicalAdmission() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-summary-stopped-drift", "owner-a");
        kernel.requestCancel(CONTEXT, reservation.executionId());
        assertThat(kernel.summarizeConsistent(CONTEXT, reservation.executionId()).totals().notProcessed())
                .isEqualTo(2);
        observer.execute("alter table praxis_bulk.praxis_bulk_admission "
                + "disable trigger praxis_bulk_admission_guard_terminal");
        try {
            observer.update("""
                    insert into praxis_bulk.praxis_bulk_admission
                      (execution_id, unit_ordinal, target_digest, expected_version, attempt_id,
                       owner_epoch, outcome, reason_code, recorded_at)
                    select e.execution_id, m.ordinal, m.target_digest, 'v2', ?, e.owner_epoch,
                           'DENIED', 'TARGET_DENIED', clock_timestamp()
                    from praxis_bulk.praxis_bulk_execution e
                    join praxis_bulk.praxis_bulk_target_manifest m on m.proposal_id=e.proposal_id
                    where e.execution_id=? and m.ordinal=1
                    """, UUID.randomUUID(), reservation.executionId());
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_admission "
                    + "enable trigger praxis_bulk_admission_guard_terminal");
        }
        assertThatThrownBy(() -> kernel.summarizeConsistent(CONTEXT, reservation.executionId()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
    }

    @Test
    void internalConsistentReadUsesPhysicalReadOnlyRepeatableReadWithJpaManager() {
        var reservation = reserve(kernel(), persist(twoTargetEvaluation()), "rs3-jpa-read", "owner-a");
        var jpaKernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(runtimeDataSource,
                jpaManager, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        var live = jpaKernel.inspectConsistent(CONTEXT, reservation.executionId());
        assertThat(live.kind()).isEqualTo(BulkConsistentExecutionRead.Kind.LIVE);
        assertThat(live.execution().status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
    }

    @Test
    void internalResultPagesCertifyPrefixAndStoppedSuffixWithoutExposingProtectedPayload() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs4-prefix", "owner-a");
        var reader = resultReader(runtimeDataSource, manager);
        assertThat(reader.read(CONTEXT, reservation.executionId(), -1, 1).items()).isEmpty();
        var first = kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed());
        assertThat(reader.read(CONTEXT, reservation.executionId(), -1, 1).items())
                .extracting(BulkExecutionResultsReader.Item::status)
                .containsExactly(BulkItemStatus.CONFIRMED);
        kernel.requestCancel(CONTEXT, reservation.executionId());
        var page0 = reader.read(CONTEXT, reservation.executionId(), -1, 1);
        assertThat(page0.kind()).isEqualTo(BulkExecutionResultsReader.Kind.LIVE);
        assertThat(page0.hasMore()).isTrue();
        assertThat(page0.lastReturnedOrdinal()).isZero();
        assertThat(page0.items().getFirst().status()).isEqualTo(BulkItemStatus.CONFIRMED);
        assertThat(page0.items().getFirst().wireIdentity()).isEqualTo(BulkSnapshotStorageCodec.json(JSON.textNode("1")));
        assertThat(page0.toString()).doesNotContain("1", "reason", "v1");
        assertThat(page0.items().getFirst().toString()).doesNotContain("1", "v1");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(page0))
                .doesNotContain("wireIdentity", "v1", "CONFIRMED", "reason");
        var page1 = reader.read(CONTEXT, reservation.executionId(), 0, 1,
                page0.watermarkExclusive());
        assertThat(page1.items()).extracting(BulkExecutionResultsReader.Item::status)
                .containsExactly(BulkItemStatus.NOT_PROCESSED);
        assertThat(page1.hasMore()).isFalse();
        assertThat(page1.items().getFirst().reasonCode()).isNull();
        assertThat(reader.read(quotaSubject("other-subject"), reservation.executionId(), -1, 1).kind())
                .isEqualTo(BulkExecutionResultsReader.Kind.ABSENT);
        assertThat(first.execution().nextOrdinal()).isEqualTo(1);
    }

    @Test
    void internalResultPageRejectsManifestAndReceiptDriftWithoutReturningPartialItems() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs4-corrupt", "owner-a");
        kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed());
        var reader = resultReader(runtimeDataSource, manager);
        assertThat(reader.read(CONTEXT, reservation.executionId(), -1, 1).items()).hasSize(1);
        observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                + "disable trigger praxis_bulk_item_receipt_reject_mutation");
        try {
            observer.update("update praxis_bulk.praxis_bulk_item_receipt set expected_version='drift' "
                    + "where execution_id=? and unit_ordinal=0", reservation.executionId());
            assertResultCorrupt(reader, reservation.executionId());
            observer.update("update praxis_bulk.praxis_bulk_item_receipt set expected_version='v1' "
                    + "where execution_id=? and unit_ordinal=0", reservation.executionId());
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                    + "enable trigger praxis_bulk_item_receipt_reject_mutation");
        }
        observer.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                + "disable trigger praxis_bulk_target_manifest_immutable");
        try {
            observer.update("update praxis_bulk.praxis_bulk_target_manifest set target_digest=? "
                    + "where proposal_id=? and ordinal=0", "sha256:" + "0".repeat(64),
                    reservation.proposalId());
            assertResultCorrupt(reader, reservation.executionId());
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                    + "enable trigger praxis_bulk_target_manifest_immutable");
        }
    }

    @Test
    void internalResultPageReadsGovernedAdmissionWithoutCallingDomain() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs4-admission", "owner-a");
        var calls = new AtomicInteger();
        kernel.executeUnit(reservation.control(), 0,
                ignored -> BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED),
                ignored -> { calls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
        var page = resultReader(runtimeDataSource, manager).read(CONTEXT,
                reservation.executionId(), -1, 200);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().status()).isEqualTo(BulkItemStatus.DENIED);
        assertThat(page.items().getFirst().reasonCode()).isEqualTo(BulkUnitReasonCode.TARGET_DENIED);
        assertThat(page.hasMore()).isFalse();
        assertThat(calls).hasValue(0);
    }

    @Test
    void internalResultPageOmitsUncertainReceiptSuffixAfterConservativeRecovery() throws Exception {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs4-reconcile", "owner-a");
        var first = kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed());
        assertThatThrownBy(() -> kernel.executeUnit(first.control(), 1,
                ignored -> BulkUnitAdmission.admit(), ignored -> {
                    faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
                    return BulkUnitMutationResult.confirmed();
                })).isInstanceOf(BulkDurableExecutionException.class);
        corruptPendingReceiptEpochAsFixtureOwner(reservation.executionId(), 1);
        var recovery = kernel.recover(CONTEXT, reservation.executionId(), "recovery-owner");
        assertThat(recovery.status()).isEqualTo(BulkDurableExecutionStatus.RECONCILIATION_REQUIRED);
        var reader = resultReader(runtimeDataSource, manager);
        var certified = reader.read(CONTEXT, reservation.executionId(), -1, 200);
        assertThat(certified.items()).extracting(BulkExecutionResultsReader.Item::status)
                .containsExactly(BulkItemStatus.CONFIRMED);
        assertThat(certified.nextOrdinal()).isEqualTo(1);
        assertThat(certified.hasMore()).isFalse();
        assertThat(certified.watermarkExclusive()).isEqualTo(1);
        assertThat(reader.read(CONTEXT, reservation.executionId(), -1, 200,
                certified.watermarkExclusive()).items()).hasSize(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(2);
    }

    @Test
    void internalResultPageNeverWidensTheFirstWatermarkAfterStop() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs4-fixed-window", "owner-a");
        kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed());
        var reader = resultReader(runtimeDataSource, manager);
        var first = reader.read(CONTEXT, reservation.executionId(), -1, 1);
        assertThat(first.watermarkExclusive()).isEqualTo(1);
        assertThat(first.items()).extracting(BulkExecutionResultsReader.Item::status)
                .containsExactly(BulkItemStatus.CONFIRMED);
        kernel.requestCancel(CONTEXT, reservation.executionId());
        var sameWindow = reader.read(CONTEXT, reservation.executionId(), -1, 1,
                first.watermarkExclusive());
        assertThat(sameWindow.watermarkExclusive()).isEqualTo(1);
        assertThat(sameWindow.hasMore()).isFalse();
        assertThat(sameWindow.items()).extracting(BulkExecutionResultsReader.Item::status)
                .containsExactly(BulkItemStatus.CONFIRMED);
        assertThatThrownBy(() -> reader.read(CONTEXT, reservation.executionId(), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        var freshWindow = reader.read(CONTEXT, reservation.executionId(), -1, 1);
        assertThat(freshWindow.watermarkExclusive()).isEqualTo(2);
        assertThat(freshWindow.hasMore()).isTrue();
    }

    @Test
    void internalResultPageRejectsTerminalStatusThatContradictsAdmissionPresence() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs4-terminal-drift", "owner-a");
        var first = kernel.executeUnit(reservation.control(), 0,
                ignored -> BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED),
                ignored -> { fail("denied unit cannot mutate"); return BulkUnitMutationResult.confirmed(); });
        kernel.executeUnit(first.control(), 1, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed());
        var reader = resultReader(runtimeDataSource, manager);
        assertThat(reader.read(CONTEXT, reservation.executionId(), -1, 2).executionStatus())
                .isEqualTo(BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS);
        observer.execute("alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_guard_terminal");
        try {
            observer.update("update praxis_bulk.praxis_bulk_execution set status='COMPLETED' "
                    + "where execution_id=?", reservation.executionId());
            assertResultCorrupt(reader, reservation.executionId());
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_guard_terminal");
        }
    }

    @Test
    void internalResultPageStaysOnOldSnapshotAcrossReceiptAckCommit() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs4-ack-race", "owner-a");
        var paused = new BulkReadPauseDataSource(runtimeDataSource);
        var reader = resultReader(paused, new DataSourceTransactionManager(paused));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var beforeFuture = executor.submit(() -> {
                paused.arm(Thread.currentThread());
                return reader.read(CONTEXT, reservation.executionId(), -1, 1);
            });
            try {
                assertThat(paused.awaitObservation(5, TimeUnit.SECONDS)).isTrue();
                kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                        ignored -> BulkUnitMutationResult.confirmed());
                paused.release();
                var before = beforeFuture.get(5, TimeUnit.SECONDS);
                assertThat(before.nextOrdinal()).isZero();
                assertThat(before.items()).isEmpty();
                assertThat(before.watermarkExclusive()).isZero();
                assertThat(resultReader(runtimeDataSource, manager).read(CONTEXT,
                        reservation.executionId(), -1, 1, before.watermarkExclusive()).items()).isEmpty();
                var after = resultReader(runtimeDataSource, manager).read(CONTEXT,
                        reservation.executionId(), -1, 1);
                assertThat(after.nextOrdinal()).isEqualTo(1);
                assertThat(after.items()).extracting(BulkExecutionResultsReader.Item::status)
                        .containsExactly(BulkItemStatus.CONFIRMED);
            } finally {
                paused.release();
            }
        }
    }

    @Test
    void internalResultPageUsesBoundedKeysetOnTenThousandTargets() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(tenThousandTargetEvaluation()), "rs4-10k", "owner-a");
        kernel.requestCancel(CONTEXT, reservation.executionId());
        var reader = resultReader(runtimeDataSource, manager);
        long started = System.nanoTime();
        var first = reader.read(CONTEXT, reservation.executionId(), -1, 200);
        var last = reader.read(CONTEXT, reservation.executionId(), 9799, 200,
                first.watermarkExclusive());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("RS4 10k two bounded pages PostgreSQL ms=" + millis);
        assertThat(first.items()).hasSize(200);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.lastReturnedOrdinal()).isEqualTo(199);
        assertThat(last.items()).hasSize(200);
        assertThat(last.items().getFirst().ordinal()).isEqualTo(9800);
        assertThat(last.items().getLast().ordinal()).isEqualTo(9999);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.items()).allSatisfy(item -> assertThat(item.status())
                .isEqualTo(BulkItemStatus.NOT_PROCESSED));
    }

    @Test
    void internalResultPageKeepsLiveSnapshotAcrossPurgeAndThenSeesScopedTombstone() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs4-purge-race", "owner-a");
        kernel.requestCancel(CONTEXT, reservation.executionId());
        ageTerminalForRetentionAsFixtureOwner(reservation.executionId());
        var paused = new BulkReadPauseDataSource(runtimeDataSource);
        var reader = resultReader(paused, new DataSourceTransactionManager(paused));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldSnapshot = executor.submit(() -> {
                paused.arm(Thread.currentThread());
                return reader.read(CONTEXT, reservation.executionId(), -1, 1);
            });
            try {
                assertThat(paused.awaitObservation(5, TimeUnit.SECONDS)).isTrue();
                observer.execute("grant praxis_bulk_retention_executor to postgres");
                var ownerTx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                Boolean purged = ownerTx.execute(status -> {
                    observer.execute("set local role praxis_bulk_retention_executor");
                    return observer.queryForObject("select praxis_bulk.purge_terminal_execution(?)",
                            Boolean.class, reservation.executionId());
                });
                assertThat(purged).isTrue();
                observer.execute("revoke praxis_bulk_retention_executor from postgres");
                paused.release();
                assertThat(oldSnapshot.get(5, TimeUnit.SECONDS).kind())
                        .isEqualTo(BulkExecutionResultsReader.Kind.LIVE);
                var after = resultReader(runtimeDataSource, manager).read(CONTEXT,
                        reservation.executionId(), -1, 1);
                assertThat(after.kind()).isEqualTo(BulkExecutionResultsReader.Kind.TOMBSTONE);
                assertThat(after.tombstoneTerminalStatus()).isEqualTo("CANCELLED");
                assertThat(resultReader(runtimeDataSource, manager).read(
                        quotaSubject("other-subject"), reservation.executionId(), -1, 1).kind())
                        .isEqualTo(BulkExecutionResultsReader.Kind.ABSENT);
            } finally {
                paused.release();
                observer.execute("revoke praxis_bulk_retention_executor from postgres");
            }
        }
    }

    @Test
    void internalResultPageCountsLookaheadRowBeforeReturningAnyItems() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs4-budget", "owner-a");
        kernel.requestCancel(CONTEXT, reservation.executionId());
        var reader = resultReader(runtimeDataSource, manager);
        observer.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                + "disable trigger praxis_bulk_target_manifest_immutable");
        try {
            inflateManifestRowsForBudget(reservation.proposalId(), 4 * 1024 * 1024);
            var below = reader.read(CONTEXT, reservation.executionId(), -1, 1);
            assertThat(below.items()).hasSize(1);
            assertThat(below.hasMore()).isTrue();
            inflateManifestRowsForBudget(reservation.proposalId(), 5 * 1024 * 1024);
            assertThatThrownBy(() -> reader.read(CONTEXT, reservation.executionId(), -1, 1))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason())
                                    .isEqualTo(BulkDurableExecutionException.Reason.UNAVAILABLE));
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                    + "enable trigger praxis_bulk_target_manifest_immutable");
        }
    }

    private void inflateManifestRowsForBudget(UUID proposalId, int componentLength) {
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            String identity = (ordinal == 0 ? "a" : "b") + "x".repeat(componentLength - 1);
            String version = "v".repeat(componentLength);
            byte[] wire = BulkSnapshotStorageCodec.json(JSON.textNode(identity));
            String evaluation = observer.queryForObject("select evaluation_fingerprint "
                    + "from praxis_bulk.praxis_bulk_target_manifest where proposal_id=? and ordinal=?",
                    String.class, proposalId, ordinal);
            observer.update("update praxis_bulk.praxis_bulk_target_manifest "
                    + "set wire_identity=?, wire_identity_digest=?, expected_version=?, target_digest=? "
                    + "where proposal_id=? and ordinal=?", wire, BulkTargetDigest.wireIdentity(wire),
                    version.getBytes(StandardCharsets.UTF_8),
                    BulkTargetDigest.of(evaluation, ordinal, identity, version), proposalId, ordinal);
        }
    }

    private void assertResultCorrupt(BulkExecutionResultsReader reader, UUID executionId) {
        assertThatThrownBy(() -> reader.read(CONTEXT, executionId, -1, 1))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason())
                                .isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
    }

    private BulkExecutionResultsReader resultReader(DataSource source, DataSourceTransactionManager tx) {
        return new BulkExecutionResultsReader(new BulkExecutionInfrastructure(source, tx,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
    }

    @Test
    void internalConsistentReadRejectsMutationOnThePhysicalPostgresConnection() {
        var infrastructure = new BulkExecutionInfrastructure(runtimeDataSource, manager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration());
        String sqlState = infrastructure.withConsistentRead(connection -> {
            try (var statement = connection.prepareStatement(
                    "update bulk_durable_domain set writes=writes+1 where id=1")) {
                statement.executeUpdate();
                throw new AssertionError("PostgreSQL accepted a write in the read-only snapshot");
            } catch (SQLException expected) {
                return expected.getSQLState();
            }
        });
        assertThat(sqlState).isEqualTo("25006");
        assertThat(observer.queryForObject("select writes from bulk_durable_domain where id=1", Integer.class))
                .isZero();
    }

    @Test
    void internalConsistentReadDoesNotMixExecutionBeforeReceiptCommitWithEvidenceAfterCommit() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-receipt-race", "owner-a");
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var paused = new BulkReadPauseDataSource(runtimeDataSource);
        var reader = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(paused,
                new DataSourceTransactionManager(paused), CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var unit = executor.submit(() -> kernel.executeUnit(reservation.control(), 0,
                    ignored -> BulkUnitAdmission.admit(), ignored -> {
                        runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
                        callbackEntered.countDown();
                        try {
                            if (!releaseCallback.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("receipt writer was not released");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("receipt writer interrupted", error);
                        }
                        return BulkUnitMutationResult.confirmed();
                    }));
            try {
                assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
                var snapshot = executor.submit(() -> {
                    paused.arm(Thread.currentThread());
                    return reader.inspectConsistent(CONTEXT, reservation.executionId());
                });
                boolean observed = paused.awaitObservation(5, TimeUnit.SECONDS);
                if (!observed && snapshot.isDone()) snapshot.get(1, TimeUnit.SECONDS);
                assertThat(observed).isTrue();
                releaseCallback.countDown();
                assertThat(unit.get(5, TimeUnit.SECONDS).execution().nextOrdinal()).isEqualTo(1);
                paused.release();
                var before = snapshot.get(5, TimeUnit.SECONDS);
                assertThat(before.kind()).isEqualTo(BulkConsistentExecutionRead.Kind.LIVE);
                assertThat(before.execution().status()).isEqualTo(BulkDurableExecutionStatus.UNIT_IN_FLIGHT);
                assertThat(before.execution().nextOrdinal()).isZero();
                assertThat(before.confirmed()).isZero();
                assertThat(BulkExecutionSummary.from(before).totals().pending()).isEqualTo(2);
                var after = kernel.inspectConsistent(CONTEXT, reservation.executionId());
                assertThat(after.execution().status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
                assertThat(after.execution().nextOrdinal()).isEqualTo(1);
                assertThat(after.confirmed()).isEqualTo(1);
                assertThat(BulkExecutionSummary.from(after).totals().confirmed()).isEqualTo(1);
            } finally {
                releaseCallback.countDown();
                paused.release();
            }
        }
    }

    @Test
    void internalConsistentReadNeverMixesPurgedTombstoneWithTheEarlierLiveSnapshot() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-purge-race", "owner-a");
        kernel.requestCancel(CONTEXT, reservation.executionId());
        ageTerminalForRetentionAsFixtureOwner(reservation.executionId());
        assertThat(kernel.inspectConsistent(CONTEXT, reservation.executionId()).kind())
                .isEqualTo(BulkConsistentExecutionRead.Kind.LIVE);
        var paused = new BulkReadPauseDataSource(runtimeDataSource);
        var reader = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(paused,
                new DataSourceTransactionManager(paused), CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var snapshot = executor.submit(() -> {
                paused.arm(Thread.currentThread());
                return reader.inspectConsistent(CONTEXT, reservation.executionId());
            });
            try {
                boolean observed = paused.awaitObservation(5, TimeUnit.SECONDS);
                if (!observed && snapshot.isDone()) snapshot.get(1, TimeUnit.SECONDS);
                assertThat(observed).isTrue();
                observer.execute("grant praxis_bulk_retention_executor to postgres");
                var ownerTx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                Boolean purged = ownerTx.execute(status -> {
                    observer.execute("set local role praxis_bulk_retention_executor");
                    return observer.queryForObject("select praxis_bulk.purge_terminal_execution(?)", Boolean.class,
                            reservation.executionId());
                });
                assertThat(purged).isTrue();
                paused.release();
                var before = snapshot.get(5, TimeUnit.SECONDS);
                assertThat(before.kind()).isEqualTo(BulkConsistentExecutionRead.Kind.LIVE);
                assertThat(before.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
                observer.execute("revoke praxis_bulk_retention_executor from postgres");
                var after = kernel.inspectConsistent(CONTEXT, reservation.executionId());
                assertThat(after.kind()).isEqualTo(BulkConsistentExecutionRead.Kind.TOMBSTONE);
                assertThat(after.execution()).isNull();
                assertThat(after.tombstoneTerminalStatus()).isEqualTo("CANCELLED");
                var summary = kernel.summarizeConsistent(CONTEXT, reservation.executionId());
                assertThat(summary.kind()).isEqualTo(BulkExecutionSummary.Kind.TOMBSTONE);
                assertThat(summary.tombstoneStatus()).isEqualTo(BulkExecutionStatus.CANCELLED);
                assertThat(summary.totals()).isNull();
                assertThat(kernel.inspectConsistent(quotaSubject("foreign-subject"), reservation.executionId()).kind())
                        .isEqualTo(BulkConsistentExecutionRead.Kind.ABSENT);
            } finally {
                paused.release();
            }
        } finally {
            observer.execute("revoke praxis_bulk_retention_executor from postgres");
        }
    }

    @Test
    void internalConsistentReadFailsClosedOnReceiptVersionDriftAndDuplicateOrdinal() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-corrupt-receipt", "owner-a");
        kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                ignored -> BulkUnitMutationResult.confirmed());
        assertThat(kernel.inspectConsistent(CONTEXT, reservation.executionId()).confirmed()).isEqualTo(1);

        observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                + "disable trigger praxis_bulk_item_receipt_reject_mutation");
        try {
            observer.update("update praxis_bulk.praxis_bulk_item_receipt set expected_version='drift' "
                    + "where execution_id=? and unit_ordinal=0", reservation.executionId());
            assertThatThrownBy(() -> kernel.inspectConsistent(CONTEXT, reservation.executionId()))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
            observer.update("update praxis_bulk.praxis_bulk_item_receipt set expected_version='v1' "
                    + "where execution_id=? and unit_ordinal=0", reservation.executionId());
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_item_receipt "
                    + "enable trigger praxis_bulk_item_receipt_reject_mutation");
        }

        observer.execute("alter table praxis_bulk.praxis_bulk_admission "
                + "disable trigger praxis_bulk_admission_guard_terminal");
        try {
            observer.update("""
                    insert into praxis_bulk.praxis_bulk_admission
                      (execution_id, unit_ordinal, target_digest, expected_version, attempt_id,
                       owner_epoch, outcome, reason_code, recorded_at)
                    select execution_id, unit_ordinal, target_digest, expected_version, ?,
                           owner_epoch, 'DENIED', 'TARGET_DENIED', clock_timestamp()
                    from praxis_bulk.praxis_bulk_item_receipt
                    where execution_id=? and unit_ordinal=0
                    """, UUID.randomUUID(), reservation.executionId());
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_admission "
                    + "enable trigger praxis_bulk_admission_guard_terminal");
        }
        assertThatThrownBy(() -> kernel.inspectConsistent(CONTEXT, reservation.executionId()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
    }

    @Test
    void internalConsistentReadFailsClosedOnManifestDigestDrift() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-corrupt-manifest", "owner-a");
        observer.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                + "disable trigger praxis_bulk_target_manifest_immutable");
        try {
            observer.update("update praxis_bulk.praxis_bulk_target_manifest "
                    + "set target_digest=? where proposal_id=? and ordinal=0",
                    "sha256:" + "0".repeat(64), reservation.proposalId());
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_target_manifest "
                    + "enable trigger praxis_bulk_target_manifest_immutable");
        }
        assertThatThrownBy(() -> kernel.inspectConsistent(CONTEXT, reservation.executionId()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
    }

    @Test
    void internalConsistentReadFailsClosedOnAllocationLifecycleDrift() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "rs3-corrupt-allocation", "owner-a");
        observer.execute("alter table praxis_bulk.praxis_bulk_allocation "
                + "disable trigger praxis_bulk_allocation_protect_transition");
        try {
            observer.update("update praxis_bulk.praxis_bulk_allocation set state='PENDING' "
                    + "where proposal_id=?", reservation.proposalId());
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_allocation "
                    + "enable trigger praxis_bulk_allocation_protect_transition");
        }
        assertThatThrownBy(() -> kernel.inspectConsistent(CONTEXT, reservation.executionId()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
    }

    @Test
    void internalConsistentReadValidatesTheTenThousandTargetBoundary() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(tenThousandTargetEvaluation()), "rs3-10k", "owner-a");
        var read = kernel.inspectConsistent(CONTEXT, reservation.executionId());
        assertThat(read.kind()).isEqualTo(BulkConsistentExecutionRead.Kind.LIVE);
        assertThat(read.execution().targetCount()).isEqualTo(10_000);
        assertThat(read.execution().nextOrdinal()).isZero();
        assertThat(BulkExecutionSummary.from(read).totals().pending()).isEqualTo(10_000);
    }

    @Test
    void cancellationWinningTheLifecycleLockFencesConcurrentRecoveryWithoutAnotherCallback() throws Exception {
        var reservation = reserve(kernel(), persist(twoTargetEvaluation()), "cancel-wins-recovery", "owner-a");
        var paused = new BulkCommitPauseDataSource(runtimeDataSource,
                BulkCommitPauseDataSource.PausePoint.BEFORE_COMMIT);
        var pausedKernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(paused,
                new DataSourceTransactionManager(paused), CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        var callbacks = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancellation = executor.submit(() -> {
                paused.arm(Thread.currentThread());
                return pausedKernel.requestCancel(CONTEXT, reservation.executionId());
            });
            try {
                assertThat(paused.awaitMarker(5, TimeUnit.SECONDS)).isTrue();
                var recovery = executor.submit(() -> kernel().recover(CONTEXT, reservation.executionId(), "recovery-owner"));
                assertDatabaseLockWait("%praxis_bulk_deployment_bucket%");
                assertThat(recovery.isDone()).isFalse();
                paused.release();
                var cancelled = cancellation.get(5, TimeUnit.SECONDS);
                var recovered = recovery.get(5, TimeUnit.SECONDS);
                assertThat(cancelled.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
                assertThat(cancelled.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
                assertThat(recovered.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
                assertThat(recovered.control().epoch()).isEqualTo(reservation.control().epoch());
                assertThat(recovered.execution().cancelRequestedAt()).isEqualTo(cancelled.cancelRequestedAt());
            } finally {
                paused.release();
            }
        }
        assertTerminalRaceHasOneAllocationAndNoCallback(reservation, callbacks);
    }

    @Test
    void recoveryWinningTheLifecycleLockPreservesItsReasonAgainstConcurrentCancellation() throws Exception {
        var reservation = reserve(kernel(), persist(twoTargetEvaluation()), "recovery-wins-cancel", "owner-a");
        var paused = new BulkCommitPauseDataSource(runtimeDataSource,
                BulkCommitPauseDataSource.PausePoint.BEFORE_COMMIT);
        var pausedKernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(paused,
                new DataSourceTransactionManager(paused), CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        var callbacks = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var recovery = executor.submit(() -> {
                paused.arm(Thread.currentThread());
                return pausedKernel.recover(CONTEXT, reservation.executionId(), "recovery-owner");
            });
            try {
                assertThat(paused.awaitMarker(5, TimeUnit.SECONDS)).isTrue();
                var cancellation = executor.submit(() -> kernel().requestCancel(CONTEXT, reservation.executionId()));
                assertDatabaseLockWait("%praxis_bulk_deployment_bucket%");
                assertThat(cancellation.isDone()).isFalse();
                paused.release();
                var recovered = recovery.get(5, TimeUnit.SECONDS);
                var afterCancellation = cancellation.get(5, TimeUnit.SECONDS);
                assertThat(recovered.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
                assertThat(recovered.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
                assertThat(recovered.control().epoch()).isGreaterThan(reservation.control().epoch());
                assertThat(afterCancellation.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
                assertThat(afterCancellation.cancelRequestedAt()).isNull();
                assertThat(afterCancellation.control().epoch()).isEqualTo(recovered.control().epoch());
            } finally {
                paused.release();
            }
        }
        assertTerminalRaceHasOneAllocationAndNoCallback(reservation, callbacks);
    }

    private void assertTerminalRaceHasOneAllocationAndNoCallback(BulkExecutionReservation reservation,
            AtomicInteger callbacks) {
        assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution where execution_id=?",
                Integer.class, reservation.executionId())).isEqualTo(1);
        assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation where execution_id=?",
                Integer.class, reservation.executionId())).isEqualTo(1);
        assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                + "where execution_id=? and state='RELEASED'", Integer.class, reservation.executionId())).isEqualTo(1);
        assertThat(countForExecution("praxis_bulk_item_receipt", reservation.executionId())).isZero();
        assertThat(countForExecution("praxis_bulk_admission", reservation.executionId())).isZero();
        assertThatThrownBy(() -> kernel().executeUnit(reservation.control(), 0,
                ignored -> { callbacks.incrementAndGet(); return BulkUnitAdmission.admit(); },
                ignored -> { callbacks.incrementAndGet(); return BulkUnitMutationResult.confirmed(); }))
                .isInstanceOf(BulkDurableExecutionException.class);
        assertThat(callbacks).hasValue(0);
        assertThat(writes("bulk_durable_domain", 1)).isZero();
    }

    @Test
    void cancellationBetweenPreparedMarkerAndApplyPreventsEveryDomainCallback() throws Exception {
        var paused = new BulkCommitPauseDataSource(runtimeDataSource);
        var pausedManager = new DataSourceTransactionManager(paused);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(paused, pausedManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        var reservation = reserve(kernel(), persist(twoTargetEvaluation()), "cancel-after-prepare", "owner-a");
        var callbacks = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var unit = executor.submit(() -> {
                paused.arm(Thread.currentThread());
                return kernel.executeUnit(reservation.control(), 0,
                        ignored -> { callbacks.incrementAndGet(); return BulkUnitAdmission.admit(); },
                        ignored -> { callbacks.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
            });
            try {
                assertThat(paused.awaitMarker(2, TimeUnit.SECONDS)).isTrue();
                assertThat(observer.queryForObject("select status from praxis_bulk.praxis_bulk_execution "
                        + "where execution_id=?", String.class, reservation.executionId()))
                        .isEqualTo("UNIT_IN_FLIGHT");
                var requested = kernel().requestCancel(CONTEXT, reservation.executionId());
                assertThat(requested.cancelRequestedAt()).isNotNull();
                assertThat(requested.status()).isEqualTo(BulkDurableExecutionStatus.UNIT_IN_FLIGHT);
            } finally {
                paused.release();
            }
            var outcome = unit.get(4, TimeUnit.SECONDS);
            assertThat(outcome.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(outcome.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
        } finally {
            paused.release();
        }
        assertThat(callbacks).hasValue(0);
        assertThat(writes("bulk_durable_domain", 1)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(count("praxis_bulk_admission")).isZero();
    }

    @Test
    void cancellationPreservesConfirmedPrefixAndWorksWhenDescriptorIsSuspended() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-after-first", "owner-a");
        var first = kernel.executeUnit(reservation.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
            runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        try (var connection = dataSource.getConnection()) {
            assertThat(JdbcBulkOperationControl.transition(connection, CONTEXT.namespaceId(),
                    CONTEXT.operationRef().operationId(), 1, JdbcBulkOperationControl.Target.SUSPENDED,
                    null, null).applied()).isTrue();
        }
        var cancelled = kernel.requestCancel(CONTEXT, reservation.executionId());
        assertThat(cancelled.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(cancelled.nextOrdinal()).isEqualTo(1);
        assertThat(cancelled.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
        var calls = new AtomicInteger();
        var replay = kernel.executeUnit(first.control(), 0,
                unit -> { calls.incrementAndGet(); return BulkUnitAdmission.admit(); },
                unit -> { calls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
        assertThat(replay.replayed()).isTrue();
        assertThatThrownBy(() -> kernel.executeUnit(first.control(), 1,
                unit -> { calls.incrementAndGet(); return BulkUnitAdmission.admit(); },
                unit -> { calls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); }))
                .isInstanceOf(BulkDurableExecutionException.class);
        assertThat(calls).hasValue(0);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(writes("bulk_durable_domain", 2)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
        BulkExecutionMigrator.validate(dataSource, BulkPostgresTestSupport.testRoleConfiguration());
    }

    @Test
    void cancellationOfPendingCommittedReceiptReconcilesWithoutMutationReplay() {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-pending", "owner-a");
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 0,
                unit -> BulkUnitAdmission.admit(), unit -> {
                    calls.incrementAndGet();
                    faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
                    faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
                    return BulkUnitMutationResult.confirmed();
                })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(observer.queryForObject("select status from praxis_bulk.praxis_bulk_execution where execution_id=?",
                String.class, reservation.executionId())).isEqualTo("UNIT_COMMITTED_PENDING_ACK");
        var pendingSummary = kernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(pendingSummary.status()).isEqualTo(BulkExecutionStatus.RUNNING);
        assertThat(pendingSummary.totals().confirmed()).isZero();
        assertThat(pendingSummary.totals().pending()).isEqualTo(2);
        assertThat(kernel.requestCancel(CONTEXT, reservation.executionId()).cancelRequestedAt()).isNotNull();
        var cancelRequested = kernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(cancelRequested.status()).isEqualTo(BulkExecutionStatus.CANCEL_REQUESTED);
        assertThat(cancelRequested.totals().pending()).isEqualTo(2);
        assertThat(cancelRequested.totals().confirmed()).isZero();
        assertThat(cancelRequested.terminalAt()).isNull();
        var recovery = kernel.recover(CONTEXT, reservation.executionId(), "recovery-owner");
        assertThat(recovery.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        var recoveredSummary = kernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(recoveredSummary.status()).isEqualTo(BulkExecutionStatus.CANCELLED);
        assertThat(recoveredSummary.totals().confirmed()).isEqualTo(1);
        assertThat(recoveredSummary.totals().notProcessed()).isEqualTo(1);
        assertExecutionChronology(reservation.executionId());
        assertThat(recovery.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
        var replay = kernel.executeUnit(recovery.control(), 0,
                unit -> { calls.incrementAndGet(); return BulkUnitAdmission.admit(); },
                unit -> { calls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
        assertThat(replay.replayed()).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
    }

    @Test
    void olderWriterMayAcknowledgeAnEarlierReceiptButCannotPrepareTheNextUnit() {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var managerWithFault = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, managerWithFault,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-old-ack", "owner-a");
        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 0,
                ignored -> BulkUnitAdmission.admit(), ignored -> {
                    faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
                    faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
                    return BulkUnitMutationResult.confirmed();
                })).isInstanceOf(BulkDurableExecutionException.class);
        kernel.requestCancel(CONTEXT, reservation.executionId());

        // The V9 ACK shape must remain legal for an already committed receipt.
        runtimeJdbc.update("""
                update praxis_bulk.praxis_bulk_execution
                set next_ordinal=1, status='RUNNING', active_attempt_id=null,
                    active_attempt_ordinal=null, active_target_digest=null,
                    active_attempt_epoch=null, active_unit_deadline_at=null,
                    updated_at=clock_timestamp()
                where execution_id=? and status='UNIT_COMMITTED_PENDING_ACK'
                """, reservation.executionId());
        String digest = observer.queryForObject("select target_digest from praxis_bulk.praxis_bulk_target_manifest "
                + "where proposal_id=? and ordinal=1", String.class, reservation.proposalId());
        assertThatThrownBy(() -> runtimeJdbc.update("""
                update praxis_bulk.praxis_bulk_execution
                set status='UNIT_IN_FLIGHT', active_attempt_id=?, active_attempt_ordinal=1,
                    active_target_digest=?, active_attempt_epoch=owner_epoch,
                    active_unit_deadline_at=clock_timestamp()+interval '5 seconds'
                where execution_id=? and status='RUNNING'
                """, UUID.randomUUID(), digest, reservation.executionId()))
                .isInstanceOf(RuntimeException.class);
        var cancelled = kernel.executeUnit(reservation.control(), 1,
                ignored -> { fail("no admission after V9 ACK of a cancelled execution"); return BulkUnitAdmission.admit(); },
                ignored -> { fail("no domain after V9 ACK of a cancelled execution"); return BulkUnitMutationResult.confirmed(); });
        assertThat(cancelled.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(cancelled.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(writes("bulk_durable_domain", 2)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
    }

    @Test
    void cancellationWaitsForCommittedDomainTransactionAndNeverMutatesTheSuffix() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-during-domain", "owner-a");
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var unit = executor.submit(() -> kernel.executeUnit(reservation.control(), 0,
                    ignored -> BulkUnitAdmission.admit(), mutation -> {
                        calls.incrementAndGet();
                        runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
                        callbackEntered.countDown();
                        try {
                            if (!releaseCallback.await(2, TimeUnit.SECONDS)) throw new AssertionError("callback gate timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                        }
                        return BulkUnitMutationResult.confirmed();
                    }));
            assertThat(callbackEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var cancellation = executor.submit(() -> kernel.requestCancel(CONTEXT, reservation.executionId()));
            try {
                Thread.sleep(120);
                assertThat(cancellation.isDone()).isFalse();
            } finally {
                releaseCallback.countDown();
            }
            assertThat(unit.get(4, TimeUnit.SECONDS).receiptPresent()).isTrue();
            assertThat(cancellation.get(4, TimeUnit.SECONDS).cancelRequestedAt()).isNotNull();
        } finally {
            releaseCallback.countDown();
        }
        var finalState = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
        assertThat(finalState.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(finalState.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
        assertThat(finalState.nextOrdinal()).isEqualTo(1);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
        assertThatThrownBy(() -> kernel.executeUnit(finalState.control(), 1,
                ignored -> BulkUnitAdmission.admit(), ignored -> {
                    calls.incrementAndGet(); return BulkUnitMutationResult.confirmed();
                })).isInstanceOf(BulkDurableExecutionException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void cancellationThatLosesTheRowLockReturnsNoFalseAcknowledgement() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-lock-timeout", "owner-a");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var blocker = executor.submit(() -> transactions.executeWithoutResult(status -> {
                runtimeJdbc.queryForObject("select execution_id from praxis_bulk.praxis_bulk_execution "
                        + "where execution_id=? for update", UUID.class, reservation.executionId());
                locked.countDown();
                try {
                    if (!release.await(4, TimeUnit.SECONDS)) throw new AssertionError("row-lock gate timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                }
            }));
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> kernel.requestCancel(CONTEXT, reservation.executionId()))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(
                                    BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
            assertThat(observer.queryForObject("select cancel_requested_at is null from "
                    + "praxis_bulk.praxis_bulk_execution where execution_id=?", Boolean.class,
                    reservation.executionId())).isTrue();
            release.countDown();
            blocker.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(kernel.requestCancel(CONTEXT, reservation.executionId()).status())
                .isEqualTo(BulkDurableExecutionStatus.STOPPED);
    }

    @Test
    void cancellationCommitAcknowledgementLossRequiresReadbackAndIdempotentRetry() {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var managerWithFault = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, managerWithFault,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        var reservation = reserve(kernel(), persist(twoTargetEvaluation()), "cancel-commit-unknown", "owner-a");
        faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
        assertThatThrownBy(() -> kernel.requestCancel(CONTEXT, reservation.executionId()))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(
                                BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        var readback = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
        assertThat(readback.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(readback.cancelRequestedAt()).isNotNull();
        var retry = kernel.requestCancel(CONTEXT, reservation.executionId());
        assertThat(retry.cancelRequestedAt()).isEqualTo(readback.cancelRequestedAt());
        assertThat(retry.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(observer.queryForObject("select state from praxis_bulk.praxis_bulk_allocation "
                + "where execution_id=?", String.class, reservation.executionId())).isEqualTo("RELEASED");
    }

    @Test
    void physicalCancellationFenceRollsBackAnOlderWriterDomainAndReceiptTogether() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-old-writer", "owner-a");
        var attemptId = UUID.randomUUID();
        var digest = observer.queryForObject("select target_digest from praxis_bulk.praxis_bulk_target_manifest "
                + "where proposal_id=? and ordinal=0", String.class, reservation.proposalId());
        observer.update("""
                update praxis_bulk.praxis_bulk_execution
                set status='UNIT_IN_FLIGHT', active_attempt_id=?, active_attempt_ordinal=0,
                    active_target_digest=?, active_attempt_epoch=owner_epoch,
                    active_unit_deadline_at=clock_timestamp()+interval '5 seconds'
                where execution_id=? and status='RUNNING'
                """, attemptId, digest, reservation.executionId());
        assertThat(kernel.requestCancel(CONTEXT, reservation.executionId()).status())
                .isEqualTo(BulkDurableExecutionStatus.UNIT_IN_FLIGHT);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            runtimeJdbc.update("""
                    insert into praxis_bulk.praxis_bulk_item_receipt
                      (execution_id,unit_ordinal,target_digest,expected_version,attempt_id,
                       owner_epoch,outcome,confirmed_at,unit_deadline_at)
                    values (?,0,?,'v1',?,1,'CONFIRMED',clock_timestamp(),
                       (select active_unit_deadline_at from praxis_bulk.praxis_bulk_execution
                        where execution_id=?))
                    """, reservation.executionId(), digest, attemptId, reservation.executionId());
        })).isInstanceOf(RuntimeException.class);
        assertThat(writes("bulk_durable_domain", 1)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        var recovered = kernel.recover(CONTEXT, reservation.executionId(), "recovery-owner");
        assertThat(recovered.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(recovered.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
        assertThat(recovered.execution().nextOrdinal()).isZero();
    }

    @Test
    void cancellationTombstoneSurvivesPurgingAndDeniesReservationReplay() {
        var kernel = kernel();
        var evaluated = persist(twoTargetEvaluation());
        var reservation = reserve(kernel, evaluated, "cancel-retention", "owner-a");
        kernel.requestCancel(CONTEXT, reservation.executionId());
        ageTerminalForRetentionAsFixtureOwner(reservation.executionId());
        BulkExecutionMigrator.validate(dataSource, BulkPostgresTestSupport.testRoleConfiguration());
        observer.execute("grant praxis_bulk_retention_executor to postgres");
        try {
            var ownerTx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            Boolean purged = ownerTx.execute(status -> {
                observer.execute("set local role praxis_bulk_retention_executor");
                return observer.queryForObject("select praxis_bulk.purge_terminal_execution(?)", Boolean.class,
                        reservation.executionId());
            });
            assertThat(purged).isTrue();
        } finally {
            observer.execute("revoke praxis_bulk_retention_executor from postgres");
        }
        assertThat(observer.queryForObject("select terminal_status from praxis_bulk.praxis_bulk_tombstone "
                + "where execution_id=?", String.class, reservation.executionId())).isEqualTo("CANCELLED");
        assertThat(count("praxis_bulk_execution")).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThatThrownBy(() -> reserve(kernel, evaluated, "cancel-retention", "owner-a"))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RESULT_PURGED));
        BulkExecutionMigrator.validate(dataSource, BulkPostgresTestSupport.testRoleConfiguration());
    }

    @Test
    void lastPendingReceiptCompletesNormallyEvenWhenCancellationWasRequested() {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-last-receipt", "owner-a");
        var first = kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(), mutation -> {
            faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
            return BulkUnitMutationResult.confirmed();
        });
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeUnit(first.control(), 1,
                ignored -> BulkUnitAdmission.admit(), mutation -> {
                    calls.incrementAndGet();
                    faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=2");
                    faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
                    return BulkUnitMutationResult.confirmed();
                })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(kernel.requestCancel(CONTEXT, reservation.executionId()).status())
                .isEqualTo(BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK);
        var requested = kernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(requested.status()).isEqualTo(BulkExecutionStatus.CANCEL_REQUESTED);
        assertThat(requested.totals().confirmed()).isEqualTo(1);
        assertThat(requested.totals().pending()).isEqualTo(1);
        assertThat(requested.terminalAt()).isNull();
        var recovered = kernel.recover(CONTEXT, reservation.executionId(), "recovery-owner");
        assertThat(recovered.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertThat(kernel.summarizeConsistent(CONTEXT, reservation.executionId()).status())
                .isEqualTo(BulkExecutionStatus.COMPLETED);
        assertThat(recovered.execution().terminalReasonCode()).isNull();
        assertThat(recovered.execution().cancelRequestedAt()).isNotNull();
        assertThat(kernel.requestCancel(CONTEXT, reservation.executionId()).status())
                .isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertThat(calls).hasValue(1);
        assertThat(writes("bulk_durable_domain", 2)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(2);
    }

    @Test
    void cancelledExecutionAfterDeniedAdmissionKeepsItemEvidenceAndUnprocessedSuffix() {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-admission", "owner-a");
        var mutationCalls = new AtomicInteger();
        var denied = kernel.executeUnit(reservation.control(), 0,
                ignored -> BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED),
                ignored -> { mutationCalls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
        assertThat(denied.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
        assertThat(denied.execution().admissionCount()).isEqualTo(1);
        var cancelled = kernel.requestCancel(CONTEXT, reservation.executionId());
        assertThat(cancelled.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(cancelled.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
        assertThat(cancelled.nextOrdinal()).isEqualTo(1);
        var replay = kernel.executeUnit(denied.control(), 0,
                ignored -> { mutationCalls.incrementAndGet(); return BulkUnitAdmission.admit(); },
                ignored -> { mutationCalls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
        assertThat(replay.replayed()).isTrue();
        assertThat(mutationCalls).hasValue(0);
        assertThat(count("praxis_bulk_admission")).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isZero();
    }

    @Test
    void jpaConfirmedDomainAndReceiptStayCommittedWhenCancellationStopsTheSuffix() {
        var jpaKernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(runtimeDataSource,
                jpaManager, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        var reservation = reserve(jpaKernel, persist(twoTargetEvaluation()), "cancel-jpa", "owner-a");
        var first = jpaKernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(), ignored -> {
            var em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            em.find(BulkDurableJpaDomainRow.class, 1L).recordWrite();
            em.flush();
            return BulkUnitMutationResult.confirmed();
        });
        assertThat(first.receiptPresent()).isTrue();
        var cancelled = jpaKernel.requestCancel(CONTEXT, reservation.executionId());
        assertThat(cancelled.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(cancelled.nextOrdinal()).isEqualTo(1);
        assertThat(writes("bulk_durable_jpa_domain", 1)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
        assertThatThrownBy(() -> jpaKernel.executeUnit(cancelled.control(), 1,
                ignored -> BulkUnitAdmission.admit(), ignored -> {
                    fail("cancelled JPA suffix must not invoke domain");
                    return BulkUnitMutationResult.confirmed();
                })).isInstanceOf(BulkDurableExecutionException.class);
    }

    @Test
    void cancellationRacingARealCallbackRollbackNeverClaimsTheDomainEffect() throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-domain-rollback", "owner-a");
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var unit = executor.submit(() -> kernel.executeUnit(reservation.control(), 0,
                    ignored -> BulkUnitAdmission.admit(), ignored -> {
                        runtimeJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
                        callbackEntered.countDown();
                        try {
                            if (!releaseCallback.await(2, TimeUnit.SECONDS)) throw new AssertionError("callback gate timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                        }
                        throw new IllegalStateException("forced callback rollback");
                    }));
            assertThat(callbackEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var cancellation = executor.submit(() -> kernel.requestCancel(CONTEXT, reservation.executionId()));
            try { Thread.sleep(100); }
            finally { releaseCallback.countDown(); }
            assertThat(unit.get(4, TimeUnit.SECONDS).status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            var observed = cancellation.get(4, TimeUnit.SECONDS);
            var finalState = kernel.find(CONTEXT, reservation.executionId()).orElseThrow();
            assertThat(finalState.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            if (observed.cancelRequestedAt() != null) {
                assertThat(finalState.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
            } else {
                assertThat(finalState.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
            }
        } finally {
            releaseCallback.countDown();
        }
        assertThat(writes("bulk_durable_domain", 1)).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(count("praxis_bulk_admission")).isZero();
    }

    @Test
    void cancellationKeepsCorruptPendingReceiptUnknownInsteadOfClaimingCancellation() throws Exception {
        var faults = new BulkCommitFaultDataSource(runtimeDataSource);
        var faultManager = new DataSourceTransactionManager(faults);
        var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults, faultManager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        var faultJdbc = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(twoTargetEvaluation()), "cancel-corrupt-pending", "owner-a");
        assertThatThrownBy(() -> kernel.executeUnit(reservation.control(), 0,
                ignored -> BulkUnitAdmission.admit(), ignored -> {
                    faultJdbc.update("update bulk_durable_domain set writes=writes+1 where id=1");
                    faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
                    return BulkUnitMutationResult.confirmed();
                })).isInstanceOf(BulkDurableExecutionException.class);
        corruptPendingReceiptEpochAsFixtureOwner(reservation.executionId(), 0);
        var cancellation = kernel.requestCancel(CONTEXT, reservation.executionId());
        assertThat(cancellation.status()).isEqualTo(BulkDurableExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(cancellation.cancelRequestedAt()).isNotNull();
        assertThat(cancellation.terminalReasonCode()).isNull();
        var recovery = kernel.recover(CONTEXT, reservation.executionId(), "recovery-owner");
        assertThat(recovery.status()).isEqualTo(BulkDurableExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(writes("bulk_durable_domain", 1)).isEqualTo(1);
        assertThat(count("praxis_bulk_item_receipt")).isEqualTo(1);
    }

    private JdbcBulkDurableExecution kernel() {
        return new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(runtimeDataSource, manager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
    }

    private BulkEvaluationSnapshot twoTargetEvaluation() {
        return twoTargetEvaluation(CONTEXT);
    }

    private BulkEvaluationSnapshot tenThousandTargetEvaluation() {
        var selected = new ArrayList<BulkTarget<String>>(10_000);
        var evidence = new ArrayList<BulkTargetEvidence<?>>(10_000);
        var empty = JSON.objectNode();
        for (int ordinal = 0; ordinal < 10_000; ordinal++) {
            var target = new BulkTarget<>("target-" + ordinal, "v1");
            selected.add(target);
            evidence.add(new BulkTargetEvidence<>(target, "observed", empty, empty,
                    BulkTargetEligibility.executable()));
        }
        var request = new BulkCommandEvaluationRequest<com.fasterxml.jackson.databind.JsonNode, String,
                com.fasterxml.jackson.databind.JsonNode>(BulkExecutionMode.SYNC,
                        new BulkSelection<>(BulkSelectionMode.EXPLICIT, selected, null, null), empty);
        Instant created = Instant.now().minusSeconds(5);
        var snapshot = BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                com.fasterxml.jackson.databind.JsonNode::deepCopy,
                com.fasterxml.jackson.databind.JsonNode::deepCopy);
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(600), snapshot,
                BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
        var governance = new BulkEvaluationGovernance("test-evaluator-r1", "test-grants-r1", List.of(
                new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                        "resource:approve", "NEVER_APPLIED", "test-policy-r1", created.plusMillis(500))));
        return new BulkEvaluationSnapshot(proposal, created.plusSeconds(1), evidence, governance);
    }

    private BulkEvaluationSnapshot twoTargetEvaluation(BulkFingerprintContext context) {
        return targetEvaluation(context, false);
    }

    private BulkEvaluationSnapshot threeTargetEvaluation() {
        return targetEvaluation(CONTEXT, true);
    }

    private BulkEvaluationSnapshot targetEvaluation(BulkFingerprintContext context, boolean thirdTarget) {
        var reader = new BulkProtocolReader<>(BulkIdentityCodecs.strings());
        var request = reader.<com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.JsonNode>readCommand(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[
                  {"id":"1","expectedVersion":"v1"},{"id":"2","expectedVersion":"v2"}%s]},"parameters":{"reason":"fixture"}}
                """.formatted(thirdTarget ? ",{\"id\":\"3\",\"expectedVersion\":\"v3\"}" : "")),
                com.fasterxml.jackson.databind.JsonNode::deepCopy, com.fasterxml.jackson.databind.JsonNode::deepCopy);
        Instant created = Instant.now().minusSeconds(5);
        var snapshot = BulkIntentSnapshot.command(context, BulkIdentityCodecs.strings(), request,
                com.fasterxml.jackson.databind.JsonNode::deepCopy, com.fasterxml.jackson.databind.JsonNode::deepCopy);
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(600), snapshot,
                BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
        var evidence = new ArrayList<BulkTargetEvidence<?>>();
        evidence.add(new BulkTargetEvidence<>(new BulkTarget<>("1", "v1"), "observed-v1", JSON.objectNode(), JSON.objectNode(), BulkTargetEligibility.executable()));
        evidence.add(new BulkTargetEvidence<>(new BulkTarget<>("2", "v2"), "observed-v2", JSON.objectNode(), JSON.objectNode(), BulkTargetEligibility.executable()));
        if (thirdTarget) evidence.add(new BulkTargetEvidence<>(new BulkTarget<>("3", "v3"), "observed-v3", JSON.objectNode(), JSON.objectNode(), BulkTargetEligibility.executable()));
        Instant evaluatedAt = created.plusSeconds(1);
        var governance = new BulkEvaluationGovernance("test-evaluator-r1", "test-grants-r1", List.of(
                new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                        "resource:approve", "NEVER_APPLIED", "test-policy-r1", created.plusMillis(500))));
        return new BulkEvaluationSnapshot(proposal, evaluatedAt, evidence, governance);
    }

    private BulkEvaluationSnapshot persist(BulkEvaluationSnapshot value) {
        transactions.executeWithoutResult(status -> proposals.insertEvaluated(value, BulkEvaluationSnapshotTest.preview(value)));
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

    private void assertDatabaseLockWait(String queryPattern) throws InterruptedException {
        var deadline = Instant.now().plusSeconds(3);
        boolean observed = false;
        while (Instant.now().isBefore(deadline) && !observed) {
            observed = observer.queryForObject("""
                    select exists(select 1 from pg_stat_activity
                      where pid <> pg_backend_pid() and wait_event_type='Lock' and query ilike ?)
                    """, Boolean.class, queryPattern);
            if (!observed) Thread.sleep(20);
        }
        assertThat(observed).as("database lock wait for %s", queryPattern).isTrue();
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

    private void assertExecutionChronology(UUID executionId) {
        assertThat(observer.queryForObject("""
                select created_at <= updated_at
                   and (terminal_at is null or created_at <= terminal_at and terminal_at <= updated_at)
                   and (cancel_requested_at is null or
                        created_at <= cancel_requested_at and cancel_requested_at <= updated_at
                        and (terminal_at is null or cancel_requested_at <= terminal_at))
                from praxis_bulk.praxis_bulk_execution where execution_id=?
                """, Boolean.class, executionId)).isTrue();
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

    private void ageTerminalForRetentionAsFixtureOwner(UUID executionId) {
        // Test-only owner time travel keeps V13 chronology valid while proving retention.
        observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_guard_terminal");
        observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_protect_cancel");
        observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                + "disable trigger praxis_bulk_execution_protect_binding");
        observer.execute("alter table praxis_bulk.praxis_bulk_allocation "
                + "disable trigger praxis_bulk_allocation_protect_transition");
        try {
            assertThat(observer.update("""
                    with aged as materialized (select clock_timestamp() - interval '31 days' as terminal)
                    update praxis_bulk.praxis_bulk_execution
                    set created_at=aged.terminal - interval '2 seconds',
                        cancel_requested_at=case when cancel_requested_at is null then null
                            else aged.terminal - interval '1 second' end,
                        terminal_at=aged.terminal, updated_at=aged.terminal
                    from aged where execution_id=?
                    """, executionId)).isEqualTo(1);
            assertThat(observer.update("""
                    update praxis_bulk.praxis_bulk_allocation a
                    set created_at=e.created_at, released_at=e.terminal_at
                    from praxis_bulk.praxis_bulk_execution e
                    where a.execution_id=e.execution_id and e.execution_id=?
                    """, executionId)).isEqualTo(1);
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_allocation "
                    + "enable trigger praxis_bulk_allocation_protect_transition");
            observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_protect_binding");
            observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_protect_cancel");
            observer.execute("alter table praxis_bulk.praxis_bulk_execution "
                    + "enable trigger praxis_bulk_execution_guard_terminal");
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
