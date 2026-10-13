package org.praxisplatform.uischema.bulk;

import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.praxisplatform.uischema.bulk.BulkCapacityAuthorityCatalog.ReadBatch;

/** No database. Real collectors/validators and JDBC protocol usage, not a wire-latency/ACL proof. */
class BulkCapacityAuthorityReadBatchTest {
    private static final String OWNER="owner";
    private static final BulkCapacityAuthorityMigrator.RoleConfiguration ROLES =
            new BulkCapacityAuthorityMigrator.RoleConfiguration(OWNER,"provisioner","allocator","reader");

    @Test void oneExecuteBindsInOrderAndConsumesAndClosesEveryResult() throws Exception {
        var batch=new ReadBatch();var observed=new ArrayList<String>();
        batch.add("select ?",r->{assertThat(r.next()).isTrue();observed.add(r.getString(1));assertThat(r.next()).isFalse();},"first");
        batch.add("select ?",r->{assertThat(r.next()).isTrue();observed.add(r.getString(1));assertThat(r.next()).isFalse();},"second");
        var r1=rows(new Object[]{"A"});var r2=rows(new Object[]{"B"});var jdbc=jdbc(List.of(r1,r2));
        batch.execute(jdbc.connection);
        assertThat(observed).containsExactly("A","B");
        verify(jdbc.connection).prepareStatement("select ?;\nselect ?");
        verify(jdbc.statement).setString(1,"first");verify(jdbc.statement).setString(2,"second");
        verify(jdbc.statement,times(1)).execute();verify(jdbc.statement,never()).executeQuery();
        verify(jdbc.statement,never()).addBatch();verify(jdbc.statement,never()).executeBatch();
        verify(r1).close();verify(r2).close();verify(jdbc.statement).close();
    }

    @Test void missingExtraUpdateCountAndNullResultFailClosed() throws Exception {
        for(int mode=0;mode<5;mode++){
            var batch=new ReadBatch();batch.add("select 1",r->{});
            var jdbc=jdbc(mode==1?List.of(rows(),rows()):List.of(rows()));
            switch(mode){
                case 0 -> when(jdbc.statement.execute()).thenReturn(false);
                case 2 -> when(jdbc.statement.getUpdateCount()).thenReturn(1);
                case 3 -> when(jdbc.statement.getResultSet()).thenReturn(null);
                case 4 -> {batch.add("select 2",r->{});}
                default -> { }
            }
            assertThatThrownBy(()->batch.execute(jdbc.connection)).isInstanceOf(IllegalStateException.class);
            verify(jdbc.statement).close();
        }
    }

    @Test void validationFailureStopsFollowingChecksAndPreservesPrimaryDuringCleanup() throws Exception {
        var expected=new IllegalStateException("first-check");var checks=new AtomicInteger();
        var batch=new ReadBatch();batch.add("select 1",r->{throw expected;});batch.add("select 2",r->checks.incrementAndGet());
        var first=rows();var jdbc=jdbc(List.of(first,rows()));
        doThrow(new SQLException("close result")).when(first).close();
        doThrow(new SQLException("close statement")).when(jdbc.statement).close();
        assertThatThrownBy(()->batch.execute(jdbc.connection)).isSameAs(expected);
        assertThat(checks).hasValue(0);assertThat(expected.getSuppressed()).hasSize(2);
        verify(jdbc.statement).close();
    }

    @Test void bindingAndDriverFailuresCloseAndNeverValidate() throws Exception {
        for(boolean binding:List.of(true,false)){
            var batch=new ReadBatch();var checks=new AtomicInteger();batch.add("select ?",r->checks.incrementAndGet(),"x");
            var jdbc=jdbc(List.of(rows()));var failure=new SQLException("driver", "57014");
            if(binding)doThrow(failure).when(jdbc.statement).setString(1,"x");else when(jdbc.statement.execute()).thenThrow(failure);
            assertThatThrownBy(()->batch.execute(jdbc.connection)).isSameAs(failure);
            assertThat(checks).hasValue(0);verify(jdbc.statement).close();
        }
    }

