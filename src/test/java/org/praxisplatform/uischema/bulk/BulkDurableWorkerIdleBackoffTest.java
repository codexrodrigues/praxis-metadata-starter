package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Targeted unit tests for idle backoff, enqueue wake-up, and deferred capacity-binding checks. */
class BulkDurableWorkerIdleBackoffTest {

    private static final String NAMESPACE = "tenant:test:backoff";
    private static final String DEPLOYMENT = "deployment-test";

    private static BulkCapacityBinding sampleBinding() {
        return sampleBinding(DEPLOYMENT);
    }

    private static BulkCapacityBinding sampleBinding(String deploymentId) {
        return new BulkCapacityBinding(
                deploymentId,
                "tenant-a",
                "test",
                "binding-a",
                1L,
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                UUID.fromString("00000000-0000-0000-0000-000000000022"),
                UUID.fromString("00000000-0000-0000-0000-000000000033"),
                1L);
    }

    private static BulkDurableWorker.Handler sampleHandler() {
        return new BulkDurableWorker.Handler(
                "items",
                new CanonicalOperationRef("group", "approve", "/items/bulk", "POST"),
                () -> new BulkDurableWorkerComposition.UnitCallbacks(
                        unit -> BulkUnitAdmission.admit(),
                        unit -> BulkUnitMutationResult.confirmed(),
                        () -> { }));
    }

