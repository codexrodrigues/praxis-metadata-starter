package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.binding;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.await;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;

class BulkDurableWorkerLifecyclePostgresTest {
    @Test
    void stoppingCannotDispatchSuffixOrStartReplacementAndCallbacksWaitForActualExit() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("worker-stop-in-flight")) {
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = fixture.enqueue(fixture.persist(), "worker-stop", queue);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var callbacks = new AtomicInteger();
            var stopCallbacks = new AtomicInteger();
            var physical = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var worker = new BulkDurableWorker(List.of(binding(fixture, unit -> {
                callbacks.incrementAndGet(); entered.countDown();
                barrier(release);
                physical.add(fixture.writeDomain(unit));
                return BulkUnitMutationResult.confirmed();
            })));
            Runnable stopped = () -> { assertThat(worker.isRunning()).isFalse(); stopCallbacks.incrementAndGet(); };
            try {
                worker.start();
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
                worker.stop(stopped); worker.stop(stopped); worker.start();
                assertThat(worker.isRunning()).isTrue();
                assertThat(stopCallbacks).hasValue(0);
                release.countDown();
                await(() -> stopCallbacks.get() == 1);
                assertThat(worker.isRunning()).isFalse();
                var terminal = fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow();
                assertThat(terminal.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
                assertThat(terminal.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
                assertThat(terminal.nextOrdinal()).isEqualTo(1);
                assertThat(terminal.receiptCount()).isEqualTo(1);
                assertThat(terminal.control().epoch()).isEqualTo(3);
                assertThat(callbacks).hasValue(1);
                physical.forEach(value -> fixture.assertPhysicalCommit(queued.executionId(), value));
                assertThat(fixture.allocation(queued.executionId())).containsEntry("state", "RELEASED");
                fixture.assertionsComplete();
            } finally { release.countDown(); worker.stop(); assertThat(worker.isRunning()).isFalse(); }
        }
    }

    @Test
    void springContextStopsWorkerBeforeClosingItsRealOperationalPool() throws Exception {
        springShutdown(false);
    }

    @Test
    void springPhaseTimeoutCanClosePoolWhileWorkerTruthfullyRemainsStopping() throws Exception {
        springShutdown(true);
    }

    private void springShutdown(boolean timeout) throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture(
                timeout ? "worker-spring-timeout" : "worker-spring-close-order")) {
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = fixture.enqueue(fixture.persist(), "worker-spring", queue);
            var source = (org.springframework.jdbc.datasource.DriverManagerDataSource) fixture.runtimeSource;
            var config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(source.getUrl()); config.setUsername(source.getUsername());
            config.setPassword(source.getPassword()); config.setMinimumIdle(0); config.setMaximumPoolSize(2);
            config.setPoolName("worker-shutdown-proof");
            var pool = new com.zaxxer.hikari.HikariDataSource(config);
            var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool);
            var runtime = new BulkExecutionInfrastructure(pool, manager, fixture.context.namespaceId(),
                    BulkCapacityOccupancyPostgresFixture.DEPLOYMENT, BulkPostgresTestSupport.testRoleConfiguration());
            var kernel = new JdbcBulkDurableExecution(runtime, null, fixture.expected);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var writes = new AtomicInteger();
            var domainBefore = fixture.observer.queryForList("select * from occupancy_domain_witness order by id");
            var physical = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var worker = new BulkDurableWorker(List.of(new BulkDurableWorker.Binding(kernel, List.of(
                    new BulkDurableWorker.Handler(fixture.context.resourceKey(), fixture.context.operationRef(),
                            unit -> BulkUnitAdmission.admit(), unit -> {
                        assertThat(pool.isClosed()).isFalse();
                        entered.countDown(); barrier(release);
                        runtime.withConnection(connection -> {
                            try (var statement = connection.createStatement();
                                    var rows = statement.executeQuery("select pg_backend_pid(),txid_current()")) {
                                if (!rows.next()) throw new IllegalStateException("No physical witness");
                                physical.add(new BulkCapacityOccupancyPostgresFixture.PhysicalUnit(unit.ordinal(), rows.getInt(1), rows.getLong(2)));
                            }
                            try (var statement = connection.prepareStatement(
                                    "update occupancy_domain_witness set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current() where id=?")) {
                                statement.setInt(1, unit.ordinal() + 1); statement.executeUpdate();
                            }
                            return null;
                        });
                        writes.incrementAndGet(); return BulkUnitMutationResult.confirmed();
                    })))));
            var context = new GenericApplicationContext();
            context.registerBean("operationalDatasource", com.zaxxer.hikari.HikariDataSource.class, () -> pool,
                    definition -> definition.setDestroyMethodName("close"));
            context.registerBean("operationalTransactionManager", org.springframework.jdbc.datasource.DataSourceTransactionManager.class,
                    () -> manager, definition -> definition.setDependsOn("operationalDatasource"));
            context.registerBean("worker", BulkDurableWorker.class, () -> worker,
                    definition -> definition.setDependsOn("operationalDatasource", "operationalTransactionManager"));
            context.registerBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
                var processor = new DefaultLifecycleProcessor();
                processor.setTimeoutForShutdownPhase(BulkDurableWorker.PHASE, timeout ? 100 : 30_000);
                return processor;
            });
            Thread closer = null;
            try {
                context.refresh(); assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
                closer = new Thread(context::close, "worker-proof-context-close"); closer.start();
                // Observe the actual lifecycle processor, not a manual stop that bypasses it.
                await(worker::isStopping);
                if (timeout) {
                    closer.join(5_000); assertThat(closer.isAlive()).isFalse();
                    assertThat(pool.isClosed()).isTrue();
                    assertThat(worker.isRunning()).isTrue(); assertThat(worker.isStopping()).isTrue();
                } else assertThat(pool.isClosed()).isFalse();
                release.countDown(); closer.join(30_000);
                assertThat(closer.isAlive()).isFalse();
                await(() -> !worker.isRunning());
                assertThat(pool.isClosed()).isTrue();
                if (timeout) {
                    assertThat(writes).hasValue(0);
                    assertThat(fixture.count("item_receipt")).isZero();
                    assertThat(fixture.observer.queryForList("select * from occupancy_domain_witness order by id")).isEqualTo(domainBefore);
                    // Explicit trusted reconciliation on a live independent runtime; no resumed mutation.
                    fixture.kernel.recover(fixture.context, queued.executionId(), "post-timeout-proof-reconciler");
                } else {
                    assertThat(writes).hasValue(1);
                    assertThat(fixture.count("item_receipt")).isEqualTo(1);
                    assertThat(physical).hasSize(1);
                    physical.forEach(value -> fixture.assertPhysicalCommit(queued.executionId(), value));
                    assertThat(fixture.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isEqualTo(1);
                }
                var terminal = fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow();
                assertThat(terminal.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
                assertThat(terminal.nextOrdinal()).isEqualTo(timeout ? 0 : 1);
                assertThat(terminal.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
                assertThat(terminal.control().epoch()).isEqualTo(3);
                assertThat(fixture.allocation(queued.executionId())).containsEntry("state", "RELEASED");
                assertThat(fixture.slot(queue)).containsEntry("current_execution_id", null);
                if (timeout) assertThat(fixture.observer.queryForList("select * from occupancy_domain_witness order by id")).isEqualTo(domainBefore);
                fixture.assertionsComplete();
            } finally {
                release.countDown(); worker.stop(); context.close(); pool.close();
                if (closer != null) { closer.join(30_000); assertThat(closer.isAlive()).isFalse(); }
            }
        }
    }

    @Test
    void emptyCompositionCreatesNoWorkerThread() {
        var worker = new BulkDurableWorker(List.of());
        assertThat(worker.isAutoStartup()).isFalse(); worker.start();
        assertThat(worker.isRunning()).isFalse();
        var callbacks = new AtomicInteger(); worker.stop(callbacks::incrementAndGet);
        assertThat(callbacks).hasValue(1);
    }

    static void barrier(CountDownLatch release) {
        try {
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Finite proof barrier expired");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Proof interrupted", error);
        }
    }

}