    @Test void nullScalarsAreNeverBooleanFalseOrIntegerZero() throws Exception {
        var r=rows(new Object[]{null,null});assertThat(r.next()).isTrue();
        assertThatThrownBy(()->BulkCapacityAuthorityCatalog.booleanValue(r,1)).hasMessage("NULL capacity catalog boolean");
        assertThatThrownBy(()->BulkCapacityAuthorityCatalog.intValue(r,2)).hasMessage("NULL capacity catalog integer");
    }

    @Test void collectionCountsAndPgJdbcParserKeepAllQueriesAndBindingArity() throws Exception {
        var batches=List.of(catalog(true),live(),callers(),privileges(BulkCapacityAuthorityInfrastructure.Access.READER));
        assertThat(batches.stream().map(b->reads(b).size())).containsExactly(28,27,32,75);
        assertThat(reads(catalog(false))).hasSize(22);
        for(var batch:batches){
            var all=reads(batch);String sql=all.stream().map(Read::sql).collect(java.util.stream.Collectors.joining(";\n"));
            // Actual shipped PGJDBC parser: semicolon SELECTs become ordered NativeQuery, not addBatch updates.
            var nativeQueries=org.postgresql.core.Parser.parseJdbcSql(sql,true,true,true,false,false);
            assertThat(nativeQueries).hasSize(all.size());
            int binds=0;for(var q:nativeQueries) binds+=q.bindPositions.length;
            assertThat(binds).isEqualTo(all.stream().mapToInt(r->r.parameters.length).sum());
        }
    }

    @Test void schemaHistoryAndColumnContractsPreserveExactRowsAndNullability() throws Exception {
        var all=reads(catalog(true));var schema=all.get(0);var history=all.get(1);
        schema.check.accept(rows(new Object[]{OWNER}));history.check.accept(rows(new Object[]{OWNER,"r",false}));
        for(var bad:List.of(rows(),rows(new Object[]{"other"}),rows(new Object[]{OWNER},new Object[]{OWNER})))
            assertThatThrownBy(()->schema.check.accept(bad)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->history.check.accept(rows(new Object[]{OWNER,"r",null}))).isInstanceOf(IllegalStateException.class);
        var column=all.stream().filter(r->Arrays.asList(r.parameters).contains("praxis_bulk_capacity.fairness_cursor")).findFirst().orElseThrow();
        Object[][] good={{"deployment_id","text",true,null,"",""},{"capacity_class","text",true,null,"",""},
                {"last_tenant_id","text",false,null,"",""},{"cursor_epoch","bigint",true,"0","",""}};
        column.check.accept(rows(good));
        Object[][] drift=Arrays.stream(good).map(Object[]::clone).toArray(Object[][]::new);drift[2][2]=true;
        assertThatThrownBy(()->column.check.accept(rows(drift))).hasMessage("Capacity authority column contract changed");
        assertThatThrownBy(()->column.check.accept(rows(Arrays.copyOf(good,3)))).isInstanceOf(IllegalStateException.class);
        var extra=new ArrayList<Object[]>(Arrays.asList(good));extra.add(good[0]);
        assertThatThrownBy(()->column.check.accept(rows(extra.toArray(Object[][]::new)))).hasMessage("Extra capacity authority column");
    }

    @Test void functionSignatureAndBodyChecksRejectChangedSourceAndNullFlags() throws Exception {
        var signature=reads(catalog(true)).stream().filter(r->Arrays.asList(r.parameters).contains("praxis_bulk_capacity.assert_authority_identity(text,text,uuid,bigint)")).findFirst().orElseThrow();
        Object[] good={"sql","s","boolean",false,false,true,true,"praxis_bulk_capacity_definer","boolean","u",false};
        signature.check.accept(rows(good));
        for(int field:new int[]{3,4,5,6,10}){var bad=good.clone();bad[field]=null;assertThatThrownBy(()->signature.check.accept(rows(bad))).isInstanceOf(IllegalStateException.class);}
        var body=reads(live()).stream().filter(r->r.sql.strip().startsWith("select p.prosrc")).findFirst().orElseThrow();
        assertThatThrownBy(()->body.check.accept(rows(new Object[]{"select wrong",true,"praxis_bulk_capacity_definer",true}))).hasMessage("Capacity authority protected function changed");
    }

