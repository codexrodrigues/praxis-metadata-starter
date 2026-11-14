package org.praxisplatform.uischema.bulk;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BulkCatalogBatchTimeoutPostgresTest {
    @Test
    void tightOperatorTimeoutsArePreservedWithoutScanningTheCatalog() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
             var connection = postgres.getPostgresDatabase().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("set statement_timeout='100ms'");
            statement.execute("set lock_timeout='50ms'");
            connection.setAutoCommit(false);
            for (boolean independent : new boolean[]{false, true}) {
                try (var rows = statement.executeQuery(BulkCatalogPhase.LiveTimeout.prefix(independent))) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("100ms");
                    if (independent) {
                        assertThat(rows.getString(2)).isEqualTo("50ms");
                        assertThat(rows.getString(3)).isEqualTo("100ms");
                    } else {
                        assertThat(rows.getString(2)).isEqualTo("100ms");
                    }
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = statement.executeQuery("show lock_timeout")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("50ms");
                }
                assertThatThrownBy(() -> statement.execute("select pg_sleep(1)"))
                        .isInstanceOfSatisfying(SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("57014"));
                connection.rollback();
            }
        }
    }

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
