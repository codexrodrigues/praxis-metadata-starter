package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.JSON;

/**
 * F-CORE PostgreSQL consumer of the protected atomic kernel, with actual versioned domain
 * writes and a constrained transactional outbox. The test-only control/publication fixture
 * does not certify HTTP publication, a host's IAM, or an operational capability.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkAtomicExecutionPostgresTest {
    private static final String NAMESPACE = "atomic:production:conformance";
    private static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext(
            NAMESPACE, "atomic-operator", "atomic-records",
            new CanonicalOperationRef("admin", "atomic-records.apply", "/atomic-records/apply", "POST"),
            "schema-r1", ActionCollectionAtomicity.ATOMIC);
    private EmbeddedPostgres postgres;
    private DataSource owner;
    private DataSource runtime;
    private JdbcTemplate observer;
    private JdbcTemplate domain;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transactions;
    private JdbcBulkProposalStore proposals;

    @BeforeAll
    void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        observer = new JdbcTemplate(owner);
        observer.execute("create role bulk_runtime_test login");
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        domain = new JdbcTemplate(runtime);
        manager = new DataSourceTransactionManager(runtime);
        transactions = new TransactionTemplate(manager);
        proposals = new JdbcBulkProposalStore(infrastructure());
        observer.execute("""
                create table public.atomic_domain (
                    id integer primary key,
                    row_version bigint not null default 0 check (row_version >= 0),
                    writes integer not null default 0 check (writes >= 0),
                    value integer not null default 0,
                    state text not null default 'PENDING' check (state in ('PENDING','APPROVED','BLOCKED')))
                """);
        observer.execute("grant select, update on public.atomic_domain to bulk_runtime_test");
        System.out.println("Atomic kernel proof PostgreSQL: "
                + observer.queryForObject("select version()", String.class));
    }

    @AfterAll
    void close() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void reset() {
        observer.execute("drop table if exists public.atomic_effect_detail");
        observer.execute("drop table if exists public.atomic_outbox");
        observer.execute("drop schema if exists praxis_bulk cascade");
        observer.execute("truncate public.atomic_domain");
        observer.update("insert into public.atomic_domain(id) select generate_series(1,51)");
        assertThat(BulkPostgresTestSupport.migrate(owner, NAMESPACE)).isEqualTo(20);
        BulkPostgresTestSupport.ready(owner, NAMESPACE, CONTEXT.operationRef().operationId());
        observer.execute("""
                create table public.atomic_outbox (
                    effect_ref text primary key check (length(effect_ref) between 1 and 200),
                    execution_id uuid not null references praxis_bulk.praxis_bulk_execution(execution_id),
                    attempt_id uuid not null,
                    ordinal integer not null check (ordinal >= 0 and ordinal < 50),
                    target_id integer not null references public.atomic_domain(id),
                    set_digest text not null,
                    unique(execution_id, ordinal))
                """);
        observer.execute("grant select, insert on public.atomic_outbox to bulk_runtime_test");
    }

    static Stream<Arguments> modesAndSetSizes() {
        return Stream.of(BulkMode.values()).flatMap(mode -> Stream.of(1, 50)
                .map(size -> Arguments.of(mode, size)));
    }

    @ParameterizedTest
    @MethodSource("modesAndSetSizes")
    void commitsTheWholeDomainOutboxAndReceiptOnce(BulkMode mode, int size) {
        var kernel = kernel();
        var evaluation = persist(evaluation(mode, size));
        var reservation = reserve(kernel, evaluation, "atomic-success");
        var admissions = new AtomicInteger();
        var mutations = new AtomicInteger();
        var result = kernel.executeAtomic(reservation.control(), set -> {
            admissions.incrementAndGet();
            return admitLockedSet(set);
        }, set -> {
            mutations.incrementAndGet();
            return apply(set, false);
        });
        assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertThat(result.durableReceiptPresent()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(result.execution().nextOrdinal()).isEqualTo(size);
        assertCommittedSet(kernel, reservation.executionId(), mode, size, true);

        var replayReservation = reserve(kernel, evaluation, "atomic-success");
        assertThat(replayReservation.executionId()).isEqualTo(reservation.executionId());
        assertThat(replayReservation.replayed()).isTrue();
        var replay = kernel.executeAtomic(replayReservation.control(), set -> {
            admissions.incrementAndGet();
            throw new AssertionError("atomic replay must not readmit the domain");
        }, set -> {
            mutations.incrementAndGet();
            throw new AssertionError("atomic replay must not mutate the domain");
        });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.durableReceiptPresent()).isTrue();
        assertThat(admissions).hasValue(1);
        assertThat(mutations).hasValue(1);
        assertCommittedSet(kernel, reservation.executionId(), mode, size, true);
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void committedWholeSetReplaysReceiptFirstAfterItsImmutableDeadline(BulkMode mode) throws Exception {
        var kernel = kernel();
        var evaluation = persist(evaluation(mode, 1));
        var reservation = kernel.reserve(CONTEXT, evaluation.proposal().id(), "atomic-expired-replay",
                "atomic-owner", "structural-r1", Instant.now().plusSeconds(2));
        var admissions = new AtomicInteger();
        var mutations = new AtomicInteger();
        var completed = kernel.executeAtomic(reservation.control(), set -> {
            admissions.incrementAndGet();
            return admitLockedSet(set);
        }, set -> {
            mutations.incrementAndGet();
            return apply(set, false);
        });
        assertThat(completed.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertThat(completed.durableReceiptPresent()).isTrue();
        assertCommittedSet(kernel, reservation.executionId(), mode, 1, true);
        // Only the external test thread waits; the execution deadline and unit budget remain immutable.
        Thread.sleep(Math.max(1, Duration.between(Instant.now(), reservation.execution().deadlineAt()).toMillis() + 100));
        assertThat(observer.queryForObject("select deadline_at < clock_timestamp() "
                + "from praxis_bulk.praxis_bulk_execution where execution_id=?", Boolean.class,
                reservation.executionId())).isTrue();
        var replay = kernel.executeAtomic(completed.execution().control(), set -> {
            admissions.incrementAndGet();
            throw new AssertionError("a committed receipt must replay before deadline admission");
        }, set -> {
            mutations.incrementAndGet();
            throw new AssertionError("a committed receipt must replay without domain dispatch");
        });
        assertThat(replay.execution().executionId()).isEqualTo(reservation.executionId());
        assertThat(replay.execution().deadlineAt()).isEqualTo(reservation.execution().deadlineAt());
        assertThat(replay.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertThat(replay.execution().nextOrdinal()).isEqualTo(1);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.durableReceiptPresent()).isTrue();
        assertThat(admissions).hasValue(1);
        assertThat(mutations).hasValue(1);
        assertCommittedSet(kernel, reservation.executionId(), mode, 1, true);
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void lastTargetCallbackFailureRollsBackEveryDomainAndOutboxWrite(BulkMode mode) {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(evaluation(mode, 50)), "atomic-last-failure");
        var mutations = new AtomicInteger();
        var result = kernel.executeAtomic(reservation.control(), this::admitLockedSet, set -> {
            mutations.incrementAndGet();
            return apply(set, true);
        });
        assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(result.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
        assertThat(result.execution().nextOrdinal()).isZero();
        assertThat(result.durableReceiptPresent()).isFalse();
        assertThat(mutations).hasValue(1);
        assertRejection(reservation.executionId(), BulkUnitReasonCode.UNIT_ROLLED_BACK);
        assertNoEffects();
        assertThat(kernel.summarizeConsistent(CONTEXT, reservation.executionId()).totals().notProcessed())
                .isEqualTo(50);
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void denialOnTheLastLockedTargetNeverInvokesMutation(BulkMode mode) {
        observer.update("update public.atomic_domain set state='BLOCKED' where id=3");
        var kernel = kernel();
        var reservation = reserve(kernel, persist(evaluation(mode, 3)), "atomic-last-denied");
        var mutations = new AtomicInteger();
        var result = kernel.executeAtomic(reservation.control(), this::admitLockedSet, set -> {
            mutations.incrementAndGet();
            throw new AssertionError("a rejected set must not invoke mutation");
        });
        assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(result.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
        assertRejection(reservation.executionId(), BulkUnitReasonCode.TARGET_DENIED);
        assertThat(result.execution().nextOrdinal()).isZero();
        assertThat(result.durableReceiptPresent()).isFalse();
        assertThat(mutations).hasValue(0);
        assertNoEffects();
        assertThat(observer.queryForObject("select state from public.atomic_domain where id=3", String.class))
                .isEqualTo("BLOCKED");
        assertThat(kernel.summarizeConsistent(CONTEXT, reservation.executionId()).totals().notProcessed())
                .isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void admissionSideEffectsAreRolledBackBeforeTheFencedSetRejection(BulkMode mode) {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(evaluation(mode, 3)), "atomic-dirty-admission");
        var initialWindow = resultReader().read(CONTEXT, reservation.executionId(), -1, 200);
        assertThat(initialWindow.watermarkExclusive()).isZero();
        var admissionTransaction = new AtomicReference<String>();
        var mutations = new AtomicInteger();
        var result = kernel.executeAtomic(reservation.control(), set -> {
            assertThat(admitLockedSet(set).admitted()).isTrue();
            admissionTransaction.set(domain.queryForObject("select pg_current_xact_id()::text", String.class));
            // Deliberately invalid admission: even dirty preparation cannot commit on rejection.
            apply(set, false);
            return BulkAtomicAdmission.reject(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
        }, set -> {
            mutations.incrementAndGet();
            throw new AssertionError("a rejected dirty admission must never invoke mutation");
        });
        assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(result.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
        assertThat(result.execution().nextOrdinal()).isZero();
        assertThat(result.durableReceiptPresent()).isFalse();
        assertThat(mutations).hasValue(0);
        assertNoEffects();
        assertRejection(reservation.executionId(), BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
        var sameWindow = resultReader().read(CONTEXT, reservation.executionId(), -1, 200,
                initialWindow.watermarkExclusive());
        assertThat(sameWindow.watermarkExclusive()).isZero();
        assertThat(sameWindow.items()).isEmpty();
        assertThat(observer.queryForObject("select xmin::text from praxis_bulk.praxis_bulk_atomic_rejection "
                + "where execution_id=?", String.class, reservation.executionId()))
                .isNotEqualTo(admissionTransaction.get());
        assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution "
                + "where execution_id=? and status='STOPPED' and next_ordinal=0 and active_attempt_id is null",
                Integer.class, reservation.executionId())).isEqualTo(1);
        assertThat(kernel.summarizeConsistent(CONTEXT, reservation.executionId()).totals().notProcessed())
                .isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void unchangedWholeSetHasNoDomainOrOutboxEffect(BulkMode mode) {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(evaluation(mode, 3)), "atomic-unchanged");
        var result = kernel.executeAtomic(reservation.control(), this::admitLockedSet,
                set -> new BulkAtomicMutationResult(set.units().stream()
                        .map(unit -> new BulkAtomicMutationResult.Item(unit.ordinal(), BulkUnitOutcome.UNCHANGED,
                                List.of())).toList()));
        assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertThat(result.durableReceiptPresent()).isTrue();
        assertCommittedSet(kernel, reservation.executionId(), mode, 3, false);
        assertThat(observer.queryForObject("select sum(writes) from public.atomic_domain", Integer.class)).isZero();
        assertThat(observer.queryForObject("select sum(row_version) from public.atomic_domain", Long.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void unicodeEffectReferencesHaveTheSameByteFramedDigestInJavaAndPostgres(BulkMode mode) {
        observer.execute("""
                create table public.atomic_effect_detail (
                    effect_ref text primary key,
                    outbox_ref text not null references public.atomic_outbox(effect_ref))
                """);
        observer.execute("grant select,insert on public.atomic_effect_detail to bulk_runtime_test");
        // U+E000 sorts before U+1F600 in UTF-8, although Java's UTF-16 String order is reversed.
        var references = List.of("zz-effects/😀", "zz-effects/é", "zz-effects/e\u0301",
                "zz-effects/\uE000", "zz-effects/中", "zz-effects/á", "zz-effects/z");
        assertThat("zz-effects/😀".compareTo("zz-effects/\uE000")).isNegative();
        var expectedReferences = new AtomicReference<List<String>>();
        var kernel = kernel();
        var reservation = reserve(kernel, persist(evaluation(mode, 1)), "atomic-unicode-effects");
        var result = kernel.executeAtomic(reservation.control(), this::admitLockedSet, set -> {
            var original = apply(set, false).items().get(0);
            String outboxReference = original.effectReferences().get(0);
            for (String reference : references)
                assertThat(domain.update("insert into public.atomic_effect_detail values (?,?)",
                        reference, outboxReference)).isEqualTo(1);
            expectedReferences.set(List.of(outboxReference, "zz-effects/e\u0301", "zz-effects/z",
                    "zz-effects/á", "zz-effects/é", "zz-effects/中", "zz-effects/\uE000", "zz-effects/😀"));
            var all = new ArrayList<>(references);
            all.add(outboxReference);
            return new BulkAtomicMutationResult(List.of(new BulkAtomicMutationResult.Item(
                    0, BulkUnitOutcome.CONFIRMED, all)));
        });
        assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertCommittedSet(kernel, reservation.executionId(), mode, 1, true, 8);
        String independentDigest = framedEffectDigest(expectedReferences.get());
        assertThat(observer.queryForObject("select effect_digest from praxis_bulk.praxis_bulk_atomic_receipt "
                + "where execution_id=?", String.class, reservation.executionId())).isEqualTo(independentDigest);
        assertThat(BulkTargetDigest.effectsOf(expectedReferences.get().stream()
                .map(reference -> new BulkTargetDigest.EffectReference(0, reference)).toList()))
                .isEqualTo(independentDigest);
        assertThat(observer.queryForList("select effect_ref from public.atomic_effect_detail "
                + "order by effect_ref collate \"C\"", String.class))
                .containsExactly("zz-effects/e\u0301", "zz-effects/z", "zz-effects/á", "zz-effects/é",
                        "zz-effects/中", "zz-effects/\uE000", "zz-effects/😀");
        assertThat(observer.queryForObject("""
                select count(*) from public.atomic_effect_detail d
                join public.atomic_outbox o on o.effect_ref=d.outbox_ref
                join praxis_bulk.praxis_bulk_atomic_effect_ref f
                  on f.execution_id=o.execution_id and f.unit_ordinal=o.ordinal and f.effect_ref=d.effect_ref
                where o.execution_id=?
                """, Integer.class, reservation.executionId())).isEqualTo(7);
        var replay = kernel.executeAtomic(reservation.control(), set -> {
            throw new AssertionError("Unicode evidence must replay without readmission");
        }, set -> { throw new AssertionError("Unicode effects must not repeat"); });
        assertThat(replay.replayed()).isTrue();
        assertCommittedSet(kernel, reservation.executionId(), mode, 1, true, 8);
    }

    static Stream<Arguments> invalidUnicodeEffectReferences() {
        var edgeWhitespace = Stream.of(0x1680, 0x2003, 0x2028, 0x3000).flatMap(codePoint -> {
            String whitespace = new String(Character.toChars(codePoint));
            String label = "U+" + Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT);
            return Stream.of(Arguments.of(label + " leading", whitespace + "effect/valid"),
                    Arguments.of(label + " trailing", "effect/valid" + whitespace),
                    Arguments.of(label + " all whitespace", whitespace + whitespace));
        });
        var embeddedControls = Stream.of(0x85, 0x9f).map(codePoint -> Arguments.of(
                "embedded C1 U+" + Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT),
                "effect/" + new String(Character.toChars(codePoint)) + "invalid"));
        return Stream.concat(edgeWhitespace, embeddedControls);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUnicodeEffectReferences")
    void javaAndPostgresRejectTheSameUnicodeEffectBoundaryWithoutCertifyingAnyMutation(
            String label, String invalidReference) {
        assertThatThrownBy(() -> new BulkAtomicMutationResult.Item(
                0, BulkUnitOutcome.CONFIRMED, List.of(invalidReference)))
                .as(label).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid atomic effect reference");
        assertThatThrownBy(() -> BulkTargetDigest.effectsOf(List.of(
                new BulkTargetDigest.EffectReference(0, invalidReference))))
                .as(label).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid atomic effect reference");
        var kernel = kernel();
        var reservation = reserve(kernel, persist(evaluation(BulkMode.DOMAIN_COMMAND, 1)), "atomic-invalid-unicode");
        var sqlState = new AtomicReference<String>();
        var constraintDiagnostic = new AtomicReference<String>();
        var evidencePreserved = new AtomicReference<Boolean>();
        var stagedEvidenceComplete = new AtomicReference<Boolean>();
        var mutations = new AtomicInteger();
        var result = kernel.executeAtomic(reservation.control(), this::admitLockedSet, set -> {
            mutations.incrementAndGet();
            String stableReference = apply(set, false).items().get(0).effectReferences().get(0);
            // Stage a complete valid receipt under the kernel's real in-flight attempt, before any PENDING/ACK.
            assertThat(domain.update("""
                    insert into praxis_bulk.praxis_bulk_atomic_receipt
                    (execution_id,attempt_id,owner_epoch,set_digest,target_count,effect_count,effect_digest,
                     confirmed_at,unit_deadline_at)
                    select execution_id,active_attempt_id,owner_epoch,active_set_digest,target_count,1,?,
                           clock_timestamp(),active_unit_deadline_at
                    from praxis_bulk.praxis_bulk_execution where execution_id=?
                    """, framedEffectDigest(List.of(stableReference)), set.executionId())).isEqualTo(1);
            assertThat(domain.update("""
                    insert into praxis_bulk.praxis_bulk_atomic_item_result
                    (execution_id,unit_ordinal,target_digest,expected_version,outcome)
                    select e.execution_id,m.ordinal,m.target_digest,convert_from(m.expected_version,'UTF8'),'CONFIRMED'
                    from praxis_bulk.praxis_bulk_execution e
                    join praxis_bulk.praxis_bulk_target_manifest m on m.proposal_id=e.proposal_id
                    where e.execution_id=? and m.ordinal=0
                    """, set.executionId())).isEqualTo(1);
            assertThat(domain.update("""
                    insert into praxis_bulk.praxis_bulk_atomic_effect_ref(execution_id,unit_ordinal,effect_ref)
                    values (?,0,?)
                    """, set.executionId(), stableReference)).isEqualTo(1);
            var headerBefore = domain.queryForList("select * from praxis_bulk.praxis_bulk_atomic_receipt "
                    + "where execution_id=?", set.executionId());
            var itemBefore = domain.queryForList("select * from praxis_bulk.praxis_bulk_atomic_item_result "
                    + "where execution_id=?", set.executionId());
            var effectsBefore = domain.queryForList("select * from praxis_bulk.praxis_bulk_atomic_effect_ref "
                    + "where execution_id=?", set.executionId());
            domain.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                var savepoint = connection.setSavepoint();
                try (var statement = connection.prepareStatement("""
                        insert into praxis_bulk.praxis_bulk_atomic_effect_ref(execution_id,unit_ordinal,effect_ref)
                        values (?,0,?)
                        """)) {
                    statement.setObject(1, set.executionId());
                    statement.setString(2, invalidReference);
                    try { statement.executeUpdate(); }
                    catch (java.sql.SQLException denied) {
                        sqlState.set(denied.getSQLState());
                        constraintDiagnostic.set(denied.getMessage());
                    }
                } finally {
                    connection.rollback(savepoint);
                    connection.releaseSavepoint(savepoint);
                }
                return null;
            });
            evidencePreserved.set(headerBefore.equals(domain.queryForList(
                    "select * from praxis_bulk.praxis_bulk_atomic_receipt where execution_id=?", set.executionId()))
                    && itemBefore.equals(domain.queryForList(
                    "select * from praxis_bulk.praxis_bulk_atomic_item_result where execution_id=?", set.executionId()))
                    && effectsBefore.equals(domain.queryForList(
                    "select * from praxis_bulk.praxis_bulk_atomic_effect_ref where execution_id=?", set.executionId())));
            stagedEvidenceComplete.set(domain.queryForObject("select praxis_bulk.atomic_evidence_complete(?,1)",
                    Boolean.class, set.executionId()));
            throw new IllegalStateException("abort all staged effects after the negative SQL CHECK probe");
        });
        assertThat(sqlState.get()).as(label).isEqualTo("23514");
        assertThat(constraintDiagnostic.get()).as(label)
                .contains("praxis_bulk_atomic_effect_ref_effect_ref_check");
        assertThat(evidencePreserved.get()).isTrue();
        assertThat(stagedEvidenceComplete.get()).isTrue();
        assertThat(mutations).hasValue(1);
        assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(result.execution().nextOrdinal()).isZero();
        assertThat(result.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
        assertThat(result.durableReceiptPresent()).isFalse();
        assertNoEffects();
        assertRejection(reservation.executionId(), BulkUnitReasonCode.UNIT_ROLLED_BACK);
        var replay = kernel.executeAtomic(reservation.control(), set -> {
            throw new AssertionError("a rolled-back invalid Unicode attempt cannot readmit");
        }, set -> {
            mutations.incrementAndGet();
            throw new AssertionError("a rolled-back invalid Unicode attempt cannot repeat mutation");
        });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(mutations).hasValue(1);
        assertNoEffects();
    }

    static Stream<Arguments> modesAndEvidenceOrder() {
        return Stream.of(BulkMode.values()).flatMap(mode -> Stream.of(true, false)
                .map(receiptFirst -> Arguments.of(mode, receiptFirst)));
    }

    @ParameterizedTest
    @MethodSource("modesAndEvidenceOrder")
    void theSameActiveAttemptCannotHaveBothReceiptAndRejection(BulkMode mode, boolean receiptFirst) {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(evaluation(mode, 1)), "atomic-exclusive-evidence");
        var deniedSqlState = new AtomicReference<String>();
        var attempt = new AtomicReference<UUID>();
        var result = kernel.executeAtomic(reservation.control(), this::admitLockedSet, set -> {
            attempt.set(set.attemptId());
            String header = """
                    insert into praxis_bulk.praxis_bulk_atomic_receipt
                    (execution_id,attempt_id,owner_epoch,set_digest,target_count,effect_count,effect_digest,
                     confirmed_at,unit_deadline_at)
                    select execution_id,active_attempt_id,owner_epoch,active_set_digest,target_count,0,?,
                           clock_timestamp(),active_unit_deadline_at
                    from praxis_bulk.praxis_bulk_execution where execution_id=?
                    """;
            String rejection = """
                    insert into praxis_bulk.praxis_bulk_atomic_rejection
                    (execution_id,attempt_id,set_digest,reason_code,recorded_at)
                    select execution_id,active_attempt_id,active_set_digest,'TARGET_DENIED',clock_timestamp()
                    from praxis_bulk.praxis_bulk_execution where execution_id=?
                    """;
            if (receiptFirst) domain.update(header, framedEffectDigest(List.of()), set.executionId());
            else domain.update(rejection, set.executionId());
            domain.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                var savepoint = connection.setSavepoint();
                try (var statement = connection.prepareStatement(receiptFirst ? rejection : header)) {
                    if (receiptFirst) statement.setObject(1, set.executionId());
                    else {
                        statement.setString(1, framedEffectDigest(List.of()));
                        statement.setObject(2, set.executionId());
                    }
                    try { statement.executeUpdate(); }
                    catch (java.sql.SQLException denied) { deniedSqlState.set(denied.getSQLState()); }
                } finally {
                    // Only the rejected probe is rolled back here; the enclosing callback then aborts entirely.
                    connection.rollback(savepoint);
                    connection.releaseSavepoint(savepoint);
                }
                return null;
            });
            throw new IllegalStateException("abort the deliberate incomplete evidence fixture");
        });
        assertThat(deniedSqlState.get()).isEqualTo("55000");
        assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(result.execution().nextOrdinal()).isZero();
        assertThat(result.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
        assertNoEffects();
        assertRejection(reservation.executionId(), BulkUnitReasonCode.UNIT_ROLLED_BACK);
        assertThat(observer.queryForObject("select attempt_id from praxis_bulk.praxis_bulk_atomic_rejection "
                + "where execution_id=?", UUID.class, reservation.executionId())).isEqualTo(attempt.get());
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void fiftyOneTargetsAreDeniedBeforeAnyCallbackOrExecution(BulkMode mode) {
        var kernel = kernel();
        var evaluation = persist(evaluation(mode, 51));
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> {
            var reservation = reserve(kernel, evaluation, "atomic-over-limit");
            kernel.executeAtomic(reservation.control(), set -> {
                calls.incrementAndGet();
                return admitLockedSet(set);
            }, set -> {
                calls.incrementAndGet();
                return apply(set, false);
            });
        }).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
        assertThat(calls).hasValue(0);
        assertThat(count("praxis_bulk_execution")).isZero();
        assertNoEffects();
    }

    static Stream<Arguments> modesAndCommitOutcomes() {
        return Stream.of(BulkMode.values()).flatMap(mode -> Stream.of(BulkCommitFaultDataSource.Mode.values())
                .map(fault -> Arguments.of(mode, fault)));
    }

    @ParameterizedTest
    @MethodSource("modesAndCommitOutcomes")
    void uncertainCommitPublishesNoPartialWindowAndFreshRecoveryNeverRepeatsMutation(
            BulkMode mode, BulkCommitFaultDataSource.Mode faultMode) throws Exception {
        var faults = new BulkCommitFaultDataSource(runtime);
        var faultManager = new DataSourceTransactionManager(faults);
        var faultInfrastructure = new BulkExecutionInfrastructure(faults, faultManager, NAMESPACE,
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration());
        var kernel = new JdbcBulkDurableExecution(faultInfrastructure);
        var faultDomain = new JdbcTemplate(faults);
        var reservation = reserve(kernel, persist(evaluation(mode, 3)), "atomic-uncertain-commit");
        var admissions = new AtomicInteger();
        var mutations = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeAtomic(reservation.control(), set -> {
            admissions.incrementAndGet();
            return admitLockedSet(set, faultDomain);
        }, set -> {
            mutations.incrementAndGet();
            var result = apply(set, false, faultDomain);
            faults.arm(Thread.currentThread(), faultMode);
            return result;
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(faults.isArmed()).isFalse();
        var freshKernel = kernel();
        var before = freshKernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(before.totals().confirmed()).isZero();
        assertThat(before.totals().pending() + before.totals().unknown()).isEqualTo(3);
        var beforePage = resultReader().read(CONTEXT, reservation.executionId(), -1, 200);
        assertThat(beforePage.nextOrdinal()).isZero();
        assertThat(beforePage.watermarkExclusive()).isZero();
        assertThat(beforePage.items()).isEmpty();
        if (faultMode == BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST) {
            assertThat(count("praxis_bulk_atomic_receipt")).isEqualTo(1);
            assertThat(count("praxis_bulk_atomic_item_result")).isEqualTo(3);
            assertThat(observer.queryForObject("select sum(writes) from public.atomic_domain", Integer.class))
                    .isEqualTo(3);
            assertThat(observer.queryForObject("select status from praxis_bulk.praxis_bulk_execution "
                    + "where execution_id=?", String.class, reservation.executionId()))
                    .isEqualTo("UNIT_COMMITTED_PENDING_ACK");
            assertLateEvidenceInsertDenied(reservation.executionId());
        } else {
            assertNoEffects();
            assertUncommittedRecoveryWaitsForStoredAttemptDeadline(freshKernel, reservation.executionId());
            assertThat(admissions).hasValue(1);
            assertThat(mutations).hasValue(1);
        }
        var recovered = freshKernel.recover(CONTEXT, reservation.executionId(), "atomic-recovery-owner");
        assertThat(recovered.control().epoch()).isGreaterThan(reservation.control().epoch());
        if (faultMode == BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST) {
            assertThat(recovered.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
            assertThat(recovered.execution().nextOrdinal()).isEqualTo(3);
            assertCommittedSet(freshKernel, reservation.executionId(), mode, 3, true);
            assertLateEvidenceInsertDenied(reservation.executionId());
            assertCommittedSet(freshKernel, reservation.executionId(), mode, 3, true);
        } else {
            assertThat(recovered.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(recovered.execution().nextOrdinal()).isZero();
            assertThat(recovered.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
            assertNoEffects();
        }
        var sameWindow = resultReader().read(CONTEXT, reservation.executionId(), -1, 200,
                beforePage.watermarkExclusive());
        assertThat(sameWindow.watermarkExclusive()).isZero();
        assertThat(sameWindow.items()).isEmpty();
        assertThatThrownBy(() -> freshKernel.executeAtomic(reservation.control(), set -> {
            admissions.incrementAndGet();
            throw new AssertionError("a former owner must not readmit after recovery");
        }, set -> {
            mutations.incrementAndGet();
            throw new AssertionError("a former owner must not mutate after recovery");
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
        var replay = freshKernel.executeAtomic(recovered.control(), set -> {
            admissions.incrementAndGet();
            throw new AssertionError("explicit recovery must not readmit the set");
        }, set -> {
            mutations.incrementAndGet();
            throw new AssertionError("explicit recovery must not repeat domain mutation");
        });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(recovered.status());
        assertThat(admissions).hasValue(1);
        assertThat(mutations).hasValue(1);
    }

    static Stream<Arguments> modesAndCorruptedEffectBinding() {
        return Stream.of(BulkMode.values()).flatMap(mode -> Stream.of("COUNT", "DIGEST", "REFERENCE")
                .map(corruption -> Arguments.of(mode, corruption)));
    }

    @ParameterizedTest
    @MethodSource("modesAndCorruptedEffectBinding")
    void corruptedEffectBindingBlocksReadbackAcknowledgementAndRecovery(
            BulkMode mode, String corruption) {
        var faults = new BulkCommitFaultDataSource(runtime);
        var faultKernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(faults,
                new DataSourceTransactionManager(faults), NAMESPACE,
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        var faultDomain = new JdbcTemplate(faults);
        var reservation = reserve(faultKernel, persist(evaluation(mode, 3)), "atomic-corrupt-effect-binding");
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> faultKernel.executeAtomic(reservation.control(), set -> {
            callbacks.incrementAndGet();
            return admitLockedSet(set, faultDomain);
        }, set -> {
            callbacks.incrementAndGet();
            var result = apply(set, false, faultDomain);
            faults.arm(Thread.currentThread(), BulkCommitFaultDataSource.Mode.COMMIT_THEN_ACK_LOST);
            return result;
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(faults.isArmed()).isFalse();
        UUID executionId = reservation.executionId();
        String originalDigest = observer.queryForObject("select effect_digest "
                + "from praxis_bulk.praxis_bulk_atomic_receipt where execution_id=?", String.class, executionId);
        var effectsBefore = observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_effect_ref "
                + "where execution_id=? order by unit_ordinal", executionId);
        // Deliberate owner corruption only: the immutability trigger is restored before any kernel read.
        String originalReference = observer.queryForObject("select effect_ref "
                + "from praxis_bulk.praxis_bulk_atomic_effect_ref where execution_id=? and unit_ordinal=0",
                String.class, executionId);
        if (corruption.equals("REFERENCE")) replaceEffectReferenceAsOwner(executionId, "tampered-reference");
        else replaceEffectBindingAsOwner(executionId, corruption.equals("COUNT") ? 4 : 3,
                corruption.equals("COUNT") ? originalDigest : "sha256:" + "0".repeat(64));
        var corruptHeader = observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_receipt "
                + "where execution_id=?", executionId);
        var corruptEffects = observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_effect_ref "
                + "where execution_id=? order by unit_ordinal", executionId);
        assertThat(domain.queryForObject("select praxis_bulk.atomic_evidence_complete(?,3)",
                Boolean.class, executionId)).isFalse();
        assertSqlGuardDenied(() -> domain.update("""
                update praxis_bulk.praxis_bulk_execution
                set status='COMPLETED',next_ordinal=target_count,active_attempt_id=null,active_attempt_ordinal=null,
                    active_target_digest=null,active_set_digest=null,active_attempt_epoch=null,
                    active_unit_deadline_at=null,updated_at=clock_timestamp(),terminal_at=clock_timestamp()
                where execution_id=?
                """, executionId));
        var kernel = kernel();
        BulkAtomicAdmissionCallback admission = set -> {
            callbacks.incrementAndGet();
            throw new AssertionError("corrupt committed evidence cannot readmit");
        };
        BulkAtomicMutationCallback mutation = set -> {
            callbacks.incrementAndGet();
            throw new AssertionError("corrupt committed evidence cannot repeat mutation");
        };
        assertThatThrownBy(() -> kernel.executeAtomic(reservation.control(), admission, mutation))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThatThrownBy(() -> kernel.recover(CONTEXT, executionId, "cannot-certify-corrupted-effects"))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThatThrownBy(() -> kernel.summarizeConsistent(CONTEXT, executionId))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, BulkPostgresTestSupport.testRoleConfiguration()))
                .isInstanceOf(IllegalStateException.class);
        // This recent pending execution is also ineligible by status/age: false is not mature-retention proof.
        assertThat(observer.queryForObject("select praxis_bulk.purge_terminal_execution(?)",
                Boolean.class, executionId)).isFalse();
        assertThat(observer.queryForObject("select status from praxis_bulk.praxis_bulk_execution "
                + "where execution_id=?", String.class, executionId)).isEqualTo("UNIT_COMMITTED_PENDING_ACK");
        assertThat(observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_effect_ref "
                + "where execution_id=? order by unit_ordinal", executionId)).isEqualTo(corruptEffects);
        assertThat(observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_receipt "
                + "where execution_id=?", executionId)).isEqualTo(corruptHeader);
        assertThat(callbacks).hasValue(2);
        // Explicit owner repair is distinct from forbidden automatic healing or retrying domain work.
        if (corruption.equals("REFERENCE")) replaceEffectReferenceAsOwner(executionId, originalReference);
        else replaceEffectBindingAsOwner(executionId, 3, originalDigest);
        assertThat(observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_effect_ref "
                + "where execution_id=? order by unit_ordinal", executionId)).isEqualTo(effectsBefore);
        assertEffectBinding(executionId, 3, 3);
        var replay = kernel.executeAtomic(reservation.control(), admission, mutation);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertThat(callbacks).hasValue(2);
        assertCommittedSet(kernel, executionId, mode, 3, true);
    }

    private void replaceEffectReferenceAsOwner(UUID executionId, String reference) {
        observer.execute("alter table praxis_bulk.praxis_bulk_atomic_effect_ref "
                + "disable trigger praxis_bulk_atomic_effect_ref_reject_mutation");
        try {
            assertThat(observer.update("update praxis_bulk.praxis_bulk_atomic_effect_ref "
                    + "set effect_ref=? where execution_id=? and unit_ordinal=0", reference, executionId)).isEqualTo(1);
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_atomic_effect_ref "
                    + "enable trigger praxis_bulk_atomic_effect_ref_reject_mutation");
        }
    }

    private void replaceEffectBindingAsOwner(UUID executionId, int effectCount, String effectDigest) {
        observer.execute("alter table praxis_bulk.praxis_bulk_atomic_receipt "
                + "disable trigger praxis_bulk_atomic_receipt_reject_mutation");
        try {
            assertThat(observer.update("update praxis_bulk.praxis_bulk_atomic_receipt "
                    + "set effect_count=?,effect_digest=? where execution_id=?",
                    effectCount, effectDigest, executionId)).isEqualTo(1);
        } finally {
            observer.execute("alter table praxis_bulk.praxis_bulk_atomic_receipt "
                    + "enable trigger praxis_bulk_atomic_receipt_reject_mutation");
        }
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void technicalReceiptAppendCannotCommitTheSetAfterItsAbsoluteUnitDeadline(BulkMode mode) throws Exception {
        observer.execute("""
                create or replace function public.atomic_technical_append_pause()
                returns trigger language plpgsql as $$
                begin
                    perform pg_sleep(1.5);
                    return new;
                end $$
                """);
        // Each individual wait fits the original statement budget, but header + three children take 6 s.
        // This discriminates a stale per-statement timeout from the absolute canonical 5 s set deadline.
        observer.execute("""
                create trigger zz_atomic_receipt_append_pause before insert
                on praxis_bulk.praxis_bulk_atomic_receipt for each row
                execute function public.atomic_technical_append_pause()
                """);
        observer.execute("""
                create trigger zz_atomic_child_append_pause before insert
                on praxis_bulk.praxis_bulk_atomic_item_result for each row
                execute function public.atomic_technical_append_pause()
                """);
        var kernel = kernel();
        BulkExecutionReservation reservation;
        var admissions = new AtomicInteger();
        var mutations = new AtomicInteger();
        var completedCallbacks = new AtomicInteger();
        var callbackDeadline = new AtomicReference<Instant>();
        var callbackRemaining = new AtomicReference<Duration>();
        var callbackReturnedBeforeDeadline = new AtomicReference<Boolean>();
        BulkAtomicExecutionResult result = null;
        boolean uncertain = false;
        try {
            reservation = reserve(kernel, persist(evaluation(mode, 3)), "atomic-late-technical-append");
            try {
                result = kernel.executeAtomic(reservation.control(), set -> {
                    admissions.incrementAndGet();
                    return admitLockedSet(set);
                }, set -> {
                    mutations.incrementAndGet();
                    var mutation = apply(set, false);
                    callbackDeadline.set(set.unitDeadline());
                    callbackRemaining.set(set.remainingBudget());
                    callbackReturnedBeforeDeadline.set(domain.queryForObject("select clock_timestamp() < ?",
                            Boolean.class, java.sql.Timestamp.from(set.unitDeadline())));
                    completedCallbacks.incrementAndGet();
                    return mutation;
                });
            } catch (BulkDurableExecutionException error) {
                // SQLSTATE 57014 may precede the explicit deadline gate; uncertainty never becomes success.
                assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
                uncertain = true;
            }
        } finally {
            observer.execute("drop trigger zz_atomic_receipt_append_pause on praxis_bulk.praxis_bulk_atomic_receipt");
            observer.execute("drop trigger zz_atomic_child_append_pause on praxis_bulk.praxis_bulk_atomic_item_result");
            observer.execute("drop function public.atomic_technical_append_pause()");
        }
        assertThat(completedCallbacks).hasValue(1);
        assertThat(callbackReturnedBeforeDeadline.get()).isTrue();
        assertThat(callbackRemaining.get()).isGreaterThan(Duration.ZERO);
        assertThat(admissions).hasValue(1);
        assertThat(mutations).hasValue(1);
        assertNoEffects();
        BulkExecutionControl replayControl;
        if (uncertain) {
            assertThat(result).isNull();
            assertThat(count("praxis_bulk_atomic_rejection")).isZero();
            var before = resultReader().read(CONTEXT, reservation.executionId(), -1, 200);
            assertThat(before.nextOrdinal()).isZero();
            assertThat(before.watermarkExclusive()).isZero();
            assertThat(before.items()).isEmpty();
            // Wait outside any transaction for the immutable attempt deadline, never replace the canonical clock.
            long remainingMillis = Duration.between(Instant.now(), callbackDeadline.get().plusMillis(100)).toMillis();
            if (remainingMillis > 0) Thread.sleep(remainingMillis);
            var recovered = kernel().recover(CONTEXT, reservation.executionId(), "atomic-append-recovery-owner");
            assertThat(recovered.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(recovered.execution().nextOrdinal()).isZero();
            assertThat(recovered.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
            assertThat(recovered.control().epoch()).isGreaterThan(reservation.control().epoch());
            replayControl = recovered.control();
            assertThatThrownBy(() -> kernel.executeAtomic(reservation.control(), set -> {
                admissions.incrementAndGet();
                throw new AssertionError("the former owner cannot readmit after recovery");
            }, set -> {
                mutations.incrementAndGet();
                throw new AssertionError("the former owner cannot mutate after recovery");
            })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                    error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
        } else {
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(result.execution().nextOrdinal()).isZero();
            assertThat(result.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.DEADLINE_EXCEEDED);
            assertThat(result.durableReceiptPresent()).isFalse();
            assertRejection(reservation.executionId(), BulkUnitReasonCode.DEADLINE_EXCEEDED);
            replayControl = reservation.control();
        }
        var replay = kernel().executeAtomic(replayControl, set -> {
            admissions.incrementAndGet();
            throw new AssertionError("a deadline failure cannot readmit the set");
        }, set -> {
            mutations.incrementAndGet();
            throw new AssertionError("a deadline failure cannot repeat mutation");
        });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(replay.execution().nextOrdinal()).isZero();
        assertThat(admissions).hasValue(1);
        assertThat(mutations).hasValue(1);
        assertNoEffects();
        assertThat(kernel().summarizeConsistent(CONTEXT, reservation.executionId()).totals().notProcessed())
                .isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void deferredSqlFailureAtCommitRequiresExplicitRecoveryWithoutRepeatingMutation(BulkMode mode) throws Exception {
        observer.execute("""
                create or replace function public.atomic_outbox_deferred_failure()
                returns trigger language plpgsql as $$
                begin
                    if new.ordinal=2 then
                        raise exception 'injected last outbox constraint at commit' using errcode='23514';
                    end if;
                    return new;
                end $$
                """);
        observer.execute("""
                create constraint trigger atomic_outbox_deferred_failure
                after insert on public.atomic_outbox deferrable initially deferred
                for each row execute function public.atomic_outbox_deferred_failure()
                """);
        var kernel = kernel();
        var reservation = reserve(kernel, persist(evaluation(mode, 3)), "atomic-deferred-sql-failure");
        var admissions = new AtomicInteger();
        var completedCallbacks = new AtomicInteger();
        assertThatThrownBy(() -> kernel.executeAtomic(reservation.control(), set -> {
            admissions.incrementAndGet();
            return admitLockedSet(set);
        }, set -> {
            var result = apply(set, false);
            completedCallbacks.incrementAndGet();
            return result;
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(completedCallbacks).hasValue(1);
        assertNoEffects();
        assertThat(count("praxis_bulk_atomic_rejection")).isZero();
        var before = resultReader().read(CONTEXT, reservation.executionId(), -1, 200);
        assertThat(before.nextOrdinal()).isZero();
        assertThat(before.watermarkExclusive()).isZero();
        assertThat(before.items()).isEmpty();
        var freshKernel = kernel();
        assertUncommittedRecoveryWaitsForStoredAttemptDeadline(freshKernel, reservation.executionId());
        assertThat(admissions).hasValue(1);
        assertThat(completedCallbacks).hasValue(1);
        var recovery = freshKernel.recover(CONTEXT, reservation.executionId(), "atomic-sql-recovery-owner");
        assertThat(recovery.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(recovery.execution().nextOrdinal()).isZero();
        assertThat(recovery.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
        assertThat(recovery.control().epoch()).isGreaterThan(reservation.control().epoch());
        assertThat(freshKernel.summarizeConsistent(CONTEXT, reservation.executionId()).totals().notProcessed())
                .isEqualTo(3);
        var sameWindow = resultReader().read(CONTEXT, reservation.executionId(), -1, 200,
                before.watermarkExclusive());
        assertThat(sameWindow.watermarkExclusive()).isZero();
        assertThat(sameWindow.items()).isEmpty();
        assertThatThrownBy(() -> freshKernel.executeAtomic(reservation.control(), set -> {
            admissions.incrementAndGet();
            throw new AssertionError("a former owner cannot readmit after deferred SQL recovery");
        }, set -> {
            completedCallbacks.incrementAndGet();
            throw new AssertionError("a former owner cannot mutate after deferred SQL recovery");
        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
        var replay = freshKernel.executeAtomic(recovery.control(), set -> {
            admissions.incrementAndGet();
            throw new AssertionError("recovery must not repeat admission after deferred SQL failure");
        }, set -> {
            completedCallbacks.incrementAndGet();
            throw new AssertionError("recovery must not repeat mutation after deferred SQL failure");
        });
        assertThat(replay.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(replay.replayed()).isTrue();
        assertThat(admissions).hasValue(1);
        assertThat(completedCallbacks).hasValue(1);
        assertNoEffects();
    }

    /** Recovery cannot infer rollback while the actual, immutable database attempt deadline is still live. */
    private void assertUncommittedRecoveryWaitsForStoredAttemptDeadline(JdbcBulkDurableExecution freshKernel,
            UUID executionId) throws InterruptedException {
        var before = observer.queryForMap("select * from praxis_bulk.praxis_bulk_execution where execution_id=?",
                executionId);
        assertThat(before).containsEntry("status", "UNIT_IN_FLIGHT");
        assertThat(observer.queryForObject("select clock_timestamp() < active_unit_deadline_at "
                + "from praxis_bulk.praxis_bulk_execution where execution_id=?", Boolean.class, executionId)).isTrue();
        assertThatThrownBy(() -> freshKernel.recover(CONTEXT, executionId, "premature-recovery-owner"))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason())
                                .isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
        assertThat(observer.queryForMap("select * from praxis_bulk.praxis_bulk_execution where execution_id=?",
                executionId)).isEqualTo(before);
        var window = resultReader().read(CONTEXT, executionId, -1, 200);
        assertThat(window.nextOrdinal()).isZero();
        assertThat(window.watermarkExclusive()).isZero();
        assertThat(window.items()).isEmpty();
        assertThat(count("praxis_bulk_atomic_rejection")).isZero();
        assertNoEffects();
        // Only this external test thread waits, using the database clock rather than changing the deadline or budget.
        long remainingMillis = observer.queryForObject("""
                select greatest(0,ceil(extract(epoch from (active_unit_deadline_at-clock_timestamp()))*1000))::bigint
                from praxis_bulk.praxis_bulk_execution where execution_id=?
                """, Long.class, executionId);
        Thread.sleep(remainingMillis + 100);
        assertThat(observer.queryForObject("select active_unit_deadline_at <= clock_timestamp() "
                + "from praxis_bulk.praxis_bulk_execution where execution_id=?", Boolean.class, executionId)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(BulkMode.class)
    void independentReadersObserveZeroThenTheWholeCommittedSet(BulkMode mode) throws Exception {
        var kernel = kernel();
        var reservation = reserve(kernel, persist(evaluation(mode, 3)), "atomic-concurrent-read");
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var executing = executor.submit(() -> kernel.executeAtomic(reservation.control(), this::admitLockedSet, set -> {
            var result = apply(set, false);
            written.countDown();
            awaitRelease(release);
            return result;
        }));
        try {
            assertThat(written.await(2, TimeUnit.SECONDS)).isTrue();
            assertNoEffects(); // Independent physical owner connection cannot see uncommitted domain/outbox.
            var before = resultReader().read(CONTEXT, reservation.executionId(), -1, 200);
            assertThat(before.nextOrdinal()).isZero();
            assertThat(before.watermarkExclusive()).isZero();
            assertThat(before.items()).isEmpty();
            release.countDown();
            assertThat(executing.get(5, TimeUnit.SECONDS).status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
            assertCommittedSet(kernel, reservation.executionId(), mode, 3, true);
            var after = resultReader().read(CONTEXT, reservation.executionId(), -1, 200);
            assertThat(after.watermarkExclusive()).isEqualTo(3);
            assertThat(after.items()).extracting(BulkExecutionResultsReader.Item::ordinal).containsExactly(0, 1, 2);
            var sameWindow = resultReader().read(CONTEXT, reservation.executionId(), -1, 200,
                    before.watermarkExclusive());
            assertThat(sameWindow.watermarkExclusive()).isZero();
            assertThat(sameWindow.items()).isEmpty();
        } finally {
            release.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                    throw new AssertionError("atomic executor did not drain after release");
            }
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("atomic callback release timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private BulkExecutionResultsReader resultReader() { return new BulkExecutionResultsReader(infrastructure()); }

    private BulkAtomicAdmission admitLockedSet(BulkAtomicExecutionSet set) {
        return admitLockedSet(set, domain);
    }

    private BulkAtomicAdmission admitLockedSet(BulkAtomicExecutionSet set, JdbcTemplate transactionalDomain) {
        assertThat(set.units()).isNotEmpty().hasSizeLessThanOrEqualTo(50);
        assertThat(set.remainingBudget()).isGreaterThan(Duration.ZERO);
        assertThat(set.setDigest()).startsWith("sha256:");
        var ordered = set.units().stream().sorted(Comparator.comparingInt(this::targetId)).toList();
        boolean denied = false;
        for (var unit : ordered) {
            assertThat(unit.executionId()).isEqualTo(set.executionId());
            assertThat(unit.attemptId()).isEqualTo(set.attemptId());
            assertThat(unit.unitDeadline()).isEqualTo(set.unitDeadline());
            var row = transactionalDomain.queryForMap("select id,row_version,state from public.atomic_domain where id=? for update",
                    targetId(unit));
            assertThat(((Number) row.get("row_version")).longValue()).isZero();
            assertThat(unit.targetEvidence().target().expectedVersion()).isEqualTo("v0");
            denied |= !"PENDING".equals(row.get("state"));
        }
        return denied ? BulkAtomicAdmission.reject(BulkUnitReasonCode.TARGET_DENIED) : BulkAtomicAdmission.admit();
    }

    private BulkAtomicMutationResult apply(BulkAtomicExecutionSet set, boolean failLast) {
        return apply(set, failLast, domain);
    }

    private BulkAtomicMutationResult apply(BulkAtomicExecutionSet set, boolean failLast, JdbcTemplate transactionalDomain) {
        var items = new ArrayList<BulkAtomicMutationResult.Item>();
        for (var unit : set.units()) {
            int id = targetId(unit);
            JsonNode intent = unit.originalIntent().intent();
            BulkMode mode = unit.originalIntent().mode();
            String state = mode == BulkMode.DOMAIN_COMMAND ? intent.at("/parameters/state").textValue() : "PENDING";
            int value = switch (mode) {
                case DOMAIN_COMMAND -> 0;
                case UNIFORM_UPDATE -> intent.at("/changes/0/value").intValue();
                case PER_ITEM_UPDATE -> intent.at("/items/" + unit.ordinal() + "/changes/0/value").intValue();
            };
            if (failLast && unit.ordinal() == set.units().size() - 1)
                throw new IllegalStateException("injected last-target domain failure");
            assertThat(transactionalDomain.update("""
                    update public.atomic_domain set state=?,value=?,row_version=row_version+1,writes=writes+1
                     where id=? and row_version=0 and state='PENDING'
                    """, state, value, id)).isEqualTo(1);
            String reference = set.executionId() + "/" + set.attemptId() + "/" + unit.ordinal();
            assertThat(transactionalDomain.update("""
                    insert into public.atomic_outbox(effect_ref,execution_id,attempt_id,ordinal,target_id,set_digest)
                    values(?,?,?,?,?,?)
                    """, reference, set.executionId(), set.attemptId(), unit.ordinal(), id, set.setDigest()))
                    .isEqualTo(1);
            items.add(new BulkAtomicMutationResult.Item(unit.ordinal(), BulkUnitOutcome.CONFIRMED, List.of(reference)));
        }
        return new BulkAtomicMutationResult(items);
    }

    private int targetId(BulkExecutionUnit unit) {
        return Integer.parseInt((String) unit.targetEvidence().target().id());
    }

    private void assertCommittedSet(JdbcBulkDurableExecution kernel, UUID executionId,
            BulkMode mode, int size, boolean changed) {
        assertCommittedSet(kernel, executionId, mode, size, changed, changed ? size : 0);
    }

    private void assertCommittedSet(JdbcBulkDurableExecution kernel, UUID executionId,
            BulkMode mode, int size, boolean changed, int effectCount) {
        assertThat(count("praxis_bulk_atomic_rejection")).isZero();
        assertThat(count("praxis_bulk_atomic_receipt")).isEqualTo(1);
        assertThat(count("praxis_bulk_atomic_item_result")).isEqualTo(size);
        assertThat(count("praxis_bulk_atomic_effect_ref")).isEqualTo(effectCount);
        assertEffectBinding(executionId, effectCount, size);
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(count("praxis_bulk_admission")).isZero();
        assertThat(observer.queryForList("select unit_ordinal from praxis_bulk.praxis_bulk_atomic_item_result "
                + "where execution_id=? order by unit_ordinal", Integer.class, executionId))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, size).boxed().toList());
        assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_atomic_item_result "
                + "where execution_id=? and outcome=?", Integer.class, executionId,
                changed ? "CONFIRMED" : "UNCHANGED")).isEqualTo(size);
        assertThat(observer.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_atomic_receipt
                 where execution_id=? and target_count=? and unit_deadline_at > confirmed_at
                """, Integer.class, executionId, size)).isEqualTo(1);
        assertThat(observer.queryForObject("""
                select count(*) from public.atomic_outbox o join praxis_bulk.praxis_bulk_atomic_receipt r
                on r.execution_id=o.execution_id and r.attempt_id=o.attempt_id and r.set_digest=o.set_digest
                join praxis_bulk.praxis_bulk_atomic_effect_ref e
                on e.execution_id=o.execution_id and e.unit_ordinal=o.ordinal and e.effect_ref=o.effect_ref
                 where o.execution_id=?
                """, Integer.class, executionId)).isEqualTo(changed ? size : 0);
        assertThat(observer.queryForObject("select count(*) from public.atomic_outbox", Integer.class))
                .isEqualTo(changed ? size : 0);
        if (changed) {
            assertThat(observer.queryForObject("select count(*) from public.atomic_domain "
                    + "where id<=? and writes=1 and row_version=1 and state=?", Integer.class, size,
                    mode == BulkMode.DOMAIN_COMMAND ? "APPROVED" : "PENDING")).isEqualTo(size);
            for (int id = 1; id <= size; id++)
                assertThat(observer.queryForObject("select value from public.atomic_domain where id=?", Integer.class, id))
                        .isEqualTo(mode == BulkMode.PER_ITEM_UPDATE ? id * 10 : mode == BulkMode.UNIFORM_UPDATE ? 7 : 0);
            assertThat(observer.queryForObject("select sum(writes) from public.atomic_domain", Integer.class)).isEqualTo(size);
        }
        var summary = kernel.summarizeConsistent(CONTEXT, executionId);
        assertThat(summary.status()).isEqualTo(BulkExecutionStatus.COMPLETED);
        assertThat(summary.totals().confirmed()).isEqualTo(changed ? size : 0);
        assertThat(summary.totals().unchanged()).isEqualTo(changed ? 0 : size);
        assertThat(summary.totals().pending()).isZero();
        assertThat(summary.totals().unknown()).isZero();
        var page = resultReader().read(CONTEXT, executionId, -1, 200);
        assertThat(page.nextOrdinal()).isEqualTo(size);
        assertThat(page.watermarkExclusive()).isEqualTo(size);
        assertThat(page.items()).hasSize(size).extracting(BulkExecutionResultsReader.Item::status)
                .containsOnly(changed ? BulkItemStatus.CONFIRMED : BulkItemStatus.UNCHANGED);
        assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution e "
                + "join praxis_bulk.praxis_bulk_proposal p on p.proposal_id=e.proposal_id "
                + "where e.execution_id=? and e.atomicity='ATOMIC' and p.atomicity=e.atomicity "
                + "and e.protocol_version=2 and p.protocol_version=e.protocol_version", Integer.class, executionId))
                .isEqualTo(1);
    }

    /** Independent wire-format oracle: caller supplies the expected C/UTF-8 order, never a production sort. */
    private static String framedEffectDigest(List<String> orderedReferences) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var frames = new java.io.DataOutputStream(bytes);
            var segments = new ArrayList<String>();
            segments.add("praxis.bulk.atomic-effects/1");
            segments.add(Integer.toString(orderedReferences.size()));
            for (String reference : orderedReferences) {
                segments.add("0");
                segments.add(reference);
            }
            for (String segment : segments) {
                byte[] utf8 = segment.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                frames.writeInt(utf8.length);
                frames.write(utf8);
            }
            frames.flush();
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
            throw new AssertionError("cannot calculate the independent framing oracle", error);
        }
    }

    private void assertEffectBinding(UUID executionId, int expectedCount, int targetCount) {
        var effects = observer.query("select unit_ordinal,effect_ref "
                + "from praxis_bulk.praxis_bulk_atomic_effect_ref where execution_id=? "
                + "order by unit_ordinal,effect_ref collate \"C\"",
                (rows, ordinal) -> new BulkTargetDigest.EffectReference(rows.getInt(1), rows.getString(2)),
                executionId);
        assertThat(effects).hasSize(expectedCount);
        assertThat(observer.queryForObject("select effect_count from praxis_bulk.praxis_bulk_atomic_receipt "
                + "where execution_id=?", Integer.class, executionId)).isEqualTo(expectedCount);
        String digest = observer.queryForObject("select effect_digest from praxis_bulk.praxis_bulk_atomic_receipt "
                + "where execution_id=?", String.class, executionId);
        assertThat(digest).isEqualTo(BulkTargetDigest.effectsOf(effects));
        if (expectedCount == 0) assertThat(digest).isEqualTo(framedEffectDigest(List.of()));
        assertThat(domain.queryForObject("select praxis_bulk.atomic_evidence_complete(?,?)",
                Boolean.class, executionId, targetCount)).isTrue();
    }

    private void assertLateEvidenceInsertDenied(UUID executionId) {
        var headerBefore = observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_receipt "
                + "where execution_id=?", executionId);
        var itemsBefore = observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_item_result "
                + "where execution_id=? order by unit_ordinal", executionId);
        var effectsBefore = observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_effect_ref "
                + "where execution_id=? order by unit_ordinal,effect_ref collate \"C\"", executionId);
        assertSqlGuardDenied(() -> domain.update("""
                insert into praxis_bulk.praxis_bulk_atomic_item_result
                select execution_id,unit_ordinal,target_digest,expected_version,outcome
                from praxis_bulk.praxis_bulk_atomic_item_result where execution_id=? and unit_ordinal=0
                """, executionId));
        assertSqlGuardDenied(() -> domain.update("""
                insert into praxis_bulk.praxis_bulk_atomic_effect_ref(execution_id,unit_ordinal,effect_ref)
                values (?,0,'forbidden-late-effect')
                """, executionId));
        assertThat(observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_receipt "
                + "where execution_id=?", executionId)).isEqualTo(headerBefore);
        assertThat(observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_item_result "
                + "where execution_id=? order by unit_ordinal", executionId)).isEqualTo(itemsBefore);
        assertThat(observer.queryForList("select * from praxis_bulk.praxis_bulk_atomic_effect_ref "
                + "where execution_id=? order by unit_ordinal,effect_ref collate \"C\"", executionId))
                .isEqualTo(effectsBefore);
        assertEffectBinding(executionId, 3, 3);
    }

    private static void assertSqlGuardDenied(Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                error -> {
                    assertThat(error.getRootCause()).isInstanceOf(java.sql.SQLException.class);
                    assertThat(((java.sql.SQLException) error.getRootCause()).getSQLState()).isEqualTo("55000");
                });
    }

    private void assertRejection(UUID executionId, BulkUnitReasonCode reason) {
        assertThat(count("praxis_bulk_atomic_rejection")).isEqualTo(1);
        assertThat(observer.queryForObject("select reason_code from praxis_bulk.praxis_bulk_atomic_rejection "
                + "where execution_id=?", String.class, executionId)).isEqualTo(reason.name());
        int size = observer.queryForObject("select target_count from praxis_bulk.praxis_bulk_execution "
                + "where execution_id=?", Integer.class, executionId);
        var terminal = resultReader().read(CONTEXT, executionId, -1, 200);
        assertThat(terminal.executionStatus()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
        assertThat(terminal.nextOrdinal()).isZero();
        assertThat(terminal.watermarkExclusive()).isEqualTo(size);
        assertThat(terminal.items()).hasSize(size).extracting(BulkExecutionResultsReader.Item::status)
                .containsOnly(BulkItemStatus.NOT_PROCESSED);
    }

    private void assertNoEffects() {
        assertThat(observer.queryForObject("select sum(writes) from public.atomic_domain", Integer.class)).isZero();
        assertThat(observer.queryForObject("select sum(row_version) from public.atomic_domain", Long.class)).isZero();
        assertThat(observer.queryForObject("select count(*) from public.atomic_outbox", Integer.class)).isZero();
        assertThat(count("praxis_bulk_atomic_receipt")).isZero();
        assertThat(count("praxis_bulk_atomic_item_result")).isZero();
        assertThat(count("praxis_bulk_atomic_effect_ref")).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
        assertThat(count("praxis_bulk_admission")).isZero();
    }

    private BulkEvaluationSnapshot evaluation(BulkMode mode, int size) {
        var targets = new ArrayList<BulkTarget<String>>();
        var evidence = new ArrayList<BulkTargetEvidence<?>>();
        var changes = new ArrayList<BulkItemChange<String>>();
        for (int id = 1; id <= size; id++) {
            var target = new BulkTarget<>(Integer.toString(id), "v0");
            targets.add(target);
            evidence.add(new BulkTargetEvidence<>(target, "v0", JSON.objectNode().put("state", "PENDING"),
                    JSON.objectNode(), BulkTargetEligibility.executable()));
            changes.add(new BulkItemChange<>(target.id(), target.expectedVersion(),
                    List.of(BulkFieldChange.set("value", JSON.numberNode(id * 10)))));
        }
        var selection = new BulkSelection<String, JsonNode>(BulkSelectionMode.EXPLICIT, targets, null, null);
        BulkIntentSnapshot snapshot = switch (mode) {
            case DOMAIN_COMMAND -> BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(),
                    new BulkCommandEvaluationRequest<>(BulkExecutionMode.SYNC, selection,
                            JSON.objectNode().put("state", "APPROVED")), JsonNode::deepCopy, JsonNode::deepCopy);
            case UNIFORM_UPDATE -> BulkIntentSnapshot.uniform(CONTEXT, BulkIdentityCodecs.strings(),
                    new BulkUniformEvaluationRequest<>(BulkExecutionMode.SYNC, selection,
                            List.of(BulkFieldChange.set("value", JSON.numberNode(7)))), JsonNode::deepCopy);
            case PER_ITEM_UPDATE -> BulkIntentSnapshot.items(CONTEXT, BulkIdentityCodecs.strings(),
                    new BulkItemEvaluationRequest<>(BulkExecutionMode.SYNC, changes));
        };
        Instant created = Instant.now().minusSeconds(5);
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(600), snapshot,
                BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
        var governance = new BulkEvaluationGovernance("atomic-evaluator-r1", "atomic-grants-r1", List.of(
                new BulkPolicyObservation("tenant", "production", "approval_policy", "resource-action-approval",
                        "atomic-records:apply", "NEVER_APPLIED", "atomic-policy-r1", created.plusMillis(500))));
        return new BulkEvaluationSnapshot(proposal, created.plusSeconds(1), evidence, governance);
    }

    private BulkEvaluationSnapshot persist(BulkEvaluationSnapshot evaluation) {
        transactions.executeWithoutResult(status -> proposals.insertEvaluated(evaluation,
                BulkEvaluationSnapshotTest.preview(evaluation)));
        return evaluation;
    }

    private BulkExecutionReservation reserve(JdbcBulkDurableExecution kernel, BulkEvaluationSnapshot evaluation, String key) {
        return kernel.reserve(CONTEXT, evaluation.proposal().id(), key, "atomic-owner", "structural-r1",
                Instant.now().plusSeconds(30));
    }

    private BulkExecutionInfrastructure infrastructure() {
        return new BulkExecutionInfrastructure(runtime, manager, NAMESPACE, BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration());
    }

    private JdbcBulkDurableExecution kernel() { return new JdbcBulkDurableExecution(infrastructure()); }
    private int count(String table) { return observer.queryForObject("select count(*) from praxis_bulk." + table, Integer.class); }
}
