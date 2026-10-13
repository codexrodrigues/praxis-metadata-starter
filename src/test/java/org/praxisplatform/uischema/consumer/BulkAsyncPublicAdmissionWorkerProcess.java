package org.praxisplatform.uischema.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.ds.PGSimpleDataSource;
import org.praxisplatform.uischema.bulk.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Child JVM receives runtime credentials only; no owner, control or authority passwords. */
public final class BulkAsyncPublicAdmissionWorkerProcess {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Private input, output and fixed peer required");
        var input = Path.of(args[0]); var output = Path.of(args[1]); String peer = args[2];
        if (!Files.isRegularFile(input, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(input).equals(PosixFilePermissions.fromString("rw-------"))
                || !Files.isDirectory(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(output).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IllegalArgumentException("Private file permissions differ");
        if (!Set.of("peer-1", "peer-2", "restart").contains(peer)) throw new IllegalArgumentException("Unknown peer");
        var p = new Properties(); try (var reader = Files.newBufferedReader(input)) { p.load(reader); }
        if (!p.stringPropertyNames().equals(Set.of("host", "database", "runtimeRole", "runtimePassword", "ownerRole",
                "controlRole", "namespace", "deployment", "tenant", "environment", "binding", "generation",
                "databaseId", "attestationId", "authorityId", "authorityEpoch", "candidateJar", "candidateJarSha256")))
            throw new IllegalArgumentException("Runtime-only input shape differs");
        Path jar = Path.of(p.getProperty("candidateJar")).toRealPath();
        if (!sha(jar).equals(p.getProperty("candidateJarSha256"))) throw new IllegalArgumentException("Candidate artifact differs");
        for (Class<?> type : new Class<?>[]{JdbcBulkDurableExecution.class, BulkDurableWorkerComposition.class})
            if (!Files.isSameFile(jar, Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())))
                throw new IllegalArgumentException("Worker does not use the assigned SDK artifact");
        var source = new PGSimpleDataSource(); source.setServerNames(new String[]{p.getProperty("host")});
        source.setDatabaseName(p.getProperty("database")); source.setUser(p.getProperty("runtimeRole"));
        source.setPassword(p.getProperty("runtimePassword")); source.setSslMode("verify-full");
        source.setProperty(org.postgresql.PGProperty.SSL_FACTORY, "org.postgresql.ssl.DefaultJavaSSLFactory");
        source.setConnectTimeout(10); source.setSocketTimeout(30);
        var admissions = new AtomicInteger(); var mutations = new AtomicInteger(); var cleanups = new AtomicInteger();
        boolean stopped = false;
        Throwable primary = null;
        var pool = new HikariDataSource();
        try (pool) {
            pool.setDataSource(source); pool.setMaximumPoolSize(2); pool.setMinimumIdle(1);
            var infrastructure = new BulkExecutionInfrastructure(pool, new DataSourceTransactionManager(pool),
                    p.getProperty("namespace"), p.getProperty("deployment"),
                    new BulkExecutionRoleConfiguration(p.getProperty("ownerRole"), Set.of(p.getProperty("runtimeRole")),
                            Set.of(), Set.of(p.getProperty("controlRole"))));
            var capacity = new BulkCapacityBinding(p.getProperty("deployment"), p.getProperty("tenant"),
                    p.getProperty("environment"), p.getProperty("binding"), Long.parseLong(p.getProperty("generation")),
                    UUID.fromString(p.getProperty("databaseId")), UUID.fromString(p.getProperty("attestationId")),
                    UUID.fromString(p.getProperty("authorityId")), Long.parseLong(p.getProperty("authorityEpoch")));
            var lifecycle = BulkAsyncPublicAdmissionConsumer.worker(infrastructure, capacity, admissions, mutations, cleanups, false);
            Files.writeString(output.resolve(peer + "-ready"), Long.toString(ProcessHandle.current().pid()));
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while (!Files.exists(output.resolve("start"))) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Start barrier expired");
                Thread.sleep(20);
            }
            try {
                lifecycle.start();
                while (!Files.exists(output.resolve("stop"))) {
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("Stop barrier expired");
                    Thread.sleep(20);
                }
            } finally {
                var acknowledged = new java.util.concurrent.CountDownLatch(1);
                lifecycle.stop(acknowledged::countDown);
                if (!acknowledged.await(20, java.util.concurrent.TimeUnit.SECONDS) || lifecycle.isRunning())
                    throw new IllegalStateException("Worker termination not acknowledged");
                stopped = true;
            }
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
            var result = new ObjectMapper().createObjectNode().put("pid", ProcessHandle.current().pid())
                    .put("admissions", admissions.get()).put("mutations", mutations.get()).put("cleanups", cleanups.get())
                    .put("workerStopped", stopped)
                    .put("sdkJarSha256", p.getProperty("candidateJarSha256")).put("at", Instant.now().toString());
            Path receipt = output.resolve(peer + "-result.private.json");
            Files.createFile(receipt, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(receipt, result.toString());
            } catch (Exception diagnostic) {
                if (primary != null) primary.addSuppressed(diagnostic); else throw diagnostic;
            }
        }
    }

    private static String sha(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

}
