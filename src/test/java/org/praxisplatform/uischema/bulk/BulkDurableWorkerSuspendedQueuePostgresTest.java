package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.await;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.binding;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Certified expiration is maintenance, not reauthorization of a suspended operation. */
class BulkDurableWorkerSuspendedQueuePostgresTest {
    @Test
    void suspendedOperationExpiresQueuedExecutionWithoutReopeningControlOrCallingDomain() throws Exception {
        try (var shared = new BulkCapacityOccupancyPostgresFixture.SharedScope()) {
            var fixture = shared.local("worker-suspended-expiry", 1);
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var queued = fixture.enqueue(fixture.persist(), "worker-suspended-expiry", queue,
                    Instant.now().plusMillis(700));
            try (var connection = fixture.ownerSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    JdbcBulkCapacityOccupancy.lockMarker(connection);
                    JdbcBulkOpenApiPublication.lockForRead(connection, fixture.context.namespaceId(),
                            BulkCapacityOccupancyPostgresFixture.DEPLOYMENT);
                    var current = JdbcBulkOperationControl.lockForAdmission(connection, fixture.context.namespaceId(),
                            fixture.context.operationRef().operationId());
                    assertThat(current.ready()).isTrue();
                    var transition = JdbcBulkOperationControl.transition(connection, fixture.context.namespaceId(),
                            fixture.context.operationRef().operationId(), current.generation(),
                            JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null);
                    assertThat(transition.applied()).isTrue();
                    assertThat(transition.generation()).isEqualTo(current.generation() + 1);
                    connection.commit();
                } catch (Exception | Error failure) {
                    connection.rollback();
                    throw failure;
                }
            }
            var suspended = fixture.controlRow();
            assertThat(suspended).containsEntry("state", "SUSPENDED");
            var domainBefore = fixture.observer.queryForList("select * from occupancy_domain_witness order by id");
            var installationsBefore = fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id");
            var authorityBefore = authoritySnapshot(shared);
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
            assertThat(fixture.count("capacity_occupation")).isEqualTo(1);
            assertThat(fixture.slot(queue)).containsEntry("current_execution_id", null)
                    .containsEntry("occupancy_sequence", 1L);
            assertThat(fixture.allocation(queued.executionId())).containsEntry("state", "RELEASED");
            assertThat(fixture.controlRow()).isEqualTo(suspended);
            assertThat(authoritySnapshot(shared)).isEqualTo(authorityBefore);
            assertThat(fixture.observer.queryForList("select * from occupancy_domain_witness order by id"))
                    .isEqualTo(domainBefore);
            assertThat(fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id"))
                    .isEqualTo(installationsBefore);
            fixture.observe("suspended-operation-queued-expiry-without-reauthorization", queued.executionId());
            fixture.assertionsComplete();
        }
    }

    private static java.util.Map<String, java.util.List<java.util.Map<String, Object>>> authoritySnapshot(
            BulkCapacityOccupancyPostgresFixture.SharedScope shared) {
        var result = new java.util.LinkedHashMap<String, java.util.List<java.util.Map<String, Object>>>();
        for (String table : List.of("capacity_token", "capacity_binding", "binding_attestation", "capacity_request",
                "deployment_capacity", "tenant_capacity", "fairness_cursor"))
            result.put(table, shared.authorityObserver.queryForList("select * from praxis_bulk_capacity." + table + " order by 1"));
        return result;
    }
}
