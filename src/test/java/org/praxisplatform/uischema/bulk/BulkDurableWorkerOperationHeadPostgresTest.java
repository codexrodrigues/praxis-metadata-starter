package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.await;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Two trusted storage controls on one unchanged photo; not public READY/SpringDoc composition. */
class BulkDurableWorkerOperationHeadPostgresTest {
    @Test
    void suspendedMatchingHeadFailsRealClaimAndHealthyDistinctOperationStillCompletes() throws Exception {
        try (var shared = new BulkCapacityOccupancyPostgresFixture.SharedScope()) {
            var fixture = shared.local("worker-two-operation-head", 1);
            fixture.activate();
            var photoBefore = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1");
            var healthyControl = fixture.controlRow();
            var headContext = new BulkFingerprintContext(fixture.context.namespaceId(), "head-subject", "head-resource",
                    new CanonicalOperationRef("conformance", "occupancy-head-operation", "/protected/occupancy-head", "PATCH"),
                    "occupancy-head-r1", ActionCollectionAtomicity.PER_ITEM);
            var headControl = fixture.composeAdditionalStorageControl(headContext);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1"))
                    .isEqualTo(photoBefore);
            assertThat(fixture.controlRow()).isEqualTo(healthyControl);
            var headQueue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var healthyQueue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var headProposal = fixture.persistFor(headContext, headControl, 2);
            var head = fixture.kernel.enqueue(headContext, headProposal.proposal().id(), "worker-head",
                    "fixture-supervisor", headContext.schemaRevision(), Instant.now().plusSeconds(300), headQueue);
            var healthy = fixture.enqueue(fixture.persist(), "worker-healthy", healthyQueue);
            assertThat(fixture.observer.queryForList("""
                    select execution_id from praxis_bulk.praxis_bulk_execution
                    where status='QUEUED' order by created_at,execution_id
                    """, UUID.class)).containsExactly(head.executionId(), healthy.executionId());
            try (var connection = fixture.ownerSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    JdbcBulkCapacityOccupancy.lockMarker(connection);
                    JdbcBulkOpenApiPublication.lockForRead(connection, headContext.namespaceId(),
                            BulkCapacityOccupancyPostgresFixture.DEPLOYMENT);
                    var current = JdbcBulkOperationControl.lockForAdmission(connection, headContext.namespaceId(),
                            headContext.operationRef().operationId());
                    assertThat(JdbcBulkOperationControl.transition(connection, headContext.namespaceId(),
                            headContext.operationRef().operationId(), current.generation(),
                            JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null).applied()).isTrue();
                    connection.commit();
                } catch (Exception | Error failure) { connection.rollback(); throw failure; }
            }
            var suspendedBefore = fixture.observer.queryForMap("""
                    select * from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?
                    """, headContext.namespaceId(), headContext.operationRef().operationId());
            assertThat(suspendedBefore).containsEntry("state", "SUSPENDED");
            var headSlotBefore = fixture.slot(headQueue);
            var headAllocationBefore = fixture.allocation(head.executionId());
            var authorityBefore = authoritySnapshot(shared);
            var installationsBefore = fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id");
            var source = new HeadProbeSource(fixture.runtimeSource);
            var runtime = new BulkExecutionInfrastructure(source, new DataSourceTransactionManager(source),
                    fixture.context.namespaceId(), BulkCapacityOccupancyPostgresFixture.DEPLOYMENT,
                    BulkPostgresTestSupport.testRoleConfiguration());
            var kernel = new JdbcBulkDurableExecution(runtime, null, fixture.expected);
            var headCalls = new AtomicInteger();
            var healthyCalls = new AtomicInteger();
            var physical = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var headHandler = new BulkDurableWorker.Handler(headContext.resourceKey(), headContext.operationRef(),
                    unit -> { headCalls.incrementAndGet(); return BulkUnitAdmission.admit(); },
                    unit -> { headCalls.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
            var healthyHandler = new BulkDurableWorker.Handler(fixture.context.resourceKey(), fixture.context.operationRef(),
                    unit -> BulkUnitAdmission.admit(), unit -> {
                        healthyCalls.incrementAndGet();
                        assertThat(unit.executionId()).isEqualTo(healthy.executionId());
                        physical.add(runtime.withConnection(connection -> {
                            try (var statement = connection.prepareStatement("""
                                    update occupancy_domain_witness set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current()
                                     where id=? returning last_pid,last_xid
                                    """)) {
                                statement.setInt(1, unit.ordinal() + 1);
                                try (var rows = statement.executeQuery()) {
                                    assertThat(rows.next()).isTrue();
                                    var result = new BulkCapacityOccupancyPostgresFixture.PhysicalUnit(unit.ordinal(),
                                            rows.getInt(1), rows.getLong(2));
                                    assertThat(rows.next()).isFalse();
                                    return result;
                                }
                            }
                        }));
                        return BulkUnitMutationResult.confirmed();
                    });
            var worker = new BulkDurableWorker(List.of(new BulkDurableWorker.Binding(kernel,
                    List.of(headHandler, healthyHandler))));
            try {
                worker.start();
                await(() -> kernel.find(fixture.context, healthy.executionId()).orElseThrow().status()
                        == BulkDurableExecutionStatus.COMPLETED);
            } finally { worker.stop(); assertThat(worker.isRunning()).isFalse(); }
            assertThat(source.trace).contains("selected:" + head.executionId(), "rejected:" + head.executionId() + ":55000",
                    "selected:" + healthy.executionId());
            assertThat(source.trace.indexOf("selected:" + head.executionId()))
                    .isLessThan(source.trace.indexOf("rejected:" + head.executionId() + ":55000"));
            assertThat(source.trace.indexOf("rejected:" + head.executionId() + ":55000"))
                    .isLessThan(source.trace.indexOf("selected:" + healthy.executionId()));
            assertThat(headCalls).hasValue(0);
            assertThat(healthyCalls).hasValue(2);
            physical.forEach(value -> fixture.assertPhysicalCommit(healthy.executionId(), value));
            assertThat(fixture.count("item_receipt")).isEqualTo(2);
            var unchanged = kernel.find(headContext, head.executionId()).orElseThrow();
            assertThat(unchanged.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
            assertThat(unchanged.control().epoch()).isEqualTo(1);
            assertThat(unchanged.nextOrdinal()).isZero();
            assertThat(unchanged.receiptCount()).isZero();
            assertThat(fixture.slot(headQueue)).isEqualTo(headSlotBefore);
            assertThat(fixture.allocation(head.executionId())).isEqualTo(headAllocationBefore);
            assertThat(fixture.controlRow()).isEqualTo(healthyControl);
            assertThat(fixture.observer.queryForMap("""
                    select * from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?
                    """, headContext.namespaceId(), headContext.operationRef().operationId())).isEqualTo(suspendedBefore);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1"))
                    .isEqualTo(photoBefore);
            assertThat(authoritySnapshot(shared)).isEqualTo(authorityBefore);
            assertThat(fixture.observer.queryForList(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id")).isEqualTo(installationsBefore);
            String proofDirectory = System.getProperty("praxis.bulk.proof.directory");
            if (proofDirectory != null) {
                var proof = java.util.Map.of("headExecution", head.executionId(), "healthyExecution", healthy.executionId(),
                        "trace", source.trace, "scope", "private-storage-control-routing-only",
                        "headSqlState", "55000", "headCallbackCount", headCalls.get(), "healthyCallbackCount", healthyCalls.get());
                new com.fasterxml.jackson.databind.ObjectMapper().writeValue(java.nio.file.Path.of(proofDirectory,
                        "worker-two-operation-head-sql-trace.json").toFile(), proof);
            }
            fixture.observe("suspended-matching-head-rejected-before-healthy-two-unit-completion", head.executionId());
            fixture.kernel.requestCancel(headContext, head.executionId());
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

    /** Records original SQLSTATE and selection after delegation; never rewrites a driver result/error. */
    private static final class HeadProbeSource extends AbstractDataSource {
        private static final String CLAIM = "select praxis_bulk.claim_capacity_execution(?,?,?,?,?)";
        private static final String SELECT = """
                select execution_id,created_at from praxis_bulk.praxis_bulk_execution
                where namespace_id=? and execution_mode='ASYNC' and status='QUEUED'
                """;
        private static final String ORDER = " order by created_at,execution_id limit 1";
        private static final String POSITION = " and (created_at,execution_id) > (?,?)";
        private final DataSource delegate;
        final List<String> trace = new CopyOnWriteArrayList<>();
        HeadProbeSource(DataSource delegate) { this.delegate = delegate; }
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
                                    && args[0] instanceof String sql && result instanceof PreparedStatement statement
                                    && (CLAIM.equals(sql) || (SELECT + ORDER).equals(sql)
                                            || (SELECT + POSITION + ORDER).equals(sql))) {
                                UUID[] execution = new UUID[1];
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (prepared, operation, parameters) -> {
                                            try {
                                                Object rows = operation.invoke(statement, parameters);
                                                if (CLAIM.equals(sql) && operation.getName().equals("setObject")
                                                        && parameters != null && Integer.valueOf(1).equals(parameters[0]))
                                                    execution[0] = (UUID) parameters[1];
                                                if (!CLAIM.equals(sql) && operation.getName().equals("executeQuery")
                                                        && rows instanceof ResultSet observed)
                                                    return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                                            new Class<?>[]{ResultSet.class}, (cursor, read, values) -> {
                                                                try {
                                                                    Object value = read.invoke(observed, values);
                                                                    if (read.getName().equals("next") && Boolean.TRUE.equals(value))
                                                                        trace.add("selected:" + observed.getObject(1, UUID.class));
                                                                    return value;
                                                                } catch (InvocationTargetException error) { throw error.getCause(); }
                                                            });
                                                return rows;
                                            } catch (InvocationTargetException error) {
                                                if (CLAIM.equals(sql) && operation.getName().equals("executeQuery")
                                                        && error.getCause() instanceof SQLException failure)
                                                    trace.add("rejected:" + execution[0] + ":" + failure.getSQLState());
                                                throw error.getCause();
                                            }
                                        });
                            }
                            return result;
                        } catch (InvocationTargetException error) { throw error.getCause(); }
                    });
        }
    }
}
