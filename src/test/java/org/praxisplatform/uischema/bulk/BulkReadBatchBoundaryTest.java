package org.praxisplatform.uischema.bulk;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.*;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.*;
import java.sql.*;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.junit.jupiter.api.Test;

/** JDBC result ordering and real protected/page decoders, without a database. */
class BulkReadBatchBoundaryTest {
    @Test void absentLocatorNeverIssuesEvaluationEvenIfItsQueryWouldFail() throws Exception {
        var c=mock(Connection.class);var q=batch(rows(new Object[]{"200ms"}),rows());
        when(c.prepareStatement(anyString())).thenAnswer(x->{if(x.getArgument(0,String.class).contains("praxis_bulk_evaluation"))throw new SQLException("must not query absent evaluation");return q;});
        var actual=protectedRead(c,UUID.randomUUID());
        assertThat(actual.kind()).isEqualTo(BulkProtectedProposalReader.Kind.ABSENT);
        verify(c,times(1)).prepareStatement(anyString());verify(q).close();
    }
    @Test void absentProposalRetainsAuthorizedTombstoneWhenEvaluationWouldThrow() throws Exception {
        try(var f=new BulkConsistentReadBatchTest.Fixture()) {
            var c=f.connection;var q=batch(rows(new Object[]{"200ms"}),rows());
            var tomb=mock(PreparedStatement.class);when(tomb.executeQuery()).thenAnswer(x->rows(new Object[]{"STOPPED"}));
            when(c.prepareStatement(argThat(BulkReadBatchBoundaryTest::proposalReadSql))).thenAnswer(x->{String sql=x.getArgument(0);if(sql.contains("praxis_bulk_evaluation"))throw new SQLException("evaluation must not run");return sql.contains("praxis_bulk_tombstone")?tomb:q;});
            var infra=infrastructure(f.ds);var auth=mock(BulkReadAuthorizationProvider.class);var projection=mock(BulkProposalProjectionProvider.class);
            var reader=authorizedReader(infra,auth,projection);
            assertThat(reader.readProposal(CONTEXT.subjectId(),UUID.randomUUID()).state()).isEqualTo(BulkAuthorizedProposalReader.State.TOMBSTONED);
            verify(auth,never()).authorize(any(),anyString(),anyList(),any());verify(projection,never()).projectRedactedIntent(any());
            verify(tomb).setString(4,BulkScopeDigests.authorizationScopeDigest(CONTEXT.namespaceId(),CONTEXT.subjectId(),CONTEXT.resourceKey(),CONTEXT.operationRef().operationId()));
            assertThat(f.attests).hasValue(1);verify(c).commit();
        }
    }
    @Test void expiredProposalIsGoneOnlyAfterCompleteAuthorizationAndNeverProjects() throws Exception {
        try(var f=new BulkConsistentReadBatchTest.Fixture()) {
            var p=proposal();var e=evaluation(p);var c=f.connection;var q=batch(rows(new Object[]{"200ms"}),proposalRows(p));
            var ev=mock(PreparedStatement.class);when(ev.executeQuery()).thenAnswer(x->rows(new Object[]{p.snapshot().fingerprint(),e.fingerprint(),BulkEvaluationStorageCodec.encode(e)}));when(c.prepareStatement(argThat(BulkReadBatchBoundaryTest::proposalReadSql))).thenReturn(q,ev);
            var infra=infrastructure(f.ds);var auth=mock(BulkReadAuthorizationProvider.class);var projection=mock(BulkProposalProjectionProvider.class);
            var reader=authorizedReader(infra,auth,projection);
            when(auth.authorize(any(),anyString(),anyList(),any())).thenReturn(BulkReadAuthorizationProvider.ScopeDecision.authorized(new byte[32]));
            assertThat(reader.readProposal(CONTEXT.subjectId(),p.id()).state()).isEqualTo(BulkAuthorizedProposalReader.State.GONE);
            verify(auth).authorize(any(),eq(CONTEXT.subjectId()),argThat(x->x.size()==1),any());verify(projection,never()).projectRedactedIntent(any());
            assertThat(f.attests).hasValue(1);verify(c).commit();
        }
    }
    private static BulkAuthorizedProposalReader authorizedReader(BulkExecutionInfrastructure infra,BulkReadAuthorizationProvider auth,BulkProposalProjectionProvider projection) throws Exception {
        when(auth.executionInfrastructure()).thenReturn(infra);when(auth.confirmationOperationId()).thenReturn(CONTEXT.operationRef().operationId());when(auth.preAuthorize(any(),any())).thenReturn(BulkReadAuthorizationProvider.GlobalDecision.ALLOWED);
        when(projection.resourceKey()).thenReturn(CONTEXT.resourceKey());when(projection.confirmationOperationId()).thenReturn(CONTEXT.operationRef().operationId());when(projection.projectionRevision()).thenReturn("r");
        return new BulkAuthorizedProposalReader(infra,CONTEXT.resourceKey(),auth,projection,java.time.Clock.fixed(java.time.Instant.parse("2026-10-13T00:00:00Z"),ZoneOffset.UTC));
    }
    @Test void validProtectedPairRetainsBothFingerprintsAndClosesLocatorBeforeEvaluation() throws Exception {
        var p=proposal();var e=evaluation(p);var c=mock(Connection.class);var q=batch(rows(new Object[]{"200ms"}),proposalRows(p));
        var ev=mock(PreparedStatement.class);when(ev.executeQuery()).thenAnswer(x->rows(new Object[]{p.snapshot().fingerprint(),e.fingerprint(),BulkEvaluationStorageCodec.encode(e)}));
        when(c.prepareStatement(anyString())).thenReturn(q,ev);
        var actual=protectedRead(c,p.id());
        assertThat(actual.proposal().snapshot().fingerprint()).isEqualTo(p.snapshot().fingerprint());assertThat(actual.evaluation().fingerprint()).isEqualTo(e.fingerprint());
        var order=inOrder(q,c,ev);order.verify(c).prepareStatement(anyString());order.verify(q).close();order.verify(c).prepareStatement(anyString());order.verify(ev).executeQuery();
    }
    @Test void corruptProposalAndLocatorCloseFailureNeverIssueEvaluation() throws Exception {
        for(boolean closing:List.of(false,true)) {
            var p=proposal();var c=mock(Connection.class);var r=proposalRows(p);
            if(!closing)doReturn(new byte[]{1}).when(r).getBytes(4);
            var q=batch(rows(new Object[]{"200ms"}),r);when(c.prepareStatement(anyString())).thenReturn(q);
            if(closing)doThrow(new SQLException("close")).when(q).close();
            assertThatThrownBy(()->protectedRead(c,p.id())).isInstanceOf(closing?SQLException.class:BulkProposalStorageException.class);
            verify(c,times(1)).prepareStatement(anyString());
        }
    }
    @Test void missingNullDuplicateAndExtraBatchResultsFailClosedWithCleanup() throws Exception {
        for(int fault=0;fault<5;fault++) {
            var c=mock(Connection.class);var clamp=fault==1?rows(new Object[]{null}):fault==2?rows(new Object[]{"1ms"},new Object[]{"1ms"}):rows(new Object[]{"1ms"});
            var q=fault==3?batch(clamp,rows(),rows()):batch(clamp,rows());
            if(fault==0)when(q.execute()).thenReturn(false);if(fault==4)when(q.getUpdateCount()).thenReturn(0);
            when(c.prepareStatement(anyString())).thenReturn(q);
            assertThatThrownBy(()->protectedRead(c,UUID.randomUUID())).isInstanceOf(BulkProposalStorageException.class);verify(q).close();
        }
    }
    @Test void absentAndNoncompletePreviewNeverIssueSuffixOrPageButKeepDependentChecks() throws Exception {
        for(String kind:List.of("ABSENT","NOT_EVALUATED","UNAVAILABLE","UNAVAILABLE_LEGACY")) {
            var c=mock(Connection.class);var h=kind.equals("ABSENT")?rows():kind.equals("NOT_EVALUATED")?rows(new Object[]{"input",null,null,null,null,null,null,null,null,null}):rows(new Object[]{"input","input","eval","eval",kind,11,null,null,null,null});
            var q=batch(rows(new Object[]{"100ms"}),rows(new Object[]{true}),h);var dep=mock(PreparedStatement.class);when(dep.executeQuery()).thenAnswer(x->rows());
            when(c.prepareStatement(anyString())).thenAnswer(x->{String sql=x.getArgument(0);if(sql.contains("set_config"))return q;assertThat(sql).doesNotContain("ordinal>=", "limit ?");return dep;});
            assertThat(preview(c).read(c,CONTEXT,UUID.randomUUID(),-1,1,1,100).kind().name()).isEqualTo(kind);
            verify(dep,times(kind.equals("ABSENT")?0:kind.equals("NOT_EVALUATED")?3:2)).executeQuery();
        }
    }
    @Test void gateNullAndCorruptHeaderRejectWithoutSuffixOrPage() throws Exception {
        for(boolean gate:List.of(false,true)) {
            var c=mock(Connection.class);var q=batch(rows(new Object[]{"100ms"}),rows(new Object[]{gate?true:null}),rows(new Object[]{"input","different","eval","eval","COMPLETE",11,"r",1,new byte[]{1},"sha256:"+"0".repeat(64)}));
            when(c.prepareStatement(anyString())).thenReturn(q);
            assertThatThrownBy(()->preview(c).read(c,CONTEXT,UUID.randomUUID(),-1,1,1,100)).isInstanceOf(BulkProposalStorageException.class);
            verify(c,times(1)).prepareStatement(anyString());
        }
    }
    @Test void completePageKeepsStreamingAndRealDigestVerification() throws Exception {
        try(var f=new PageFixture(false)) {
            var page=preview(f.c).read(f.c,CONTEXT,f.id,-1,1,1,100);
            assertThat(page.kind()).isEqualTo(BulkPreviewPageReader.Kind.COMPLETE);assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().wireIdentity()).isEqualTo("id");
            verify(f.page).setFetchSize(1);verify(f.page).setInt(4,2);verify(f.suffix).executeQuery();
        }
    }
    @Test void pageByteBudgetRejectsBeforeFetchingSecondPayloadRow() throws Exception {
        try(var f=new PageFixture(true)) {
            assertThatThrownBy(()->preview(f.c).read(f.c,CONTEXT,f.id,-1,1,1,100)).isInstanceOf(BulkProposalStorageException.class).satisfies(x->assertThat(((BulkProposalStorageException)x).reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE));
            verify(f.payload,times(1)).next();verify(f.page).setFetchSize(1);verify(f.payload).close();
        }
    }
    private static BulkProtectedProposalReader.Observation protectedRead(Connection c,UUID id)throws Exception{return BulkProtectedProposalReader.readForAuthorizedComposition(c,CONTEXT.namespaceId(),CONTEXT.resourceKey(),CONTEXT.operationRef().operationId(),id,200);}
    private static BulkPreviewPageReader preview(Connection c) {
        var ds=mock(DataSource.class);var reader=new BulkPreviewPageReader(infrastructure(ds));
        verifyNoInteractions(ds);return reader;
    }
    private static BulkExecutionInfrastructure infrastructure(DataSource ds) {
        return new BulkExecutionInfrastructure(ds,new DataSourceTransactionManager(ds),CONTEXT.namespaceId(),"dep",
                new BulkExecutionRoleConfiguration("owner",Set.of("runtime"),Set.of(),Set.of("control")));
    }
    private static boolean proposalReadSql(String sql) {
        return sql!=null&&(sql.contains("praxis_bulk_proposal")||sql.contains("praxis_bulk_evaluation")
                ||sql.contains("praxis_bulk_tombstone"));
    }
    private static ResultSet proposalRows(BulkStoredProposal p)throws Exception {
        var r=rows(new Object[]{p.createdAt().atOffset(ZoneOffset.UTC),p.expiresAt().atOffset(ZoneOffset.UTC),p.snapshot().fingerprint(),BulkSnapshotStorageCodec.encode(p.snapshot()),1L,CONTROL_EXPECTATION.descriptorFingerprint(),CONTROL_EXPECTATION.structuralRevision()});
        when(r.getString("subject_id")).thenReturn(CONTEXT.subjectId());when(r.getString("execution_mode")).thenReturn("SYNC");when(r.getString("atomicity")).thenReturn("ATOMIC");when(r.getShort("protocol_version")).thenReturn((short)2);return r;
    }
    static PreparedStatement batch(ResultSet... rs)throws Exception {
        var q=mock(PreparedStatement.class);var index=new AtomicInteger();when(q.execute()).thenReturn(true);when(q.getResultSet()).thenAnswer(x->rs[index.get()]);
        when(q.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenAnswer(x->index.incrementAndGet()<rs.length);when(q.getUpdateCount()).thenReturn(-1);return q;
    }
    static ResultSet rows(Object[]... values)throws Exception {
        var r=mock(ResultSet.class);var index=new AtomicInteger(-1);var nil=new boolean[1];when(r.next()).thenAnswer(x->index.incrementAndGet()<values.length);
        org.mockito.stubbing.Answer<Object> value=x->{Object v=values[index.get()][(int)x.getArgument(0)-1];nil[0]=v==null;return v;};
        when(r.getString(anyInt())).thenAnswer(x->{Object v=value.answer(x);return v==null?null:v.toString();});
        when(r.getBytes(anyInt())).thenAnswer(value);when(r.getInt(anyInt())).thenAnswer(x->{Object v=value.answer(x);return v==null?0:((Number)v).intValue();});
        when(r.getObject(anyInt(),any(Class.class))).thenAnswer(value);when(r.wasNull()).thenAnswer(x->nil[0]);return r;
    }
    static final class PageFixture implements AutoCloseable {
        final Connection c=mock(Connection.class);final PreparedStatement suffix=mock(PreparedStatement.class),page=mock(PreparedStatement.class);final UUID id=UUID.randomUUID();final ResultSet payload;
        PageFixture(boolean oversized)throws Exception {
            String fp="sha256:"+"1".repeat(64),projection="sha256:"+"2".repeat(64),target="sha256:"+"3".repeat(64);byte[] allow=bytes("[]"),wire=bytes("\"id\""),version=bytes("\"v1\""),diag=bytes("[]");
            String wireDigest=BulkTargetDigest.wireIdentity(wire);byte[] parent=BulkPreviewItemIntegrity.contextDigest(id,fp,"r",1,allow,projection);
            String item=BulkPreviewItemIntegrity.itemDigest(parent,0,wire,wireDigest,version,target,"EXECUTABLE",diag);
            var header=rows(new Object[]{"input","input",fp,fp,"COMPLETE",11,"r",1,allow,projection});
            var metadata=batch(rows(new Object[]{"100ms"}),rows(new Object[]{true}),header);when(suffix.executeQuery()).thenAnswer(x->rows());
            payload=rows(new Object[]{0,fp,1,oversized?new byte[21*1024*1024]:wire,wireDigest,version,target,fp,"EXECUTABLE",diag,1,item});when(page.executeQuery()).thenReturn(payload);
            when(c.prepareStatement(anyString())).thenReturn(metadata,suffix,page);
        }
        public void close(){}
    }
}
