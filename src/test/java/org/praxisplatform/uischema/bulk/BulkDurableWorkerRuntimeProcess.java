package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Finite runtime-only worker JVM. No enqueue, claim invocation, install, issuer or migration here. */
public final class BulkDurableWorkerRuntimeProcess {
    private static final ObjectMapper JSON = new ObjectMapper();
    private BulkDurableWorkerRuntimeProcess() { }
    public static void main(String[] args) {
        Path result = null;
        BulkDurableWorker worker = null;
        try {
            if (args.length != 3) throw new IllegalArgumentException("Three private proof paths required");
            Path config = Path.of(args[0]); Path directory = Path.of(args[1]); result = Path.of(args[2]);
            var values = new Properties();
            try (var input = Files.newInputStream(config)) { values.load(input); }
            var countA = new AtomicInteger(); var countB = new AtomicInteger();
            var a = kernel(values, "a.", directory); var b = kernel(values, "b.", directory);
            var contextA = context(values, "a."); var contextB = context(values, "b.");
            worker = new BulkDurableWorker(List.of(binding(a, contextA, values, "a.", directory, countA),
                    binding(b, contextB, values, "b.", directory, countB)));
            emit(directory.resolve(values.getProperty("actor") + "-ready.json"), JSON.createObjectNode()
                    .put("pid", ProcessHandle.current().pid()).put("ready", true)
                    .put("kernelSource", JdbcBulkDurableExecution.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm())
                    .put("workerSource", BulkDurableWorker.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm())
                    .put("processSource", BulkDurableWorkerRuntimeProcess.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm())
                    .put("kernelHash", hash(JdbcBulkDurableExecution.class)).put("workerHash", hash(BulkDurableWorker.class))
                    .put("processHash", hash(BulkDurableWorkerRuntimeProcess.class)));
            awaitFile(directory.resolve("start"), Duration.ofSeconds(30)); worker.start();
            UUID bExecution = values.getProperty("b.execution") == null ? null : UUID.fromString(values.getProperty("b.execution"));
            UUID aExecution = UUID.fromString(values.getProperty("a.execution"));
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            boolean observedRound = false;
            while (!Files.exists(directory.resolve("stop"))) {
                if (bExecution != null && b.kernel().find(contextB, bExecution).orElseThrow().status() == BulkDurableExecutionStatus.COMPLETED)
                    Files.writeString(directory.resolve("b-committed"), "durable B completed");
                if (!observedRound && a.source().selectionObserved && b.source().selectionObserved) {
                    emit(directory.resolve(values.getProperty("actor") + "-running.json"), JSON.createObjectNode()
                            .put("selectionObservedA", true).put("selectionObservedB", true)
                            .put("terminalA", a.kernel().find(contextA, aExecution).orElseThrow().status().name()));
                    observedRound = true;
                }
                if (System.nanoTime() - end >= 0) throw new IllegalStateException("Finite worker process deadline");
                LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
            }
            worker.stop();
            if (worker.isRunning()) throw new IllegalStateException("Worker still running");
            emit(result, JSON.createObjectNode().put("pid", ProcessHandle.current().pid()).put("success", true)
                    .put("callbacksA", countA.get()).put("callbacksB", countB.get()).put("workerStopped", true));
        } catch (Exception | AssertionError error) {
            if (worker != null) worker.stop();
            try { if (result != null) emit(result, JSON.createObjectNode().put("pid", ProcessHandle.current().pid())
                    .put("success", false).put("failureType", error.getClass().getSimpleName())); }
            catch (Exception ignored) { }
            System.exit(1);
        }
    }
    private record Composed(JdbcBulkDurableExecution kernel, BulkExecutionInfrastructure runtime, ProbeSource source) { }
    private static Composed kernel(Properties v, String p, Path directory) {
        var source = new ProbeSource(v, p, directory);
        var runtime = new BulkExecutionInfrastructure(source, new DataSourceTransactionManager(source),
                v.getProperty(p + "namespace"), v.getProperty(p + "deployment"),
                new BulkExecutionRoleConfiguration("postgres", Set.of("bulk_runtime_test", "durable_runtime"), Set.of(), Set.of()));
        var expected = new JdbcBulkCapacityInstallation.ExpectedBinding(v.getProperty(p + "deployment"),
                v.getProperty(p + "tenant"), v.getProperty(p + "environment"), v.getProperty(p + "binding"),
                Long.parseLong(v.getProperty(p + "generation")), UUID.fromString(v.getProperty(p + "databaseId")),
                UUID.fromString(v.getProperty(p + "attestationId")), UUID.fromString(v.getProperty(p + "authorityId")),
                Long.parseLong(v.getProperty(p + "authorityEpoch")));
        return new Composed(new JdbcBulkDurableExecution(runtime, null, expected), runtime, source);
    }
    private static BulkFingerprintContext context(Properties v, String p) {
        return new BulkFingerprintContext(v.getProperty(p + "namespace"), v.getProperty(p + "subject"),
                v.getProperty(p + "resource"), new CanonicalOperationRef(v.getProperty(p + "group"),
                v.getProperty(p + "operation"), v.getProperty(p + "path"), v.getProperty(p + "method")),
                v.getProperty(p + "revision"), org.praxisplatform.uischema.action.ActionCollectionAtomicity.PER_ITEM);
    }
    private static BulkDurableWorker.Binding binding(Composed composed, BulkFingerprintContext context,
            Properties values, String prefix, Path directory, AtomicInteger callbacks) {
        // Same trusted operational binding as the kernel; domain SQL must join its transaction.
        return new BulkDurableWorker.Binding(composed.kernel(), List.of(new BulkDurableWorker.Handler(context.resourceKey(),
                context.operationRef(), unit -> BulkUnitAdmission.admit(), unit -> {
                    if (Boolean.parseBoolean(values.getProperty("fairness")) && unit.ordinal() == 0) {
                        if (prefix.equals("a.")) {
                            try { Files.writeString(directory.resolve("a-entered"), "A unit entered, no mutation yet"); }
                            catch (java.io.IOException error) { throw new IllegalStateException("Proof marker failed"); }
                            awaitFiles(List.of(directory.resolve("b-committed"), directory.resolve("a-release")), Duration.ofSeconds(3));
                        } else awaitFile(directory.resolve("b-allowed"), Duration.ofSeconds(3));
                    }
                    composed.runtime().withConnection(connection -> {
                        try (var statement = connection.prepareStatement("""
                                update occupancy_domain_witness
                                set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current() where id=?
                                """)) {
                            statement.setInt(1, unit.ordinal() + 1);
                            if (statement.executeUpdate() != 1) throw new IllegalStateException("Missing target witness");
                        }
                        return null;
                    });
                    callbacks.incrementAndGet();
                    return BulkUnitMutationResult.confirmed();
                })));
    }
    private static void awaitFile(Path path, Duration timeout) { awaitFiles(List.of(path), timeout); }
    private static void awaitFiles(List<Path> paths, Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (paths.stream().anyMatch(path -> !Files.exists(path))) {
            if (System.nanoTime() - end >= 0) throw new IllegalStateException("Finite proof barrier deadline");
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
    }
    static String hash(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null) throw new IllegalStateException("Class bytes missing");
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }

