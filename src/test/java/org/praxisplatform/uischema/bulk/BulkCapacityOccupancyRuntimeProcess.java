package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Finite runtime-only test JVM. It cannot install, allocate, migrate, schedule or register work. */
public final class BulkCapacityOccupancyRuntimeProcess {
    private static final ObjectMapper JSON = new ObjectMapper();
    private BulkCapacityOccupancyRuntimeProcess() { }

    /** Config, READY path, fixed phase directory and sanitized result path; never credentials in argv. */
    public static void main(String[] args) {
        Path result = null;
        String actor = "uninitialized";
        String database = "uninitialized";
        String stage = "SETUP";
        try {
            require(args != null && args.length == 4);
            var values = properties(Path.of(args[0]));
            Path ready = Path.of(args[1]);
            Path directory = Path.of(args[2]);
            result = Path.of(args[3]);
            actor = required(values, "actor");
            database = required(values, "logicalDatabase");
            require(actor.matches("db-[ab]-peer-[12]") && database.matches("capacity_local_[12]"));
            String runtimeActor = actor;
            int witnessActor = Integer.parseInt(required(values, "witnessActor"));
            require(witnessActor == 1 || witnessActor == 2);
            var source = new DriverManagerDataSource(required(values, "runtimeUrl"),
                    required(values, "runtimeUser"), values.getProperty("runtimePassword", ""));
            var connectionProperties = new Properties();
            connectionProperties.setProperty("ApplicationName", "occupancy_runtime_" + actor.replace('-', '_'));
            source.setConnectionProperties(connectionProperties);
            var roles = new BulkExecutionRoleConfiguration(required(values, "ownerRole"),
                    roles(values, "runtimeRoles"), Set.of(), Set.of());
            var runtime = new BulkExecutionInfrastructure(source, new DataSourceTransactionManager(source),
                    required(values, "namespace"), required(values, "deployment"), roles);
            var context = new BulkFingerprintContext(required(values, "namespace"), required(values, "subject"),
                    required(values, "resource"), new CanonicalOperationRef(required(values, "operationGroup"),
                    required(values, "operation"), required(values, "operationPath"), required(values, "operationMethod")),
                    required(values, "revision"), ActionCollectionAtomicity.PER_ITEM);
            var expected = new BulkCapacityBinding(required(values, "deployment"),
                    required(values, "tenant"), required(values, "environment"), required(values, "binding"),
                    number(values, "bindingGeneration"), uuid(values, "databaseId"), uuid(values, "attestationId"),
                    uuid(values, "authorityId"), number(values, "authorityEpoch"));
            var kernel = new JdbcBulkDurableExecution(runtime, null, expected);
            var sql = new JdbcTemplate(source);
            require(kernelHash().equals(required(values, "kernelHash")));
            require(JdbcBulkDurableExecution.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm()
                    .equals(required(values, "kernelCodeSource")));
            var backend = runtime.withLifecycleRead(connection -> {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "select pg_backend_pid(),current_user,current_database(),(select oid from pg_database where datname=current_database())")) {
                    require(rows.next());
                    require(rows.getString(2).equals(required(values, "runtimeUser")));
                    var data = new long[]{rows.getInt(1), rows.getLong(4)};
                    require(rows.getString(3).equals(required(values, "logicalDatabase")) && !rows.next());
                    return data;
                }
            });
            emit(ready, event(actor, database, "READY_OUTSIDE_UNIT_TX").put("readyBackendPid", backend[0])
                    .put("databaseOid", backend[1]).put("kernelClassSha256", kernelHash()).put("codeSourceMatchesParent", true));

