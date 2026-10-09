package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/** Real protected control-loop proofs, not public ASYNC or host authorization acceptance. */
class BulkDurableWorkerPostgresTest {
    @Test
    void quantumServesOtherTenantBeforeFirstTwoOrdinalJobCompletes() throws Exception {
        try (var shared = new BulkCapacityOccupancyPostgresFixture.SharedScope()) {
            var a = shared.local("worker-quantum-a", 1);
            var b = shared.local("worker-quantum-b", 2);
            a.activate(); b.activate();
            var qa = a.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var qb = b.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            a.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            b.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var ja = a.enqueue(a.persist(), "worker-a", qa);
            var jb = b.enqueue(b.persist(), "worker-b", qb);
            var order = new CopyOnWriteArrayList<String>();
            var physicalA = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var physicalB = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var worker = new BulkDurableWorker(List.of(binding(a, unit -> {
                physicalA.add(a.writeDomain(unit)); order.add("a" + unit.ordinal());
                return BulkUnitMutationResult.confirmed();
            }), binding(b, unit -> {
                physicalB.add(b.writeDomain(unit)); order.add("b" + unit.ordinal());
                return BulkUnitMutationResult.confirmed();
            })));
            try {
                worker.start();
                await(() -> completed(a, ja.executionId()) && completed(b, jb.executionId()));
            } finally { worker.stop(); assertThat(worker.isRunning()).isFalse(); }
            assertThat(order).containsExactly("a0", "b0", "a1", "b1");
            physicalA.forEach(value -> a.assertPhysicalCommit(ja.executionId(), value));
            physicalB.forEach(value -> b.assertPhysicalCommit(jb.executionId(), value));
            assertThat(a.count("item_receipt")).isEqualTo(2);
            assertThat(b.count("item_receipt")).isEqualTo(2);
            assertThat(a.allocation(ja.executionId())).containsEntry("state", "RELEASED");
            assertThat(b.allocation(jb.executionId())).containsEntry("state", "RELEASED");
            a.assertionsComplete(); b.assertionsComplete();
        }
    }