    @Test void sourceOwnedStructuralSetsStillRejectDuplicateAndMissingTuples() throws Exception {
        var constraint=reads(catalog(true)).stream().filter(r->r.sql.contains("c.relname, k.conname")).findFirst().orElseThrow();
        Object[] tuple={"authority_identity","authority_identity_pkey","p","PRIMARY KEY (deployment_id)","t","f","f","{1}",null,"","",""};
        assertThatThrownBy(()->constraint.check.accept(rows())).hasMessage("Capacity authority constraint manifest changed");
        assertThatThrownBy(()->constraint.check.accept(rows(tuple,tuple))).hasMessage("Duplicate capacity constraint tuple");
        var index=reads(catalog(true)).stream().filter(r->r.sql.contains("c.relname, i.relname")).findFirst().orElseThrow();
        assertThatThrownBy(()->index.check.accept(rows())).hasMessage("Capacity authority index manifest changed");
    }

    @Test void v2AttestationIndexChecksUniqueStateAndAllExpectedNames() throws Exception {
        var index=reads(catalog(true)).stream().filter(r->r.sql.contains("x.indexprs is null")).findFirst().orElseThrow();
        Object[][] good={
            {"binding_attestation_pkey","CREATE UNIQUE INDEX binding_attestation_pkey ON praxis_bulk_capacity.binding_attestation USING btree (attestation_id)","1",true,true,true,true,true,null,true,1,1},
            {"binding_attestation_database_unique","CREATE UNIQUE INDEX binding_attestation_database_unique ON praxis_bulk_capacity.binding_attestation USING btree (database_id)","2",true,false,true,true,true,null,true,1,1},
            {"binding_attestation_binding_unique","CREATE UNIQUE INDEX binding_attestation_binding_unique ON praxis_bulk_capacity.binding_attestation USING btree (binding_id)","3",true,false,true,true,true,null,true,1,1}};
        index.check.accept(rows(good));
        assertThatThrownBy(()->index.check.accept(rows(Arrays.copyOf(good,2)))).hasMessage("Capacity attestation index manifest changed");
        var bad=Arrays.stream(good).map(Object[]::clone).toArray(Object[][]::new);bad[0][5]=false;
        assertThatThrownBy(()->index.check.accept(rows(bad))).hasMessage("Capacity attestation index changed");
        assertThatThrownBy(()->index.check.accept(rows(good[0],good[0]))).hasMessage("Capacity attestation index changed");
    }

    @Test void schemaAclRequiresExactGrantorCardinalityAndPrivilegeSet() throws Exception {
        var acl=reads(catalog(true)).stream().filter(r->r.sql.contains("n.nspacl")).findFirst().orElseThrow();
        var good=List.of(new Object[]{"praxis_bulk_capacity_definer","USAGE",false,OWNER},new Object[]{"praxis_bulk_capacity_provisioner","USAGE",false,OWNER},
                new Object[]{"praxis_bulk_capacity_allocator","USAGE",false,OWNER},new Object[]{"praxis_bulk_capacity_reader","USAGE",false,OWNER});
        acl.check.accept(rows(good.toArray(Object[][]::new)));
        var duplicate=new ArrayList<Object[]>(good);duplicate.add(good.get(0));
        assertThatThrownBy(()->acl.check.accept(rows(duplicate.toArray(Object[][]::new)))).hasMessage("Capacity authority schema ACL changed");
        for(Object[] bad:List.of(new Object[]{"PUBLIC","USAGE",false,OWNER},new Object[]{"praxis_bulk_capacity_reader","USAGE",false,"other"},new Object[]{"praxis_bulk_capacity_reader","USAGE",true,OWNER}))
            assertThatThrownBy(()->acl.check.accept(rows(bad))).isInstanceOf(IllegalStateException.class);
    }

