package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Real lifecycle publication and RR storage; based on BulkCrudOperationalLifecyclePostgresTest's fixture. */
class JdbcBulkUniformQueryPostgresTest {
    private static final String NAMESPACE = "crud-test";

    @Test void publishedQueryAdmissionCapturesFrozenRowsAndOldApisRemainClosed() throws Exception {
        try (var fixture = new Fixture()) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            fixture.lifecycle.publish(identity, 0);
            var admission = fixture.lifecycle.requireReady(identity, BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
            var proposal = fixture.proposal(admission.expectation());
            var store = new JdbcBulkProposalStore(fixture.store.runtime);
            var transaction = new TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var observed = new AtomicInteger();
            transaction.executeWithoutResult(status -> {
                var evaluation = store.captureAndInsertEvaluated(admission, proposal, (connection, remaining) -> {
                    observed.incrementAndGet();
                    assertThat(new JdbcTemplate(fixture.store.runtime.dataSource())
                            .queryForObject("select pg_backend_pid()", Integer.class)).isEqualTo(pid(connection));
                    assertThat(remaining.get()).isGreaterThan(Duration.ZERO);
                    return fixture.evaluation(proposal, 1, 2, 10);
                }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8));
                assertThat(evaluation.targets().stream().map(value -> (Integer) value.target().id()).toList())
                        .containsExactly(1, 2, 10);
            });
            assertThat(observed.get()).isEqualTo(1);
            var recovered = transaction.execute(status -> store.findEvaluation(proposal.snapshot().context(),
                    proposal.id()).orElseThrow());
            assertThat(recovered.targets().stream().map(value -> (Integer) value.target().id()).toList())
                    .containsExactly(1, 2, 10);
            assertThat(fixture.count("praxis_bulk_target_manifest")).isEqualTo(3);
            for (var table : List.of("praxis_bulk_proposal", "praxis_bulk_evaluation", "praxis_bulk_preview_state",
                    "praxis_bulk_allocation")) assertThat(fixture.count(table)).as(table).isEqualTo(1);
            for (var table : List.of("praxis_bulk_target_preview", "praxis_bulk_preview_item_integrity"))
                assertThat(fixture.count(table)).as(table).isEqualTo(3);

            var denied = fixture.proposal(admission.expectation());
            assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                    store.captureAndInsertEvaluated(denied, (connection, remaining) -> {
                        throw new AssertionError("legacy capture must not invoke QUERY callback");
                    }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8))))
                    .isInstanceOf(BulkProposalStorageException.class);
            assertThat(fixture.count("praxis_bulk_proposal")).isEqualTo(1);
            var legacyKernel = new JdbcBulkDurableExecution(fixture.store.runtime);
            assertThatThrownBy(() -> legacyKernel.reserve(proposal.snapshot().context(), proposal.id(),
                    "query-key", "worker-a", admission.expectation().structuralRevision(),
                    Instant.now().plusSeconds(60)))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            var kernel = new JdbcBulkDurableExecution(fixture.store.runtime, fixture.lifecycle);
            var reserved = kernel.reserve(proposal.snapshot().context(), proposal.id(), "query-key",
                    "worker-a", admission.expectation().structuralRevision(), Instant.now().plusSeconds(60));
            assertThat(reserved.replayed()).isFalse();
            fixture.owner.execute("create table public.query_effect (execution_id uuid not null, ordinal integer not null, primary key(execution_id, ordinal))");
            fixture.owner.execute("grant select, insert on public.query_effect to bulk_runtime_test");
            var domain = new JdbcTemplate(fixture.store.runtime.dataSource());
            var mutationCalls = new AtomicInteger();
            kernel.advance(reserved, unit -> BulkUnitAdmission.admit(), unit -> {
                mutationCalls.incrementAndGet();
                domain.update("insert into public.query_effect(execution_id, ordinal) values (?, ?)",
                        unit.executionId(), unit.ordinal());
                return BulkUnitMutationResult.confirmed();
            });
            assertThat(mutationCalls.get()).isEqualTo(3);
            assertThat(fixture.owner.queryForObject("select count(*) from public.query_effect", Integer.class)).isEqualTo(3);
            var completed = kernel.find(proposal.snapshot().context(), reserved.executionId()).orElseThrow();
            assertThat(completed.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
            assertThat(completed.nextOrdinal()).isEqualTo(3);
            assertThat(completed.receiptCount()).isEqualTo(3);
            assertThat(completed.control().epoch()).isEqualTo(reserved.control().epoch());
            var receiptsBefore = fixture.owner.queryForList("""
                    select to_jsonb(r)::text from praxis_bulk.praxis_bulk_item_receipt r
                    where execution_id=? order by unit_ordinal
                    """, String.class, reserved.executionId());
            assertThat(receiptsBefore).hasSize(3);
            var competing = fixture.proposal(admission.expectation());
            transaction.executeWithoutResult(status -> store.captureAndInsertEvaluated(admission, competing,
                    (connection, budget) -> fixture.evaluation(competing, 4),
                    BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8)));
            fixture.lifecycle.suspend(identity, admission.expectation().generation());
            var replay = legacyKernel.reserve(proposal.snapshot().context(), proposal.id(), "query-key",
                    "worker-a", admission.expectation().structuralRevision(), Instant.now().plusSeconds(60));
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.executionId()).isEqualTo(reserved.executionId());
            kernel.advance(replay, unit -> {
                throw new AssertionError("terminal QUERY replay admitted a new unit");
            }, unit -> {
                throw new AssertionError("terminal QUERY replay mutated the domain");
            });
            assertThat(fixture.owner.queryForObject("select count(*) from public.query_effect", Integer.class)).isEqualTo(3);
            var afterReplay = kernel.find(proposal.snapshot().context(), reserved.executionId()).orElseThrow();
            assertThat(afterReplay.nextOrdinal()).isEqualTo(3);
            assertThat(afterReplay.receiptCount()).isEqualTo(3);
            assertThat(afterReplay.control().epoch()).isEqualTo(completed.control().epoch());
            assertThat(fixture.owner.queryForList("""
                    select to_jsonb(r)::text from praxis_bulk.praxis_bulk_item_receipt r
                    where execution_id=? order by unit_ordinal
                    """, String.class, reserved.executionId())).isEqualTo(receiptsBefore);
            // The immutable key belongs to the first proposal even after readiness is suspended.
            assertThatThrownBy(() -> legacyKernel.reserve(competing.snapshot().context(), competing.id(),
                    "query-key", "worker-b", admission.expectation().structuralRevision(),
                    Instant.now().plusSeconds(60)))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.CONFLICT));
        }
    }

    @Test void publicationChangedAfterRealAdmissionCannotCreateAQueryExecution() throws Exception {
        try (var fixture = new Fixture()) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            fixture.lifecycle.publish(identity, 0);
            var current = fixture.lifecycle.requireReady(identity, BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
            var proposal = fixture.proposal(current.expectation());
            var transaction = new TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> new JdbcBulkProposalStore(fixture.store.runtime)
                    .captureAndInsertEvaluated(current, proposal,
                            (connection, budget) -> fixture.evaluation(proposal, 1),
                            BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8)));
            var realAdmissions = new AtomicInteger();
            var lifecycle = mock(BulkOperationLifecycle.class,
                    withSettings().spiedInstance(fixture.lifecycle)
                            .defaultAnswer(org.mockito.Answers.CALLS_REAL_METHODS)
                            .mockMaker(org.mockito.MockMakers.INLINE));
            doAnswer(invocation -> {
                var minted = (BulkOperationLifecycle.ReadyAdmission) invocation.callRealMethod();
                realAdmissions.incrementAndGet();
                long suspended = fixture.lifecycle.suspend(identity, current.expectation().generation());
                fixture.lifecycle.publish(identity, suspended);
                return minted;
            }).when(lifecycle).requireReady(identity, BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
            var kernel = new JdbcBulkDurableExecution(fixture.store.runtime, lifecycle);
            assertThatThrownBy(() -> kernel.reserve(proposal.snapshot().context(), proposal.id(),
                    "stale-query-key", "worker-a", current.expectation().structuralRevision(),
                    Instant.now().plusSeconds(60)))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            assertThat(realAdmissions.get()).isEqualTo(1);
            assertThat(fixture.count("praxis_bulk_execution")).isZero();
            assertThat(fixture.count("praxis_bulk_allocation")).isEqualTo(1);
        }
    }

    @Test void purgedQueryTombstonePrecedesMissingEvaluationForBothKernelConstructors() throws Exception {
        try (var fixture = new Fixture()) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            fixture.lifecycle.publish(identity, 0);
            var admission = fixture.lifecycle.requireReady(identity, BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
            var proposal = fixture.proposal(admission.expectation());
            var transaction = new TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> new JdbcBulkProposalStore(fixture.store.runtime)
                    .captureAndInsertEvaluated(admission, proposal,
                            (connection, budget) -> fixture.evaluation(proposal, 1),
                            BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8)));
            var kernel = new JdbcBulkDurableExecution(fixture.store.runtime, fixture.lifecycle);
            var reserved = kernel.reserve(proposal.snapshot().context(), proposal.id(), "retained-query-key",
                    "worker-a", admission.expectation().structuralRevision(), Instant.now().plusSeconds(60));
            kernel.requestCancel(proposal.snapshot().context(), reserved.executionId());
            ageTerminalForRetention(fixture.owner, reserved.executionId());
            fixture.lifecycle.suspend(identity, admission.expectation().generation());
            fixture.owner.execute("grant praxis_bulk_retention_executor to postgres");
            try {
                var ownerTransaction = new TransactionTemplate(
                        new DataSourceTransactionManager(fixture.store.postgres.getPostgresDatabase()));
                Boolean purged = ownerTransaction.execute(status -> {
                    fixture.owner.execute("set local role praxis_bulk_retention_executor");
                    return fixture.owner.queryForObject("select praxis_bulk.purge_terminal_execution(?)",
                            Boolean.class, reserved.executionId());
                });
                assertThat(purged).isTrue();
            } finally {
                fixture.owner.execute("revoke praxis_bulk_retention_executor from postgres");
            }
            assertThat(fixture.owner.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution where execution_id=?",
                    Integer.class, reserved.executionId())).isZero();
            assertThat(fixture.owner.queryForObject("select count(*) from praxis_bulk.praxis_bulk_tombstone where execution_id=?",
                    Integer.class, reserved.executionId())).isEqualTo(1);
            for (var reader : List.of(new JdbcBulkDurableExecution(fixture.store.runtime), kernel))
                assertThatThrownBy(() -> reader.reserve(proposal.snapshot().context(), proposal.id(),
                        "retained-query-key", "worker-b", admission.expectation().structuralRevision(),
                        Instant.now().plusSeconds(60)))
                        .isInstanceOfSatisfying(BulkDurableExecutionException.class, failure ->
                                assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.RESULT_PURGED));
        }
    }

    private static void ageTerminalForRetention(JdbcTemplate owner, UUID executionId) {
        for (var command : List.of(
                "alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_guard_terminal",
                "alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_cancel",
                "alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_binding",
                "alter table praxis_bulk.praxis_bulk_allocation disable trigger praxis_bulk_allocation_protect_transition"))
            owner.execute(command);
        try {
            assertThat(owner.update("""
                    with aged as materialized (select clock_timestamp() - interval '31 days' as terminal)
                    update praxis_bulk.praxis_bulk_execution
                    set created_at=aged.terminal - interval '2 seconds',
                        cancel_requested_at=aged.terminal - interval '1 second',
                        terminal_at=aged.terminal, updated_at=aged.terminal
                    from aged where execution_id=?
                    """, executionId)).isEqualTo(1);
            assertThat(owner.update("""
                    update praxis_bulk.praxis_bulk_allocation a
                    set created_at=e.created_at, released_at=e.terminal_at
                    from praxis_bulk.praxis_bulk_execution e
                    where a.execution_id=e.execution_id and e.execution_id=?
                    """, executionId)).isEqualTo(1);
        } finally {
            owner.execute("alter table praxis_bulk.praxis_bulk_allocation enable trigger praxis_bulk_allocation_protect_transition");
            owner.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_binding");
            owner.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_cancel");
            owner.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_guard_terminal");
        }
    }

    @Test void publishedExplicitOrSuspendedQueryNeverCallsCapture() throws Exception {
        try (var explicit = new Fixture(20, false)) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            explicit.lifecycle.publish(identity, 0);
            assertThatThrownBy(() -> explicit.lifecycle.requireReady(identity,
                    BulkExecutionMode.SYNC, BulkSelectionMode.QUERY)).isInstanceOf(IllegalStateException.class);
        }
        try (var fixture = new Fixture()) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            fixture.lifecycle.publish(identity, 0);
            var admission = fixture.lifecycle.requireReady(identity, BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
            var oldApiProposal = fixture.proposal(admission.expectation());
            var legacyStore = new JdbcBulkProposalStore(fixture.store.runtime);
            assertThatThrownBy(() -> legacyStore.insert(oldApiProposal))
                    .isInstanceOf(BulkProposalStorageException.class);
            var oldApiEvaluation = fixture.evaluation(oldApiProposal, 1);
            assertThatThrownBy(() -> legacyStore.insertEvaluated(oldApiEvaluation,
                    BulkEvaluationSnapshotTest.preview(oldApiEvaluation)))
                    .isInstanceOf(BulkProposalStorageException.class);
            fixture.lifecycle.suspend(identity, admission.expectation().generation());
            var proposal = fixture.proposal(admission.expectation());
            var transaction = new TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                    new JdbcBulkProposalStore(fixture.store.runtime).captureAndInsertEvaluated(admission,
                            proposal, (connection, budget) -> {
                                calls.incrementAndGet();
                                return fixture.evaluation(proposal, 1);
                            }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8))))
                    .isInstanceOf(BulkProposalStorageException.class);
            assertThat(calls.get()).isZero();
            assertThat(fixture.count("praxis_bulk_proposal")).isZero();
        }
    }

    @Test void publishedCapRejectsZeroAndOverCapacityAfterCallbackWithoutDurableRows() throws Exception {
        try (var fixture = new Fixture(200)) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            fixture.lifecycle.publish(identity, 0);
            var admission = fixture.lifecycle.requireReady(identity, BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
            var store = new JdbcBulkProposalStore(fixture.store.runtime);
            var transaction = new TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var all = java.util.stream.IntStream.rangeClosed(1, 201).filter(id -> id != 9).toArray();
            var valid = fixture.proposal(admission.expectation());
            transaction.executeWithoutResult(status -> store.captureAndInsertEvaluated(admission, valid,
                    (connection, budget) -> fixture.evaluation(valid, all), BulkEvaluationSnapshotTest::preview,
                    () -> Duration.ofSeconds(8)));
            assertThat(fixture.count("praxis_bulk_target_manifest")).isEqualTo(200);
            assertThat(fixture.count("praxis_bulk_target_preview")).isEqualTo(200);

            var empty = fixture.proposal(admission.expectation());
            var callbacks = new AtomicInteger();
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                try {
                    store.captureAndInsertEvaluated(admission, empty, (connection, budget) -> {
                        callbacks.incrementAndGet();
                        throw BulkProposalStorageException.invalidSelection();
                    }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8));
                } catch (BulkProposalStorageException failure) {
                    assertThat(failure.reason()).isEqualTo(BulkProposalStorageException.Reason.INVALID_SELECTION);
                    assertThat(failure).hasNoCause();
                }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(callbacks.get()).isEqualTo(1);
            assertThat(fixture.count("praxis_bulk_proposal")).isEqualTo(1);

            var oversized = fixture.proposal(admission.expectation());
            var tooMany = java.util.stream.IntStream.rangeClosed(1, 202).filter(id -> id != 9).toArray();
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                try {
                    store.captureAndInsertEvaluated(admission, oversized, (connection, budget) ->
                            fixture.evaluation(oversized, tooMany), BulkEvaluationSnapshotTest::preview,
                            () -> Duration.ofSeconds(8));
                } catch (BulkProposalStorageException failure) {
                    assertThat(failure.reason()).isEqualTo(BulkProposalStorageException.Reason.INVALID_SELECTION);
                    assertThat(failure).hasNoCause();
                }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(fixture.count("praxis_bulk_proposal")).isEqualTo(1);
            assertThat(fixture.count("praxis_bulk_target_manifest")).isEqualTo(200);

            var excluded = fixture.proposal(admission.expectation());
            var excludedCaptureCalls = new AtomicInteger();
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                try {
                    store.captureAndInsertEvaluated(admission, excluded, (connection, budget) -> {
                        excludedCaptureCalls.incrementAndGet();
                        return fixture.evaluation(excluded, 9);
                    }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8));
                } catch (BulkProposalStorageException failure) {
                    assertThat(failure.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
                    assertThat(failure).hasNoCause();
                }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(excludedCaptureCalls.get()).isEqualTo(1);
            for (var table : List.of("praxis_bulk_proposal", "praxis_bulk_evaluation",
                    "praxis_bulk_preview_state", "praxis_bulk_allocation"))
                assertThat(fixture.count(table)).as(table).isEqualTo(1);
            for (var table : List.of("praxis_bulk_target_manifest", "praxis_bulk_target_preview",
                    "praxis_bulk_preview_item_integrity"))
                assertThat(fixture.count(table)).as(table).isEqualTo(200);
        }
    }

    @Test void unexpectedCaptureFailureIsSafeAndRollsBackTheOwningTransaction() throws Exception {
        try (var fixture = new Fixture()) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            fixture.lifecycle.publish(identity, 0);
            var admission = fixture.lifecycle.requireReady(identity, BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
            var proposal = fixture.proposal(admission.expectation());
            var transaction = new TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                try {
                    new JdbcBulkProposalStore(fixture.store.runtime).captureAndInsertEvaluated(admission,
                            proposal, (connection, budget) -> {
                                calls.incrementAndGet();
                                throw new IllegalStateException("SECRET-target-identity");
                            }, BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8));
                } catch (BulkProposalStorageException safe) {
                    assertThat(safe.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
                    assertThat(safe).hasNoCause().hasMessageNotContaining("SECRET");
                }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(calls.get()).isEqualTo(1);
            for (var table : List.of("praxis_bulk_proposal", "praxis_bulk_evaluation",
                    "praxis_bulk_target_manifest", "praxis_bulk_preview_state", "praxis_bulk_target_preview",
                    "praxis_bulk_preview_item_integrity", "praxis_bulk_allocation"))
                assertThat(fixture.count(table)).as(table).isZero();
        }
    }

    @Test void projectionCannotManufactureInvalidSelectionAfterCapture() throws Exception {
        try (var fixture = new Fixture()) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            fixture.lifecycle.publish(identity, 0);
            var admission = fixture.lifecycle.requireReady(identity, BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
            var proposal = fixture.proposal(admission.expectation());
            var transaction = new TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                try {
                    new JdbcBulkProposalStore(fixture.store.runtime).captureAndInsertEvaluated(admission,
                            proposal, (connection, budget) -> fixture.evaluation(proposal, 1),
                            evaluation -> { throw BulkProposalStorageException.invalidSelection(); },
                            () -> Duration.ofSeconds(8));
                } catch (BulkProposalStorageException safe) {
                    assertThat(safe.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE);
                    assertThat(safe).hasNoCause();
                }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            for (var table : List.of("praxis_bulk_proposal", "praxis_bulk_evaluation",
                    "praxis_bulk_target_manifest", "praxis_bulk_preview_state", "praxis_bulk_target_preview",
                    "praxis_bulk_preview_item_integrity", "praxis_bulk_allocation"))
                assertThat(fixture.count(table)).as(table).isZero();
        }
    }

    @Test void explicitFreshReservationAndReplayKeepTheOriginalSingleTransactionPath() throws Exception {
        try (var fixture = new Fixture(20, false)) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            fixture.lifecycle.publish(identity, 0);
            var ready = fixture.lifecycle.requireReady(identity);
            var proposal = fixture.explicitProposal(ready);
            var store = new JdbcBulkProposalStore(fixture.store.runtime);
            var transaction = new TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> store.captureAndInsertEvaluated(proposal,
                    (connection, remaining) -> fixture.evaluation(proposal, 1),
                    BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8)));
            var kernel = new JdbcBulkDurableExecution(fixture.store.runtime);
            var first = kernel.reserve(proposal.snapshot().context(), proposal.id(), "explicit-key",
                    "worker-a", ready.structuralRevision(), Instant.now().plusSeconds(60));
            assertThat(first.replayed()).isFalse();
            fixture.lifecycle.suspend(identity, ready.generation());
            var replay = kernel.reserve(proposal.snapshot().context(), proposal.id(), "explicit-key",
                    "worker-b", ready.structuralRevision(), Instant.now().plusSeconds(60));
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.executionId()).isEqualTo(first.executionId());
            assertThat(fixture.count("praxis_bulk_execution")).isEqualTo(1);
        }
    }

    @Test @Timeout(30)
    void queryReservationAndPublicationTransitionSerializeOnTheDurableControlFence() throws Exception {
        try (var fixture = new Fixture()) {
            var identity = new BulkOperationControlIdentity(NAMESPACE, "crud.uniform");
            fixture.lifecycle.publish(identity, 0);
            var admission = fixture.lifecycle.requireReady(identity, BulkExecutionMode.SYNC, BulkSelectionMode.QUERY);
            var proposal = fixture.proposal(admission.expectation());
            var transaction = new TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> new JdbcBulkProposalStore(fixture.store.runtime)
                    .captureAndInsertEvaluated(admission, proposal,
                            (connection, budget) -> fixture.evaluation(proposal, 1),
                            BulkEvaluationSnapshotTest::preview, () -> Duration.ofSeconds(8)));
            var kernel = new JdbcBulkDurableExecution(fixture.store.runtime, fixture.lifecycle);
            var executor = Executors.newFixedThreadPool(2);
            try (var owner = fixture.store.postgres.getPostgresDatabase().getConnection()) {
                owner.setAutoCommit(false);
                int ownerPid = pid(owner);
                try (var lock = owner.prepareStatement("select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket where deployment_id=? for update")) {
                    lock.setString(1, "deployment-test");
                    try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                var reserved = executor.submit(() -> kernel.reserve(proposal.snapshot().context(), proposal.id(),
                        "query-race-key", "worker-a", admission.expectation().structuralRevision(),
                        Instant.now().plusSeconds(60)));
                try {
                    int reservePid = awaitBlockedPid(fixture.owner, ownerPid, "praxis_bulk_deployment_bucket");
                    assertThat(reserved.isDone()).isFalse();
                    var suspension = executor.submit(() -> fixture.lifecycle.suspend(identity,
                            admission.expectation().generation()));
                    int suspendPid = awaitBlockedPid(fixture.owner, reservePid, "transition_operation_control");
                    assertThat(suspendPid).isNotEqualTo(reservePid).isNotEqualTo(ownerPid);
                    assertThat(suspension.isDone()).isFalse();
                    owner.commit();
                    var first = reserved.get(5, TimeUnit.SECONDS);
                    assertThat(first.replayed()).isFalse();
                    assertThat(suspension.get(5, TimeUnit.SECONDS)).isEqualTo(admission.expectation().generation() + 1);
                    assertThat(fixture.count("praxis_bulk_execution")).isEqualTo(1);
                    assertThat(new JdbcBulkDurableExecution(fixture.store.runtime).reserve(
                            proposal.snapshot().context(), proposal.id(), "query-race-key", "worker-b",
                            admission.expectation().structuralRevision(), Instant.now().plusSeconds(60)).replayed())
                            .isTrue();
                } finally {
                    owner.rollback();
                }
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static int awaitBlockedPid(JdbcTemplate observer, int blockerPid, String queryMarker)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var pids = observer.queryForList("""
                    select a.pid from pg_stat_activity a
                    where a.pid<>? and a.wait_event_type='Lock'
                      and ?=any(pg_blocking_pids(a.pid)) and a.query like ?
                    """, Integer.class, blockerPid, blockerPid, "%" + queryMarker + "%");
            if (pids.size() == 1) return pids.getFirst();
            Thread.sleep(10);
        }
        throw new AssertionError("Expected durable SQL blocker was not observed for " + queryMarker);
    }

    private static int pid(java.sql.Connection connection) {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("select pg_backend_pid()")) {
            if (!rows.next()) throw new AssertionError("No backend PID");
            return rows.getInt(1);
        } catch (java.sql.SQLException error) { throw new AssertionError(error); }
    }

    private static final class Fixture implements AutoCloseable {
        final BulkCrudOperationalLifecyclePostgresTest.Store store;
        final org.springframework.web.context.support.AnnotationConfigWebApplicationContext context;
        final OpenApiInternalRestTemplate client;
        final CachedOpenApiDocumentService documents;
        final BulkOperationLifecycle lifecycle;
        final JdbcTemplate owner;

        Fixture() throws Exception { this(20, true); }

        Fixture(int maxTargets) throws Exception { this(maxTargets, true); }

        Fixture(int maxTargets, boolean queryEnabled) throws Exception {
            store = new BulkCrudOperationalLifecyclePostgresTest.Store(List.of("crud.uniform", "crud.items"));
            context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class);
            owner = new JdbcTemplate(store.postgres.getPostgresDatabase());
            var value = BulkCrudOperationalCompositionTest.document();
            var selection = (com.fasterxml.jackson.databind.node.ObjectNode) value.at(
                    "/paths/~1crud-items~1bulk~1uniform~1evaluation/post/requestBody/content/application~1json/schema/properties/selection/properties");
            selection.putObject("filter").put("type", "object");
            selection.putObject("excludedIds").put("type", "array").set("items", BulkIdentityCodecs.integers().canonicalWireSchema());
            value.put("openapi", "3.0.3");
            value.putObject("info").put("title", "QUERY publication fixture").put("version", "1");
            var registration = mock(jakarta.servlet.FilterRegistration.class);
            org.mockito.Mockito.when(registration.getClassName()).thenReturn(
                    org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter.class.getName());
            org.mockito.Mockito.when(registration.getUrlPatternMappings()).thenReturn(List.of("/*"));
            org.mockito.Mockito.when(registration.getServletNameMappings()).thenReturn(List.of());
            var servlet = new org.springframework.mock.web.MockServletContext() {
                @Override public jakarta.servlet.FilterRegistration getFilterRegistration(String name) { return registration; }
            };
            var filter = new org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter[1];
            var source = new OpenApiDocsSupport() {
                @Override public String localPublicationContextPath() { return ""; }
                @Override public org.praxisplatform.uischema.openapi.OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(
                        RestTemplate rest, String path, org.slf4j.Logger logger, ObjectMapper mapper) {
                    var request = new org.springframework.mock.web.MockHttpServletRequest(servlet, "GET", path);
                    request.setServletPath(path);
                    request.addHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER,
                            ((OpenApiInternalRestTemplate) rest).producerCaptureToken(path));
                    var response = new org.springframework.mock.web.MockHttpServletResponse();
                    try {
                        filter[0].doFilter(request, response, (input, output) -> {
                            byte[] bytes = path.endsWith("/swagger-config")
                                    ? "{\"urls\":[{\"name\":\"crud\",\"url\":\"/v3/api-docs/crud\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                    : mapper.writeValueAsBytes(value);
                            output.getOutputStream().write(bytes);
                        });
                        assertEquals(200, response.getStatus());
                        return org.praxisplatform.uischema.openapi.OpenApiDocumentCapture.parse(response.getContentAsByteArray(), mapper);
                    } catch (Exception error) { throw new IllegalStateException("QUERY publication fixture failed", error); }
                }
                @Override public String resolveGroupFromPath(String path) { return "crud"; }
                @Override public JsonNode fetchOpenApiDocument(RestTemplate rest, String base, String group, org.slf4j.Logger logger) { return value.deepCopy(); }
                @Override public JsonNode fetchOpenApiGroupDocument(RestTemplate rest, String base, String group, org.slf4j.Logger logger) { return value.deepCopy(); }
                @Override public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate rest, String base, String group, org.slf4j.Logger logger) { return value.deepCopy(); }
            };
            client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10));
            documents = new CachedOpenApiDocumentService(client, new ObjectMapper(), source, true);
            ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");
            filter[0] = new org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter(documents);
            filter[0].init(new org.springframework.mock.web.MockFilterConfig(servlet, "bulkPublication"));
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("crud"));
            var query = new BulkCrudOperationalCompositionTest.Provider("crud.uniform", "query-r1",
                    BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, store.runtime) {
                @Override public BulkOperationalProfile profile() {
                    return new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE), Set.of(BulkExecutionMode.SYNC),
                            queryEnabled ? Set.of(BulkSelectionMode.EXPLICIT, BulkSelectionMode.QUERY)
                                    : Set.of(BulkSelectionMode.EXPLICIT), maxTargets, 65_536,
                            Duration.ofMinutes(2), Duration.ofSeconds(2));
                }
            };
            var items = new BulkCrudOperationalCompositionTest.Provider("crud.items", "r1",
                    BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, store.runtime);
            lifecycle = new BulkOperationLifecycle(bindings, resolver, documents, mock(ActionDefinitionRegistry.class),
                    BulkCrudStructuralCompilerTest.mapper(), new FilteredSchemaReferenceResolver(), store.runtime,
                    store.control, List.of(query, items));
        }

        BulkStoredProposal proposal(BulkOperationControlExpectation expectation) {
            var context = new BulkFingerprintContext(NAMESPACE, "subject-a", "crud-items",
                    new CanonicalOperationRef("crud", "crud.uniform", "/crud-items/bulk/uniform/evaluation", "POST"),
                    expectation.structuralRevision(), ActionCollectionAtomicity.PER_ITEM);
            var selection = new BulkSelection<Integer, JsonNode>(BulkSelectionMode.QUERY, null,
                    JsonNodeFactory.instance.objectNode().put("active", true), List.of(9));
            var request = new BulkUniformEvaluationRequest<Integer, JsonNode>(BulkExecutionMode.SYNC, selection,
                    List.of(BulkFieldChange.set("amount", JsonNodeFactory.instance.numberNode(1))));
            var snapshot = BulkIntentSnapshot.uniform(context, BulkIdentityCodecs.integers(), request, JsonNode::deepCopy);
            Instant now = Instant.now();
            return new BulkStoredProposal(UUID.randomUUID(), now, now.plusSeconds(120), snapshot, expectation);
        }

        BulkStoredProposal explicitProposal(BulkOperationControlExpectation expectation) {
            var context = new BulkFingerprintContext(NAMESPACE, "subject-a", "crud-items",
                    new CanonicalOperationRef("crud", "crud.uniform", "/crud-items/bulk/uniform/evaluation", "POST"),
                    expectation.structuralRevision(), ActionCollectionAtomicity.PER_ITEM);
            var selection = new BulkSelection<Integer, JsonNode>(BulkSelectionMode.EXPLICIT,
                    List.of(new BulkTarget<>(1, "v1")), null, null);
            var request = new BulkUniformEvaluationRequest<Integer, JsonNode>(BulkExecutionMode.SYNC, selection,
                    List.of(BulkFieldChange.set("amount", JsonNodeFactory.instance.numberNode(1))));
            var snapshot = BulkIntentSnapshot.uniform(context, BulkIdentityCodecs.integers(), request,
                    JsonNode::deepCopy);
            Instant now = Instant.now();
            return new BulkStoredProposal(UUID.randomUUID(), now, now.plusSeconds(120), snapshot, expectation);
        }

        BulkEvaluationSnapshot evaluation(BulkStoredProposal proposal, int... ids) {
            var evidence = new java.util.ArrayList<BulkTargetEvidence<?>>();
            for (int id : ids) evidence.add(new BulkTargetEvidence<>(new BulkTarget<>(id, "v1"), "v1",
                    JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                    BulkTargetEligibility.executable()));
            var governance = new BulkEvaluationGovernance("evaluator-r1", "grant-r1", List.of(
                    new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                            "resource:approve", "NEVER_APPLIED", "policy-r1", proposal.createdAt())));
            return new BulkEvaluationSnapshot(proposal, proposal.createdAt().plusSeconds(1), evidence, governance);
        }

        int count(String table) {
            return owner.queryForObject("select count(*) from praxis_bulk." + table, Integer.class);
        }

        @Override public void close() throws Exception {
            try { context.close(); } finally { try { client.close(); } finally { store.close(); } }
        }
    }
}