    /** Test instrumentation only: exact SQL passes through unchanged, never a product hook. */
    private static final class ProbeSource extends DriverManagerDataSource {
        private static final String CLAIM = "select praxis_bulk.claim_capacity_execution(?,?,?,?,?)";
        private static final String INITIAL_SELECTION = """
                select execution_id,created_at from praxis_bulk.praxis_bulk_execution
                where namespace_id=? and execution_mode='ASYNC' and status='QUEUED'
                """ + " order by created_at,execution_id limit 1";
        private final Properties values;
        private final String prefix;
        private final Path directory;
        private final java.util.concurrent.atomic.AtomicBoolean claimObserved = new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean selectionObserved;
        ProbeSource(Properties values, String prefix, Path directory) {
            super(values.getProperty(prefix + "url"), "bulk_runtime_test", "");
            this.values = values; this.prefix = prefix; this.directory = directory;
            var properties = new Properties(); properties.setProperty("ApplicationName", "worker_proof_" + values.getProperty("actor"));
            setConnectionProperties(properties);
        }
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException { return wrap(super.getConnection()); }
        @Override public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException {
            return wrap(super.getConnection(user, password));
        }
        private java.sql.Connection wrap(java.sql.Connection connection) {
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(connection, args);
                            if (method.getName().equals("prepareStatement") && args != null
                                    && (CLAIM.equals(args[0]) || INITIAL_SELECTION.equals(args[0]))
                                    && result instanceof java.sql.PreparedStatement statement) {
                                boolean claim = CLAIM.equals(args[0]);
                                return java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{java.sql.PreparedStatement.class}, (prepared, operation, parameters) -> {
                                            try {
                                                if (operation.getName().equals("executeQuery") && claim && prefix.equals("a.")
                                                        && !Boolean.parseBoolean(values.getProperty("fairness"))
                                                        && claimObserved.compareAndSet(false, true)) {
                                                    try (var probe = connection.createStatement();
                                                            var rows = probe.executeQuery("select pg_backend_pid(),txid_current(),clock_timestamp()")) {
                                                        if (!rows.next() || connection.getAutoCommit()) throw new IllegalStateException("Invalid claim transaction");
                                                        emit(directory.resolve("claim-" + values.getProperty("actor") + ".json"), JSON.createObjectNode()
                                                                .put("pid", rows.getInt(1)).put("xid", rows.getLong(2))
                                                                .put("databaseClock", rows.getObject(3, java.time.OffsetDateTime.class).toString()));
                                                    }
                                                    awaitFile(directory.resolve("allow-claim"), Duration.ofMillis(700));
                                                }
                                                Object observed = operation.invoke(statement, parameters);
                                                if (operation.getName().equals("executeQuery") && !claim) selectionObserved = true;
                                                return observed;
                                            } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                                        });
                            }
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                    });
        }
    }

    private static void emit(Path path, ObjectNode value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temporary, JSON.writeValueAsBytes(value));
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
