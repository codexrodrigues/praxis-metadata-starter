package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Public admission/projection boundaries without a database; physical occupancy belongs to Neon. */
class BulkAsyncPublicAdmissionTest {
    static BulkOperationalProfile profile(int max) {
        return new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE), Set.of(BulkExecutionMode.ASYNC),
                Set.of(BulkSelectionMode.EXPLICIT), max, 65536, Duration.ofMinutes(15), Duration.ofSeconds(5));
    }
    static BulkCapacityBinding binding(long generation) {
        return new BulkCapacityBinding("deployment-test", "tenant-a", "test", "binding-a", generation,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"), 1);
    }
    @Test void asyncProfileCannotWidenSyncOrEnableOtherShapes() {
        assertThat(profile(10000).maxTargets()).isEqualTo(10000);
        assertThatThrownBy(() -> profile(10001)).isInstanceOf(IllegalArgumentException.class);
        for (var modes : List.of(Set.of(BulkExecutionMode.SYNC), Set.of(BulkExecutionMode.SYNC,BulkExecutionMode.ASYNC)))
            assertThatThrownBy(() -> new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE), modes,
                    Set.of(BulkSelectionMode.EXPLICIT), 201, 65536, Duration.ofMinutes(15), Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class);
        for (var mode : List.of(BulkMode.DOMAIN_COMMAND,BulkMode.PER_ITEM_UPDATE))
            assertThatThrownBy(() -> new BulkOperationalProfile(Set.of(mode), Set.of(BulkExecutionMode.ASYNC),
                    Set.of(BulkSelectionMode.EXPLICIT), 1, 65536, Duration.ofMinutes(15), Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE), Set.of(BulkExecutionMode.ASYNC),
                Set.of(BulkSelectionMode.QUERY), 1, 65536, Duration.ofMinutes(15), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void realStructuralCompositionBindsCapacityAndProjectsNoPrivateIdentity() throws Exception {
        try (var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var structure = BulkCrudOperationalCompositionTest.structures(context).stream()
                    .filter(s -> s.mode()==BulkMode.UNIFORM_UPDATE).findFirst().orElseThrow();
            var original = BulkCrudOperationalCompositionTest.provider(structure);
            var reads = new AtomicInteger();
            var provider = new BulkCrudOperationalCompositionTest.Provider(original.operationId,original.revision,
                    original.pointer,original.mode,original.runtime) {
                public BulkOperationalProfile profile() { return BulkAsyncPublicAdmissionTest.profile(10000); }
                public Optional<BulkCapacityBinding> asynchronousCapacityBinding() { reads.incrementAndGet(); return Optional.of(binding(1)); }
            };
            var first = BulkOperationalDescriptorComposer.compose(structure,provider);
            assertThat(reads).hasValue(1);
            var projection = BulkExecutionContract.from(first);
            assertThat(projection.executionModes()).containsExactly(BulkExecutionMode.ASYNC);
            assertThat(projection.limits().maxTargets()).isEqualTo(10000);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(projection))
                    .doesNotContain("databaseId", "authorityId", "binding-a", "attestationId");
            var originalBinding=binding(1);
            var components=BulkCapacityBinding.class.getRecordComponents();
            var constructor=BulkCapacityBinding.class.getDeclaredConstructors()[0];
            for(int index=0;index<components.length;index++) {
                Object[] args=new Object[components.length];
                for(int j=0;j<args.length;j++)args[j]=components[j].getAccessor().invoke(originalBinding);
                args[index]=args[index] instanceof String value ? value+"-changed"
                        : args[index] instanceof Long value ? value+1 : UUID.randomUUID();
                var changedBinding=(BulkCapacityBinding)constructor.newInstance(args);
                var changed = new BulkCrudOperationalCompositionTest.Provider(original.operationId,original.revision,
                        original.pointer,original.mode,original.runtime) {
                    public BulkOperationalProfile profile() { return BulkAsyncPublicAdmissionTest.profile(10000); }
                    public Optional<BulkCapacityBinding> asynchronousCapacityBinding() { return Optional.of(changedBinding); }
                };
                if(index==0) assertThatThrownBy(() -> BulkOperationalDescriptorComposer.compose(structure,changed))
                        .isInstanceOf(IllegalArgumentException.class);
                else assertThat(BulkOperationalDescriptorComposer.compose(structure,changed).descriptorFingerprint())
                        .as(components[index].getName()).isNotEqualTo(first.descriptorFingerprint());
            }
            var capturedType=Class.forName(BulkOperationLifecycle.class.getName()+"$CapturedProvider");
            var capturedConstructor=capturedType.getDeclaredConstructors()[0];capturedConstructor.setAccessible(true);
            var captured=(BulkOperationDescriptorProvider)capturedConstructor.newInstance(provider);
            assertThat(reads).hasValue(2);
            BulkOperationalDescriptorComposer.compose(structure,captured);
            BulkOperationalDescriptorComposer.compose(structure,captured);
            assertThat(reads).hasValue(2);
            assertThatThrownBy(() -> BulkOperationalDescriptorComposer.compose(structure,
                    new BulkCrudOperationalCompositionTest.Provider(original.operationId,original.revision,
                    original.pointer,original.mode,original.runtime) {
                        public BulkOperationalProfile profile() { return BulkAsyncPublicAdmissionTest.profile(10000); }
                    })).isInstanceOf(IllegalArgumentException.class);
            var sync = BulkOperationalDescriptorComposer.compose(structure,original);
            // Golden computed with composer + descriptor extracted from public rc164 (5879a9e),
            // compiled outside this candidate against this same structural fixture; no database.
            assertThat(sync.descriptorFingerprint()).isEqualTo(
                    "sha256:065a19d9afc064882fada2719f495affb2c0ea7d70f65f3504bf6c2e2dde187e");
            assertThat(sync.asynchronousCapacityBinding()).isEmpty();
            assertThat(BulkExecutionContract.from(sync).executionModes()).containsExactly(BulkExecutionMode.SYNC);
        }
    }
    @Test void mintedFactoryRejectsForeignScopeAndOverlongValidity() throws Exception {
        var f=new Input(); var admission=f.admission();
        var proposal=BulkStoredProposal.asynchronous(admission,UUID.randomUUID(),f.now,f.now.plusSeconds(900),f.snapshot);
        assertThat(proposal.controlExpectation()).isEqualTo(admission.expectation());
        assertThatThrownBy(() -> BulkStoredProposal.asynchronous(admission,UUID.randomUUID(),f.now,f.now.plusSeconds(901),f.snapshot))
                .isInstanceOf(IllegalArgumentException.class);
        var foreign=new BulkFingerprintContext("namespace-a","subject-a","other",f.scope.operationRef(),"r1",ActionCollectionAtomicity.PER_ITEM);
        assertThatThrownBy(() -> BulkStoredProposal.asynchronous(admission,UUID.randomUUID(),f.now,f.now.plusSeconds(30),snapshot(foreign)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkStoredProposal(UUID.randomUUID(),f.now,f.now.plusSeconds(30),f.snapshot,admission.expectation()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void captureRejectsMissingOrForeignInfrastructureBeforeAnyCallbackOrIo() throws Exception {
        var f=new Input(); var admission=f.admission();
        var proposal=BulkStoredProposal.asynchronous(admission,UUID.randomUUID(),f.now,f.now.plusSeconds(30),f.snapshot);
        var foreign=mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(foreign.namespace()).thenReturn(f.scope.namespaceId());
        var calls=new AtomicInteger();
        assertThatThrownBy(() -> new JdbcBulkProposalStore(foreign).captureAndInsertEvaluated(admission,proposal,
                (connection,budget)->{calls.incrementAndGet();throw new AssertionError();}, x->{throw new AssertionError();},()->Duration.ofSeconds(5)))
                .isInstanceOf(BulkProposalStorageException.class);
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> new JdbcBulkProposalStore(f.runtime).captureAndInsertEvaluated(proposal,
                (c,b)->{throw new AssertionError();},x->{throw new AssertionError();},()->Duration.ofSeconds(5)))
                .isInstanceOf(BulkProposalStorageException.class);
        verify(foreign,never()).withConnection(any());
    }
    @Test void durableProposalInsertBindsActualModeWithoutChangingProtocolVersion() throws Exception {
        var f=new Input(); var proposal=BulkStoredProposal.asynchronous(f.admission(),UUID.randomUUID(),f.now,f.now.plusSeconds(30),f.snapshot);
        var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        var method=JdbcBulkProposalStore.class.getDeclaredMethod("insertProposal",Connection.class,BulkStoredProposal.class);
        method.setAccessible(true);method.invoke(null,connection,proposal);
        verify(statement).setString(14,"ASYNC"); verify(statement).executeUpdate();
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("?, 2, ?").doesNotContain("'SYNC'");
    }
    @Test void queueSelectionIsBoundedAndNoSlotFailsClosedWithoutSlotWrite() throws Exception {
        var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(rows);
        assertThatThrownBy(() -> JdbcBulkCapacityOccupancy.availableQueueToken(connection))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,e->assertThat(e.reason()).isEqualTo(BulkDurableExecutionException.Reason.CAPACITY));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("order by i.token_ordinal,s.token_id limit 1").doesNotContain("for update", "update ");
        UUID token=UUID.randomUUID();when(rows.next()).thenReturn(true,false);when(rows.getObject(1,UUID.class)).thenReturn(token);
        assertThat(JdbcBulkCapacityOccupancy.availableQueueToken(connection)).isEqualTo(token);
    }
    @Test void publicReplayReturnsBeforeSuspendedReadinessAndFullQueueWithoutRenewingDeadline() throws Exception {
        var f=new Input(); var proposal=BulkStoredProposal.asynchronous(f.admission(),UUID.randomUUID(),f.now,f.now.plusSeconds(900),f.snapshot);
        var evaluation=BulkEvaluationSnapshotTest.evaluation(proposal);
        var connection=mock(Connection.class); var queries=new ArrayList<String>();
        var lifecycle=mock(BulkOperationLifecycle.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(lifecycle.requireReady(any(),any(),any())).thenThrow(new IllegalStateException("suspended"));
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(f.runtime.transactionManager()).thenReturn(manager);
        doAnswer(call -> ((org.springframework.jdbc.core.ConnectionCallback<?>)call.getArgument(0)).doInConnection(connection))
                .when(f.runtime).withConnection(any());
        var evaluationType=Class.forName(JdbcBulkDurableExecution.class.getName()+"$Evaluation");
        var ctor=evaluationType.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var protectedEvaluation=ctor.newInstance(proposal,evaluation,(short)2);
        var fingerprintMethod=JdbcBulkDurableExecution.class.getDeclaredMethod("reservationFingerprint",evaluationType,String.class);
        fingerprintMethod.setAccessible(true);
        String fingerprint=(String)fingerprintMethod.invoke(null,protectedEvaluation,"structural-r1");
        UUID executionId=UUID.randomUUID(); var persisted=new HashMap<Object,Object>();
        persisted.putAll(Map.of("execution_id",executionId,"proposal_id",proposal.id(),"namespace_id",f.scope.namespaceId(),
                "subject_id",f.scope.subjectId(),"resource_key",f.scope.resourceKey(),"operation_id",f.scope.operationRef().operationId(),
                "reservation_fingerprint",fingerprint,"input_fingerprint",f.snapshot.fingerprint(),"evaluation_fingerprint",evaluation.fingerprint(),"structural_revision","structural-r1"));
        persisted.putAll(Map.of("control_generation",1L,"control_descriptor_fingerprint",proposal.controlExpectation().descriptorFingerprint(),
                "owner_id","original-supervisor","owner_epoch",1L,"status","QUEUED","next_ordinal",0,"target_count",1,
                "deadline_at",f.now.plusSeconds(1800).atOffset(ZoneOffset.UTC),"atomicity","PER_ITEM","protocol_version",(short)2));
        persisted.put("execution_mode","ASYNC");persisted.put("queue_token_id",UUID.randomUUID());
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql=call.getArgument(0); queries.add(sql);var statement=mock(PreparedStatement.class);
            ResultSet result;
            if(sql.contains("join praxis_bulk.praxis_bulk_evaluation")) {
                var values=new HashMap<Object,Object>();
                values.putAll(Map.of(1,proposal.createdAt().atOffset(ZoneOffset.UTC),2,proposal.expiresAt().atOffset(ZoneOffset.UTC),
                        3,f.snapshot.fingerprint(),4,BulkSnapshotStorageCodec.encode(f.snapshot),5,1L,6,proposal.controlExpectation().descriptorFingerprint(),
                        7,"structural-r1",8,evaluation.fingerprint(),9,BulkEvaluationStorageCodec.encode(evaluation),10,"PER_ITEM"));
                values.put(11,(short)2);values.put(12,"ASYNC");result=one(values);
            } else if(sql.contains("select e.*")) result=one(persisted);
            else if(sql.contains("tombstone")) result=mock(ResultSet.class);
            else if(sql.contains("set_config")) result=mock(ResultSet.class);
            else throw new AssertionError("Replay attempted a new-admission query: "+sql);
            when(statement.executeQuery()).thenReturn(result);return statement;
        });
        var replay=new JdbcBulkDurableExecution(f.runtime,lifecycle,binding(1)).enqueue(f.scope,proposal.id(),"same-key",
                "another-supervisor","structural-r1",Instant.EPOCH);
        assertThat(replay.replayed()).isTrue();assertThat(replay.executionId()).isEqualTo(executionId);
        assertThat(replay.execution().deadlineAt()).isEqualTo(f.now.plusSeconds(1800));
        assertThat(replay.control().ownerId()).isEqualTo("original-supervisor");
        var foreignScope=new BulkFingerprintContext(f.scope.namespaceId(),f.scope.subjectId(),f.scope.resourceKey(),
                new CanonicalOperationRef("foreign",f.scope.operationRef().operationId(),"/foreign","POST"),
                f.scope.schemaRevision(),f.scope.atomicity());
        assertThatThrownBy(() -> new JdbcBulkDurableExecution(f.runtime,lifecycle,binding(1)).enqueue(foreignScope,
                proposal.id(),"same-key","another-supervisor","structural-r1",Instant.EPOCH))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,e->assertThat(e.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
        verifyNoInteractions(lifecycle);
        assertThat(queries).noneMatch(q -> q.contains("capacity_slot") || q.contains("capacity_marker") || q.contains("insert into"));
    }
    @Test void changedExpectationIsRejectedBeforeCaptureConnection() throws Exception {
        var f=new Input();var admission=f.admission();
        var changed=new BulkOperationControlExpectation(2,admission.expectation().descriptorFingerprint(),admission.expectation().structuralRevision());
        var proposal=BulkStoredProposal.asynchronous(UUID.randomUUID(),f.now,f.now.plusSeconds(30),f.snapshot,changed);
        assertThatThrownBy(() -> new JdbcBulkProposalStore(f.runtime).captureAndInsertEvaluated(admission,proposal,
                (c,b)->{throw new AssertionError();},x->{throw new AssertionError();},()->Duration.ofSeconds(5)))
                .isInstanceOf(BulkProposalStorageException.class);
        verify(f.runtime,never()).withConnection(any());
    }
    @Test void liveBindingDriftOrSuspensionAfterMintPreventsCaptureCallback() throws Exception {
        for(boolean suspended:List.of(false,true)) {
            var f=new Input();var admission=f.admission();
            var proposal=BulkStoredProposal.asynchronous(admission,UUID.randomUUID(),f.now,f.now.plusSeconds(30),f.snapshot);
            var connection=mock(Connection.class);var queries=new ArrayList<String>();
            doAnswer(call -> ((org.springframework.jdbc.core.ConnectionCallback<?>)call.getArgument(0)).doInConnection(connection))
                    .when(f.runtime).withConnection(any());
            var plain=mock(Statement.class);when(connection.createStatement()).thenReturn(plain);
            when(plain.executeQuery(anyString())).thenAnswer(call -> {
                String sql=call.getArgument(0);
                return sql.contains("transaction_isolation") ? one(Map.of(1,"repeatable read",2,"off"))
                        : one(Map.of(1,"0",2,"0",3,f.now.atOffset(ZoneOffset.UTC)));
            });
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql=call.getArgument(0);queries.add(sql);var statement=mock(PreparedStatement.class);
                ResultSet result;
                if(sql.contains("lock_capacity_marker"))result=one(Map.of(1,suspended ? "SUSPENDED" : "ACTIVE"));
                else if(sql.contains("select m.database_id")) {
                    var b=binding(suspended ? 1 : 2);
                    result=one(Map.of(1,b.databaseId(),2,b.deploymentId(),3,b.tenantId(),4,b.environment(),5,b.bindingId(),
                            6,b.generation(),7,b.attestationId(),8,b.authorityId(),9,b.authorityEpoch(),10,suspended ? "SUSPENDED" : "ACTIVE"));
                } else if(sql.contains("set_config"))result=one(Map.of(1,"100ms",2,"100ms",3,f.now.atOffset(ZoneOffset.UTC)));
                else throw new AssertionError("Binding drift reached quota/capture SQL");
                when(statement.executeQuery()).thenReturn(result);return statement;
            });
            when(f.runtime.deploymentId()).thenReturn(binding(1).deploymentId());
            var calls=new AtomicInteger();
            assertThatThrownBy(() -> new JdbcBulkProposalStore(f.runtime).captureAndInsertEvaluated(admission,proposal,
                    (c,b)->{calls.incrementAndGet();throw new AssertionError();},x->{throw new AssertionError();},()->Duration.ofSeconds(5)))
                    .isInstanceOf(BulkProposalStorageException.class);
            assertThat(calls).hasValue(0);
            assertThat(queries.stream().filter(q->q.contains("lock_capacity_marker")||q.contains("select m.database_id")))
                    .hasSize(2);
        }
    }
    static ResultSet one(Map<?,?> values) throws Exception {
        var rows=mock(ResultSet.class, invocation -> {
            if(invocation.getMethod().getName().startsWith("get") && invocation.getArguments().length>0) {
                Object value=values.get(invocation.getArgument(0));
                if(value!=null)return value;
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        when(rows.next()).thenReturn(true,false);return rows;
    }
    static BulkIntentSnapshot snapshot(BulkFingerprintContext scope) {
        return BulkIntentSnapshot.uniform(scope,BulkIdentityCodecs.strings(),new BulkProtocolReader<>(BulkIdentityCodecs.strings())
                .readUniform(BulkSnapshotStorageCodecTest.bytes("{\"executionMode\":\"ASYNC\",\"selection\":{\"mode\":\"EXPLICIT\",\"targets\":[{\"id\":\"101\",\"expectedVersion\":\"v1\"}]},\"changes\":[{\"field\":\"amount\",\"operator\":\"SET\",\"value\":1.0}]}")),JsonNode::deepCopy);
    }
    static class Input {
        final Instant now=Instant.parse("2026-09-13T12:00:00Z");
        final BulkFingerprintContext scope=new BulkFingerprintContext("namespace-a","subject-a","items",new CanonicalOperationRef("test","items.bulk","/items/bulk","POST"),"r1",ActionCollectionAtomicity.PER_ITEM);
        final BulkIntentSnapshot snapshot=snapshot(scope);
        final BulkExecutionInfrastructure runtime=mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        Input(){when(runtime.namespace()).thenReturn(scope.namespaceId());}
        BulkOperationLifecycle.ReadyAdmission admission() throws Exception {
            // Constructor remains private to the lifecycle. This unit fixture cannot mint authority in a consumer.
            var constructor=BulkOperationLifecycle.ReadyAdmission.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
            return (BulkOperationLifecycle.ReadyAdmission)constructor.newInstance(new BulkOperationControlIdentity(scope.namespaceId(),scope.operationRef().operationId()),
                    BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION,BulkMode.UNIFORM_UPDATE,ActionCollectionAtomicity.PER_ITEM,
                    BulkExecutionMode.ASYNC,BulkSelectionMode.EXPLICIT,profile(10000),runtime,scope.resourceKey(),scope.operationRef(),Optional.of(binding(1)),snapshot.codecId());
        }
    }
}
