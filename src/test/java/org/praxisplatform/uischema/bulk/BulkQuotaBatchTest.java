package org.praxisplatform.uischema.bulk;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class BulkQuotaBatchTest {
 static final String HASH="sha256:"+"1".repeat(64);
 static class Fixture {
  final Connection c=mock(Connection.class);
  final PreparedStatement batch=mock(PreparedStatement.class);
  final ResultSet subject=mock(ResultSet.class),proposalRows=mock(ResultSet.class);
  final BulkStoredProposal proposal;
  final BulkExecutionInfrastructure infrastructure=mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
  Fixture()throws Exception {
   var snapshot=BulkSnapshotStorageCodecTest.snapshot(BulkMode.UNIFORM_UPDATE,BulkIdentityCodecs.strings(),"\"101\"","1");
   proposal=new BulkStoredProposal(UUID.randomUUID(),Instant.now(),Instant.now().plusSeconds(600),snapshot,new BulkOperationControlExpectation(1,HASH,"r1"));
   when(infrastructure.deploymentId()).thenReturn("deployment-test");
   var ns=mock(PreparedStatement.class);var control=ns;var deployment=ns;
   when(c.prepareStatement(anyString())).thenReturn(ns,batch);
   var n=mock(ResultSet.class);when(ns.executeQuery()).thenReturn(n);when(n.next()).thenReturn(true,false);when(n.getString(1)).thenReturn("deployment-test");
   var k=mock(ResultSet.class);when(control.executeQuery()).thenReturn(k);when(k.next()).thenReturn(true,false);when(k.getString(1)).thenReturn("READY");when(k.getLong(2)).thenReturn(1L);when(k.getString(3)).thenReturn(HASH);when(k.getString(4)).thenReturn("r1");
   var d=mock(ResultSet.class);when(deployment.executeQuery()).thenReturn(d);when(d.next()).thenReturn(true,false);when(d.getString(1)).thenReturn("deployment-test");
   when(ns.execute()).thenReturn(true);when(ns.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenReturn(true,true,false);when(ns.getUpdateCount()).thenReturn(-1);when(ns.getResultSet()).thenReturn(n,k,d);
   when(batch.execute()).thenReturn(false);when(batch.getUpdateCount()).thenReturn(1,1,-1);when(batch.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenReturn(true,true,false);
   when(batch.getResultSet()).thenReturn(subject,proposalRows);when(subject.next()).thenReturn(true,false);when(subject.getString(1)).thenReturn("deployment-test");
   var context=snapshot.context();when(proposalRows.next()).thenReturn(true,false);when(proposalRows.getString(1)).thenReturn(context.namespaceId());when(proposalRows.getString(2)).thenReturn(context.subjectId());when(proposalRows.getString(3)).thenReturn(context.resourceKey());when(proposalRows.getString(4)).thenReturn(context.operationRef().operationId());
  }
  BulkQuotaLedger.Scope run()throws Exception{return BulkQuotaLedger.lockProposal(c,infrastructure,proposal,true,false);}
  void corrupt()throws Exception{assertThatThrownBy(this::run).isInstanceOfSatisfying(BulkProposalStorageException.class,e->assertThat(e.reason()).isEqualTo(BulkProposalStorageException.Reason.CORRUPT));}
 }
 @Test void sameSubmissionRetainsSubjectAndProposalValidation()throws Exception{var f=new Fixture();assertThat(f.run().deploymentId()).isEqualTo("deployment-test");verify(f.batch).close();}
 @Test void missingSubjectCannotBeAccepted()throws Exception{var f=new Fixture();when(f.subject.next()).thenReturn(false);f.corrupt();}
 @Test void duplicateSubjectCannotBeAccepted()throws Exception{var f=new Fixture();when(f.subject.next()).thenReturn(true,true);f.corrupt();}
 @Test void mismatchedProposalScopeCannotBeAccepted()throws Exception{var f=new Fixture();when(f.proposalRows.getString(1)).thenReturn("another-namespace");f.corrupt();}
 @Test void missingRequiredProposalRemainsAConflict()throws Exception{var f=new Fixture();when(f.proposalRows.next()).thenReturn(false);assertThatThrownBy(f::run).isInstanceOfSatisfying(BulkProposalStorageException.class,e->assertThat(e.reason()).isEqualTo(BulkProposalStorageException.Reason.CONFLICT));}
 @Test void unexpectedResultsCannotBeIgnored()throws Exception{var f=new Fixture();when(f.batch.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenReturn(true,true,true);f.corrupt();}
}