    private static PlatformTransactionManager noopTransactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
    }

    @SuppressWarnings("unchecked")
    private static BulkExecutionInfrastructure mockedInfrastructure(Connection connection) {
        BulkExecutionInfrastructure infrastructure = mock(
                BulkExecutionInfrastructure.class,
                withSettings().mockMaker(MockMakers.INLINE));
        when(infrastructure.namespace()).thenReturn(NAMESPACE);
        when(infrastructure.deploymentId()).thenReturn(DEPLOYMENT);
        when(infrastructure.transactionManager()).thenReturn(noopTransactionManager());
        when(infrastructure.withConnection(any())).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });
        return infrastructure;
    }

    private static Connection recordingConnection(
            List<String> executedSql,
            UUID queuedExecutionId,
            OffsetDateTime queuedCreatedAt,
            BulkCapacityBinding markerBinding) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && args != null && args[0] instanceof String sql) {
                        executedSql.add(sql);
                        return recordingStatement(sql, queuedExecutionId, queuedCreatedAt, markerBinding);
                    }
                    if ("isClosed".equals(method.getName())) return false;
                    if ("getAutoCommit".equals(method.getName())) return false;
                    if ("isReadOnly".equals(method.getName())) return false;
                    return null;
                });
    }

    private static PreparedStatement recordingStatement(
            String sql,
            UUID queuedExecutionId,
            OffsetDateTime queuedCreatedAt,
            BulkCapacityBinding markerBinding) {
        boolean isQueueQuery = sql.contains("praxis_bulk.praxis_bulk_execution");
        boolean isBindingQuery = sql.contains("praxis_bulk.praxis_bulk_capacity_marker");
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "execute":
                            return true;
                        case "executeQuery":
                            return resultSet(isQueueQuery, isBindingQuery, queuedExecutionId, queuedCreatedAt, markerBinding);
                        case "close":
                        case "setString":
                        case "setObject":
                        case "setLong":
                            return null;
                        default:
                            return null;
                    }
                });
    }

    private static ResultSet resultSet(
            boolean isQueueQuery,
            boolean isBindingQuery,
            UUID queuedExecutionId,
            OffsetDateTime queuedCreatedAt,
            BulkCapacityBinding markerBinding) {
        AtomicInteger rowIndex = new AtomicInteger(-1);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        int idx = rowIndex.incrementAndGet();
                        if (isQueueQuery) return idx == 0 && queuedExecutionId != null;
                        if (isBindingQuery) return idx == 0 && markerBinding != null;
                        return false;
                    }
                    if ("close".equals(name)) return null;
                    if (isQueueQuery && "getObject".equals(name) && args != null && args.length == 2) {
                        int col = (Integer) args[0];
                        return col == 1 ? queuedExecutionId : queuedCreatedAt;
                    }
                    if (isBindingQuery && markerBinding != null) {
                        int col = (Integer) args[0];
                        if ("getObject".equals(name)) {
                            return switch (col) {
                                case 1 -> markerBinding.databaseId();
                                case 7 -> markerBinding.attestationId();
                                case 8 -> markerBinding.authorityId();
                                default -> null;
                            };
                        }
                        if ("getString".equals(name)) {
                            return switch (col) {
                                case 2 -> markerBinding.deploymentId();
                                case 3 -> markerBinding.tenantId();
                                case 4 -> markerBinding.environment();
                                case 5 -> markerBinding.bindingId();
                                case 10 -> "ACTIVE";
                                default -> null;
                            };
                        }
                        if ("getLong".equals(name)) {
                            return switch (col) {
                                case 6 -> markerBinding.generation();
                                case 9 -> markerBinding.authorityEpoch();
                                default -> 0L;
                            };
                        }
                    }
                    return null;
                });
    }

    @Test
    void idleDelayMillisFollowsExponentialProgressionCappedAtThirtySeconds() {
        assertThat(BulkDurableWorker.idleDelayMillis(0)).isEqualTo(250L);
        assertThat(BulkDurableWorker.idleDelayMillis(1)).isEqualTo(250L);
        assertThat(BulkDurableWorker.idleDelayMillis(2)).isEqualTo(500L);
        assertThat(BulkDurableWorker.idleDelayMillis(3)).isEqualTo(1_000L);
        assertThat(BulkDurableWorker.idleDelayMillis(4)).isEqualTo(2_000L);
        assertThat(BulkDurableWorker.idleDelayMillis(5)).isEqualTo(4_000L);
        assertThat(BulkDurableWorker.idleDelayMillis(6)).isEqualTo(8_000L);
        assertThat(BulkDurableWorker.idleDelayMillis(7)).isEqualTo(16_000L);
        assertThat(BulkDurableWorker.idleDelayMillis(8)).isEqualTo(30_000L);
        assertThat(BulkDurableWorker.idleDelayMillis(9)).isEqualTo(30_000L);
        assertThat(BulkDurableWorker.idleDelayMillis(32)).isEqualTo(30_000L);
    }

    @Test
    void workerNextQueuedFailsFastOnDeploymentMismatchAndSkipsRequireBindingOnlyWhenQueueIsEmpty() {
        BulkCapacityBinding binding = sampleBinding();

        BulkExecutionInfrastructure guardInfrastructure = mockedInfrastructure(
                recordingConnection(new CopyOnWriteArrayList<>(), null, null, binding));
        JdbcBulkDurableExecution mismatchDeploymentKernel = new JdbcBulkDurableExecution(
                guardInfrastructure, null, sampleBinding("other-deployment"));
        assertThatThrownBy(() -> mismatchDeploymentKernel.workerNextQueued(null))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
        verify(guardInfrastructure, never()).withConnection(any());

        List<String> emptyQueueSql = new CopyOnWriteArrayList<>();
        Connection emptyConnection = recordingConnection(emptyQueueSql, null, null, binding);
        JdbcBulkDurableExecution emptyKernel = new JdbcBulkDurableExecution(
                mockedInfrastructure(emptyConnection), null, binding);

        assertThat(emptyKernel.workerNextQueued(null)).isEmpty();
        assertThat(emptyQueueSql).anyMatch(sql -> sql.contains("praxis_bulk.praxis_bulk_execution"));
        assertThat(emptyQueueSql).noneMatch(sql ->
                sql.contains("praxis_bulk_capacity_marker") || sql.contains("praxis_bulk_namespace_binding"));

        UUID queuedId = UUID.randomUUID();
        OffsetDateTime queuedAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<String> populatedSql = new CopyOnWriteArrayList<>();
        Connection populatedConnection = recordingConnection(populatedSql, queuedId, queuedAt, binding);
        JdbcBulkDurableExecution populatedKernel = new JdbcBulkDurableExecution(
                mockedInfrastructure(populatedConnection), null, binding);

        var hint = populatedKernel.workerNextQueued(null);
        assertThat(hint).isPresent();
        assertThat(hint.orElseThrow().executionId()).isEqualTo(queuedId);
        assertThat(populatedSql).anyMatch(sql -> sql.contains("praxis_bulk.praxis_bulk_execution"));
        assertThat(populatedSql).anyMatch(sql ->
                sql.contains("praxis_bulk_capacity_marker") && sql.contains("praxis_bulk_namespace_binding"));

        Connection corruptConnection = recordingConnection(
                new CopyOnWriteArrayList<>(), queuedId, queuedAt, sampleBinding("other-deployment"));
        JdbcBulkDurableExecution corruptKernel = new JdbcBulkDurableExecution(
                mockedInfrastructure(corruptConnection), null, binding);

        assertThatThrownBy(() -> corruptKernel.workerNextQueued(null))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
    }

    @Test
    void notifyQueuedWakesSleepingWorkerAndPreventsSleepWhenTriggeredDuringInFlightScan() throws Exception {
        BulkCapacityBinding binding = sampleBinding();
        AtomicInteger polls = new AtomicInteger();
        CountDownLatch enteredBackoffWait = new CountDownLatch(1);
        AtomicReference<CountDownLatch> nextPollLatch = new AtomicReference<>();
        AtomicBoolean notifyDuringScan = new AtomicBoolean();
        AtomicLong inFlightNotifyAtNanos = new AtomicLong();
        AtomicLong immediateRescanAtNanos = new AtomicLong();
        CountDownLatch immediateRescanLatch = new CountDownLatch(1);

        Connection emptyConnection = recordingConnection(new CopyOnWriteArrayList<>(), null, null, binding);
        BulkExecutionInfrastructure infrastructure = mock(
                BulkExecutionInfrastructure.class,
                withSettings().mockMaker(MockMakers.INLINE));
        when(infrastructure.namespace()).thenReturn(NAMESPACE);
        when(infrastructure.deploymentId()).thenReturn(DEPLOYMENT);
        when(infrastructure.transactionManager()).thenReturn(noopTransactionManager());
        when(infrastructure.withConnection(any())).thenAnswer(invocation -> {
            int pollNumber = polls.incrementAndGet();
            if (pollNumber >= 3) {
                enteredBackoffWait.countDown();
            }
            if (notifyDuringScan.compareAndSet(true, false)) {
                inFlightNotifyAtNanos.set(System.nanoTime());
                BulkDurableWorker.notifyQueued(NAMESPACE, DEPLOYMENT);
            } else if (inFlightNotifyAtNanos.get() != 0L && immediateRescanLatch.getCount() > 0) {
                immediateRescanAtNanos.set(System.nanoTime());
                immediateRescanLatch.countDown();
            }
            CountDownLatch watcher = nextPollLatch.get();
            if (watcher != null) {
                watcher.countDown();
            }
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(emptyConnection);
        });

        JdbcBulkDurableExecution kernel = new JdbcBulkDurableExecution(infrastructure, null, binding);
        BulkDurableWorker worker = new BulkDurableWorker(List.of(new BulkDurableWorker.Binding(kernel, List.of(sampleHandler()))));

        try {
            worker.start();
            // Poll 1 (0ms) + 250ms + Poll 2 + 500ms + Poll 3 -> enters 1000ms wait.
            assertThat(enteredBackoffWait.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(60);

            CountDownLatch mismatchedLatch = new CountDownLatch(1);
            nextPollLatch.set(mismatchedLatch);
            BulkDurableWorker.notifyQueued("other-namespace", DEPLOYMENT);
            BulkDurableWorker.notifyQueued(NAMESPACE, "other-deployment");
            assertThat(mismatchedLatch.await(150, TimeUnit.MILLISECONDS)).isFalse();

            CountDownLatch wakeLatch = new CountDownLatch(1);
            nextPollLatch.set(wakeLatch);
            notifyDuringScan.set(true);
            long startNanos = System.nanoTime();
            BulkDurableWorker.notifyQueued(NAMESPACE, DEPLOYMENT);
            assertThat(wakeLatch.await(300, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)).isLessThan(300L);

            // Because notifyQueued ran during the woken in-flight empty scan, the next scan
            // must proceed immediately without sleeping for MIN_IDLE (250ms).
            assertThat(immediateRescanLatch.await(300, TimeUnit.MILLISECONDS)).isTrue();
            long rescanDelayMs = TimeUnit.NANOSECONDS.toMillis(immediateRescanAtNanos.get() - inFlightNotifyAtNanos.get());
            assertThat(rescanDelayMs).isLessThan(120L);
        } finally {
            worker.stop();
            assertThat(worker.isRunning()).isFalse();
        }
    }

    @Test
    void observedQueuedItemKeepsPollingAtMinIdleWithoutEscalatingBackoff() throws Exception {
        BulkCapacityBinding binding = sampleBinding();
        UUID queuedId = UUID.randomUUID();
        var hint = new JdbcBulkDurableExecution.WorkerQueueHint(queuedId, Instant.now());
        var otherScope = new BulkFingerprintContext(
                NAMESPACE,
                "subject",
                "other-resource",
                new CanonicalOperationRef("group", "other-op", "/other", "POST"),
                "r1",
                ActionCollectionAtomicity.PER_ITEM);

        JdbcBulkDurableExecution kernel = mock(
                JdbcBulkDurableExecution.class,
                withSettings().mockMaker(MockMakers.INLINE));
        when(kernel.workerBindingIdentity()).thenReturn(binding);
        when(kernel.workerNamespaceId()).thenReturn(NAMESPACE);
        when(kernel.workerDeploymentId()).thenReturn(DEPLOYMENT);
        when(kernel.workerContext(queuedId)).thenReturn(otherScope);
        when(kernel.workerExpireQueued(otherScope, queuedId)).thenReturn(false);

        AtomicInteger rounds = new AtomicInteger();
        CountDownLatch fiveRoundsCompleted = new CountDownLatch(1);
        when(kernel.workerNextQueued(null)).thenAnswer(invocation -> {
            if (rounds.incrementAndGet() >= 5) {
                fiveRoundsCompleted.countDown();
            }
            return Optional.of(hint);
        });
        when(kernel.workerNextQueued(hint)).thenReturn(Optional.empty());

        BulkDurableWorker worker = new BulkDurableWorker(List.of(new BulkDurableWorker.Binding(kernel, List.of(sampleHandler()))));

        try {
            long startNanos = System.nanoTime();
            worker.start();
            // 5 rounds at MIN_IDLE = 4 * 250ms (~1000ms); escalating backoff would take 3750ms.
            assertThat(fiveRoundsCompleted.await(1_600, TimeUnit.MILLISECONDS)).isTrue();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertThat(elapsedMs).isLessThan(1_600L);
        } finally {
            worker.stop();
            assertThat(worker.isRunning()).isFalse();
        }
    }

    @Test
    void transientScanErrorRespectsErrorBackoffAndResetsIdleStreak() throws Exception {
        BulkCapacityBinding binding = sampleBinding();
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong failedAtNanos = new AtomicLong();
        AtomicLong recoveredAtNanos = new AtomicLong();
        AtomicLong pollAfterRecoveryAtNanos = new AtomicLong();
        CountDownLatch fourthPollCompleted = new CountDownLatch(1);
        Connection emptyConnection = recordingConnection(new CopyOnWriteArrayList<>(), null, null, binding);

        BulkExecutionInfrastructure infrastructure = mock(
                BulkExecutionInfrastructure.class,
                withSettings().mockMaker(MockMakers.INLINE));
        when(infrastructure.namespace()).thenReturn(NAMESPACE);
        when(infrastructure.deploymentId()).thenReturn(DEPLOYMENT);
        when(infrastructure.transactionManager()).thenReturn(noopTransactionManager());
        when(infrastructure.withConnection(any())).thenAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) {
                failedAtNanos.set(System.nanoTime());
                throw new IllegalStateException("Simulated transient connection failure");
            }
            if (attempt == 3) {
                recoveredAtNanos.set(System.nanoTime());
            } else if (attempt == 4) {
                pollAfterRecoveryAtNanos.set(System.nanoTime());
                fourthPollCompleted.countDown();
            }
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(emptyConnection);
        });

        JdbcBulkDurableExecution kernel = new JdbcBulkDurableExecution(infrastructure, null, binding);
        BulkDurableWorker worker = new BulkDurableWorker(List.of(new BulkDurableWorker.Binding(kernel, List.of(sampleHandler()))));

        try {
            worker.start();
            // Poll 1 (empty -> 250ms) -> Poll 2 (throws -> 1s ERROR_BACKOFF) -> Poll 3 (recovers, resets idleStreak) -> Poll 4 (250ms later).
            assertThat(fourthPollCompleted.await(3, TimeUnit.SECONDS)).isTrue();
            long recoveryDelayMs = TimeUnit.NANOSECONDS.toMillis(recoveredAtNanos.get() - failedAtNanos.get());
            long nextPollDelayMs = TimeUnit.NANOSECONDS.toMillis(pollAfterRecoveryAtNanos.get() - recoveredAtNanos.get());
            assertThat(recoveryDelayMs).isBetween(900L, 1_500L);
            assertThat(nextPollDelayMs).isLessThan(450L);
        } finally {
            worker.stop();
            assertThat(worker.isRunning()).isFalse();
        }
    }
}
