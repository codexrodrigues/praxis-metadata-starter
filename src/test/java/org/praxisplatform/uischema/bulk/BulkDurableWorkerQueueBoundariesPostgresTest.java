package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.await;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.binding;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.completed;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Additional private worker boundaries; no host authorization or public ASYNC acceptance. */
class BulkDurableWorkerQueueBoundariesPostgresTest {
    @Test
    void fencedBindingStillExpiresCertifiedQueuedJobWithoutActiveOrDomainDispatch() throws Exception {
        try (var shared = new BulkCapacityOccupancyPostgresFixture.SharedScope()) {
            var fixture = shared.local("worker-expiry-fenced", 1);
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var queued = fixture.enqueue(fixture.persist(), "worker-fenced-expiry", queue,
                    Instant.now().plusMillis(700));
            var controlBefore = fixture.controlRow();
            var domainBefore = fixture.observer.queryForList("select * from occupancy_domain_witness order by id");
            var authorityBefore = authoritySnapshot(shared);
            var installationsBefore = fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id");
            fixture.installation.fence();
            var markerAfterFence = fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_marker order by 1");
            var callbacks = new AtomicInteger();
            var worker = new BulkDurableWorker(List.of(binding(fixture, unit -> {
                callbacks.incrementAndGet();
                return BulkUnitMutationResult.confirmed();
            })));
            try {
                worker.start();
                await(() -> fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow().status()
                        == BulkDurableExecutionStatus.STOPPED);
            } finally {
                worker.stop();
                assertThat(worker.isRunning()).isFalse();
            }
            var terminal = fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow();
            assertThat(terminal.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.DEADLINE_EXCEEDED);
            assertThat(terminal.control().epoch()).isEqualTo(1);
            assertThat(terminal.nextOrdinal()).isZero();
            assertThat(callbacks).hasValue(0);
            assertThat(fixture.count("item_receipt")).isZero();
            assertThat(fixture.observer.queryForList("select * from occupancy_domain_witness order by id"))
                    .isEqualTo(domainBefore);
            assertThat(authoritySnapshot(shared)).isEqualTo(authorityBefore);
            assertThat(fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id"))
                    .isEqualTo(installationsBefore);
            assertThat(fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_marker order by 1"))
                    .isEqualTo(markerAfterFence);
            assertThat(fixture.slot(queue)).containsEntry("current_execution_id", null)
                    .containsEntry("occupancy_sequence", 1L);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(1);
            assertThat(fixture.allocation(queued.executionId())).containsEntry("state", "RELEASED");
            assertThat(fixture.controlRow()).isEqualTo(controlBefore);
            fixture.observe("fenced-queued-expiry-without-active", queued.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void maintenanceExpiresOtherQueuedJobBetweenOwnedUnitsInTheSameBinding() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("worker-expiry-owned-binding")) {
            fixture.activate();
            var firstQueue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var otherQueue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var first = fixture.enqueue(fixture.persist(), "worker-owned-first", firstQueue);
            var other = fixture.enqueue(fixture.persist(), "worker-owned-expiring", otherQueue,
                    Instant.now().plusMillis(1500));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var callbacks = new AtomicInteger();
            var physical = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var worker = new BulkDurableWorker(List.of(binding(fixture, unit -> {
                callbacks.incrementAndGet();
                if (unit.ordinal() == 0) {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Finite test barrier expired");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Test barrier interrupted", interrupted);
                    }
                } else {
                    assertThat(fixture.observer.queryForObject(
                            "select status from praxis_bulk.praxis_bulk_execution where execution_id=?",
                            String.class, other.executionId())).isEqualTo("STOPPED");
                }
                physical.add(fixture.writeDomain(unit));
                return BulkUnitMutationResult.confirmed();
            })));
            try {
                worker.start();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.observer.queryForObject(
                        "select status from praxis_bulk.praxis_bulk_execution where execution_id=?",
                        String.class, other.executionId())).isEqualTo("QUEUED");
                assertThat(fixture.observer.queryForObject("select clock_timestamp()", java.time.OffsetDateTime.class)
                        .toInstant()).isBefore(other.execution().deadlineAt());
                assertThat(fixture.kernel.find(fixture.context, first.executionId()).orElseThrow().status())
                        .isEqualTo(BulkDurableExecutionStatus.UNIT_IN_FLIGHT);
                assertThat(fixture.slot(active)).containsEntry("current_execution_id", first.executionId());
                fixture.waitUntilDeadline(other.execution().deadlineAt());
                // Single worker: cleanup is between units, never concurrent with the blocked callback.
                assertThat(fixture.kernel.find(fixture.context, other.executionId()).orElseThrow().status())
                        .isEqualTo(BulkDurableExecutionStatus.QUEUED);
                release.countDown();
                await(() -> completed(fixture, first.executionId()));
            } finally {
                release.countDown();
                worker.stop();
                assertThat(worker.isRunning()).isFalse();
            }
            assertThat(callbacks).hasValue(2);
            assertThat(fixture.kernel.find(fixture.context, first.executionId()).orElseThrow().nextOrdinal()).isEqualTo(2);
            physical.forEach(value -> fixture.assertPhysicalCommit(first.executionId(), value));
            var expired = fixture.kernel.find(fixture.context, other.executionId()).orElseThrow();
            assertThat(expired.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.DEADLINE_EXCEEDED);
            assertThat(expired.control().epoch()).isEqualTo(1);
            assertThat(expired.nextOrdinal()).isZero();
            assertThat(fixture.observer.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?",
                    Long.class, other.executionId())).isZero();
            assertThat(fixture.count("item_receipt")).isEqualTo(2);
            assertThat(fixture.slot(otherQueue)).containsEntry("current_execution_id", null)
                    .containsEntry("occupancy_sequence", 1L);
            assertThat(fixture.slot(active)).containsEntry("current_execution_id", null)
                    .containsEntry("occupancy_sequence", 1L);
            assertThat(fixture.allocation(other.executionId())).containsEntry("state", "RELEASED");
            assertThat(fixture.allocation(first.executionId())).containsEntry("state", "RELEASED");
            fixture.observe("other-queued-expired-between-owned-units", other.executionId());
            fixture.assertionsComplete();
        }
    }
    private static java.util.Map<String, java.util.List<java.util.Map<String, Object>>> authoritySnapshot(
            BulkCapacityOccupancyPostgresFixture.SharedScope shared) {
        var snapshot = new java.util.LinkedHashMap<String, java.util.List<java.util.Map<String, Object>>>();
        for (String table : List.of("capacity_token", "capacity_binding", "binding_attestation", "capacity_request",
                "deployment_capacity", "tenant_capacity", "fairness_cursor")) {
            snapshot.put(table, shared.authorityObserver.queryForList(
                    "select * from praxis_bulk_capacity." + table + " order by 1"));
        }
        return snapshot;
    }
}
