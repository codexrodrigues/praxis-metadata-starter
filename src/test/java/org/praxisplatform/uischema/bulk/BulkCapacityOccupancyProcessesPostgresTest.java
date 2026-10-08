package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Four actual runtime JVMs; two local databases, parent-only capacity provisioning and fencing. */
class BulkCapacityOccupancyProcessesPostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void fourRuntimeJvmsCommitReceiptsInTwoDatabasesAndRespectMarkerFencing() throws Exception {
        try (var scope = new BulkCapacityOccupancyPostgresFixture.SharedScope(); var harness = new Harness()) {
            var locals = List.of(scope.local("runtime-process-local-a", 1), scope.local("runtime-process-local-b", 2));
            var immutable = new ArrayList<Map<String, List<String>>>();
            for (int database = 1; database <= 2; database++) {
                var local = locals.get(database - 1);
                local.activate();
                local.observer.execute("""
                        create table process_domain_witness(actor integer not null,ordinal integer not null,
                            writes integer not null,last_pid integer,last_xid bigint,primary key(actor,ordinal))
                        """);
                local.observer.update("insert into process_domain_witness(actor,ordinal,writes) values(1,0,0),(1,1,0),(2,0,0),(2,1,0)");
                local.observer.execute("grant select,update on process_domain_witness to bulk_runtime_test");
                for (int peer = 1; peer <= 2; peer++) {
                    UUID active = local.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
                    UUID queue = local.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
                    var primary = local.persist();
                    var pending = local.persist();
                    harness.launch(scope, local, database, peer, primary.proposal().id(), pending.proposal().id(), queue, active);
                }
                immutable.add(immutableLocal(local));
            }
            assertThat(locals.get(0).expected.databaseId()).isNotEqualTo(locals.get(1).expected.databaseId());
            assertThat(locals.get(0).expected.attestationId()).isNotEqualTo(locals.get(1).expected.attestationId());
            var authorityBefore = authoritySnapshot(scope);
            var osPids = new HashSet<Long>();
            var databaseOids = new HashSet<Long>();
            long readyStop = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            for (var child : harness.children) {
                var ready = harness.awaitEvent(child, "ready.json", remaining(readyStop, "all runtime JVMs reached READY outside unit transactions"));
                assertThat(ready.path("phase").asText()).isEqualTo("READY_OUTSIDE_UNIT_TX");
                assertThat(ready.path("kernelClassSha256").asText()).isEqualTo(BulkCapacityOccupancyRuntimeProcess.kernelHash());
                assertThat(ready.path("codeSourceMatchesParent").asBoolean()).isTrue();
                assertThat(osPids.add(child.process().pid())).isTrue();
                databaseOids.add(ready.path("databaseOid").asLong());
            }
            assertThat(osPids).hasSize(4);
            assertThat(databaseOids).hasSize(2);
            assertThat(harness.children.stream().allMatch(child -> child.process().isAlive())).isTrue();
            harness.phase("FOUR_RUNTIME_JVMS_READY_BEFORE_UNIT_TRANSACTIONS");

            // Enqueue/claim and the epoch-one negative run outside the callback pause, one peer at a time.
            for (var child : harness.children) {
                harness.signal(child, "claim-start");
                var claimed = harness.awaitEvent(child, "claimed.json", Duration.ofSeconds(30));
                assertThat(claimed.path("phase").asText()).isEqualTo("CLAIMED_EPOCH_TWO_OLD_SUPERVISOR_FENCED");
                assertThat(claimed.path("oldEpochReason").asText()).isEqualTo("FENCED");
                assertThat(claimed.path("epoch").asInt()).isEqualTo(2);
                assertThat(claimed.path("admissions").asInt()).isZero();
                assertThat(claimed.path("callbacks").asInt()).isZero();
                var execution = primaryExecution(child);
                assertThat(execution).containsEntry("status", "RUNNING").containsEntry("owner_epoch", 2L)
                        .containsEntry("next_ordinal", 0).containsEntry("active_attempt_id", null);
                assertThat(child.local().observer.queryForObject("select sum(writes) from process_domain_witness", Long.class)).isZero();
                assertThat(child.local().count("item_receipt")).isZero();
            }
            var extraQueued = new ArrayList<UUID>();
            for (int database = 1; database <= 2; database++) {
                var local = locals.get(database - 1);
                var pair = harness.pair(database);
                // This QUEUE right was released by its genuine primary claim; it is not reissued.
                var extra = local.enqueue(local.persist(), "process-extra-queued", pair.getFirst().queue());
                assertThat(extra.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
                extraQueued.add(extra.executionId());
                for (var child : pair) {
                    var values = new Properties(); values.setProperty("queuedExecution", extra.executionId().toString());
                    writePrivate(child.directory().resolve("negatives.properties"), values);
                }
            }
            harness.phase("FOUR_CLAIMS_AND_OLD_EPOCH_NEGATIVES_CONFIRMED_BEFORE_FENCE");

            var callbackPids = new HashSet<Long>();
            for (int database = 1; database <= 2; database++) {
                var local = locals.get(database - 1);
                var pair = harness.pair(database);
                for (var child : pair) harness.signal(child, "unit-start");
                var entered = new LinkedHashMap<Long, Child>();
                var physical = new LinkedHashMap<Child, JsonNode>();
                long enteredStop = System.nanoTime() + Duration.ofSeconds(4).toNanos();
                for (var child : pair) {
                    var event = harness.awaitEvent(child, "entered.json", remaining(enteredStop, "both callbacks entered inside their original unit budgets"));
                    assertThat(event.path("phase").asText()).isEqualTo("DOMAIN_WRITTEN_INSIDE_UNIT_TX");
                    assertThat(event.path("remainingBudgetMillis").asLong()).isBetween(1L, 5000L);
                    long pid = event.path("callbackPid").asLong();
                    assertThat(pid).isPositive();
                    assertThat(callbackPids.add(pid)).isTrue();
                    assertThat(entered.put(pid, child)).isNull();
                    physical.put(child, event);
                    assertUncommitted(child, event, harness);
                }
                assertThat(entered).hasSize(2);
                // Both unit callbacks are demonstrably open before the owner fence is dispatched.
                harness.phase("PAIR_" + database + "_BOTH_UNIT_TRANSACTIONS_ENTERED_BEFORE_FENCE");
                String applicationName = "occupancy_owner_fence_" + database;
                var owner = new DriverManagerDataSource(scope.postgres.getJdbcUrl("postgres", "capacity_local_" + database), "postgres", "");
                var properties = new Properties(); properties.setProperty("ApplicationName", applicationName);
                owner.setConnectionProperties(properties);
                var installation = new JdbcBulkCapacityInstallation(local.expected, owner, new DataSourceTransactionManager(owner),
                        "postgres", local.runtime, scope.provisioner, scope.reader, Duration.ofSeconds(20), Duration.ofSeconds(3));
                Future<?> fence = harness.executor.submit(installation::fence);
                harness.fences.add(fence);
                var edge = awaitEdge(local, applicationName, entered.keySet(), fence);
                long ownerPid = ((Number) edge.get("fence_pid")).longValue();
                long firstBlocker = ((Number) edge.get("blocking_pid")).longValue();
                var first = entered.get(firstBlocker);
                harness.edge(database, ownerPid, firstBlocker, physical.get(first), "FIRST_MARKER_BLOCKER_OBSERVED");
                harness.signal(first, "callback-release");
                var remainingBlockers = new HashSet<>(entered.keySet()); remainingBlockers.remove(firstBlocker);
                // Do not wait for the first child's receipt ACK/exit while the second unit still holds SHARE.
                var otherEdge = awaitEdge(local, applicationName, remainingBlockers, fence);
                assertThat(((Number) otherEdge.get("fence_pid")).longValue()).isEqualTo(ownerPid);
                long secondBlocker = ((Number) otherEdge.get("blocking_pid")).longValue();
                var second = entered.get(secondBlocker);
                harness.edge(database, ownerPid, secondBlocker, physical.get(second), "SECOND_MARKER_BLOCKER_OBSERVED_AFTER_FIRST_RELEASE");
                harness.signal(second, "callback-release");
                fence.get(5, TimeUnit.SECONDS);
                assertThat(local.observer.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)).isEqualTo("FENCED");
                for (var child : pair) {
                    var committed = harness.awaitEvent(child, "committed.json", Duration.ofSeconds(10));
                    assertThat(committed.path("receiptPresent").asBoolean()).isTrue();
                    assertThat(committed.path("nextOrdinal").asInt()).isEqualTo(1);
                    assertPhysicalCommit(child, physical.get(child), harness);
                }
                var beforeNegatives = mutableSnapshot(local);
                for (var child : pair) harness.signal(child, "after-fence");
                for (var child : pair) {
                    var result = harness.awaitEvent(child, "result.json", Duration.ofSeconds(15));
                    assertThat(result.path("phase").asText()).isEqualTo("ALL_ASSERTIONS_COMPLETE");
                    for (String reason : List.of("oldEpochReason", "unitOneReason", "claimReason", "enqueueReason"))
                        assertThat(result.path(reason).asText()).isEqualTo("FENCED");
                    assertThat(result.path("receiptReplayed").asBoolean()).isTrue();
                    assertThat(result.path("admissions").asInt()).isEqualTo(1);
                    assertThat(result.path("callbacks").asInt()).isEqualTo(1);
                    assertThat(child.process().waitFor(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(child.process().exitValue()).isZero();
                    assertThat(Files.size(child.directory().resolve("output.log"))).isZero();
                    assertPending(child);
                }
                assertThat(mutableSnapshot(local).equals(beforeNegatives)).as("fenced negatives and receipt replay preserve durable/domain rows").isTrue();
                assertThat(local.count("item_receipt")).isEqualTo(2);
                assertThat(local.count("admission")).isZero();
                assertThat(local.observer.queryForObject("select sum(writes) from process_domain_witness", Long.class)).isEqualTo(2);
                assertThat(local.observer.queryForObject("select count(*) from process_domain_witness where ordinal=1 and writes<>0", Long.class)).isZero();
                assertThat(local.kernel.find(local.context, extraQueued.get(database - 1)).orElseThrow().status())
                        .isEqualTo(BulkDurableExecutionStatus.QUEUED);
                assertImmutable(local, immutable.get(database - 1));
                BulkExecutionMigrator.validate(local.ownerSource, BulkPostgresTestSupport.testRoleConfiguration());
                local.assertionsComplete();
                harness.phase("PAIR_" + database + "_COMMIT_FENCE_NEGATIVES_REPLAY_AND_EXIT_CERTIFIED");
            }
            assertThat(callbackPids).hasSize(4);
            assertThat(authoritySnapshot(scope).equals(authorityBefore)).as("issued rights and attestations remain immutable").isTrue();
            harness.assertionsComplete = true;
        }
    }

    private record Child(BulkCapacityOccupancyPostgresFixture local, int database, int peer, UUID proposal,
                         UUID pending, UUID queue, UUID active, Path directory, Process process) {
        String actor() { return "db-" + (database == 1 ? "a" : "b") + "-peer-" + peer; }
    }

    private static Map<String, Object> primaryExecution(Child child) {
        var rows = child.local().observer.queryForList("select * from praxis_bulk.praxis_bulk_execution where proposal_id=?", child.proposal());
        assertThat(rows.size()).isEqualTo(1);
        return rows.getFirst();
    }

    private static void assertPending(Child child) {
        var rows = child.local().observer.queryForList("select kind,state,execution_id from praxis_bulk.praxis_bulk_allocation where proposal_id=?", child.pending());
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.getFirst()).containsEntry("kind", "PROPOSAL_PENDING").containsEntry("state", "PENDING").containsEntry("execution_id", null);
    }

