package org.praxisplatform.uischema.bulk;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.consumer.BulkWorkerExternalConsumer;
import static org.assertj.core.api.Assertions.*;

/** Protected seed is kept in the canonical harness; consumer uses only public construction. */
class BulkDurableWorkerCallbackScopePostgresTest {
    @Test void externalConsumerAllocatesFreshPairsAndCleanupFailureCannotUndoCommitOrReplay() throws Exception {
        try (var f = new BulkCapacityOccupancyPostgresFixture("worker-public-pair")) {
            f.activate();
            var queue = f.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            f.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = f.enqueue(f.persist(), "worker-public-pair", queue);
            var factories = new AtomicInteger(); var cleanups = new AtomicInteger();
            var control = new AtomicReference<BulkExecutionControl>();
            var operation = new BulkDurableWorkerComposition.Operation(f.context.resourceKey(), f.context.operationRef(),
                    () -> {
                        factories.incrementAndGet();
                        var admitted = new AtomicReference<BulkExecutionUnit>();
                        return new BulkDurableWorkerComposition.UnitCallbacks(unit -> {
                            assertThat(admitted.compareAndSet(null, unit)).isTrue();
                            control.set(unit.control());
                            return BulkUnitAdmission.admit();
                        }, unit -> {
                            assertThat(admitted.get()).isSameAs(unit);
                            f.writeDomain(unit);
                            return BulkUnitMutationResult.confirmed();
                        }, () -> {
                            assertThat(admitted.getAndSet(null)).isNotNull();
                            cleanups.incrementAndGet();
                            throw new IllegalStateException("private cleanup failure must not escape");
                        });
                    });
            var b = f.expected;
            var lifecycle = BulkWorkerExternalConsumer.compose(f.runtimeSource, f.context.namespaceId(), b.deploymentId(),
                    BulkPostgresTestSupport.testRoleConfiguration(), b.tenantId(), b.environment(), b.bindingId(),
                    b.generation(), b.databaseId(), b.attestationId(), b.authorityId(), b.authorityEpoch(), operation);
            try {
                lifecycle.start();
                BulkDurableWorkerPostgresTest.await(() -> BulkDurableWorkerPostgresTest.completed(f, queued.executionId()));
            } finally { lifecycle.stop(); assertThat(lifecycle.isRunning()).isFalse(); }
            assertThat(factories).hasValue(2); assertThat(cleanups).hasValue(2);
            assertThat(f.count("item_receipt")).isEqualTo(2);
            assertThat(f.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isEqualTo(2);
            var replay = BulkDurableWorker.executeOne(f.kernel, control.get(), 0,
                    new BulkDurableWorker.Handler(f.context.resourceKey(), f.context.operationRef(), () -> {
                        throw new AssertionError("Replay must not allocate callbacks");
                    }));
            assertThat(replay.replayed()).isTrue();
            assertThat(factories).hasValue(2); assertThat(cleanups).hasValue(2);
            assertThat(f.count("item_receipt")).isEqualTo(2);
            f.assertionsComplete();
        }
    }
    @Test void deniedAdmissionDiscardsPairOnceWithoutMutationAndReplayAllocatesNothing() throws Exception {
        try (var f = new BulkCapacityOccupancyPostgresFixture("worker-pair-denied")) {
            f.activate();
            var queue = f.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var active = f.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = f.enqueue(f.persist(), "worker-pair-denied", queue);
            var claimed = f.kernel.claim(f.context, queued.executionId(), "worker-pair-test", active).orElseThrow();
            var factories = new AtomicInteger(); var cleanups = new AtomicInteger();
            var handler = new BulkDurableWorker.Handler(f.context.resourceKey(), f.context.operationRef(), () -> {
                factories.incrementAndGet();
                return new BulkDurableWorkerComposition.UnitCallbacks(
                        unit -> BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED),
                        unit -> { throw new AssertionError("Denied unit must not mutate"); }, cleanups::incrementAndGet);
            });
            var result = BulkDurableWorker.executeOne(f.kernel, claimed.control(), 0, handler);
            assertThat(result.durableResultPresent()).isTrue();
            assertThat(BulkDurableWorker.executeOne(f.kernel, claimed.control(), 0, handler).replayed()).isTrue();
            assertThat(factories).hasValue(1); assertThat(cleanups).hasValue(1);
            assertThat(f.count("item_receipt")).isZero();
            assertThat(f.count("admission")).isEqualTo(1);
            assertThat(f.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isZero();
            f.kernel.recover(f.context, claimed.executionId(), "trusted-recovery");
            f.assertionsComplete();
        }
    }
    @Test void mutationFailureRollsBackDomainAndReceiptAndCleanupDoesNotMaskFailure() throws Exception {
        try (var f = new BulkCapacityOccupancyPostgresFixture("worker-pair-failure")) {
            f.activate();
            var queue = f.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var active = f.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = f.enqueue(f.persist(), "worker-pair-failure", queue);
            var claimed = f.kernel.claim(f.context, queued.executionId(), "worker-pair-test", active).orElseThrow();
            var cleanups = new AtomicInteger();
            var handler = new BulkDurableWorker.Handler(f.context.resourceKey(), f.context.operationRef(), () ->
                    new BulkDurableWorkerComposition.UnitCallbacks(unit -> BulkUnitAdmission.admit(), unit -> {
                        f.writeDomain(unit);
                        throw new IllegalStateException("private domain failure");
                    }, () -> { cleanups.incrementAndGet(); throw new IllegalArgumentException("private cleanup failure"); }));
            var result = BulkDurableWorker.executeOne(f.kernel, claimed.control(), 0, handler);
            assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(result.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
            assertThat(cleanups).hasValue(1);
            assertThat(f.count("item_receipt")).isZero();
            assertThat(f.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isZero();
            f.kernel.recover(f.context, claimed.executionId(), "trusted-recovery");
            f.assertionsComplete();
        }
    }


    @Test void twoWorkersSharingOperationKeepPreparedPairsIndependentAcrossConcurrentUnits() throws Exception {
        try (var shared = new BulkCapacityOccupancyPostgresFixture.SharedScope()) {
            var a = shared.local("worker-pair-a", 1);
            var b = shared.local("worker-pair-b", 2);
            a.activate(); b.activate();
            var qa = a.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var qb = b.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            a.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            b.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var first = a.enqueue(a.persist(), "shared-first", qa);
            var second = b.enqueue(b.persist(), "shared-second", qb);
            assertThat(a.context.resourceKey()).isEqualTo(b.context.resourceKey());
            assertThat(a.context.operationRef()).isEqualTo(b.context.operationRef());
            var bothEntered = new java.util.concurrent.CountDownLatch(2);
            var allocations = new AtomicInteger(); var cleanups = new AtomicInteger();
            var failure = new AtomicReference<Throwable>();
            var physicalA = new java.util.concurrent.CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var physicalB = new java.util.concurrent.CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var operation = new BulkDurableWorkerComposition.Operation(a.context.resourceKey(), a.context.operationRef(), () -> {
                allocations.incrementAndGet();
                var prepared = new AtomicReference<BulkExecutionUnit>();
                return new BulkDurableWorkerComposition.UnitCallbacks(unit -> {
                    prepared.set(unit);
                    return BulkUnitAdmission.admit();
                }, unit -> {
                    try {
                        if (unit.ordinal() == 0) {
                            bothEntered.countDown();
                            if (!bothEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("Workers did not overlap inside real units");
                        }
                        assertThat(prepared.get()).isSameAs(unit);
                        if (unit.executionId().equals(first.executionId())) physicalA.add(a.writeDomain(unit));
                        else { assertThat(unit.executionId()).isEqualTo(second.executionId()); physicalB.add(b.writeDomain(unit)); }
                        return BulkUnitMutationResult.confirmed();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt(); failure.set(error); throw new IllegalStateException(error);
                    } catch (RuntimeException | Error error) { failure.set(error); throw error; }
                }, () -> { prepared.set(null); cleanups.incrementAndGet(); });
            });
            var one = BulkDurableWorkerComposition.compose(java.util.List.of(
                    new BulkDurableWorkerComposition.Binding(a.runtime, a.expected, java.util.List.of(operation))));
            var two = BulkDurableWorkerComposition.compose(java.util.List.of(
                    new BulkDurableWorkerComposition.Binding(b.runtime, b.expected, java.util.List.of(operation))));
            try {
                one.start(); two.start();
                BulkDurableWorkerPostgresTest.await(() -> failure.get() != null
                        || BulkDurableWorkerPostgresTest.completed(a, first.executionId())
                        && BulkDurableWorkerPostgresTest.completed(b, second.executionId()));
            } finally { one.stop(); two.stop(); }
            assertThat(failure.get()).isNull();
            assertThat(one.isRunning()).isFalse(); assertThat(two.isRunning()).isFalse();
            assertThat(allocations).hasValue(4); assertThat(cleanups).hasValue(4);
            assertThat(a.count("item_receipt")).isEqualTo(2); assertThat(b.count("item_receipt")).isEqualTo(2);
            assertThat(physicalA).hasSize(2); assertThat(physicalB).hasSize(2);
            physicalA.forEach(value -> a.assertPhysicalCommit(first.executionId(), value));
            physicalB.forEach(value -> b.assertPhysicalCommit(second.executionId(), value));
            a.assertionsComplete(); b.assertionsComplete();
        }
    }
}
