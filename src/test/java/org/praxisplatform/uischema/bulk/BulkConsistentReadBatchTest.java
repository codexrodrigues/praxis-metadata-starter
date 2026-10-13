package org.praxisplatform.uischema.bulk;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real Spring RR/RO boundaries; JDBC doubles do not certify PG transport or catalog SQL. */
class BulkConsistentReadBatchTest {
    @Test void repeatedSnapshotsRemainReadOnlyAndAttestFreshWithExactLowerRestoration() throws Exception {
        try(var f=new Fixture()) {
            for(int i=0;i<2;i++) assertThat(f.infra.<String>withConsistentRead(c->{
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                return "ok";
            })).isEqualTo("ok");
            assertThat(f.attests).hasValue(2); assertThat(f.restored).isEqualTo("75ms");
            verify(f.connection,times(2)).commit(); verify(f.connection,never()).rollback();
        }
    }
    @Test void missingNullDuplicateAndWrongStateFailBeforeAttestationOrCallback() throws Exception {
        for(String fault:List.of("missing","duplicate","previous-null","lock-null","clamp-null","rc","rw","isolation-null","readonly-null")) {
            try(var f=new Fixture()) {
                f.fault=fault;
                assertThatThrownBy(()->f.infra.withConsistentRead(c->{throw new AssertionError("callback");})).isInstanceOf(IllegalStateException.class);
                assertThat(f.attests).hasValue(0);verify(f.connection).rollback();
            }
        }
    }
    @Test void namespaceNullWrongDuplicateMissingAndExtraRejectBeforeCallback() throws Exception {
        for(String fault:List.of("namespace-null","namespace-wrong","namespace-duplicate","tail-missing","tail-extra","tail-update")) {
            try(var f=new Fixture()) {
                f.fault=fault;
                assertThatThrownBy(()->f.infra.withConsistentRead(c->{throw new AssertionError("callback");})).isInstanceOf(IllegalStateException.class);
                verify(f.connection).rollback();
            }
        }
    }
    @Test void attestationPrimaryRetainsRestoreFailureAndNeverCallsWork() throws Exception {
        try(var f=new Fixture()) {
            var primary=new IllegalStateException("catalog"); f.primary=primary;f.restoreFailure=new SQLException("restore");
            assertThatThrownBy(()->f.infra.withConsistentRead(c->{throw new AssertionError("callback");})).isSameAs(primary);
            assertThat(primary.getSuppressed()).containsExactly(f.restoreFailure);verify(f.connection).rollback();
        }
    }
    @Test void expiryBeforeAndAfterCallbackRollsBackWithoutRenewal() throws Exception {
        try(var f=new Fixture()) {
            f.expireBefore=true;
            assertThatThrownBy(()->f.infra.withConsistentRead(c->{throw new AssertionError("callback");})).isInstanceOf(TransactionTimedOutException.class);
            verify(f.connection).rollback();
        }
        try(var f=new Fixture()) {
            var calls=new AtomicInteger();
            assertThatThrownBy(()->f.infra.withConsistentRead(c->{calls.incrementAndGet();f.expire();return "no";})).isInstanceOf(TransactionTimedOutException.class);
            assertThat(calls).hasValue(1);verify(f.connection).rollback();
        }
    }
    static final class Fixture implements AutoCloseable {
        final DataSource ds=mock(DataSource.class); final Connection connection=mock(Connection.class);
        final AtomicInteger attests=new AtomicInteger(); final BulkExecutionInfrastructure infra;
        final MockedStatic<BulkExecutionMigrator> migrator;
        String fault="",restored; boolean autoCommit=true,expireBefore; RuntimeException primary; SQLException restoreFailure;
        Fixture() throws Exception {
            when(ds.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenAnswer(c->autoCommit);
            doAnswer(c->{autoCommit=c.getArgument(0);return null;}).when(connection).setAutoCommit(anyBoolean());
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(connection.createStatement()).thenAnswer(c->{
                var st=mock(Statement.class);
                when(st.executeQuery(anyString())).thenAnswer(x->{
                    Object[] v={"75ms","20ms","75ms","repeatable read","on"};
                    switch(fault){case "previous-null"->v[0]=null;case "lock-null"->v[1]=null;case "clamp-null"->v[2]=null;case "rc"->v[3]="read committed";case "rw"->v[4]="off";case "isolation-null"->v[3]=null;case "readonly-null"->v[4]=null;}
                    return fault.equals("missing")?rows():fault.equals("duplicate")?rows(v,v):rows(v);
                });return st;
            });
            when(connection.prepareStatement(anyString())).thenAnswer(c->{
                String sql=c.getArgument(0);var st=mock(PreparedStatement.class);
                if(!sql.contains(";")){if(restoreFailure!=null)throw restoreFailure;return st;}
                doAnswer(x->{if((int)x.getArgument(0)==1)restored=x.getArgument(1);return null;}).when(st).setString(anyInt(),anyString());
                Object[] ns={fault.equals("namespace-null")?null:fault.equals("namespace-wrong")?"wrong":"dep"};
                var values=new ArrayList<ResultSet>();values.add(rows(new Object[]{"75ms"}));values.add(fault.equals("namespace-duplicate")?rows(ns,ns):rows(ns));
                if(fault.equals("tail-extra"))values.add(rows(new Object[]{1}));var i=new AtomicInteger();
                when(st.execute()).thenReturn(true);when(st.getResultSet()).thenAnswer(x->values.get(i.get()));
                when(st.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenAnswer(x->{int n=i.incrementAndGet();if(n==values.size()&&expireBefore)expire();return n<values.size()&&!fault.equals("tail-missing");});
                when(st.getUpdateCount()).thenReturn(fault.equals("tail-update")?0:-1);return st;
            });
            infra=new BulkExecutionInfrastructure(ds,new DataSourceTransactionManager(ds),"ns","dep",new BulkExecutionRoleConfiguration("owner",Set.of("runtime"),Set.of(),Set.of("control")));
            migrator=mockStatic(BulkExecutionMigrator.class,withSettings().mockMaker("mock-maker-inline"));
            migrator.when(()->BulkExecutionMigrator.validateLiveRuntimeRoleAccess(any(Connection.class),any(BulkExecutionRoleConfiguration.class))).thenAnswer(c->{attests.incrementAndGet();if(primary!=null)throw primary;return null;});
        }
        void expire(){((ConnectionHolder)TransactionSynchronizationManager.getResource(ds)).setTimeoutInMillis(-1);}
        public void close(){migrator.close();assertThat(TransactionSynchronizationManager.hasResource(ds)).isFalse();}
    }
    static ResultSet rows(Object[]... values) throws Exception {
        var r=mock(ResultSet.class);var i=new AtomicInteger(-1);
        when(r.next()).thenAnswer(c->i.incrementAndGet()<values.length);
        when(r.getString(anyInt())).thenAnswer(c->{var v=values[i.get()][(int)c.getArgument(0)-1];return v==null?null:v.toString();});return r;
    }
}
