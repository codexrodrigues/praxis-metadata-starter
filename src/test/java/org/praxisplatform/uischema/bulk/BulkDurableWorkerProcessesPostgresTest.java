package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.await;
import static org.praxisplatform.uischema.bulk.BulkDurableWorkerPostgresTest.completed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BulkDurableWorkerProcessesPostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test void twoRealJvmWorkersDispatchOneJobOnceAndRestartDoesNotRepeatIt() throws Exception { prove(false); }
    @Test void twoRealJvmWorkersWithSameMultiBindingRosterServeOtherTenantBeforeLongJobCompletes() throws Exception { prove(true); }

    private void prove(boolean fairness) throws Exception {
        var proofRoot = Path.of(System.getProperty("praxis.worker.proofRoot", "target/b5b2-worker-process-proofs"));
        Files.createDirectories(proofRoot);
        var directory = Files.createTempDirectory(proofRoot, "praxis-worker-processes-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var children = new ArrayList<Process>();
        try (var shared = new BulkCapacityOccupancyPostgresFixture.SharedScope()) {
            var a = shared.local(fairness ? "worker-process-fair-a" : "worker-process-once-a", 1);
            var b = shared.local(fairness ? "worker-process-fair-b" : "worker-process-once-b", 2);
            a.activate(); b.activate();
            var qa = a.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            a.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            b.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var ja = a.enqueue(a.persist(fairness ? 2 : 1), "worker-process-a", qa);
            BulkExecutionReservation jb = null;
            if (fairness) {
                var qb = b.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
                jb = b.enqueue(b.persist(1), "worker-process-b", qb);
            }
            var values = new Properties(); configure(values, "a.", a); configure(values, "b.", b);
            values.setProperty("a.execution", ja.executionId().toString());
            values.setProperty("fairness", Boolean.toString(fairness));
            if (jb != null) values.setProperty("b.execution", jb.executionId().toString());
            try {
            for (int i = 1; i <= 2; i++) children.add(start(directory, values, "peer-" + i));
            await(() -> Files.exists(directory.resolve("peer-1-ready.json")) && Files.exists(directory.resolve("peer-2-ready.json")));
            var ready1 = JSON.readTree(directory.resolve("peer-1-ready.json").toFile());
            var ready2 = JSON.readTree(directory.resolve("peer-2-ready.json").toFile());
            assertThat(ready1.path("pid").asLong()).isNotEqualTo(ready2.path("pid").asLong())
                    .isEqualTo(children.get(0).pid());
            assertThat(ready2.path("pid").asLong()).isEqualTo(children.get(1).pid());
            String source = JdbcBulkDurableExecution.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
            assertThat(ready1.path("kernelSource").asText()).isEqualTo(source);
            assertThat(ready2.path("kernelSource").asText()).isEqualTo(source);
            for (var ready : List.of(ready1, ready2)) {
                assertThat(ready.path("workerSource").asText()).isEqualTo(BulkDurableWorker.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm());
                assertThat(ready.path("processSource").asText()).isEqualTo(BulkDurableWorkerRuntimeProcess.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm());
                assertThat(ready.path("kernelHash").asText()).isEqualTo(BulkDurableWorkerRuntimeProcess.hash(JdbcBulkDurableExecution.class));
                assertThat(ready.path("workerHash").asText()).isEqualTo(BulkDurableWorkerRuntimeProcess.hash(BulkDurableWorker.class));
                assertThat(ready.path("processHash").asText()).isEqualTo(BulkDurableWorkerRuntimeProcess.hash(BulkDurableWorkerRuntimeProcess.class));
            }
            Files.writeString(directory.resolve("start"), "start actual control loops");
            if (!fairness) certifyClaimContention(directory, a, ja);
            else {
                await(() -> Files.exists(directory.resolve("a-entered")));
                var blockedA = a.kernel.find(a.context, ja.executionId()).orElseThrow();
                assertThat(blockedA.status()).isEqualTo(BulkDurableExecutionStatus.UNIT_IN_FLIGHT);
                assertThat(blockedA.nextOrdinal()).isZero(); assertThat(a.count("item_receipt")).isZero();
                Files.writeString(directory.resolve("b-allowed"), "A is durably in flight before B mutation");
                var observedB = jb;
                await(() -> completed(b, observedB.executionId()));
                assertThat(a.kernel.find(a.context, ja.executionId()).orElseThrow().status()).isEqualTo(BulkDurableExecutionStatus.UNIT_IN_FLIGHT);
                assertThat(a.count("item_receipt")).isZero();
                Files.writeString(directory.resolve("a-release"), "B committed while A still in flight");
            }
            var bJob = jb;
            await(() -> completed(a, ja.executionId()) && (bJob == null || completed(b, bJob.executionId())));
            Files.writeString(directory.resolve("stop"), "stop workers");
            for (Process child : children) { assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue(); assertThat(child.exitValue()).isZero(); }
            JsonNode r1 = JSON.readTree(directory.resolve("peer-1-result.json").toFile());
            JsonNode r2 = JSON.readTree(directory.resolve("peer-2-result.json").toFile());
            assertThat(r1.path("success").asBoolean()).isTrue(); assertThat(r2.path("success").asBoolean()).isTrue();
            assertThat(r1.path("callbacksA").asInt() + r2.path("callbacksA").asInt()).isEqualTo(fairness ? 2 : 1);
            assertThat(r1.path("callbacksB").asInt() + r2.path("callbacksB").asInt()).isEqualTo(fairness ? 1 : 0);
            assertThat(a.count("item_receipt")).isEqualTo(fairness ? 2 : 1);
            assertThat(b.count("item_receipt")).isEqualTo(fairness ? 1 : 0);
            assertThat(a.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isEqualTo(fairness ? 2 : 1);
            assertThat(b.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isEqualTo(fairness ? 1 : 0);
            for (var fixture : List.of(a, b)) {
                java.util.UUID executionId = fixture == a ? ja.executionId() : jb == null ? null : jb.executionId();
                var rows = executionId == null ? List.<java.util.Map<String,Object>>of() : fixture.observer.queryForList("""
                        select d.xmin::text as domain_xmin,r.xmin::text as receipt_xmin,d.writes,r.owner_epoch,
                               d.last_pid,d.last_xid,r.unit_ordinal
                        from occupancy_domain_witness d join praxis_bulk.praxis_bulk_item_receipt r
                        on r.unit_ordinal=d.id-1 where r.execution_id=? order by r.unit_ordinal
                        """, executionId);
                assertThat(rows).hasSize(fixture == a ? fairness ? 2 : 1 : fairness ? 1 : 0);
                for (int ordinal = 0; ordinal < rows.size(); ordinal++) {
                    var row = rows.get(ordinal);
                    assertThat(row).containsEntry("unit_ordinal", ordinal);
                    assertThat(((Number) row.get("last_pid")).intValue()).isPositive();
                    assertThat(Long.parseLong(row.get("domain_xmin").toString()))
                            .isEqualTo(((Number) row.get("last_xid")).longValue() & 0xffffffffL);
                    assertThat(row).containsEntry("receipt_xmin", row.get("domain_xmin"))
                        .containsEntry("writes", 1).containsEntry("owner_epoch", 2L);
                }
                Files.write(directory.resolve(fixture == a ? "domain-receipts-a.json" : "domain-receipts-b.json"), JSON.writeValueAsBytes(rows));
            }
            if (fairness) {
                assertThat(Files.exists(directory.resolve("b-committed"))).isTrue();
                var bTime = b.observer.queryForObject("select terminal_at from praxis_bulk.praxis_bulk_execution where execution_id=?",
                        java.time.OffsetDateTime.class, jb.executionId()).toInstant();
                var aFirst = a.observer.queryForObject("select confirmed_at from praxis_bulk.praxis_bulk_item_receipt where execution_id=? and unit_ordinal=0",
                        java.time.OffsetDateTime.class, ja.executionId()).toInstant();
                assertThat(bTime).isBeforeOrEqualTo(aFirst);
            } else {
                var restartDir = Files.createDirectory(directory.resolve("restart"),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Process restarted = start(restartDir, values, "restart"); children.add(restarted);
                await(() -> Files.exists(restartDir.resolve("restart-ready.json")));
                Files.writeString(restartDir.resolve("start"), "start new owner generation");
                await(() -> Files.exists(restartDir.resolve("restart-running.json")));
                var observed = JSON.readTree(restartDir.resolve("restart-running.json").toFile());
                assertThat(observed.path("selectionObservedA").asBoolean()).isTrue();
                assertThat(observed.path("selectionObservedB").asBoolean()).isTrue();
                assertThat(observed.path("terminalA").asText()).isEqualTo("COMPLETED");
                assertThat(a.kernel.workerNextQueued(null)).isEmpty();
                Files.writeString(restartDir.resolve("stop"), "stop restarted worker");
                assertThat(restarted.waitFor(20, TimeUnit.SECONDS)).isTrue(); assertThat(restarted.exitValue()).isZero();
                var result = JSON.readTree(restartDir.resolve("restart-result.json").toFile());
                assertThat(result.path("callbacksA").asInt()).isZero();
                assertThat(a.count("item_receipt")).isEqualTo(1);
            }
            a.assertionsComplete(); b.assertionsComplete();
            Files.write(directory.resolve("proof.json"), JSON.writeValueAsBytes(JSON.createObjectNode()
                    .put("success", true).put("fairnessBarrier", fairness).put("harnessPid", ProcessHandle.current().pid())
                    .put("workerPid1", children.get(0).pid()).put("workerPid2", children.get(1).pid())
                    .put("allWorkersExited", children.stream().noneMatch(Process::isAlive))
                    .put("restartPid", fairness ? 0 : children.get(2).pid()).put("completedAt", Instant.now().toString())));
            } finally { closeChildren(children); }
        } finally {
            closeChildren(children);
            Files.write(directory.resolve("cleanup.json"), JSON.writeValueAsBytes(JSON.createObjectNode()
                    .put("observedAllWorkersExited", children.stream().noneMatch(Process::isAlive))
                    .put("workerCount", children.size()).put("sharedScopeClosedBeforeThisMarker", true)));
        }
    }

    private static void closeChildren(List<Process> children) throws Exception {
        for (Process child : children) {
            if (child.isAlive()) { child.destroy(); if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly(); }
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void certifyClaimContention(Path directory, BulkCapacityOccupancyPostgresFixture fixture,
            BulkExecutionReservation queued) throws Exception {
        await(() -> Files.exists(directory.resolve("claim-peer-1.json")) && Files.exists(directory.resolve("claim-peer-2.json")));
        var first = JSON.readTree(directory.resolve("claim-peer-1.json").toFile());
        var second = JSON.readTree(directory.resolve("claim-peer-2.json").toFile());
        int pid1 = first.path("pid").asInt(); int pid2 = second.path("pid").asInt();
        assertThat(pid1).isNotEqualTo(pid2); assertThat(first.path("xid").asLong()).isNotEqualTo(second.path("xid").asLong());
        try (var nativeBarrier = fixture.ownerSource.getConnection()) {
            nativeBarrier.setAutoCommit(false);
            try {
                int parentPid;
                try (var statement = nativeBarrier.createStatement(); var rows = statement.executeQuery("select pg_backend_pid()")) {
                    assertThat(rows.next()).isTrue(); parentPid = rows.getInt(1);
                }
                try (var statement = nativeBarrier.prepareStatement("select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket where deployment_id=? for update")) {
                    statement.setString(1, BulkCapacityOccupancyPostgresFixture.DEPLOYMENT);
                    try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                Files.writeString(directory.resolve("allow-claim"), "both claim preflight phases observed; native gate is held");
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                List<java.util.Map<String,Object>> waiters = List.of();
                while (System.nanoTime() - deadline < 0 && waiters.size() != 2) {
                    waiters = fixture.observer.queryForList("""
                            with recursive roots as (
                                select unnest(array[?::integer,?::integer]) as pid
                            ), wait_chain(root_pid,pid,depth,path) as (
                                select pid,pid,0,array[pid] from roots
                                union all
                                select w.root_pid,b.pid,w.depth+1,w.path||b.pid
                                from wait_chain w cross join lateral unnest(pg_catalog.pg_blocking_pids(w.pid)) b(pid)
                                where w.depth<8 and not b.pid=any(w.path)
                            )
                            select a.pid,a.backend_xid::text as xid,a.wait_event_type,a.query,
                                (select jsonb_agg(jsonb_build_object('pid',w.pid,'depth',w.depth))::text
                                 from wait_chain w where w.root_pid=a.pid) as wait_graph_json
                            from pg_catalog.pg_stat_activity a
                            where a.pid in (select pid from roots) and a.wait_event_type='Lock'
                              and exists(select 1 from wait_chain w where w.root_pid=a.pid and w.pid=?)
                              and a.query='select praxis_bulk.claim_capacity_execution($1,$2,$3,$4,$5)'
                            order by a.pid
                            """, pid1, pid2, parentPid);
                    if (waiters.size() != 2) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
                assertThat(waiters).hasSize(2);
                for (var waiter : waiters) {
                    long expected = ((Number) waiter.get("pid")).intValue() == pid1
                            ? first.path("xid").asLong() : second.path("xid").asLong();
                    assertThat(waiter.get("xid")).isEqualTo(Long.toString(expected & 0xffffffffL));
                    assertThat(JSON.readTree(waiter.get("wait_graph_json").toString()).isArray()).isTrue();
                }
                var current = fixture.kernel.find(fixture.context, queued.executionId()).orElseThrow();
                assertThat(current.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
                assertThat(current.control().epoch()).isEqualTo(1);
                assertThat(fixture.count("item_receipt")).isZero();
                assertThat(fixture.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isZero();
                Files.write(directory.resolve("native-claim-contention.json"), JSON.writeValueAsBytes(waiters));
                nativeBarrier.commit();
            } finally { nativeBarrier.rollback(); }
        }
    }

    private static Process start(Path directory, Properties template, String actor) throws Exception {
        var values = new Properties(); values.putAll(template); values.setProperty("actor", actor);
        var config = Files.createFile(directory.resolve(actor + ".properties"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(config)) { values.store(output, "private trusted runtime proof"); }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java, "-cp", classpath, BulkDurableWorkerRuntimeProcess.class.getName(),
                config.toString(), directory.toString(), directory.resolve(actor + "-result.json").toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve(actor + ".log").toFile()).start();
    }
    private static void configure(Properties v, String p, BulkCapacityOccupancyPostgresFixture f) {
        var source = (DriverManagerDataSource) f.runtimeSource; var e = f.expected; var c = f.context;
        v.setProperty(p + "url", source.getUrl()); v.setProperty(p + "namespace", c.namespaceId());
        v.setProperty(p + "subject", c.subjectId()); v.setProperty(p + "resource", c.resourceKey());
        v.setProperty(p + "revision", c.schemaRevision()); v.setProperty(p + "group", c.operationRef().group());
        v.setProperty(p + "operation", c.operationRef().operationId()); v.setProperty(p + "path", c.operationRef().path());
        v.setProperty(p + "method", c.operationRef().method()); v.setProperty(p + "deployment", e.deploymentId());
        v.setProperty(p + "tenant", e.tenantId()); v.setProperty(p + "environment", e.environment());
        v.setProperty(p + "binding", e.bindingId()); v.setProperty(p + "generation", Long.toString(e.generation()));
        v.setProperty(p + "databaseId", e.databaseId().toString()); v.setProperty(p + "attestationId", e.attestationId().toString());
        v.setProperty(p + "authorityId", e.authorityId().toString()); v.setProperty(p + "authorityEpoch", Long.toString(e.authorityEpoch()));
    }
}
