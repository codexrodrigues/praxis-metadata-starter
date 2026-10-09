package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local ADMIN operations only. No D0 continuity, boot, fleet exclusion or restore certificate. */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class BulkCapacityLocalAdministrationPostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void authenticatedOwnerInspectsFencesAndReplaysWithoutAnAccessibleAuthority() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-no-authority")) {
            var local = setup.local;
            local.installation.bootstrap();
            var administration = administration(local, local.expected, local.ownerSource);
            assertThat(administration.inspect()).isEqualTo("PROVISIONED");
            local.activate();
            local.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var before = rows(local.observer);
            var authorityBefore = authorityRows(setup.scope);
            var cluster = new JdbcTemplate(setup.scope.source("postgres", "postgres"));
            cluster.execute("alter database capacity_global allow_connections false");
            try {
                try (var connection = setup.scope.source("occupancy_reader", "capacity_global").getConnection()) {
                    throw new AssertionError("Authority remained accessible");
                } catch (SQLException denied) { assertThat(denied.getSQLState()).isEqualTo("55000"); }
                assertThat(administration.inspect()).isEqualTo("ACTIVE");
                assertThat(rows(local.observer)).isEqualTo(before);
                assertThat(administration.fence()).isTrue();
                assertThat(administration.inspect()).isEqualTo("FENCED");
                var after = rows(local.observer);
                assertThat(administration.fence()).isFalse();
                assertThat(rows(local.observer)).isEqualTo(after);
                assertThat(local.observer.queryForObject(
                        "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)).isEqualTo("FENCED");
            } finally { cluster.execute("alter database capacity_global allow_connections true"); }
            assertThat(authorityRows(setup.scope)).isEqualTo(authorityBefore);
            local.assertionsComplete();
        }
    }

    @Test
    void everyBindingComponentMismatchAndWrongAuthenticatedRoleLeaveTheMarkerUntouched() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-binding-negative")) {
            var local = setup.local;
            local.activate();
            var expected = local.expected;
            var before = rows(local.observer);
            for (int component = 0; component < 9; component++) {
                var wrong = mismatch(expected, component);
                var administration = administration(local, wrong, local.ownerSource);
                assertThatThrownBy(administration::fence).isExactlyInstanceOf(IllegalStateException.class)
                        .hasMessage("Capacity marker differs from trusted provisioning identity");
                assertThat(rows(local.observer)).isEqualTo(before);
            }
            var runtimeCredential = administration(local, expected, local.runtimeSource);
            assertThatThrownBy(runtimeCredential::fence).isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("Capacity owner credential changed");
            assertThat(rows(local.observer)).isEqualTo(before);
            local.assertionsComplete();
        }
    }

    @Test
    void canonicalOwnerScopeIgnoresUrlCurrentSchemaAndRejectsAclDriftWithoutHealing() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-catalog-scope")) {
            var local = setup.local;
            local.activate();
            local.observer.execute("create schema untrusted authorization postgres");
            local.observer.execute("create function untrusted.pg_get_constraintdef(oid) returns text"
                    + " language sql immutable as 'select ''UNTRUSTED_CATALOG_SPOOF''::text'");
            var source = new DriverManagerDataSource(urlOption(
                    setup.scope.postgres.getJdbcUrl("postgres", "capacity_local_1"),
                    "currentSchema=untrusted,pg_catalog"), "postgres", setup.scope.password("postgres"));
            var hostilePath = new JdbcTemplate(source);
            assertThat(hostilePath.queryForObject("select pg_get_constraintdef(oid) from pg_catalog.pg_constraint"
                    + " where conrelid='praxis_bulk.praxis_bulk_capacity_marker'::regclass limit 1", String.class))
                    .isEqualTo("UNTRUSTED_CATALOG_SPOOF");
            var administration = administration(local, local.expected, source);
            String pathBefore = hostilePath.queryForObject("show search_path", String.class);
            assertThat(pathBefore).contains("untrusted", "pg_catalog");
            assertThat(administration.inspect()).isEqualTo("ACTIVE");
            assertThat(hostilePath.queryForObject("show search_path", String.class)).isEqualTo(pathBefore);
            local.observer.execute("revoke select on praxis_bulk.praxis_bulk_capacity_installation from bulk_runtime_test");
            var before = rows(local.observer);
            try {
                assertThatThrownBy(administration::fence).isExactlyInstanceOf(IllegalStateException.class);
                assertThat(rows(local.observer)).isEqualTo(before);
                assertThat(local.observer.queryForObject("select has_table_privilege('bulk_runtime_test',"
                        + "'praxis_bulk.praxis_bulk_capacity_installation','SELECT')", Boolean.class)).isFalse();
            } finally {
                local.observer.execute("grant select on praxis_bulk.praxis_bulk_capacity_installation to bulk_runtime_test");
            }
            assertThat(administration.inspect()).isEqualTo("ACTIVE");
            local.assertionsComplete();
        }
    }

    @Test
    void outerTransactionAndAbsentMarkerAreRejectedWithoutInitialization() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-no-healing")) {
            var local = setup.local;
            var administration = administration(local, local.expected, local.ownerSource);
            var before = rows(local.observer);
            assertThatThrownBy(administration::inspect).isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("Capacity marker is absent");
            assertThatThrownBy(administration::fence).isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("Capacity marker is absent");
            var tx = new TransactionTemplate(new DataSourceTransactionManager(local.ownerSource));
            tx.executeWithoutResult(status -> {
                assertThatThrownBy(administration::fence).isExactlyInstanceOf(IllegalStateException.class)
                        .hasMessage("Capacity installation rejects an outer transaction");
                assertThatThrownBy(administration::inspect).isExactlyInstanceOf(IllegalStateException.class)
                        .hasMessage("Capacity installation rejects an outer transaction");
            });
            assertThat(rows(local.observer)).isEqualTo(before);
            local.assertionsComplete();
        }
    }

    @Test
    void fenceWaitsForTheActualUnitCommitThenFreshUnitIsDeniedAndReceiptReplays() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-unit-linearization")) {
            var local = setup.local;
            local.activate();
            UUID queue = local.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = local.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var reservation = local.enqueue(local.persist(), "local-admin-unit-key", queue);
            var claimed = local.kernel.claim(local.context, reservation.executionId(), "local-admin-worker", active).orElseThrow();
            var source = new DriverManagerDataSource(urlOption(setup.scope.postgres.getJdbcUrl("postgres", "capacity_local_1"),
                    "ApplicationName=local-admin-fence-unit"), "postgres", setup.scope.password("postgres"));
            var administration = administration(local, local.expected, source);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var physical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var callbacks = new AtomicInteger();
            var executor = Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<?> unit = null;
            java.util.concurrent.Future<Boolean> fence = null;
            try {
                unit = executor.submit(() -> local.kernel.executeUnit(claimed.control(), 0,
                        value -> BulkUnitAdmission.admit(), value -> {
                            callbacks.incrementAndGet();
                            physical.set(local.writeDomain(value));
                            entered.countDown();
                            try {
                                if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Unit barrier expired");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt(); throw new IllegalStateException("Unit barrier interrupted");
                            }
                            return BulkUnitMutationResult.confirmed();
                        }));
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                fence = executor.submit(() -> { return administration.fence(); });
                boolean edge = awaitMarkerEdge(local.observer, "local-admin-fence-unit", physical.get().backendPid());
                release.countDown();
                assertThat(edge).as("real marker SHARE holder blocks owner FENCE within 700ms").isTrue();
                unit.get(20, TimeUnit.SECONDS);
                assertThat(fence.get(20, TimeUnit.SECONDS)).isTrue();
                local.assertPhysicalCommit(claimed.executionId(), physical.get());
                assertThat(callbacks).hasValue(1);
                assertThatThrownBy(() -> local.kernel.executeUnit(claimed.control(), 1,
                        value -> BulkUnitAdmission.admit(), value -> {
                            callbacks.incrementAndGet(); local.writeDomain(value); return BulkUnitMutationResult.confirmed();
                        })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                                failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
                var replay = local.kernel.executeUnit(claimed.control(), 0, value -> BulkUnitAdmission.admit(), value -> {
                    callbacks.incrementAndGet(); local.writeDomain(value); return BulkUnitMutationResult.confirmed();
                });
                assertThat(replay.receiptPresent()).isTrue();
                assertThat(callbacks).hasValue(1);
                local.assertPhysicalCommit(claimed.executionId(), physical.get());
                local.assertionsComplete();
            } finally {
                release.countDown();
                if (unit != null) unit.cancel(true);
                if (fence != null) fence.cancel(true);
                executor.shutdownNow();
                assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void catalogRevocationCommittedDuringMarkerWaitIsRecheckedBeforeFenceMutation() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-wait-drift")) {
            var local = setup.local;
            local.activate();
            var source = new DriverManagerDataSource(urlOption(setup.scope.postgres.getJdbcUrl("postgres", "capacity_local_1"),
                    "ApplicationName=local-admin-fence-drift"), "postgres", setup.scope.password("postgres"));
            var administration = administration(local, local.expected, source);
            var executor = Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<Boolean> fence = null;
            try (Connection holder = local.ownerSource.getConnection()) {
                holder.setAutoCommit(false);
                try {
                    int pid;
                    try (var statement = holder.createStatement();
                            var row = statement.executeQuery("select pg_backend_pid() from praxis_bulk.praxis_bulk_capacity_marker for update")) {
                        assertThat(row.next()).isTrue(); pid = row.getInt(1); assertThat(row.next()).isFalse();
                    }
                    fence = executor.submit(() -> { return administration.fence(); });
                    assertThat(awaitMarkerEdge(local.observer, "local-admin-fence-drift", pid)).isTrue();
                    local.observer.execute("revoke select on praxis_bulk.praxis_bulk_capacity_installation from bulk_runtime_test");
                    holder.rollback();
                    var completed = fence;
                    assertThatThrownBy(() -> completed.get(20, TimeUnit.SECONDS))
                            .isInstanceOf(java.util.concurrent.ExecutionException.class)
                            .hasCauseInstanceOf(IllegalStateException.class);
                    assertThat(local.observer.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker",
                            String.class)).isEqualTo("ACTIVE");
                    assertThat(local.observer.queryForObject("select has_table_privilege('bulk_runtime_test',"
                            + "'praxis_bulk.praxis_bulk_capacity_installation','SELECT')", Boolean.class)).isFalse();
                } finally {
                    holder.rollback();
                    local.observer.execute("grant select on praxis_bulk.praxis_bulk_capacity_installation to bulk_runtime_test");
                }
            } finally {
                if (fence != null) fence.cancel(true);
                executor.shutdownNow();
                assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(administration.inspect()).isEqualTo("ACTIVE");
            local.assertionsComplete();
        }
    }

    @Test
    void knownFenceCommitIsReadBackOnAFreshPhysicalOwnerConnection() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-fresh-readback")) {
            var local = setup.local;
            local.activate();
            var pids = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
            var source = new DriverManagerDataSource(ownerUrl(setup), "postgres", setup.scope.password("postgres")) {
                @Override public Connection getConnection() throws SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement();
                            var row = statement.executeQuery("select pg_catalog.pg_backend_pid()")) {
                        if (!row.next()) throw new SQLException("Owner backend observation absent");
                        pids.add(row.getInt(1));
                        if (row.next()) throw new SQLException("Owner backend observation duplicated");
                    } catch (SQLException | RuntimeException failed) {
                        connection.close(); throw failed;
                    }
                    return connection;
                }
            };
            var administration = administration(local, local.expected, source);
            assertThat(administration.fence()).isTrue();
            assertThat(pids).hasSize(2);
            assertThat(pids.get(0)).isPositive().isNotEqualTo(pids.get(1));
            assertThat(pids.get(1)).isPositive();
            assertThat(local.observer.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class))
                    .isEqualTo("FENCED");
            var before = rows(local.observer);
            assertThatThrownBy(() -> administration.fence(System.nanoTime() - 1))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("Capacity installation time budget expired");
            assertThat(pids).hasSize(2);
            assertThat(rows(local.observer)).isEqualTo(before);
            local.assertionsComplete();
        }
    }

    @Test
    void acquisitionReturningAfterDeadlineIsDeniedBeforeCreatingAnyOperationStatement() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-late-acquisition")) {
            var local = setup.local;
            local.activate();
            var before = rows(local.observer);
            var acquisitions = new AtomicInteger();
            var statementFactories = new AtomicInteger();
            var capturedPid = new AtomicInteger();
            var delivered = new java.util.concurrent.atomic.AtomicBoolean();
            var physical = new AtomicReference<Connection>();
            // Keep the actual aggregate 20s owner budget; only return of the acquired loan is held.
            // No driver/network/commit error is injected, and no unit transaction is held.
            var deadline = new java.util.concurrent.atomic.AtomicLong();
            var source = new DriverManagerDataSource(ownerUrl(setup), "postgres", setup.scope.password("postgres")) {
                @Override public Connection getConnection() throws SQLException {
                    acquisitions.incrementAndGet();
                    Connection connection = super.getConnection();
                    physical.set(connection);
                    try {
                        // This native observation precedes the phase being counted.
                        try (var statement = connection.createStatement();
                                var row = statement.executeQuery("select pg_catalog.pg_backend_pid()")) {
                            if (!row.next()) throw new SQLException("Owner backend observation absent");
                            capturedPid.set(row.getInt(1));
                            if (row.next()) throw new SQLException("Owner backend observation duplicated");
                        }
                        while (System.nanoTime() <= deadline.get()) {
                            if (Thread.currentThread().isInterrupted())
                                throw new SQLException("Acquisition observation interrupted");
                            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                        }
                        Connection observed = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                                org.springframework.jdbc.datasource.ConnectionProxy.class.getClassLoader(),
                                new Class<?>[]{org.springframework.jdbc.datasource.ConnectionProxy.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("getTargetConnection")) return connection;
                                    if (delivered.get() && List.of("createStatement", "prepareStatement", "prepareCall")
                                            .contains(method.getName())) statementFactories.incrementAndGet();
                                    try { return method.invoke(connection, arguments); }
                                    catch (java.lang.reflect.InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
                        delivered.set(true);
                        return observed;
                    } catch (SQLException | RuntimeException failure) {
                        try { connection.close(); }
                        catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                        throw failure;
                    }
                }
            };
            var administration = administration(local, local.expected, source);
            assertThatThrownBy(() -> {
                long operationDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                deadline.set(operationDeadline);
                administration.fence(operationDeadline);
            }).isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("Capacity installation time budget expired");
            assertThat(acquisitions).hasValue(1);
            assertThat(delivered).isTrue();
            assertThat(capturedPid.get()).isPositive();
            // Counts JDBC statement creation by this operation after delivery, not internal driver messages.
            assertThat(statementFactories).hasValue(0);
            assertThat(physical.get().isClosed()).isTrue();
            assertThat(rows(local.observer)).isEqualTo(before);
            local.assertionsComplete();
        }
    }

    @Test
    void realPostgresDriverPreservesTheExplicitStricterSocketBudgetWithoutGlobalFlags() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-driver-budget")) {
            var local = setup.local;
            local.activate();
            var before = rows(local.observer);
            String url = BulkCapacityLocalAdministrationMain.boundedOwnerUrl(
                    urlOption(ownerUrl(setup), "connectTimeout=1&socketTimeout=1"), 20);
            assertThat(url).endsWith("connectTimeout=1&socketTimeout=1");
            var source = new DriverManagerDataSource(url, "postgres", setup.scope.password("postgres"));
            try (Connection connection = source.getConnection()) {
                assertThat(connection.getNetworkTimeout()).isEqualTo(1000);
                try (var statement = connection.createStatement()) {
                    assertThatThrownBy(() -> statement.executeQuery("select pg_catalog.pg_sleep(3)"))
                            .isInstanceOfSatisfying(SQLException.class, failure -> {
                                assertThat(failure.getSQLState()).isEqualTo("08006");
                                assertThat(failure.getCause()).isInstanceOf(java.net.SocketTimeoutException.class);
                            });
                }
            }
            assertThat(rows(local.observer)).isEqualTo(before);
            assertThat(administration(local, local.expected, local.ownerSource).inspect()).isEqualTo("ACTIVE");
            local.assertionsComplete();
        }
    }

    @Test
    void publicMainRunsInSeparateJvmAndPublishesOnlyConfirmedClosedResults() throws Exception {
        try (var setup = new AuthenticatedLocal("local-admin-public-process");
                var input = new BulkCapacityLocalAdministrationMainTest.PrivateInput()) {
            var local = setup.local;
            local.activate();
            ObjectNode configuration = configuration(setup);
            input.write(configuration.toString());
            var before = rows(local.observer);
            var inspect = child(input, "INSPECT", 0);
            assertThat(inspect.exit()).isZero();
            assertThat(JSON.readTree(inspect.output()).path("result").textValue()).isEqualTo("INSPECTED");
            assertThat(rows(local.observer)).isEqualTo(before);
            var fence = child(input, "FENCE", 0);
            assertThat(fence.exit()).isZero();
            assertThat(JSON.readTree(fence.output()).path("result").textValue()).isEqualTo("FENCED");
            var replay = child(input, "FENCE", 0);
            assertThat(replay.exit()).isZero();
            assertThat(JSON.readTree(replay.output()).path("result").textValue()).isEqualTo("FENCE_REPLAYED");
            assertThat(local.observer.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class))
                    .isEqualTo("FENCED");
            var confirmedRows = rows(local.observer);
            configuration.with("binding").put("attestationId", UUID.randomUUID().toString());
            input.write(configuration.toString());
            var denied = child(input, "FENCE", 2);
            assertThat(denied.exit()).isEqualTo(2);
            BulkCapacityLocalAdministrationMainTest.assertClosed(denied.output(), "FENCE", "LOCAL_OPERATION_NOT_CONFIRMED");
            assertThat(rows(local.observer)).isEqualTo(confirmedRows);
            for (var result : List.of(inspect, fence, replay, denied)) {
                assertThat(JSON.readTree(result.output()).size()).isEqualTo(5);
                assertThat(result.output().contains(setup.scope.password("postgres"))).isFalse();
                assertThat(result.output().contains(local.expected.databaseId().toString())).isFalse();
                assertThat(result.output().contains("jdbc:")).isFalse();
            }
            local.assertionsComplete();
        }
    }

    private static BulkCapacityLocalAdministrationMainTest.Result child(
            BulkCapacityLocalAdministrationMainTest.PrivateInput input, String operation, int expectedExit) throws Exception {
        Path stdout = Files.createTempFile(input.directory, "stdout-", ".json",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Path stderr = Files.createTempFile(input.directory, "stderr-", ".log",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Process process = null;
        boolean complete = false;
        try {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, BulkCapacityLocalAdministrationMain.class.getName(), operation, input.path.toString())
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            assertThat(process.pid()).isPositive();
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.size(stdout)).isLessThan(1024);
            assertThat(Files.size(stderr)).isZero();
            assertThat(process.exitValue()).isEqualTo(expectedExit);
            String output = Files.readString(stdout);
            var json = JSON.readTree(output);
            assertThat(json.isObject() && json.size() == 5).isTrue();
            assertThat(json.path("schemaVersion").intValue()).isEqualTo(1);
            assertThat(json.path("operation").textValue()).isEqualTo(operation);
            complete = true;
            return new BulkCapacityLocalAdministrationMainTest.Result(process.exitValue(), output);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly(); assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            }
            // Incomplete child diagnostics remain private 0600 in the 0700 directory.
            // PrivateInput always deletes the credential file, even when raw diagnostics remain.
            if (complete) { Files.deleteIfExists(stdout); Files.deleteIfExists(stderr); }
        }
    }

    private static boolean awaitMarkerEdge(JdbcTemplate observer, String application, int holder) throws Exception {
        long stop = System.nanoTime() + Duration.ofMillis(700).toNanos();
        while (System.nanoTime() < stop) {
            if (Boolean.TRUE.equals(observer.queryForObject("""
                    select exists(select 1 from pg_stat_activity a where a.application_name=?
                        and a.wait_event_type='Lock' and ?=any(pg_blocking_pids(a.pid))
                        and exists(select 1 from pg_locks l where l.pid=a.pid
                            and l.relation='praxis_bulk.praxis_bulk_capacity_marker'::regclass))
                    """, Boolean.class, application, holder))) return true;
            Thread.sleep(10);
        }
        return false;
    }

    private static JdbcBulkCapacityLocalAdministration administration(BulkCapacityOccupancyPostgresFixture local,
            BulkCapacityBinding binding, javax.sql.DataSource source) {
        return new JdbcBulkCapacityLocalAdministration(binding, source, new DataSourceTransactionManager(source),
                "postgres", local.runtime.roleConfiguration(), Duration.ofSeconds(20), Duration.ofSeconds(3));
    }

    private static BulkCapacityBinding mismatch(
            BulkCapacityBinding b, int i) {
        return new BulkCapacityBinding(i == 0 ? b.deploymentId() + "-wrong" : b.deploymentId(),
                i == 1 ? b.tenantId() + "-wrong" : b.tenantId(), i == 2 ? b.environment() + "-wrong" : b.environment(),
                i == 3 ? b.bindingId() + "-wrong" : b.bindingId(), i == 4 ? b.generation() + 1 : b.generation(),
                i == 5 ? UUID.randomUUID() : b.databaseId(), i == 6 ? UUID.randomUUID() : b.attestationId(),
                i == 7 ? UUID.randomUUID() : b.authorityId(), i == 8 ? b.authorityEpoch() + 1 : b.authorityEpoch());
    }

    private static ObjectNode configuration(AuthenticatedLocal setup) {
        var root = BulkCapacityLocalAdministrationMainTest.configuration();
        root.with("owner").put("url", ownerUrl(setup))
                .put("password", setup.scope.password("postgres"));
        var roles = setup.local.runtime.roleConfiguration();
        ObjectNode roleJson = root.with("roles");
        roleJson.set("runtimeGranteeRoles", JSON.valueToTree(roles.runtimeGranteeRoles()));
        roleJson.set("retentionExecutorMembers", JSON.valueToTree(roles.retentionExecutorMembers()));
        roleJson.set("controlPlaneGranteeRoles", JSON.valueToTree(roles.controlPlaneGranteeRoles()));
        var b = setup.local.expected;
        root.with("binding").put("deploymentId", b.deploymentId()).put("tenantId", b.tenantId())
                .put("environment", b.environment()).put("bindingId", b.bindingId()).put("generation", b.generation())
                .put("databaseId", b.databaseId().toString()).put("attestationId", b.attestationId().toString())
                .put("authorityId", b.authorityId().toString()).put("authorityEpoch", b.authorityEpoch());
        return root;
    }

    private static String ownerUrl(AuthenticatedLocal setup) {
        String url = setup.scope.postgres.getJdbcUrl("postgres", "capacity_local_1");
        // Zonky 2.2.2 supplies only this credential option; the CLI deliberately forbids URL credentials.
        String suffix = "?user=postgres";
        if (!url.endsWith(suffix)) throw new IllegalStateException("Unexpected fixture owner URL shape");
        return url.substring(0, url.length() - suffix.length());
    }

    private static String urlOption(String url, String option) {
        return url + (url.contains("?") ? "&" : "?") + option;
    }

    private static Map<String, List<String>> rows(JdbcTemplate sql) {
        var result = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : sql.queryForList("select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace"
                + " where n.nspname='praxis_bulk' and c.relkind='r' order by 1", String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalStateException("Unexpected fixture table");
            result.put(table, sql.queryForList("select to_jsonb(t)::text from praxis_bulk." + table + " t order by 1", String.class));
        }
        return result;
    }

    private static Map<String, List<String>> authorityRows(BulkCapacityOccupancyPostgresFixture.SharedScope scope) {
        var result = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : scope.authorityObserver.queryForList("select c.relname from pg_class c join pg_namespace n"
                + " on n.oid=c.relnamespace where n.nspname=? and c.relkind='r' order by 1", String.class, BulkCapacityAuthorityMigrator.SCHEMA)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalStateException("Unexpected fixture table");
            result.put(table, scope.authorityObserver.queryForList("select to_jsonb(t)::text from " + BulkCapacityAuthorityMigrator.SCHEMA + "."
                    + table + " t order by 1", String.class));
        }
        assertThat(result).isNotEmpty();
        return result;
    }

    /** Authentication closes before any authority/migration/runtime setup; only owned resources are touched. */
    private static final class AuthenticatedLocal implements AutoCloseable {
        final Path directory;
        final Path hba;
        final BulkCapacityOccupancyPostgresFixture.SharedScope scope;
        final BulkCapacityOccupancyPostgresFixture local;

        AuthenticatedLocal(String caseId) throws Exception {
            directory = Files.createTempDirectory("local-admin-auth-").toRealPath();
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            hba = directory.resolve("hba.conf");
            Files.writeString(hba, "host all postgres 127.0.0.1/32 trust\nhost all all 0.0.0.0/0 reject\n");
            Files.setPosixFilePermissions(hba, PosixFilePermissions.fromString("rw-------"));
            var credentials = new java.util.LinkedHashMap<String, String>();
            for (String role : List.of("postgres", "occupancy_provisioner", "occupancy_allocator", "occupancy_reader",
                    "bulk_runtime_test", "durable_runtime")) credentials.put(role, UUID.randomUUID().toString());
            BulkCapacityOccupancyPostgresFixture.SharedScope created = null;
            try {
                created = new BulkCapacityOccupancyPostgresFixture.SharedScope(EmbeddedPostgres.builder()
                        .setCleanDataDirectory(true).setRegisterShutdownHook(false).setServerConfig("hba_file", hba.toString())
                        .setServerConfig("listen_addresses", "127.0.0.1").setServerConfig("unix_socket_directories", ""),
                        credentials, connection -> {
                            char[] password = credentials.get("postgres").toCharArray();
                            try { connection.unwrap(org.postgresql.PGConnection.class)
                                    .alterUserPassword("postgres", password, "scram-sha-256"); }
                            finally { java.util.Arrays.fill(password, '\0'); }
                            try { Files.writeString(hba, "host all all 127.0.0.1/32 scram-sha-256\n"
                                    + "host all all 0.0.0.0/0 reject\nhost all all ::0/0 reject\n"); }
                            catch (java.io.IOException failure) { throw new SQLException("Private HBA setup failed", failure); }
                            try (var statement = connection.createStatement();
                                    var row = statement.executeQuery("select pg_reload_conf()")) {
                                if (!row.next() || !row.getBoolean(1) || row.next()) throw new SQLException("HBA reload failed");
                            }
                            long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                            while (true) {
                                try (var probe = new DriverManagerDataSource(connection.getMetaData().getURL(), "postgres",
                                        credentials.get("bulk_runtime_test")).getConnection()) {
                                    if (System.nanoTime() >= stop) throw new SQLException("Authentication setup expired");
                                } catch (SQLException denied) {
                                    if (!"28P01".equals(denied.getSQLState())) throw denied;
                                    break;
                                }
                                try { Thread.sleep(10); }
                                catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt(); throw new SQLException("Authentication setup interrupted");
                                }
                            }
                            return null;
                        });
                scope = created;
                local = scope.local(caseId, 1);
                assertThat(local.observer.queryForObject("select current_user", String.class)).isEqualTo("postgres");
                assertThat(local.runtimeSql.queryForObject("select current_user", String.class)).isEqualTo("bulk_runtime_test");
            } catch (Exception | Error failure) {
                if (created != null) try { created.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                try { Files.deleteIfExists(hba); Files.deleteIfExists(directory); }
                catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            Exception failure = null;
            try { scope.close(); } catch (Exception close) { failure = close; }
            try { Files.deleteIfExists(hba); Files.deleteIfExists(directory); }
            catch (Exception cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
            if (failure != null) throw failure;
        }
    }
}