    private static void assertUncommitted(Child child, JsonNode event, Harness harness) {
        var local = child.local();
        UUID execution = (UUID) primaryExecution(child).get("execution_id");
        var absent = local.observer.queryForMap("""
                select pg_backend_pid() as observer_pid,writes,last_pid,last_xid,
                       (select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=? and unit_ordinal=0) as receipts
                from process_domain_witness where actor=? and ordinal=0
                """, execution, child.peer());
        assertThat(absent).containsEntry("writes", 0).containsEntry("last_pid", null).containsEntry("last_xid", null).containsEntry("receipts", 0L);
        assertThat(((Number) absent.get("observer_pid")).longValue()).isNotEqualTo(event.path("callbackPid").asLong());
        var live = local.observer.queryForMap("select backend_xid::text::bigint as xid,state from pg_stat_activity where pid=?",
                event.path("callbackPid").asInt());
        assertThat(live).containsEntry("state", "idle in transaction").containsEntry("xid", event.path("callbackXid").asLong() & 0xffffffffL);
        assertThat(local.observer.queryForObject("select count(*) from pg_locks where pid=? and granted "
                + "and relation='praxis_bulk.praxis_bulk_capacity_marker'::regclass and mode='RowShareLock'", Long.class,
                event.path("callbackPid").asInt())).isPositive();
        harness.certificate(child, "UNCOMMITTED_DOMAIN_AND_RECEIPT_ABSENT", event,
                ((Number) absent.get("observer_pid")).longValue(), 0, 0);
    }