            stage = "ENQUEUE_CLAIM_OLD_EPOCH";
            await(directory.resolve("claim-start"), directory, Duration.ofSeconds(60));
            var enqueued = kernel.enqueue(context, uuid(values, "proposalId"), required(values, "idempotencyKey"),
                    "process-supervisor", context.schemaRevision(), Instant.parse(required(values, "deadline")), uuid(values, "queueToken"));
            require(!enqueued.replayed() && enqueued.control().epoch() == 1);
            var claimed = kernel.claim(context, enqueued.executionId(), "process-worker-" + actor,
                    uuid(values, "activeToken")).orElseThrow();
            require(claimed.control().epoch() == 2 && claimed.status() == BulkDurableExecutionStatus.RUNNING);
            var admissions = new AtomicInteger();
            var callbacks = new AtomicInteger();
            expectFenced(() -> kernel.executeUnit(enqueued.control(), 0, unit -> {
                admissions.incrementAndGet(); return BulkUnitAdmission.admit();
            }, unit -> {
                callbacks.incrementAndGet(); writeWitness(runtime, sql, witnessActor, unit.ordinal());
                return BulkUnitMutationResult.confirmed();
            }));
            require(admissions.get() == 0 && callbacks.get() == 0);
            emit(directory.resolve("claimed.json"), event(actor, database, "CLAIMED_EPOCH_TWO_OLD_SUPERVISOR_FENCED")
                    .put("epoch", 2).put("oldEpochReason", "FENCED").put("admissions", 0).put("callbacks", 0));

            stage = "DOMAIN_RECEIPT";
            await(directory.resolve("unit-start"), directory, Duration.ofSeconds(60));
            String callbackActor = actor;
            String callbackDatabase = database;
            var committed = kernel.executeUnit(claimed.control(), 0, unit -> {
                admissions.incrementAndGet(); return BulkUnitAdmission.admit();
            }, unit -> {
                callbacks.incrementAndGet();
                var physical = writeWitness(runtime, sql, witnessActor, unit.ordinal());
                try {
                    emit(directory.resolve("entered.json"), event(callbackActor, callbackDatabase, "DOMAIN_WRITTEN_INSIDE_UNIT_TX")
                            .put("callbackPid", physical[0]).put("callbackXid", physical[1]).put("ordinal", unit.ordinal())
                            .put("remainingBudgetMillis", unit.remainingBudget().toMillis()));
                    // Waiting belongs only to this test callback and never extends the kernel's absolute deadline.
                    await(directory.resolve("callback-release"), directory, unit.remainingBudget());
                } catch (Exception failure) { throw new IllegalStateException("Runtime proof callback barrier failed"); }
                return BulkUnitMutationResult.confirmed();
            });
            require(committed.receiptPresent() && committed.outcome() == BulkUnitOutcome.CONFIRMED);
            require(committed.execution().nextOrdinal() == 1 && admissions.get() == 1 && callbacks.get() == 1);
            emit(directory.resolve("committed.json"), event(actor, database, "PHYSICAL_RECEIPT_RETURNED_AFTER_CALLBACK_RELEASE")
                    .put("receiptPresent", true).put("nextOrdinal", committed.execution().nextOrdinal())
                    .put("admissions", admissions.get()).put("callbacks", callbacks.get()));

