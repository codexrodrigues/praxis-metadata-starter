package org.praxisplatform.uischema.bulk;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import jakarta.persistence.*;
import org.springframework.orm.jpa.*;
import org.springframework.jdbc.datasource.ConnectionHandle;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Test void separateSetPrecedesClampAndUsesOnlyTheOriginalHolderTimeout() throws Exception {
        try(var f=new Fixture()) {
            assertThat(f.infra.<String>withConsistentRead(c->{f.events.add("callback");return "ok";})).isEqualTo("ok");
            assertThat(f.events).containsExactly("set","clamp","attest","callback");
            assertThat(f.setTimeouts).isNotEmpty().allSatisfy(value->assertThat(value).isBetween(1,3));
            assertThat(f.restored).isEqualTo("75ms");
        }
    }
    @Test void setSqlRuntimeErrorAndCloseFailuresRejectBeforeClampAndPreservePrimary() throws Exception {
        for(Throwable failure:List.of(new SQLException("private","25001"),new IllegalStateException("private"),new AssertionError("private"))) {
            try(var f=new Fixture()) {
                f.setFailure=failure;f.setCloseFailure=new SQLException("private close");
                Throwable thrown=catchThrowable(()->f.infra.withConsistentRead(c->{throw new AssertionError("callback must not run");}));
                if(failure instanceof SQLException) assertThat(thrown).hasRootCause(failure);
                else assertThat(thrown).isSameAs(failure);
                assertThat(failure.getSuppressed()).containsExactly(f.setCloseFailure);
                assertThat(f.events).containsExactly("set");assertThat(f.attests).hasValue(0);verify(f.connection).rollback();
            }
        }
        try(var f=new Fixture()) {
            f.setCloseFailure=new SQLException("close");
            assertThatThrownBy(()->f.infra.withConsistentRead(c->{throw new AssertionError("callback must not run");})).hasRootCause(f.setCloseFailure);
            assertThat(f.events).containsExactly("set");verify(f.connection).rollback();
        }
    }
    @Test void expiredEntryOrSetRemainderNeverReachesClampAndNeverRenews() throws Exception {
        try(var f=new Fixture()) {
            when(f.connection.getAutoCommit()).thenAnswer(c->{
                if(TransactionSynchronizationManager.hasResource(f.ds))f.expire();return false;
            });
            assertThatThrownBy(()->f.infra.withConsistentRead(c->{throw new AssertionError("callback must not run");})).isInstanceOf(TransactionTimedOutException.class);
            assertThat(f.events).isEmpty();verify(f.connection,never()).createStatement();verify(f.connection).rollback();
        }
        try(var f=new Fixture()) {
            f.expireDuringSet=true;
            assertThatThrownBy(()->f.infra.withConsistentRead(c->{throw new AssertionError("callback must not run");})).isInstanceOf(TransactionTimedOutException.class);
            assertThat(f.events).containsExactly("set");verify(f.connection).rollback();
        }
    }
    @Test void jdbcAmbientHolderFlagsAndDeadlineResumeAfterOwnedSuccessOrFailure() throws Exception {
        for(boolean readonly:List.of(false,true))for(boolean failing:List.of(false,true))try(var f=new Fixture()) {
            Connection outer=connection();when(f.ds.getConnection()).thenReturn(outer,f.connection);
            var template=new TransactionTemplate(new DataSourceTransactionManager(f.ds));
            template.setReadOnly(readonly);template.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);template.setTimeout(20);
            assertAmbient(f,template,outer,readonly,failing,null);
            verify(outer).commit();verify(outer,never()).rollback();
        }
    }
    @Test void jpaAmbientEntityManagerAndHolderResumeWithoutChangingCallerTransaction() throws Exception {
        for(boolean readonly:List.of(false,true))for(boolean failing:List.of(false,true))try(var f=new Fixture()) {
            Connection outer=connection();EntityManager first=mock(EntityManager.class),second=mock(EntityManager.class);
            EntityTransaction firstTx=entityTransaction(),secondTx=entityTransaction();
            when(first.getTransaction()).thenReturn(firstTx);when(second.getTransaction()).thenReturn(secondTx);
            when(first.isOpen()).thenReturn(true);when(second.isOpen()).thenReturn(true);
            var emf=mock(EntityManagerFactory.class,withSettings().extraInterfaces(EntityManagerFactoryInfo.class));
            var info=(EntityManagerFactoryInfo)emf;when(info.getDataSource()).thenReturn(f.ds);
            when(info.createNativeEntityManager(anyMap())).thenReturn(first,second);
            when(emf.createEntityManager()).thenReturn(first,second);
            var manager=new JpaTransactionManager();manager.setEntityManagerFactory(emf);manager.setDataSource(f.ds);
            manager.setJpaDialect(new DefaultJpaDialect(){
                public Object beginTransaction(EntityManager em,TransactionDefinition definition)throws SQLException {
                    Connection c=em==first?outer:f.connection;
                    Integer isolation=DataSourceUtils.prepareConnectionForTransaction(c,definition);
                    c.setAutoCommit(false);em.getTransaction().begin();return new Object[]{c,isolation,definition.isReadOnly()};
                }
                public ConnectionHandle getJdbcConnection(EntityManager em,boolean ro){return new SimpleConnectionHandle(em==first?outer:f.connection);}
                public void cleanupTransaction(Object data){var v=(Object[])data;DataSourceUtils.resetConnectionAfterTransaction((Connection)v[0],(Integer)v[1],(Boolean)v[2]);}
            });
            var infra=new BulkExecutionInfrastructure(f.ds,manager,"ns","dep",new BulkExecutionRoleConfiguration("owner",Set.of("runtime"),Set.of(),Set.of("control")));
            var template=new TransactionTemplate(manager);template.setReadOnly(readonly);template.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);template.setTimeout(20);
            assertAmbient(f,template,outer,readonly,failing,new Object[]{infra,emf});
            verify(firstTx).commit();verify(firstTx,never()).rollback();verify(secondTx,failing?times(1):never()).rollback();
            assertThat(TransactionSynchronizationManager.hasResource(emf)).isFalse();
        }
    }
    private static void assertAmbient(Fixture f,TransactionTemplate template,Connection outer,boolean readonly,boolean failing,Object[] jpa){
        var infra=jpa==null?f.infra:(BulkExecutionInfrastructure)jpa[0];
        template.execute(status->{
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(f.ds);Object deadline=holder.getDeadline();
            Object entity=jpa==null?null:TransactionSynchronizationManager.getResource(jpa[1]);
            var original=new IllegalStateException("set");if(failing)f.setFailure=original;
            if(failing)assertThatThrownBy(()->infra.withConsistentRead(c->{throw new AssertionError("callback must not run");})).isSameAs(original);
            else assertThat(infra.<String>withConsistentRead(c->{
                assertThat(((ConnectionHolder)TransactionSynchronizationManager.getResource(f.ds)).getConnection()).isSameAs(f.connection);
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();return "ok";
            })).isEqualTo("ok");
            assertThat(TransactionSynchronizationManager.getResource(f.ds)).isSameAs(holder);
            assertThat(holder.getConnection()).isSameAs(outer);assertThat(holder.getDeadline()).isEqualTo(deadline);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isEqualTo(readonly);
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(status.isRollbackOnly()).isFalse();if(jpa!=null)assertThat(TransactionSynchronizationManager.getResource(jpa[1])).isSameAs(entity);
            return null;
        });
        assertThat(TransactionSynchronizationManager.hasResource(f.ds)).isFalse();
    }
    private static EntityTransaction entityTransaction() {
        var tx=mock(EntityTransaction.class);var active=new java.util.concurrent.atomic.AtomicBoolean();
        when(tx.isActive()).thenAnswer(c->active.get());
        doAnswer(c->{active.set(true);return null;}).when(tx).begin();
        doAnswer(c->{active.set(false);return null;}).when(tx).commit();
        doAnswer(c->{active.set(false);return null;}).when(tx).rollback();
        return tx;
    }
    private static Connection connection()throws Exception {
        var c=mock(Connection.class);var auto=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(c.getAutoCommit()).thenAnswer(x->auto.get());doAnswer(x->{auto.set(x.getArgument(0));return null;}).when(c).setAutoCommit(anyBoolean());
        when(c.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);return c;
    }
    static final class Fixture implements AutoCloseable {
        final DataSource ds=mock(DataSource.class); final Connection connection=mock(Connection.class);
        final AtomicInteger attests=new AtomicInteger(); final BulkExecutionInfrastructure infra;
        final MockedStatic<BulkExecutionMigrator> migrator;
        String fault="",restored; boolean autoCommit=true,expireBefore,expireDuringSet; RuntimeException primary; SQLException restoreFailure;
        Throwable setFailure,setCloseFailure;final List<String> events=new ArrayList<>();final List<Integer> setTimeouts=new ArrayList<>();
        Fixture() throws Exception {
            when(ds.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenAnswer(c->autoCommit);
            doAnswer(c->{autoCommit=c.getArgument(0);return null;}).when(connection).setAutoCommit(anyBoolean());
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(connection.createStatement()).thenAnswer(c->{
                var st=mock(Statement.class);var setup=new java.util.concurrent.atomic.AtomicBoolean();
                doAnswer(x->{setTimeouts.add(x.getArgument(0));return null;}).when(st).setQueryTimeout(anyInt());
                when(st.executeUpdate("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")).thenAnswer(x->{
                    setup.set(true);events.add("set");if(setFailure!=null)throw setFailure;if(expireDuringSet)expire();return 0;
                });
                doAnswer(x->{if(setup.get() && setCloseFailure!=null)throw setCloseFailure;return null;}).when(st).close();
                when(st.executeQuery(anyString())).thenAnswer(x->{
                    events.add("clamp");Object[] v={"75ms","20ms","75ms","repeatable read","on"};
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
            migrator.when(()->BulkExecutionMigrator.validateLiveRuntimeRoleAccess(any(Connection.class),any(BulkExecutionRoleConfiguration.class))).thenAnswer(c->{events.add("attest");attests.incrementAndGet();if(primary!=null)throw primary;return null;});
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