    @Test void pendingIndexIsExactlyOneRow() throws Exception {
        var index=reads(live()).stream().filter(r->r.sql.contains("where i.oid=to_regclass")).findFirst().orElseThrow();
        Object[] good={"CREATE UNIQUE INDEX x WHERE (state = 'PENDING'::text)"};
        index.check.accept(rows(good));
        assertThatThrownBy(()->index.check.accept(rows(good,good))).hasMessage("Capacity pending uniqueness changed");
        assertThatThrownBy(()->index.check.accept(rows())).hasMessage("Capacity pending uniqueness changed");
    }

    @Test void callerMembershipAndDatabaseGuardsPreserveSpecificRejections() throws Exception {
        var all=reads(callers());var membership=all.stream().filter(r->r.sql.contains("m.admin_option, to_jsonb")).findFirst().orElseThrow();
        membership.check.accept(rows(new Object[]{false,"true","true"}));
        for(Object[] bad:List.of(new Object[]{true,"true","true"},new Object[]{false,null,"true"},new Object[]{false,"true",null}))
            assertThatThrownBy(()->membership.check.accept(rows(bad))).hasMessage("Capacity caller membership changed");
        var definer=all.stream().filter(r->r.sql.contains("r.rolname='praxis_bulk_capacity_definer'")).findFirst().orElseThrow();
        definer.check.accept(rows(new Object[]{0}));
        assertThatThrownBy(()->definer.check.accept(rows(new Object[]{1}))).hasMessage("Capacity definer membership changed");
        var db=all.stream().filter(r->r.sql.contains("has_database_privilege")).findFirst().orElseThrow();
        db.check.accept(rows(new Object[]{true,false,false}));
        assertThatThrownBy(()->db.check.accept(rows(new Object[]{true,null,false}))).isInstanceOf(IllegalStateException.class);
        // Safe OID lookup avoids later missing-role SQL errors masking the earlier role guard.
        assertThat(all.stream().filter(r->r.sql.contains("pg_has_role(")).allMatch(r->r.sql.contains("to_regrole(?)"))).isTrue();
    }

