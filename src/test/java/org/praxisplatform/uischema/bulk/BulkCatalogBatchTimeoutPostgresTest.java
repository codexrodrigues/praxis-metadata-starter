package org.praxisplatform.uischema.bulk;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BulkCatalogBatchTimeoutPostgresTest {
 @Test void aFollowingStatementInTheSameSubmissionStillHasTheAttestationClamp() throws Exception {
  try(var postgres=EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();var c=postgres.getPostgresDatabase().getConnection()){
   for(String original:new String[]{"0","75ms"}){
    try(var s=c.createStatement()){s.execute("set statement_timeout='"+original+"'");}
    c.setAutoCommit(false);
    try(var s=c.prepareStatement(BulkCatalogPhase.LIVE_TIMEOUT_PREFIX+"select pg_sleep(0.6)")){
     assertThatThrownBy(s::execute).isInstanceOfSatisfying(java.sql.SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("57014"));
    }finally{c.rollback();c.setAutoCommit(true);}
    try(var s=c.createStatement();var rows=s.executeQuery("show statement_timeout")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo(original);}
   }
  }
 }
}