    private static void assertPhysicalCommit(Child child, JsonNode event, Harness harness) {
        UUID execution = (UUID) primaryExecution(child).get("execution_id");
        var committed = child.local().observer.queryForMap("""
                select pg_backend_pid() as observer_pid,d.writes,d.last_pid,d.last_xid,d.xmin::text::bigint as domain_xid,
                       r.xmin::text::bigint as receipt_xid,r.owner_epoch,r.outcome
                from process_domain_witness d join praxis_bulk.praxis_bulk_item_receipt r
                  on r.execution_id=? and r.unit_ordinal=0 where d.actor=? and d.ordinal=0
                """, execution, child.peer());
        long xid = event.path("callbackXid").asLong(), pid = event.path("callbackPid").asLong();
        assertThat(committed).containsEntry("writes", 1).containsEntry("last_pid", (int) pid).containsEntry("last_xid", xid)
                .containsEntry("domain_xid", xid & 0xffffffffL).containsEntry("receipt_xid", xid & 0xffffffffL)
                .containsEntry("owner_epoch", 2L).containsEntry("outcome", "CONFIRMED");
        assertThat(((Number) committed.get("observer_pid")).longValue()).isNotEqualTo(pid);
        harness.certificate(child, "DOMAIN_AND_RECEIPT_COMMITTED_IN_CALLBACK_TX", event,
                ((Number) committed.get("observer_pid")).longValue(), xid & 0xffffffffL, xid & 0xffffffffL);
    }

