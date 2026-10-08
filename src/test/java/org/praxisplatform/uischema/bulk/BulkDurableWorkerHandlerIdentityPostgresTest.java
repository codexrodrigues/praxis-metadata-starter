package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.await;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.binding;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.completed;

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
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Real-loop roster identity; not cross-namespace stored-row isolation or host authorization. */
class BulkDurableWorkerHandlerIdentityPostgresTest {
    private record WrongHandler(String dimension, String resource, CanonicalOperationRef operation) { }

    @Test
    void everyHandlerIdentityDimensionMustMatchBeforeClaimAndCorrectHandlerStillExecutes() throws Exception {
        try (var shared = new BulkCapacityOccupancyPostgresFixture.SharedScope()) {
            var fixture = shared.local("worker-handler-identity", 1);
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = fixture.enqueue(fixture.persist(), "worker-identity", queue);
            var ref = fixture.context.operationRef();
            var resource = fixture.context.resourceKey();
            var mismatches = List.of(
                    new WrongHandler("resource", "different-resource", ref),
                    new WrongHandler("group", resource, new CanonicalOperationRef("different-group", ref.operationId(), ref.path(), ref.method())),
                    new WrongHandler("operationId", resource, new CanonicalOperationRef(ref.group(), "different-operation", ref.path(), ref.method())),
                    new WrongHandler("path", resource, new CanonicalOperationRef(ref.group(), ref.operationId(), "/different-path", ref.method())),
                    new WrongHandler("method", resource, new CanonicalOperationRef(ref.group(), ref.operationId(), ref.path(), "POST")));
            var originalQueue = fixture.slot(queue);
            var originalActive = fixture.slot(active);
            var originalAllocation = fixture.allocation(queued.executionId());
            var originalControl = fixture.controlRow();
            var originalPhoto = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1");
            var originalDomain = fixture.observer.queryForList("select * from occupancy_domain_witness order by id");
            var originalAuthority = authoritySnapshot(shared);
            var originalInstallations = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id");
            var wrongCallbacks = new AtomicInteger();
            var phases = new java.util.LinkedHashMap<String, java.util.Map<String, Object>>();
            for (var mismatch : mismatches) {
                assertThat(fixture.observer.queryForObject("select clock_timestamp()", java.time.OffsetDateTime.class)
                        .toInstant()).isBefore(queued.execution().deadlineAt());
                assertThat(fixture.controlRow()).isEqualTo(originalControl).containsEntry("state", "READY");
                assertThat(fixture.slot(active)).isEqualTo(originalActive).containsEntry("current_execution_id", null);
                var source = new DecisionProbeSource(fixture.runtimeSource, queued.executionId(), fixture.context.namespaceId());
                var runtime = new BulkExecutionInfrastructure(source, new DataSourceTransactionManager(source),
                        fixture.context.namespaceId(), BulkCapacityOccupancyPostgresFixture.DEPLOYMENT,
                        BulkPostgresTestSupport.testRoleConfiguration());
                var kernel = new JdbcBulkDurableExecution(runtime, null, fixture.expected);
                var wrong = new BulkDurableWorker.Handler(mismatch.resource(), mismatch.operation(),
                        unit -> { wrongCallbacks.incrementAndGet(); return BulkUnitAdmission.admit(); },
                        unit -> { wrongCallbacks.incrementAndGet(); return BulkUnitMutationResult.confirmed(); });
                var worker = new BulkDurableWorker(List.of(new BulkDurableWorker.Binding(kernel, List.of(wrong))));
                try {
                    worker.start();
                    // Positioned exhaustion follows the first candidate's handler decision.
                    // Stopping after only the initial SELECT would mask a wrong claim.
                    assertThat(source.afterDecision.await(5, TimeUnit.SECONDS)).as(mismatch.dimension()).isTrue();
                } finally { worker.stop(); assertThat(worker.isRunning()).isFalse(); }
                phases.put(mismatch.dimension(), java.util.Map.of("trace", List.copyOf(source.trace),
                        "claimEntries", source.claimEntries.get(), "callbacks", wrongCallbacks.get(),
                        "namespaceAndPreviousJobTupleVerified", true));
                assertThat(source.trace).contains("initial-candidate", "positioned-exhaustion-after-candidate");
                assertThat(source.claimEntries).hasValue(0);
                assertThat(wrongCallbacks).hasValue(0);
                var unchanged = fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow();
                assertThat(unchanged.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
                assertThat(unchanged.control().epoch()).isEqualTo(1);
                assertThat(unchanged.nextOrdinal()).isZero();
                assertThat(fixture.count("item_receipt")).isZero();
                assertThat(fixture.slot(queue)).isEqualTo(originalQueue);
                assertThat(fixture.slot(active)).isEqualTo(originalActive);
                assertThat(fixture.allocation(queued.executionId())).isEqualTo(originalAllocation);
                assertThat(fixture.controlRow()).isEqualTo(originalControl);
                assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1"))
                        .isEqualTo(originalPhoto);
                assertThat(fixture.observer.queryForList("select * from occupancy_domain_witness order by id"))
                        .isEqualTo(originalDomain);
            }
            assertThat(authoritySnapshot(shared)).isEqualTo(originalAuthority);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id"))
                    .isEqualTo(originalInstallations);
            var physical = new CopyOnWriteArrayList<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var correctCallbacks = new AtomicInteger();
            var proper = new BulkDurableWorker(List.of(binding(fixture, unit -> {
                correctCallbacks.incrementAndGet();
                physical.add(fixture.writeDomain(unit));
                return BulkUnitMutationResult.confirmed();
            })));
            try { proper.start(); await(() -> completed(fixture, queued.executionId())); }
            finally { proper.stop(); assertThat(proper.isRunning()).isFalse(); }
            assertThat(correctCallbacks).hasValue(2);
            physical.forEach(value -> fixture.assertPhysicalCommit(queued.executionId(), value));
            assertThat(fixture.count("item_receipt")).isEqualTo(2);
            assertThat(fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow().control().epoch()).isEqualTo(2);
            assertThat(fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow().nextOrdinal()).isEqualTo(2);
            assertThat(authoritySnapshot(shared)).isEqualTo(originalAuthority);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id"))
                    .isEqualTo(originalInstallations);
            assertThat(fixture.slot(active)).containsEntry("current_execution_id", null)
                    .containsEntry("occupancy_sequence", 1L);
            assertThat(fixture.allocation(queued.executionId())).containsEntry("state", "RELEASED");
            String directory = System.getProperty("praxis.bulk.proof.directory");
            if (directory != null)
                new com.fasterxml.jackson.databind.ObjectMapper().writeValue(java.nio.file.Path.of(directory,
                        "worker-handler-identity-decisions.json").toFile(), java.util.Map.of(
                        "wrongScenarioCount", 5, "phases", phases, "wrongCallbacks", wrongCallbacks.get(), "correctCallbacks", correctCallbacks.get(),
                        "scope", "private-handler-identity-not-namespace-isolation"));
            fixture.observe("five-wrong-rosters-preserve-queue-before-correct-handler-completes", queued.executionId());
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

    /** Observes exact selector results after delegation, with no pause or changed JDBC behavior. */
    private static final class DecisionProbeSource extends AbstractDataSource {
        private static final String CLAIM = "select praxis_bulk.claim_capacity_execution(?,?,?,?,?)";
        private static final String SELECT = """
                select execution_id,created_at from praxis_bulk.praxis_bulk_execution
                where namespace_id=? and execution_mode='ASYNC' and status='QUEUED'
                """;
        private static final String ORDER = " order by created_at,execution_id limit 1";
        private static final String POSITION = " and (created_at,execution_id) > (?,?)";
        private final DataSource delegate;
        private final UUID expected;
        private final String namespace;
        private volatile java.time.OffsetDateTime initialCreatedAt;
        final AtomicInteger claimEntries = new AtomicInteger();
        private volatile boolean initialObserved;
        final CountDownLatch afterDecision = new CountDownLatch(1);
        final List<String> trace = new CopyOnWriteArrayList<>();
        DecisionProbeSource(DataSource delegate, UUID expected, String namespace) {
            this.delegate = delegate; this.expected = expected; this.namespace = namespace;
        }
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
                                    && (CLAIM.equals(sql) || (SELECT + ORDER).equals(sql) || (SELECT + POSITION + ORDER).equals(sql))) {
                                Object[] bound = new Object[3];
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (prepared, operation, parameters) -> {
                                            try {
                                                if (CLAIM.equals(sql) && operation.getName().equals("executeQuery")) claimEntries.incrementAndGet();
                                                Object rows = operation.invoke(statement, parameters);
                                                if ((operation.getName().equals("setString") || operation.getName().equals("setObject"))
                                                        && parameters != null && parameters[0] instanceof Integer index && index >= 1 && index <= 3)
                                                    bound[index - 1] = parameters[1];
                                                if (CLAIM.equals(sql)) return rows;
                                                if (!operation.getName().equals("executeQuery") || !(rows instanceof ResultSet observed)) return rows;
                                                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                                        new Class<?>[]{ResultSet.class}, (cursor, read, values) -> {
                                                            try {
                                                                Object value = read.invoke(observed, values);
                                                                if (read.getName().equals("next")) {
                                                                    if ((SELECT + ORDER).equals(sql) && Boolean.TRUE.equals(value)
                                                                            && namespace.equals(bound[0]) && expected.equals(observed.getObject(1, UUID.class))) {
                                                                        initialCreatedAt = observed.getObject(2, java.time.OffsetDateTime.class);
                                                                        initialObserved = true; trace.add("initial-candidate");
                                                                    } else if ((SELECT + POSITION + ORDER).equals(sql)
                                                                            && Boolean.FALSE.equals(value) && initialObserved && namespace.equals(bound[0])
                                                                            && initialCreatedAt.equals(bound[1]) && expected.equals(bound[2])) {
                                                                        trace.add("positioned-exhaustion-after-candidate"); afterDecision.countDown();
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
    }
}
