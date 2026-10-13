package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.praxisplatform.uischema.consumer.BulkAsyncPublicAdmissionWorkerProcess;
import org.springframework.context.SmartLifecycle;

/** Public component proof only: no embedded database, host HTTP or global-capacity claim. */
@EnabledIfEnvironmentVariable(named="PRAXIS_BULK_ASYNC_NEON_FIXTURE_CREDENTIALS_FILE", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class BulkAsyncPublicAdmissionNeonTest {
    @RegisterExtension final FailFast failFast = new FailFast();
    private BulkAsyncPublicAdmissionNeonFixture f;

    /** JUnit result includes body and lifecycle failures; later phases cannot mutate shared state. */
    static final class FailFast implements BeforeEachCallback, TestWatcher {
        private boolean blocked;
        @Override public void beforeEach(ExtensionContext context) {
            Assumptions.assumeFalse(blocked, "PREVIOUS_PHASE_FAILED");
        }
        @Override public void testFailed(ExtensionContext context, Throwable cause) { blocked = true; }
        @Override public void testAborted(ExtensionContext context, Throwable cause) { blocked = true; }
    }
    @BeforeAll void setup() throws Exception { f=new BulkAsyncPublicAdmissionNeonFixture(); }
    @AfterAll void cleanup() throws Exception { if(f!=null)f.close(); }

    @Test @Order(1)
    void publicCapturePersistsEvaluationWithoutDomainEffects() throws Exception {
        var before=f.snapshot();var value=f.consumer.capture(1);
        assertThat(f.snapshot().get("domain")).isEqualTo(before.get("domain"));
        assertThat(f.observer.queryForObject("select execution_mode from praxis_bulk.praxis_bulk_proposal where proposal_id=?",String.class,value.proposal().id())).isEqualTo("ASYNC");
        for(String table:List.of("proposal","evaluation","target_manifest","preview_state","target_preview","preview_item_integrity","allocation"))
            assertThat(f.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_"+table+" where proposal_id=?",Long.class,value.proposal().id())).as(table).isEqualTo(1);
        assertThat(f.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution where proposal_id=?",Long.class,value.proposal().id())).isZero();
        f.phase("CAPTURE");
    }

    @Test @Order(2)
    void confirmationCommitsOneQueuedExecutionAndReplaysSameKey() throws Exception {
        var evaluated=f.consumer.capture(2);var before=f.snapshot().get("domain");
        String key="public-confirmation-"+f.output.getFileName();
        String differentKey="different-key-"+f.output.getFileName();
        Instant deadline=Instant.now().plusSeconds(60);
        var first=f.consumer.confirm(evaluated,key,deadline);
        var replay=f.consumer.confirm(evaluated,key,deadline);
        assertThat(first.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
        assertThat(replay.replayed()).isTrue();assertThat(replay.executionId()).isEqualTo(first.executionId());
        var beforeDifferentKey=f.snapshot();
        var anotherKey=f.consumer.confirm(evaluated,differentKey,deadline);
        assertThat(anotherKey.replayed()).isTrue();assertThat(anotherKey.executionId()).isEqualTo(first.executionId());
        assertThat(anotherKey.status()).isEqualTo(first.status());
        assertThat(anotherKey.execution().deadlineAt()).isEqualTo(first.execution().deadlineAt());
        assertThat(f.snapshot()).isEqualTo(beforeDifferentKey);
        assertThat(f.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution where proposal_id=?",Long.class,evaluated.proposal().id())).isEqualTo(1);
        assertThat(f.count("item_receipt",first.executionId())).isZero();assertThat(f.count("admission",first.executionId())).isZero();
        assertThat(f.snapshot().get("domain")).isEqualTo(before);
        assertThat(f.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_slot where current_execution_id=? and capacity_class='QUEUE'",Long.class,first.executionId())).isEqualTo(1);
        f.phase("ENQUEUE");
        var callbacks=new AtomicInteger();var worker=f.consumer.worker(new AtomicInteger(),callbacks,new AtomicInteger(),false);
        try{worker.start();await(()->terminal(first.executionId()));}finally{stop(worker);}
        requireStatus(first.executionId(),"COMPLETED");
        assertThat(callbacks).hasValue(1);assertPhysicalCommit(first.executionId(),2);
        // Capture after completion: the negative setup does not consume the original 60s deadline.
        var otherProposal=f.consumer.capture(12);var beforeConflict=f.snapshot();
        assertThatThrownBy(()->f.consumer.confirm(otherProposal,key,Instant.now().plusSeconds(60)))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error->assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CONFLICT));
        assertThat(f.snapshot()).isEqualTo(beforeConflict);
    }

    @Test @Order(3)
    void twoJvmWorkersCommitOnceAndCompletedJobSurvivesRestartWithNewWork() throws Exception {
        var evaluated=f.consumer.capture(3);var queued=f.consumer.confirm(evaluated,"two-public-workers",Instant.now().plusSeconds(60));
        Path directory=Files.createDirectory(f.output.resolve("workers"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path credentials=directory.resolve("runtime.private.properties");
        Files.createFile(credentials,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try(var writer=Files.newBufferedWriter(credentials)){f.workerInput.store(writer,"Runtime-only assigned child");}
        var children=new ArrayList<Process>();
        Throwable primary=null;
        try{
            children.add(start(credentials,directory,"peer-1"));children.add(start(credentials,directory,"peer-2"));
            await(()->Files.exists(directory.resolve("peer-1-ready"))&&Files.exists(directory.resolve("peer-2-ready")));
            assertThat(children.get(0).pid()).isNotEqualTo(children.get(1).pid());
            Files.writeString(directory.resolve("start"),"release owned workers");
            await(()->terminal(queued.executionId()));Files.writeString(directory.resolve("stop"),"stop owned workers");
            requireStatus(queued.executionId(),"COMPLETED");
            for(Process p:children){assertThat(p.waitFor(25,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();}
            int calls=0;
            for(String peer:List.of("peer-1","peer-2")){
                var result=BulkAsyncPublicAdmissionNeonFixture.JSON.readTree(directory.resolve(peer+"-result.private.json").toFile());
                assertThat(result.path("workerStopped").asBoolean()).isTrue();calls+=result.path("mutations").asInt();
            }
            assertThat(calls).isEqualTo(1);assertPhysicalCommit(queued.executionId(),3);
            var before=f.jobSnapshot(queued.executionId());
            var fresh=f.consumer.capture(11);var next=f.consumer.confirm(fresh,"restart-new-public-work",Instant.now().plusSeconds(60));
            Path restart=Files.createDirectory(directory.resolve("restart"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var third=start(credentials,restart,"restart");children.add(third);
            await(()->Files.exists(restart.resolve("restart-ready")));Files.writeString(restart.resolve("start"),"observe completed queue");
            await(()->terminal(next.executionId()));
            requireStatus(next.executionId(),"COMPLETED");
            assertPhysicalCommit(next.executionId(),11);
            var replay=f.consumer.confirm(evaluated,"two-public-workers",queued.execution().deadlineAt());
            assertThat(replay.replayed()).isTrue();assertThat(replay.executionId()).isEqualTo(queued.executionId());
            Files.writeString(restart.resolve("stop"),"stop restarted observer");
            assertThat(third.waitFor(25,TimeUnit.SECONDS)).isTrue();assertThat(third.exitValue()).isZero();
            var result=BulkAsyncPublicAdmissionNeonFixture.JSON.readTree(restart.resolve("restart-result.private.json").toFile());
            assertThat(result.path("mutations").asInt()).isEqualTo(1);assertThat(result.path("admissions").asInt()).isEqualTo(1);
            assertThat(f.jobSnapshot(queued.executionId())).isEqualTo(before);f.phase("TWO_WORKERS_REPLAY");
        }catch(Exception|Error failure){primary=failure;throw failure;
        }finally{
            try{closeChildren(children);}catch(Exception|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
    }

    @Test @Order(4)
    void publicAdmissionRejectsMissingReadyOrCapacityBeforeCallbacks() throws Exception {
        var captured=f.consumer.capture(4);Instant deadline=Instant.now().plusSeconds(60);
        var held=f.consumer.confirm(captured,"capacity-holder",deadline);
        var another=f.consumer.capture(5);var before=f.snapshot();var authority=f.authoritySnapshot();
        assertThatThrownBy(()->f.consumer.confirm(another,"full-queue",deadline)).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error->assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CAPACITY));
        assertThat(f.snapshot()).isEqualTo(before);
        assertThat(f.authoritySnapshot()).isEqualTo(authority);
        var fullReplay=f.consumer.confirm(captured,"capacity-holder",deadline);
        assertThat(fullReplay.replayed()).isTrue();assertThat(fullReplay.executionId()).isEqualTo(held.executionId());
        var worker=f.consumer.worker(new AtomicInteger(),new AtomicInteger(),new AtomicInteger(),false);
        try{worker.start();await(()->terminal(held.executionId()));}finally{stop(worker);}
        var identity=f.consumer.identity();f.lifecycle.suspend(identity,1);
        var suspended=f.snapshot();
        assertThatThrownBy(()->f.consumer.capture(6)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.consumer.confirm(another,"suspended",deadline)).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error->assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
        var replay=f.consumer.confirm(captured,"capacity-holder",deadline);
        assertThat(replay.replayed()).isTrue();assertThat(replay.executionId()).isEqualTo(held.executionId());
        assertThat(f.snapshot()).isEqualTo(suspended);
        f.lifecycle.publish(identity,2);
        f.phase("ADMISSION_NEGATIVES");
    }

    @Test @Order(5)
    void revokedCurrentGrantStopsNewUnitAndMutationFailureRollsBack() throws Exception {
        var evaluated=f.consumer.capture(7,8);var queued=f.consumer.confirm(evaluated,"current-grant",Instant.now().plusSeconds(60));
        var committed=new CountDownLatch(1);var release=new CountDownLatch(1);
        var calls=new AtomicInteger();var worker=f.consumer.worker(new AtomicInteger(),calls,new AtomicInteger(),false,()->{
            committed.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Revocation barrier expired");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        });
        try{
            worker.start();assertThat(committed.await(30,TimeUnit.SECONDS)).isTrue();
            assertThat(f.count("item_receipt",queued.executionId())).isEqualTo(1);
            assertThat(f.observer.update("update public.async_admission_grant set allowed=false,revision=2 where subject=? and revision=1",BulkAsyncPublicAdmissionNeonFixture.SUBJECT)).isEqualTo(1);
            release.countDown();await(()->terminal(queued.executionId()));
        }finally{release.countDown();stop(worker);}
        assertThat(calls).hasValue(1);assertPhysicalCommit(queued.executionId(),7);
        requireStatus(queued.executionId(),"STOPPED");
        assertThat(f.observer.queryForObject("select writes from public.async_admission_domain where id=8",Integer.class)).isZero();
        assertThat(f.observer.queryForObject("select terminal_reason_code from praxis_bulk.praxis_bulk_execution where execution_id=?",String.class,queued.executionId())).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(f.observer.update("update public.async_admission_grant set allowed=true,revision=3 where subject=? and revision=2",BulkAsyncPublicAdmissionNeonFixture.SUBJECT)).isEqualTo(1);
        var failing=f.consumer.capture(9);var failure=f.consumer.confirm(failing,"rollback-after-write",Instant.now().plusSeconds(60));
        var before=f.snapshot();var authority=f.authoritySnapshot();var writes=new AtomicInteger();var throwing=f.consumer.worker(new AtomicInteger(),writes,new AtomicInteger(),true);
        try{throwing.start();await(()->terminal(failure.executionId()));}finally{stop(throwing);}
        assertThat(writes).hasValue(1);
        requireStatus(failure.executionId(),"STOPPED");
        assertThat(f.observer.queryForObject("select terminal_reason_code from praxis_bulk.praxis_bulk_execution where execution_id=?",String.class,failure.executionId())).isEqualTo("UNIT_ROLLED_BACK");
        assertThat(f.snapshot().get("domain")).isEqualTo(before.get("domain"));
        assertThat(f.count("item_receipt",failure.executionId())).isZero();assertThat(f.count("admission",failure.executionId())).isZero();
        assertThat(f.authoritySnapshot()).isEqualTo(authority);
        for(String table:List.of("proposal","evaluation","target_manifest","preview_state","target_preview","preview_item_integrity","grant"))
            assertThat(f.snapshot().get(table)).as(table).isEqualTo(before.get(table));
        f.phase("CURRENT_GRANT_ROLLBACK");
    }

    @Test @Order(6)
    void queuedDeadlineIncludesWaitAndStopsWithoutDomainCallback() throws Exception {
        var evaluated=f.consumer.capture(10);Instant deadline=Instant.now().plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var queued=f.consumer.confirm(evaluated,"expired-in-queue",deadline);var before=f.snapshot().get("domain");
        await(()->Instant.now().isAfter(deadline));
        var calls=new AtomicInteger();var worker=f.consumer.worker(new AtomicInteger(),calls,new AtomicInteger(),false);
        try{worker.start();await(()->terminal(queued.executionId()));}finally{stop(worker);}
        assertThat(calls).hasValue(0);assertThat(f.count("item_receipt",queued.executionId())).isZero();
        requireStatus(queued.executionId(),"STOPPED");
        assertThat(f.count("admission",queued.executionId())).isZero();assertThat(f.snapshot().get("domain")).isEqualTo(before);
        assertThat(f.observer.queryForObject("select terminal_reason_code from praxis_bulk.praxis_bulk_execution where execution_id=?",String.class,queued.executionId())).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(f.observer.queryForObject("select deadline_at from praxis_bulk.praxis_bulk_execution where execution_id=?",java.time.OffsetDateTime.class,queued.executionId()).toInstant())
                .isEqualTo(deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(f.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_slot where current_execution_id=?",Long.class,queued.executionId())).isZero();
        f.phase("QUEUE_DEADLINE");
    }

    private boolean terminal(UUID execution){String state=f.observer.queryForObject("select status from praxis_bulk.praxis_bulk_execution where execution_id=?",String.class,execution);return List.of("COMPLETED","STOPPED","CANCELLED").contains(state);}
    private void requireStatus(UUID execution,String expected){assertThat(f.observer.queryForObject("select status from praxis_bulk.praxis_bulk_execution where execution_id=?",String.class,execution)).isEqualTo(expected);}
    private void assertPhysicalCommit(UUID execution,int target){
        var row=f.observer.queryForMap("select d.writes,d.last_pid,d.last_xid,d.xmin::text as domain_xmin,r.xmin::text as receipt_xmin from public.async_admission_domain d cross join praxis_bulk.praxis_bulk_item_receipt r join praxis_bulk.praxis_bulk_execution e on e.execution_id=r.execution_id join praxis_bulk.praxis_bulk_target_manifest m on m.proposal_id=e.proposal_id and m.ordinal=r.unit_ordinal and m.target_digest=r.target_digest where d.id=? and r.execution_id=? and r.unit_ordinal=0",target,execution);
        assertThat(row.get("writes")).isEqualTo(1);assertThat(row.get("domain_xmin")).isEqualTo(row.get("receipt_xmin"));
        assertThat(Long.parseLong(row.get("domain_xmin").toString())).isEqualTo(((Number)row.get("last_xid")).longValue()&0xffffffffL);
        assertThat(((Number)row.get("last_pid")).intValue()).isPositive();
    }
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!condition.getAsBoolean()){if(System.nanoTime()>=deadline)throw new IllegalStateException("Proof observation deadline");Thread.sleep(25);}}
    private static void stop(SmartLifecycle worker)throws Exception{var done=new CountDownLatch(1);worker.stop(done::countDown);assertThat(done.await(20,TimeUnit.SECONDS)).isTrue();assertThat(worker.isRunning()).isFalse();}
    private static Process start(Path input,Path output,String peer)throws Exception{
        Path log=output.resolve(peer+".private.log");Files.createFile(log,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        String cp=Files.readString(Path.of(System.getenv("PRAXIS_BULK_ASYNC_WORKER_CLASSPATH_FILE"))).strip();
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,
                BulkAsyncPublicAdmissionWorkerProcess.class.getName(),input.toString(),output.toString(),peer);
        builder.environment().clear();builder.environment().put("LANG","C.UTF-8");
        return builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void closeChildren(List<Process> children)throws Exception{
        Throwable failed=null;
        for(Process p:children)try{
            if(p.isAlive()){p.destroy();if(!p.waitFor(5,TimeUnit.SECONDS))p.destroyForcibly();}
            if(!p.waitFor(5,TimeUnit.SECONDS)||p.isAlive())throw new IllegalStateException("Owned worker still alive");
        }catch(Exception|Error cleanup){if(failed==null)failed=cleanup;else failed.addSuppressed(cleanup);}
        if(failed instanceof Exception e)throw e;if(failed instanceof Error e)throw e;
    }
}