    private static Map<String, Object> awaitEdge(BulkCapacityOccupancyPostgresFixture local, String applicationName,
            Set<Long> blockers, Future<?> fence) throws Exception {
        assertThat(blockers).isNotEmpty();
        long stop = System.nanoTime() + Duration.ofMillis(700).toNanos();
        while (System.nanoTime() < stop) {
            if (fence.isDone()) { fence.get(); throw new AssertionError("Owner fence completed before the held callback released"); }
            var rows = local.observer.queryForList("""
                    select a.pid as fence_pid,b.pid as blocking_pid
                    from pg_stat_activity a cross join lateral unnest(pg_blocking_pids(a.pid)) b(pid)
                    where a.application_name=? and a.datname=current_database() and a.usename='postgres'
                      and a.wait_event_type='Lock'
                    """, applicationName);
            for (var row : rows) if (blockers.contains(((Number) row.get("blocking_pid")).longValue())) return row;
            Thread.sleep(10);
        }
        throw new AssertionError("Causal marker blocker was not observed inside the 700ms window");
    }

    private static Duration remaining(long stop, String purpose) {
        long nanos = stop - System.nanoTime();
        assertThat(nanos).as(purpose).isPositive();
        return Duration.ofNanos(nanos);
    }

    /** Full-row JSON text compares bytea by content; snapshots stay private and never enter evidence. */
    private static Map<String, List<String>> mutableSnapshot(BulkCapacityOccupancyPostgresFixture local) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("proposal", "execution", "allocation", "capacity_slot", "capacity_occupation", "item_receipt", "admission"))
            result.put(table, local.observer.queryForList(
                    "select to_jsonb(t)::text from praxis_bulk.praxis_bulk_" + table + " t order by 1", String.class));
        result.put("domain", local.observer.queryForList(
                "select to_jsonb(t)::text from (select *,xmin::text::bigint as row_xid from process_domain_witness) t order by 1",
                String.class));
        return result;
    }

    private static Map<String, List<String>> immutableLocal(BulkCapacityOccupancyPostgresFixture local) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("capacity_installation", "namespace_binding", "operation_control", "openapi_publication"))
            result.put(table, local.observer.queryForList(
                    "select to_jsonb(t)::text from praxis_bulk.praxis_bulk_" + table + " t order by 1", String.class));
        // Fencing changes only state; all physical binding/authority fields must remain exact.
        result.put("marker-binding", local.observer.queryForList(
                "select (to_jsonb(t)-'state')::text from praxis_bulk.praxis_bulk_capacity_marker t order by 1", String.class));
        return result;
    }

    private static void assertImmutable(BulkCapacityOccupancyPostgresFixture local, Map<String, List<String>> before) {
        assertThat(immutableLocal(local).equals(before)).as("installation, namespace and governed control stay unchanged").isTrue();
    }

    private static Map<String, List<String>> authoritySnapshot(BulkCapacityOccupancyPostgresFixture.SharedScope scope) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("authority_identity", "binding_attestation", "capacity_token"))
            result.put(table, scope.authorityObserver.queryForList(
                    "select to_jsonb(t)::text from praxis_bulk_capacity." + table + " t order by 1", String.class));
        return result;
    }

    private static void writePrivate(Path path, Properties values) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(path)) { values.store(output, "private runtime test fixture"); }
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /** Owns only subprocesses, synchronization files and sanitized evidence; PostgreSQL belongs to SharedScope. */
    private static final class Harness implements AutoCloseable {
        final List<Child> children = new ArrayList<>();
        final List<Future<?>> fences = new ArrayList<>();
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        final Path directory = Files.createTempDirectory("praxis-runtime-process-proof-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final ObjectNode manifest = JSON.createObjectNode().put("caseId", "two-databases-four-runtime-jvms")
                .put("harnessPid", ProcessHandle.current().pid()).put("subprocesses", 0).put("barriersUsed", false)
                .put("scope", "RUNTIME_DOMAIN_RECEIPT_MARKER_FENCING_NOT_GLOBAL_CAPS");
        final com.fasterxml.jackson.databind.node.ArrayNode events = manifest.putArray("events");
        private final Set<String> recordedEvents = new HashSet<>();
        boolean assertionsComplete;

        Harness() throws Exception { }

        void launch(BulkCapacityOccupancyPostgresFixture.SharedScope scope, BulkCapacityOccupancyPostgresFixture local,
                int database, int peer, UUID proposal, UUID pending, UUID queue, UUID active) throws Exception {
            String actor = "db-" + (database == 1 ? "a" : "b") + "-peer-" + peer;
            Path childDirectory = Files.createDirectory(directory.resolve(actor),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var values = new Properties();
            var expected = local.expected;
            var context = local.context;
            var operation = context.operationRef();
            values.setProperty("actor", actor);
            values.setProperty("logicalDatabase", "capacity_local_" + database);
            values.setProperty("witnessActor", Integer.toString(peer));
            values.setProperty("runtimeUrl", scope.postgres.getJdbcUrl("bulk_runtime_test", "capacity_local_" + database));
            values.setProperty("runtimeUser", "bulk_runtime_test"); values.setProperty("runtimePassword", "");
            values.setProperty("ownerRole", "postgres");
            values.setProperty("runtimeRoles", String.join(",", BulkPostgresTestSupport.testRoleConfiguration().runtimeGranteeRoles()));
            values.setProperty("namespace", context.namespaceId()); values.setProperty("subject", context.subjectId());
            values.setProperty("resource", context.resourceKey()); values.setProperty("revision", context.schemaRevision());
            values.setProperty("operationGroup", operation.group()); values.setProperty("operation", operation.operationId());
            values.setProperty("operationPath", operation.path()); values.setProperty("operationMethod", operation.method());
            values.setProperty("deployment", expected.deploymentId()); values.setProperty("tenant", expected.tenantId());
            values.setProperty("environment", expected.environment()); values.setProperty("binding", expected.bindingId());
            values.setProperty("bindingGeneration", Long.toString(expected.generation()));
            values.setProperty("databaseId", expected.databaseId().toString()); values.setProperty("attestationId", expected.attestationId().toString());
            values.setProperty("authorityId", expected.authorityId().toString()); values.setProperty("authorityEpoch", Long.toString(expected.authorityEpoch()));
            values.setProperty("proposalId", proposal.toString()); values.setProperty("pendingProposal", pending.toString());
            values.setProperty("queueToken", queue.toString()); values.setProperty("activeToken", active.toString());
            values.setProperty("idempotencyKey", "runtime-process-primary-" + peer);
            values.setProperty("deadline", Instant.now().plusSeconds(300).toString());
            values.setProperty("kernelHash", BulkCapacityOccupancyRuntimeProcess.kernelHash());
            values.setProperty("kernelCodeSource", JdbcBulkDurableExecution.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm());
            Path config = childDirectory.resolve("config.properties");
            writePrivate(config, values);
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Process process = new ProcessBuilder(java, "-cp", classpath, BulkCapacityOccupancyRuntimeProcess.class.getName(),
                    config.toString(), childDirectory.resolve("ready.json").toString(), childDirectory.toString(),
                    childDirectory.resolve("result.json").toString()).redirectErrorStream(true)
                    .redirectOutput(childDirectory.resolve("output.log").toFile()).start();
            children.add(new Child(local, database, peer, proposal, pending, queue, active, childDirectory, process));
        }

        List<Child> pair(int database) { return children.stream().filter(child -> child.database() == database).toList(); }

        void signal(Child child, String name) throws Exception {
            if (!Set.of("claim-start", "unit-start", "callback-release", "after-fence", "abort").contains(name))
                throw new IllegalArgumentException("Unknown fixed runtime proof signal");
            Files.writeString(child.directory().resolve(name), "go", StandardCharsets.UTF_8);
            if ("unit-start".equals(name)) manifest.put("barriersUsed", true);
            events.addObject().put("actor", child.actor()).put("logicalDatabase", "capacity_local_" + child.database())
                    .put("phase", name.toUpperCase(java.util.Locale.ROOT).replace('-', '_') + "_SIGNALLED")
                    .put("osPid", child.process().pid());
        }

        JsonNode awaitEvent(Child child, String name, Duration budget) throws Exception {
            Path file = child.directory().resolve(name);
            long stop = System.nanoTime() + budget.toNanos();
            while (!Files.exists(file)) {
                if (Files.exists(child.directory().resolve("result.json"))) recordEvent(child, "result.json");
                assertThat(child.process().isAlive()).as("runtime JVM stays alive until its fixed phase completes").isTrue();
                assertThat(System.nanoTime()).as("runtime JVM reached fixed phase within test envelope").isLessThan(stop);
                Thread.sleep(10);
            }
            return recordEvent(child, name);
        }

        private JsonNode recordEvent(Child child, String name) throws Exception {
            var event = JSON.readTree(Files.readString(child.directory().resolve(name)));
            assertThat(event.path("osPid").asLong()).isEqualTo(child.process().pid());
            assertThat(event.path("actor").asText()).isEqualTo(child.actor());
            assertThat(event.path("logicalDatabase").asText()).isEqualTo("capacity_local_" + child.database());
            Set<String> allowed = Set.of("actor", "logicalDatabase", "phase", "osPid", "readyBackendPid", "databaseOid",
                    "kernelClassSha256", "codeSourceMatchesParent", "epoch", "oldEpochReason", "admissions", "callbacks",
                    "callbackPid", "callbackXid", "ordinal", "remainingBudgetMillis", "receiptPresent", "nextOrdinal",
                    "unitOneReason", "claimReason", "enqueueReason", "receiptReplayed", "stage", "failureClass", "safeReason");
            event.fieldNames().forEachRemaining(field -> assertThat(allowed.contains(field)).as("sanitized child event field").isTrue());
            if (recordedEvents.add(child.actor() + ":" + name)) events.add(event);
            return event;
        }

        void phase(String phase) { events.addObject().put("phase", phase); }

        void edge(int database, long ownerPid, long blockerPid, JsonNode physical, String phase) {
            events.addObject().put("phase", phase).put("logicalDatabase", "capacity_local_" + database)
                    .put("ownerBackendPid", ownerPid).put("blockingBackendPid", blockerPid)
                    .put("callbackXid", physical.path("callbackXid").asLong());
        }

        void certificate(Child child, String phase, JsonNode physical, long observerPid, long domainXid, long receiptXid) {
            events.addObject().put("phase", phase).put("actor", child.actor()).put("logicalDatabase", "capacity_local_" + child.database())
                    .put("osPid", child.process().pid()).put("callbackPid", physical.path("callbackPid").asLong())
                    .put("callbackXid", physical.path("callbackXid").asLong()).put("observerPid", observerPid)
                    .put("domainXid", domainXid).put("receiptXid", receiptXid);
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            // Release active callbacks before any future wait, process join or executor shutdown.
            for (var child : children) if (child.process().isAlive()) {
                try { signal(child, "callback-release"); signal(child, "abort"); }
                catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
            }
            for (var fence : fences) {
                try { fence.get(5, TimeUnit.SECONDS); }
                catch (Exception | Error cleanup) { fence.cancel(true); failure = add(failure, cleanup); }
            }
            for (var child : children) {
                try {
                    if (!child.process().waitFor(3, TimeUnit.SECONDS)) {
                        child.process().destroy();
                        if (!child.process().waitFor(3, TimeUnit.SECONDS)) child.process().destroyForcibly();
                        if (!child.process().waitFor(3, TimeUnit.SECONDS)) throw new IllegalStateException("Runtime proof child remains alive");
                    }
                    if (Files.exists(child.directory().resolve("result.json"))) recordEvent(child, "result.json");
                } catch (Exception | Error cleanup) {
                    child.process().destroyForcibly(); failure = add(failure, cleanup);
                    try { child.process().waitFor(3, TimeUnit.SECONDS); }
                    catch (Exception | Error join) { failure = add(failure, join); }
                }
                var closed = events.addObject().put("phase", "RUNTIME_PROCESS_CLEANUP").put("actor", child.actor())
                        .put("logicalDatabase", "capacity_local_" + child.database()).put("osPid", child.process().pid())
                        .put("aliveAfterCleanup", child.process().isAlive());
                if (!child.process().isAlive()) closed.put("exitCode", child.process().exitValue());
            }
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Runtime proof fence executor remains alive");
            } catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
            manifest.put("subprocesses", children.size());
            manifest.put("privateConfigDirectoryRemoved", !Files.exists(directory));
            manifest.put("caseOutcome", assertionsComplete && failure == null ? "ASSERTIONS_COMPLETE" : "INCOMPLETE_OR_FAILED");
            try {
                String configured = System.getProperty("praxis.bulk.proof.directory");
                Path proofs = configured == null ? Files.createTempDirectory("praxis-runtime-process-manifest-") : Path.of(configured);
                Files.createDirectories(proofs);
                Path file = proofs.resolve("two-databases-four-runtime-jvms.json");
                Files.writeString(file, manifest.toPrettyString(), StandardCharsets.UTF_8);
                if (configured == null) System.out.println("Runtime process proof manifest: " + file.toAbsolutePath());
            } catch (Exception | Error cleanup) { failure = add(failure, cleanup); }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
        }

        private static Throwable add(Throwable original, Throwable next) {
            if (original == null) return next;
            if (original != next) original.addSuppressed(next);
            return original;
        }
    }
}
