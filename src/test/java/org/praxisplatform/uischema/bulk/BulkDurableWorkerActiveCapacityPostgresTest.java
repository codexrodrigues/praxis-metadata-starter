package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.await;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual-loop capacity and wrap proof. Instrumentation never grants ownership. */
class BulkDurableWorkerActiveCapacityPostgresTest {
    @Test
    void occupiedActiveSlotBecomesAvailableAndWorkerWrapsToPreviouslyObservedQueuedJob() throws Exception {
        try (var shared = new BulkCapacityOccupancyPostgresFixture.SharedScope()) {
            var fixture = shared.local("worker-active-full-wrap", 1);
            fixture.activate();
            var holderQueue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var queuedToken = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var holderQueued = fixture.enqueue(fixture.persist(), "worker-slot-holder", holderQueue);
            var holder = fixture.kernel.claim(fixture.context, holderQueued.executionId(), "trusted-holder", active)
                    .orElseThrow();
            var queued = fixture.enqueue(fixture.persist(), "worker-slot-waiting", queuedToken);
            var authorityBefore = authoritySnapshot(shared);
            var installationsBefore = fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id");
            var probe = new CapacityProbeSource(fixture.runtimeSource);
            var runtime = new BulkExecutionInfrastructure(probe, new DataSourceTransactionManager(probe),
                    fixture.context.namespaceId(), BulkCapacityOccupancyPostgresFixture.DEPLOYMENT,
                    BulkPostgresTestSupport.testRoleConfiguration());
            var kernel = new JdbcBulkDurableExecution(runtime, null, fixture.expected);
            var calls = new AtomicInteger();
            var physical = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var handler = new BulkDurableWorker.Handler(fixture.context.resourceKey(), fixture.context.operationRef(), () -> new BulkDurableWorkerComposition.UnitCallbacks(
                    unit -> BulkUnitAdmission.admit(), unit -> {
                        calls.incrementAndGet();
                        assertThat(unit.executionId()).isEqualTo(queued.executionId());
                        physical.add(runtime.withConnection(connection -> {
                            try (var statement = connection.prepareStatement("""
                                    update occupancy_domain_witness
                                       set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current()
                                     where id=? returning last_pid,last_xid
                                    """)) {
                                statement.setInt(1, unit.ordinal() + 1);
                                try (var rows = statement.executeQuery()) {
                                    assertThat(rows.next()).isTrue();
                                    var certificate = new BulkCapacityOccupancyPostgresFixture.PhysicalUnit(
                                            unit.ordinal(), rows.getInt(1), rows.getLong(2));
                                    assertThat(rows.next()).isFalse();
                                    assertThat(fixture.observer.queryForObject(
                                            "select writes from occupancy_domain_witness where id=?", Integer.class,
                                            unit.ordinal() + 1)).isZero();
                                    return certificate;
                                }
                            }
                        }));
                        return BulkUnitMutationResult.confirmed();
                    }, () -> { }));
            var worker = new BulkDurableWorker(List.of(new BulkDurableWorker.Binding(kernel, List.of(handler))));
            try {
                worker.start();
                assertThat(probe.emptyObserved.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(probe.barrierExpired.get()).isFalse();
                var occupied = fixture.observer.queryForMap("""
                        select h.status as holder_status,h.owner_id,h.owner_epoch,q.status as queued_status,q.owner_epoch as queued_epoch,
                               s.current_execution_id,s.current_owner_epoch,s.occupancy_sequence,
                               (select count(*) from praxis_bulk.praxis_bulk_item_receipt) as receipts,
                               (select sum(writes) from occupancy_domain_witness) as writes,
                               pg_backend_pid() as observer_pid,clock_timestamp() as observed_at
                          from praxis_bulk.praxis_bulk_execution h
                          cross join praxis_bulk.praxis_bulk_execution q
                          cross join praxis_bulk.praxis_bulk_capacity_slot s
                         where h.execution_id=? and q.execution_id=? and s.token_id=?
                        """, holder.executionId(), queued.executionId(), active);
                assertThat(occupied).containsEntry("holder_status", "RUNNING").containsEntry("owner_id", "trusted-holder").containsEntry("owner_epoch", 2L)
                        .containsEntry("queued_status", "QUEUED").containsEntry("queued_epoch", 1L)
                        .containsEntry("current_execution_id", holder.executionId())
                        .containsEntry("current_owner_epoch", 2L).containsEntry("occupancy_sequence", 1L)
                        .containsEntry("receipts", 0L).containsEntry("writes", 0L);
                assertThat(calls).hasValue(0);
                // Hint queries read marker/binding/slots without row locks; table AccessShare locks
                // are compatible with official recovery. Release before every stop/close.
                fixture.kernel.recover(fixture.context, holder.executionId(), "trusted-holder-recovery");
                assertThat(probe.barrierExpired.get()).isFalse();
                probe.release.countDown();
                assertThat(fixture.kernel.find(fixture.context, holder.executionId()).orElseThrow().status())
                        .isEqualTo(BulkDurableExecutionStatus.STOPPED);
                await(() -> kernel.find(fixture.context, queued.executionId()).orElseThrow().status()
                        == BulkDurableExecutionStatus.COMPLETED);
            } finally {
                probe.release.countDown();
                worker.stop();
                assertThat(worker.isRunning()).isFalse();
            }
            assertThat(probe.barrierExpired.get()).isFalse();
            assertThat(calls).hasValue(2);
            assertThat(probe.observedWrap(queued.executionId())).isTrue();
            physical.forEach(value -> fixture.assertPhysicalCommit(queued.executionId(), value));
            assertThat(kernel.find(fixture.context, queued.executionId()).orElseThrow().nextOrdinal()).isEqualTo(2);
            assertThat(fixture.count("item_receipt")).isEqualTo(2);
            assertThat(fixture.observer.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Long.class,
                    holder.executionId())).isZero();
            assertThat(fixture.slot(active)).containsEntry("current_execution_id", null)
                    .containsEntry("occupancy_sequence", 2L);
            assertThat(fixture.allocation(holder.executionId())).containsEntry("state", "RELEASED");
            assertThat(fixture.allocation(queued.executionId())).containsEntry("state", "RELEASED");
            assertThat(authoritySnapshot(shared)).isEqualTo(authorityBefore);
            assertThat(fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id"))
                    .isEqualTo(installationsBefore);
            fixture.observe("actual-worker-wrapped-after-official-holder-recovery", queued.executionId());
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

    /** Exact JDBC passthrough; no SQL/parameters/results/transaction settings are changed. */
    private static final class CapacityProbeSource extends AbstractDataSource {
        private static final String ACTIVE = """
                select s.token_id from praxis_bulk.praxis_bulk_capacity_slot s
                join praxis_bulk.praxis_bulk_capacity_installation i on i.token_id=s.token_id
                where s.capacity_class='ACTIVE' and s.current_execution_id is null
                order by i.token_ordinal,s.token_id limit 1
                """;
        private static final String SELECT = """
                select execution_id,created_at from praxis_bulk.praxis_bulk_execution
                where namespace_id=? and execution_mode='ASYNC' and status='QUEUED'
                """;
        private static final String POSITION = " and (created_at,execution_id) > (?,?)";
        private static final String ORDER = " order by created_at,execution_id limit 1";
        private record Selection(boolean positioned, UUID execution) { }
        private final DataSource delegate;
        final CountDownLatch emptyObserved = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean barrierExpired = new AtomicBoolean();
        private final AtomicBoolean paused = new AtomicBoolean();
        private final List<Selection> selections = new CopyOnWriteArrayList<>();
        CapacityProbeSource(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return wrap(delegate.getConnection(user, password));
        }
        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(connection, args);
                            if (method.getName().equals("prepareStatement") && args != null
                                    && result instanceof PreparedStatement statement && args[0] instanceof String sql
                                    && (ACTIVE.equals(sql) || (SELECT + ORDER).equals(sql)
                                            || (SELECT + POSITION + ORDER).equals(sql))) {
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (prepared, operation, parameters) -> {
                                            try {
                                                Object rows = operation.invoke(statement, parameters);
                                                if (!operation.getName().equals("executeQuery") || !(rows instanceof ResultSet observed))
                                                    return rows;
                                                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                                        new Class<?>[]{ResultSet.class}, (cursor, read, values) -> {
                                                            try {
                                                                Object value = read.invoke(observed, values);
                                                                if (read.getName().equals("next")) {
                                                                    boolean present = (Boolean) value;
                                                                    if (!ACTIVE.equals(sql))
                                                                        selections.add(new Selection(sql.equals(SELECT + POSITION + ORDER),
                                                                                present ? observed.getObject(1, UUID.class) : null));
                                                                    else if (!present && paused.compareAndSet(false, true)) {
                                                                        emptyObserved.countDown();
                                                                        if (!release.await(700, TimeUnit.MILLISECONDS)) barrierExpired.set(true);
                                                                    }
                                                                }
                                                                return value;
                                                            } catch (InvocationTargetException error) { throw error.getCause(); }
                                                        });
                                            } catch (InvocationTargetException error) { throw error.getCause(); }
                                        });
                            }
                            return result;
                        } catch (InvocationTargetException error) { throw error.getCause(); }
                    });
        }
        boolean observedWrap(UUID expected) {
            boolean exhausted = false;
            for (Selection selection : selections) {
                if (selection.positioned() && selection.execution() == null) exhausted = true;
                if (exhausted && !selection.positioned() && expected.equals(selection.execution())) return true;
            }
            return false;
        }
    }
}