            stage = "AFTER_FENCE_NEGATIVES_AND_REPLAY";
            await(directory.resolve("after-fence"), directory, Duration.ofSeconds(60));
            var negatives = properties(directory.resolve("negatives.properties"));
            expectFenced(() -> kernel.executeUnit(claimed.control(), 1, unit -> {
                admissions.incrementAndGet(); return BulkUnitAdmission.admit();
            }, unit -> {
                callbacks.incrementAndGet(); writeWitness(runtime, sql, witnessActor, unit.ordinal());
                return BulkUnitMutationResult.confirmed();
            }));
            expectFenced(() -> kernel.claim(context, uuid(negatives, "queuedExecution"), "process-fenced-claim-" + runtimeActor,
                    uuid(values, "activeToken")));
            expectFenced(() -> kernel.enqueue(context, uuid(values, "pendingProposal"), "process-fenced-enqueue-" + runtimeActor,
                    "process-supervisor", context.schemaRevision(), Instant.parse(required(values, "deadline")), uuid(values, "queueToken")));
            var replay = kernel.executeUnit(claimed.control(), 0, unit -> {
                admissions.incrementAndGet(); return BulkUnitAdmission.admit();
            }, unit -> {
                callbacks.incrementAndGet(); writeWitness(runtime, sql, witnessActor, unit.ordinal());
                return BulkUnitMutationResult.confirmed();
            });
            require(replay.receiptPresent() && replay.replayed() && replay.execution().nextOrdinal() == 1);
            require(admissions.get() == 1 && callbacks.get() == 1);
            emit(result, event(actor, database, "ALL_ASSERTIONS_COMPLETE").put("oldEpochReason", "FENCED")
                    .put("unitOneReason", "FENCED").put("claimReason", "FENCED").put("enqueueReason", "FENCED")
                    .put("receiptReplayed", true).put("admissions", admissions.get()).put("callbacks", callbacks.get()));
        } catch (Throwable failure) {
            if (result != null) {
                try {
                    var safe = event(actor, database, "FAILED").put("stage", stage)
                            .put("failureClass", failure.getClass().getSimpleName());
                    if (failure instanceof BulkDurableExecutionException durable) safe.put("safeReason", durable.reason().name());
                    emit(result, safe);
                } catch (Exception ignored) { /* The nonzero exit still rejects this run. */ }
            }
            // Never print SQL errors, stack traces, identifiers, configuration or protected payloads.
            System.exit(21);
        }
    }

    private static long[] writeWitness(BulkExecutionInfrastructure runtime, JdbcTemplate sql, int actor, int ordinal) {
        return runtime.withConnection(connection -> {
            require(!connection.getAutoCommit());
            long[] physical;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select pg_backend_pid(),txid_current()")) {
                require(rows.next()); physical = new long[]{rows.getInt(1), rows.getLong(2)}; require(!rows.next());
            }
            var same = sql.queryForMap("select pg_backend_pid() as pid,txid_current() as xid");
            require(((Number) same.get("pid")).longValue() == physical[0] && ((Number) same.get("xid")).longValue() == physical[1]);
            require(sql.update("update process_domain_witness set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current() "
                    + "where actor=? and ordinal=?", actor, ordinal) == 1);
            require(sql.queryForObject("select writes from process_domain_witness where actor=? and ordinal=?",
                    Integer.class, actor, ordinal) == 1);
            return physical;
        });
    }

    private static void expectFenced(Runnable call) {
        try { call.run(); throw new AssertionError("Expected fenced runtime operation"); }
        catch (BulkDurableExecutionException failure) { require(failure.reason() == BulkDurableExecutionException.Reason.FENCED); }
    }

    private static void await(Path permit, Path directory, Duration budget) throws Exception {
        require(!budget.isNegative() && !budget.isZero());
        long stop = System.nanoTime() + budget.toNanos();
        while (!Files.exists(permit)) {
            require(!Files.exists(directory.resolve("abort")) && System.nanoTime() < stop);
            Thread.sleep(10);
        }
        require(!Files.exists(directory.resolve("abort")));
    }

    static String kernelHash() throws Exception {
        try (var input = JdbcBulkDurableExecution.class.getResourceAsStream("JdbcBulkDurableExecution.class")) {
            require(input != null);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }

    static ObjectNode event(String actor, String database, String phase) {
        return JSON.createObjectNode().put("actor", actor).put("logicalDatabase", database)
                .put("phase", phase).put("osPid", ProcessHandle.current().pid());
    }

    static void emit(Path destination, ObjectNode event) throws Exception {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, event.toString(), StandardCharsets.UTF_8);
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Properties properties(Path file) throws Exception {
        var values = new Properties();
        try (InputStream input = Files.newInputStream(file)) { values.load(input); }
        return values;
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        require(value != null && !value.isBlank());
        return value;
    }

    private static long number(Properties values, String key) {
        long number = Long.parseLong(required(values, key)); require(number > 0); return number;
    }

    private static UUID uuid(Properties values, String key) { return UUID.fromString(required(values, key)); }
    private static Set<String> roles(Properties values, String key) {
        return Arrays.stream(required(values, key).split(",")).collect(Collectors.toUnmodifiableSet());
    }
    private static void require(boolean condition) { if (!condition) throw new AssertionError("Runtime proof invariant failed"); }
}