    @Test
    void controlLoopExpiresQueuedJobWithoutAnyActiveInstallation() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("worker-expiry-no-active")) {
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var queued = fixture.enqueue(fixture.persist(), "worker-expire", queue, Instant.now().plusMillis(500));
            var callbacks = new AtomicInteger();
            var worker = new BulkDurableWorker(List.of(binding(fixture, unit -> {
                callbacks.incrementAndGet(); return BulkUnitMutationResult.confirmed();
            })));
            try {
                worker.start();
                await(() -> fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow().status()
                        == BulkDurableExecutionStatus.STOPPED);
            } finally { worker.stop(); assertThat(worker.isRunning()).isFalse(); }
            var terminal = fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow();
            assertThat(terminal.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.DEADLINE_EXCEEDED);
            assertThat(terminal.control().epoch()).isEqualTo(1);
            assertThat(callbacks).hasValue(0);
            assertThat(fixture.count("item_receipt")).isZero();
            assertThat(fixture.slot(queue)).containsEntry("current_execution_id", null)
                    .containsEntry("occupancy_sequence", 1L);
            assertThat(fixture.allocation(queued.executionId())).containsEntry("state", "RELEASED");
            fixture.assertionsComplete();
        }
    }

    @Test
    void cancellationBeforeWorkerSelectionNeverDispatchesDomain() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("worker-cancel-before-selection")) {
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = fixture.enqueue(fixture.persist(), "worker-cancel", queue);
            fixture.kernel.requestCancel(fixture.context, queued.executionId());
            var callbacks = new AtomicInteger();
            var worker = new BulkDurableWorker(List.of(binding(fixture, unit -> {
                callbacks.incrementAndGet(); return BulkUnitMutationResult.confirmed();
            })));
            try {
                worker.start();
                assertThat(fixture.kernel.workerNextQueued(null)).isEmpty();
            } finally { worker.stop(); assertThat(worker.isRunning()).isFalse(); }
            assertThat(callbacks).hasValue(0);
            assertThat(fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow().terminalReasonCode())
                    .isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(1);
            fixture.assertionsComplete();
        }
    }

    @Test
    void oldOwnedRecoveryCannotChangeAlreadyRecoveredExecution() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("worker-recovery-owner-cas")) {
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = fixture.enqueue(fixture.persist(), "worker-recovery", queue);
            var claimed = fixture.kernel.claim(fixture.context, queued.executionId(), "worker-old", active).orElseThrow();
            fixture.kernel.recover(fixture.context, claimed.executionId(), "trusted-recovery");
            var before = fixture.kernel.find(fixture.context, claimed.executionId()).orElseThrow();
            var allocation = fixture.allocation(claimed.executionId());
            assertThatThrownBy(() -> fixture.kernel.workerRecoverOwned(fixture.context, claimed, "worker-old"))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            var after = fixture.kernel.find(fixture.context, claimed.executionId()).orElseThrow();
            assertThat(after.control().executionId()).isEqualTo(before.control().executionId());
            assertThat(after.control().ownerId()).isEqualTo(before.control().ownerId());
            assertThat(after.control().epoch()).isEqualTo(before.control().epoch());
            assertThat(after.status()).isEqualTo(before.status());
            assertThat(fixture.allocation(claimed.executionId())).isEqualTo(allocation);
            assertThat(fixture.count("item_receipt")).isZero();
            fixture.assertionsComplete();
        }
    }

    @Test
    void keysetHintIsOrderedAndCanWrapWithoutOwnership() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("worker-keyset-hint")) {
            fixture.activate();
            var q1 = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var q2 = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var first = fixture.enqueue(fixture.persist(), "worker-keyset-1", q1);
            var second = fixture.enqueue(fixture.persist(), "worker-keyset-2", q2);
            var one = fixture.kernel.workerNextQueued(null).orElseThrow();
            var two = fixture.kernel.workerNextQueued(one).orElseThrow();
            assertThat(List.of(one.executionId(), two.executionId()))
                    .containsExactly(first.executionId(), second.executionId());
            assertThat(fixture.kernel.workerNextQueued(two)).isEmpty();
            assertThat(fixture.kernel.workerNextQueued(null)).contains(one);
            assertThat(fixture.kernel.find(fixture.context, first.executionId()).orElseThrow().control().epoch()).isEqualTo(1);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(2);
            fixture.kernel.requestCancel(fixture.context, first.executionId());
            fixture.kernel.requestCancel(fixture.context, second.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void ownedRecoveryRejectsQueuedAndSyncReservationsWithoutChangingThem() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("worker-recovery-subset")) {
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var queued = fixture.enqueue(fixture.persist(), "worker-subset-queued", queue);
            var before = fixture.allocation(queued.executionId());
            assertThatThrownBy(() -> fixture.kernel.workerRecoverOwned(fixture.context, queued, "worker-rejected"))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            assertThat(fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow().status())
                    .isEqualTo(BulkDurableExecutionStatus.QUEUED);
            assertThat(fixture.allocation(queued.executionId())).isEqualTo(before);
            var input = fixture.persistSync();
            var sync = fixture.kernel.reserve(fixture.syncContext, input.proposal().id(), "worker-subset-sync",
                    "sync-owner", fixture.syncContext.schemaRevision(), Instant.now().plusSeconds(300));
            var syncAllocation = fixture.allocation(sync.executionId());
            assertThatThrownBy(() -> fixture.kernel.workerRecoverOwned(fixture.syncContext, sync, "worker-rejected"))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            assertThat(fixture.kernel.find(fixture.syncContext, sync.executionId()).orElseThrow().control().epoch()).isEqualTo(1);
            assertThat(fixture.allocation(sync.executionId())).isEqualTo(syncAllocation);
            assertThat(fixture.count("item_receipt")).isZero();
            fixture.kernel.requestCancel(fixture.context, queued.executionId());
            fixture.kernel.requestCancel(fixture.syncContext, sync.executionId());
            fixture.assertionsComplete();
        }
    }

    static BulkDurableWorker.Binding binding(BulkCapacityOccupancyPostgresFixture fixture,
            BulkUnitMutationCallback mutation) {
        return new BulkDurableWorker.Binding(fixture.kernel, List.of(new BulkDurableWorker.Handler(
                fixture.context.resourceKey(), fixture.context.operationRef(), () -> new BulkDurableWorkerComposition.UnitCallbacks( unit -> BulkUnitAdmission.admit(), mutation, () -> { }))));
    }
    static boolean completed(BulkCapacityOccupancyPostgresFixture fixture, UUID executionId) {
        return fixture.kernel.find(fixture.context, executionId).orElseThrow().status()
                == BulkDurableExecutionStatus.COMPLETED;
    }
    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("Protected worker condition did not complete");
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
    }
}