    @Test void allAccessModesHaveTheSameClosedPrivilegeMatrix() throws Exception {
        for(var access:BulkCapacityAuthorityInfrastructure.Access.values()){
            var all=reads(privileges(access));assertThat(all).hasSize(75);
            for(var r:all){
                if(r.sql.contains("has_function_privilege")){
                    String name=r.parameters[0].toString();
                    boolean allowed=name.contains("assert_authority_identity") || switch(access){
                        case PROVISIONER -> name.contains("enroll_binding")||name.contains("register_attestation");
                        case ALLOCATOR -> name.contains("request_capacity")||name.contains("allocate_next");
                        case READER -> name.contains("find_issue")||name.contains("read_attestation")||name.contains("read_issued_token");};
                    r.check.accept(rows(new Object[]{allowed}));
                    assertThatThrownBy(()->r.check.accept(rows(new Object[]{!allowed}))).hasMessage("Capacity authority function privileges changed");
                    assertThat(r.sql).contains("to_regprocedure(?)");
                }else if(r.sql.contains("has_schema_privilege"))r.check.accept(rows(new Object[]{false,true}));
                else if(r.sql.contains("count(*)"))r.check.accept(rows(new Object[]{0}));
                else r.check.accept(rows(new Object[]{false}));
                assertThatThrownBy(()->r.check.accept(rows())).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(()->r.check.accept(rows(new Object[]{null,null}))).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test void earlierSemanticRejectionWinsBeforeLaterCollectedBadRows() throws Exception {
        var batch=catalog(true);var values=new ArrayList<ResultSet>();values.add(rows(new Object[]{"wrong-owner"}));
        while(values.size()<28)values.add(rows());
        var jdbc=jdbc(values);
        assertThatThrownBy(()->batch.execute(jdbc.connection)).hasMessage("Capacity authority schema owner changed");
        verify(values.get(1),never()).next();verify(jdbc.statement).close();
    }

    @Test void constructorAndEveryOperationAttestAndDeadlineStopsCallbackAfterDecoding() throws Exception {
        var ds=mock(DataSource.class);var c=mock(Connection.class);when(ds.getConnection()).thenReturn(c);
        when(c.getAutoCommit()).thenReturn(false);when(c.isReadOnly()).thenReturn(true);
        var metadata=mock(DatabaseMetaData.class);when(c.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        var clamp=mock(Statement.class);when(c.createStatement()).thenReturn(clamp);
        var expire=new boolean[1];var login=new String[]{"reader"};var statementCount=new AtomicInteger();
        when(c.prepareStatement(anyString())).thenAnswer(call->{
            statementCount.incrementAndGet();String sql=call.getArgument(0);
            if(sql.contains("assert_authority_identity")){
                var p=mock(PreparedStatement.class);var result=rows(new Object[]{login[0],true,true});when(p.executeQuery()).thenReturn(result);return p;
            }
            var results=new ArrayList<ResultSet>();
            for(var r:reads(privileges(BulkCapacityAuthorityInfrastructure.Access.READER))){
                if(r.sql.contains("has_function_privilege")){String name=r.parameters[0].toString();results.add(rows(new Object[]{name.contains("find_issue")||name.contains("read_attestation")||name.contains("read_issued_token")||name.contains("assert_authority_identity")}));}
                else if(r.sql.contains("has_schema_privilege"))results.add(rows(new Object[]{false,true}));
                else if(r.sql.contains("count(*)"))results.add(rows(new Object[]{0}));else results.add(rows(new Object[]{false}));
            }
            var j=jdbc(results);var cursor=new AtomicInteger();
            when(j.statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenAnswer(ignored->{
                boolean more=cursor.incrementAndGet()<results.size();
                if(!more&&expire[0])((ConnectionHolder)TransactionSynchronizationManager.getResource(ds)).setTimeoutInMillis(-1);
                return more;});
            when(j.statement.getResultSet()).thenAnswer(ignored->results.get(cursor.get()));return j.statement;
        });
        try(var migrator=mockStatic(BulkCapacityAuthorityMigrator.class,withSettings().mockMaker("mock-maker-inline").defaultAnswer(call -> {
            if(Set.of("assertLiveCatalog","assertCallers").contains(call.getMethod().getName()))return null;
            return call.callRealMethod();
        }))){
            var identity=new BulkCapacityAuthorityMigrator.Identity("dep","env",UUID.randomUUID(),1);
            var infra=new BulkCapacityAuthorityInfrastructure(ds,new DataSourceTransactionManager(ds),identity,BulkCapacityAuthorityInfrastructure.Access.READER,ROLES);
            var callbacks=new AtomicInteger();assertThat(infra.<Integer>withConnection(connection->{assertThat(connection).isNotNull();return callbacks.incrementAndGet();})).isEqualTo(1);
            login[0]="other";
            assertThatThrownBy(()->infra.withConnection(connection->callbacks.incrementAndGet()))
                    .hasMessage("Capacity authority role, database, or expected epoch changed");
            login[0]="reader";
            expire[0]=true;
            assertThatThrownBy(()->infra.withConnection(connection->callbacks.incrementAndGet())).isInstanceOf(TransactionTimedOutException.class);
            assertThat(callbacks).hasValue(1);assertThat(statementCount).hasValue(7);
            expire[0]=false;
            assertThatThrownBy(()->infra.withConnection(connection->{
                callbacks.incrementAndGet();
                ((ConnectionHolder)TransactionSynchronizationManager.getResource(ds)).setTimeoutInMillis(-1);
                return "must-not-publish";
            })).isInstanceOf(TransactionTimedOutException.class);
            assertThat(callbacks).hasValue(2);
            verify(c,times(3)).rollback();verify(c,times(2)).commit();
            migrator.verify(()->BulkCapacityAuthorityMigrator.assertLiveCatalog(any(),eq(OWNER)),times(5));
            migrator.verify(()->BulkCapacityAuthorityMigrator.assertCallers(any(),eq(ROLES)),times(5));
            verify(clamp,times(5)).execute(argThat(sql->sql.contains("interval '2 seconds'")&&sql.contains("interval '1 second'")));
        }
        assertThat(TransactionSynchronizationManager.hasResource(ds)).isFalse();
    }

    private record Read(String sql,ReadBatch.Check check,Object[] parameters) { }
    private static List<Read> reads(ReadBatch batch){
        try{var f=ReadBatch.class.getDeclaredField("reads");f.setAccessible(true);var result=new ArrayList<Read>();
            for(Object r:(List<?>)f.get(batch)){var type=r.getClass();var sql=type.getDeclaredMethod("sql");var check=type.getDeclaredMethod("check");var params=type.getDeclaredMethod("parameters");sql.setAccessible(true);check.setAccessible(true);params.setAccessible(true);result.add(new Read((String)sql.invoke(r),(ReadBatch.Check)check.invoke(r),(Object[])params.invoke(r)));}return result;
        }catch(Exception e){throw new AssertionError(e);}
    }
    private static ReadBatch collected(Class<?> type,String method,Object... args)throws Exception{
        var b=new ReadBatch();var m=Arrays.stream(type.getDeclaredMethods()).filter(x->x.getName().equals(method)).findFirst().orElseThrow();m.setAccessible(true);var all=new Object[args.length+1];all[0]=b;System.arraycopy(args,0,all,1,args.length);
        try{m.invoke(null,all);}catch(InvocationTargetException e){throw new AssertionError(e.getCause());}return b;
    }
    private static ReadBatch catalog(boolean v2)throws Exception{return collected(BulkCapacityAuthorityCatalog.class,"collect",OWNER,v2);}
    private static ReadBatch live()throws Exception{return collected(BulkCapacityAuthorityMigrator.class,"collectLiveCatalog",OWNER,BulkCapacityAuthorityMigrationLineage.resolve(List.of()));}
    private static ReadBatch callers()throws Exception{return collected(BulkCapacityAuthorityMigrator.class,"collectCallers",ROLES);}
    private static ReadBatch privileges(BulkCapacityAuthorityInfrastructure.Access access)throws Exception{return collected(BulkCapacityAuthorityInfrastructure.class,"collectPrivileges",access);}
    private record Jdbc(Connection connection,PreparedStatement statement) { }
    private static Jdbc jdbc(List<ResultSet> results)throws Exception{
        var c=mock(Connection.class);var p=mock(PreparedStatement.class);var index=new AtomicInteger();
        when(c.prepareStatement(anyString())).thenReturn(p);when(p.execute()).thenReturn(!results.isEmpty());
        when(p.getResultSet()).thenAnswer(call->results.get(index.get()));when(p.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenAnswer(call->index.incrementAndGet()<results.size());when(p.getUpdateCount()).thenReturn(-1);return new Jdbc(c,p);
    }
    private static ResultSet rows(Object[]... values)throws Exception{
        var r=mock(ResultSet.class);var cursor=new AtomicInteger(-1);var nullValue=new boolean[1];
        when(r.next()).thenAnswer(call->cursor.incrementAndGet()<values.length);
        when(r.getString(anyInt())).thenAnswer(call->{Object value=values[cursor.get()][(int)call.getArgument(0)-1];nullValue[0]=value==null;return value==null?null:value.toString();});
        when(r.getBoolean(anyInt())).thenAnswer(call->{Object value=values[cursor.get()][(int)call.getArgument(0)-1];nullValue[0]=value==null;return value!=null&&(Boolean)value;});
        when(r.getInt(anyInt())).thenAnswer(call->{Object value=values[cursor.get()][(int)call.getArgument(0)-1];nullValue[0]=value==null;return value==null?0:((Number)value).intValue();});
        when(r.wasNull()).thenAnswer(call->nullValue[0]);return r;
    }
}
