package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.await;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Same physical database, marker and deployment photo; not cross-tenant/grant proof. */
class BulkDurableWorkerNamespacePostgresTest {
    @Test
    void samePhysicalNamespaceWorkerSkipsOlderForeignJobAndEachBoundRuntimeCommitsItsOwnDomain() throws Exception {
        String namespaceB = "tenant:prod:occupancy-conformance-b";
        try (var fixture = BulkCapacityOccupancyPostgresFixture.withAdditionalNamespace("worker-two-namespaces", namespaceB)) {
            fixture.activate();
            assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_marker", Long.class)).isEqualTo(1);
            var photo = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1");
            var controlA = fixture.controlRow();
            var ctx = fixture.context;
            var contextB = new BulkFingerprintContext(namespaceB, ctx.subjectId(), ctx.resourceKey(), ctx.operationRef(),
                    ctx.schemaRevision(), ctx.atomicity());
            var viewB = fixture.namespaceView(contextB);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1"))
                    .isEqualTo(photo).hasSize(1);
            assertThat(fixture.controlRow()).isEqualTo(controlA);
            assertThatThrownBy(() -> fixture.persistFor(contextB, viewB.control, 2))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(viewB.runtime.dataSource()).isSameAs(fixture.runtimeSource);
            assertThat(viewB.runtime.transactionManager()).isNotSameAs(fixture.runtime.transactionManager());
            assertThat(fixture.observer.queryForList("select namespace_id,deployment_id from praxis_bulk.praxis_bulk_namespace_binding order by namespace_id"))
                    .hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("deployment_id", BulkCapacityOccupancyPostgresFixture.DEPLOYMENT));
            fixture.observer.update("insert into occupancy_domain_witness(id,writes) values(3,0),(4,0)");
            var queueB = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var queueA = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var foreign = viewB.enqueue(viewB.persist(), "worker-namespace-b", queueB);
            var own = fixture.enqueue(fixture.persist(), "worker-namespace-a", queueA);
            assertThat(fixture.observer.queryForList("select execution_id from praxis_bulk.praxis_bulk_execution order by created_at,execution_id", UUID.class))
                    .containsExactly(foreign.executionId(), own.executionId());
            assertThat(fixture.kernel.workerNextQueued(null).orElseThrow().executionId()).isEqualTo(own.executionId());
            assertThat(viewB.kernel.workerNextQueued(null).orElseThrow().executionId()).isEqualTo(foreign.executionId());
            var foreignBefore = fixture.observer.queryForMap("select * from praxis_bulk.praxis_bulk_execution where execution_id=?", foreign.executionId());
            var foreignSlot = fixture.slot(queueB);
            var foreignAllocation = fixture.allocation(foreign.executionId());
            var controls = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_operation_control order by namespace_id,operation_id");
            var authority = fixture.authorityRows();
            var installs = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id");
            var aCalls = new AtomicInteger();
            var physicalA = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var workerA = worker(fixture.kernel, ctx, unit -> {
                assertThat(unit.executionId()).isEqualTo(own.executionId());
                aCalls.incrementAndGet();
                physicalA.add(fixture.writeDomain(unit));
                return BulkUnitMutationResult.confirmed();
            });
            try {
                workerA.start();
                await(() -> fixture.kernel.find(ctx, own.executionId()).orElseThrow().status() == BulkDurableExecutionStatus.COMPLETED);
            } finally { workerA.stop(); assertThat(workerA.isRunning()).isFalse(); }
            assertThat(aCalls).hasValue(2);
            physicalA.forEach(value -> fixture.assertPhysicalCommit(own.executionId(), value));
            assertThat(fixture.observer.queryForMap("select * from praxis_bulk.praxis_bulk_execution where execution_id=?", foreign.executionId()))
                    .isEqualTo(foreignBefore).containsEntry("status", "QUEUED").containsEntry("owner_epoch", 1L).containsEntry("next_ordinal", 0);
            assertThat(fixture.slot(queueB)).isEqualTo(foreignSlot);
            assertThat(fixture.allocation(foreign.executionId())).isEqualTo(foreignAllocation);
            assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Long.class, foreign.executionId()))
                    .isZero();
            assertThat(fixture.observer.queryForObject("select sum(writes) from occupancy_domain_witness where id in (3,4)", Long.class)).isZero();
            assertThat(fixture.authorityRows()).isEqualTo(authority);
            assertThat(fixture.slot(active)).containsEntry("occupancy_sequence", 1L).containsEntry("current_execution_id", null);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_operation_control order by namespace_id,operation_id"))
                    .isEqualTo(controls);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1"))
                    .isEqualTo(photo);
            // A has fully exited before B's worker starts; B uses its own bound runtime/TM.
            var bCalls = new AtomicInteger();
            var physicalB = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var workerB = worker(viewB.kernel, contextB, unit -> {
                assertThat(unit.executionId()).isEqualTo(foreign.executionId());
                bCalls.incrementAndGet();
                physicalB.add(writeDomainB(fixture, viewB.runtime, unit));
                return BulkUnitMutationResult.confirmed();
            });
            try {
                workerB.start();
                await(() -> viewB.kernel.find(contextB, foreign.executionId()).orElseThrow().status() == BulkDurableExecutionStatus.COMPLETED);
            } finally { workerB.stop(); assertThat(workerB.isRunning()).isFalse(); }
            var finishedB = viewB.kernel.find(contextB, foreign.executionId()).orElseThrow();
            assertThat(finishedB.control().epoch()).isEqualTo(2);
            assertThat(finishedB.nextOrdinal()).isEqualTo(2);
            assertThat(finishedB.receiptCount()).isEqualTo(2);
            assertThat(bCalls).hasValue(2);
            assertThat(physicalB).hasSize(2);
            for (var value : physicalB) {
                var row = fixture.observer.queryForMap("""
                        select pg_backend_pid() as observer_pid,d.writes,d.last_pid,d.last_xid,
                            d.xmin::text::bigint as write_xid,r.xmin::text::bigint as receipt_xid,r.owner_epoch,r.outcome
                        from occupancy_domain_witness d join praxis_bulk.praxis_bulk_item_receipt r
                        on r.execution_id=? and r.unit_ordinal=? where d.id=?
                        """, foreign.executionId(), value.ordinal(), value.ordinal() + 3);
                long xid = value.transactionId() & 0xffffffffL;
                assertThat(row).containsEntry("writes", 1).containsEntry("last_pid", value.backendPid())
                        .containsEntry("last_xid", value.transactionId()).containsEntry("write_xid", xid)
                        .containsEntry("receipt_xid", xid).containsEntry("owner_epoch", 2L).containsEntry("outcome", "CONFIRMED");
                assertThat(((Number) row.get("observer_pid")).intValue()).isNotEqualTo(value.backendPid());
            }
            assertThat(fixture.observer.queryForMap("select status,owner_epoch,next_ordinal from praxis_bulk.praxis_bulk_execution where execution_id=?", foreign.executionId()))
                    .containsEntry("status", "COMPLETED").containsEntry("owner_epoch", 2L).containsEntry("next_ordinal", 2);
            assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Long.class, foreign.executionId()))
                    .isEqualTo(2);
            assertThat(fixture.allocation(own.executionId())).containsEntry("state", "RELEASED");
            assertThat(fixture.allocation(foreign.executionId())).containsEntry("state", "RELEASED");
            assertThat(fixture.count("item_receipt")).isEqualTo(4);
            assertThat(fixture.slot(active)).containsEntry("occupancy_sequence", 2L).containsEntry("current_execution_id", null);
            assertThat(fixture.slot(queueA)).containsEntry("occupancy_sequence", 1L).containsEntry("current_execution_id", null);
            assertThat(fixture.slot(queueB)).containsEntry("occupancy_sequence", 1L).containsEntry("current_execution_id", null);
            assertThat(fixture.authorityRows()).isEqualTo(authority);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id"))
                    .isEqualTo(installs);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_operation_control order by namespace_id,operation_id"))
                    .isEqualTo(controls);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1"))
                    .isEqualTo(photo);
            String directory = System.getProperty("praxis.bulk.proof.directory");
            if (directory != null) new com.fasterxml.jackson.databind.ObjectMapper().writeValue(
                    java.nio.file.Path.of(directory, "worker-same-physical-namespace.json").toFile(),
                    Map.of("scope", "same-physical-database-marker-deployment-not-tenant-grant-proof",
                            "namespaceA", ctx.namespaceId(), "namespaceB", contextB.namespaceId(),
                            "executionA", own.executionId(), "executionB", foreign.executionId(),
                            "physicalA", physicalA, "physicalB", physicalB,
                            "publicationRows", 1, "activeSequence", 2));
            fixture.observe("A-only-B-intact-then-B-own-physical-runtime", foreign.executionId());
            fixture.assertionsComplete();
        }
    }

    private static BulkDurableWorker worker(JdbcBulkDurableExecution kernel, BulkFingerprintContext context,
            java.util.function.Function<BulkExecutionUnit, BulkUnitMutationResult> mutation) {
        return new BulkDurableWorker(List.of(new BulkDurableWorker.Binding(kernel, List.of(
                new BulkDurableWorker.Handler(context.resourceKey(), context.operationRef(),
                        unit -> BulkUnitAdmission.admit(), mutation::apply)))));
    }

    private static BulkCapacityOccupancyPostgresFixture.PhysicalUnit writeDomainB(
            BulkCapacityOccupancyPostgresFixture fixture, BulkExecutionInfrastructure runtime, BulkExecutionUnit unit) {
        return runtime.withConnection(connection -> {
            BulkCapacityOccupancyPostgresFixture.PhysicalUnit physical;
            try (var statement = connection.prepareStatement("""
                    update occupancy_domain_witness set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current()
                    where id=? returning last_pid,last_xid
                    """)) {
                statement.setInt(1, unit.ordinal() + 3);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    physical = new BulkCapacityOccupancyPostgresFixture.PhysicalUnit(unit.ordinal(), rows.getInt(1), rows.getLong(2));
                    assertThat(rows.next()).isFalse();
                }
            }
            var absent = fixture.observer.queryForMap("""
                    select pg_backend_pid() as observer_pid,writes,
                      (select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=? and unit_ordinal=?) as receipts
                    from occupancy_domain_witness where id=?
                    """, unit.executionId(), unit.ordinal(), unit.ordinal() + 3);
            assertThat(absent).containsEntry("writes", 0).containsEntry("receipts", 0L);
            assertThat(((Number) absent.get("observer_pid")).intValue()).isNotEqualTo(physical.backendPid());
            return physical;
        });
    }
}
