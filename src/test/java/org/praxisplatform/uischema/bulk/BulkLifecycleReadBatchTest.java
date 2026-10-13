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

/** Real Spring transaction boundaries with JDBC doubles; catalog semantics and physical PG timing are separate gates. */
class BulkLifecycleReadBatchTest {
    private static final BulkExecutionRoleConfiguration ROLES = new BulkExecutionRoleConfiguration(
            "owner", Set.of("runtime"), Set.of(), Set.of("control"));

    @Test void controlProofUsesSixSubmissionsAndDistinctFreshAttestationsEveryTime() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 0; i < 2; i++) assertThat(f.control.<String>withConnection(c -> "ok")).isEqualTo("ok");
            assertThat(f.c.executions).hasValue(6); assertThat(f.r.executions).hasValue(6);
            assertThat(f.controlAttest).hasValue(2); assertThat(f.runtimeAttest).hasValue(2);
            verify(f.c.connection, times(2)).commit(); verify(f.r.connection, times(2)).commit();
            assertThat(f.c.tailParameters.get(1)).isEqualTo("75ms");
            assertThat(f.r.tailParameters.get(1)).isEqualTo("75ms");
            assertThat(f.r.tailParameters.get(2)).isEqualTo("ns");
            assertThat(f.r.tailParameters.get(3)).isEqualTo(Integer.toUnsignedLong((Integer)f.c.tailParameters.get(2)));
            assertThat(f.r.tailParameters.get(4)).isEqualTo(Integer.toUnsignedLong((Integer)f.c.tailParameters.get(3)));
            assertThat(f.r.tailSql).contains("l.database", "current_database()", "l.pid <> pg_backend_pid()", "l.objsubid = 2");
        }
    }

    @Test void ordinaryLifecycleReadRetainsItsNamespaceFenceWithoutIdentityProbe() throws Exception {
        try (var f = new Fixture()) {
            assertThat(f.runtime.<Integer>withLifecycleRead(c -> 7)).isEqualTo(7);
            assertThat(f.r.executions).hasValue(3); assertThat(f.c.executions).hasValue(0);
            assertThat(f.r.tailSql).doesNotContain("pg_locks");
        }
    }

    @Test void combinedClampPreservesLowerLimitsAndExactRestorationValue() throws Exception {
        for (String previous : List.of("75ms", "250ms", "1s", "2s")) {
            try (var f = new Fixture()) {
                f.r.previous = previous;
                f.runtime.withLifecycleRead(c -> true);
                assertThat(f.r.tailParameters.get(1)).isEqualTo(previous);
                assertThat(f.r.clampSql).contains("previous as materialized", "interval '2 seconds'", "interval '1 second'",
                        "interval '250 milliseconds'", "then '250ms' else value", "else current_setting('lock_timeout') end");
            }
        }
    }

    @Test void clampRejectsMissingDuplicateAndNullFieldsBeforeAttestation() throws Exception {
        for (int fault = 0; fault < 5; fault++) {
            try (var f = new Fixture()) {
                f.c.clampFault = fault;
                assertThatThrownBy(() -> f.control.withConnection(c -> fail("callback"))).isInstanceOf(IllegalStateException.class);
                assertThat(f.controlAttest).hasValue(0); assertThat(f.runtimeAttest).hasValue(0);
                verify(f.c.connection).rollback();
            }
        }
    }

    @Test void controlTailRejectsMissingDuplicateNullFalseAndExtraResults() throws Exception {
        for (String fault : List.of("null-rows", "missing-first", "missing-second", "restore-null", "restore-duplicate", "lock-null", "lock-false", "lock-duplicate", "extra", "update-count")) {
            try (var f = new Fixture()) {
                f.c.tailFault = fault;
                assertThatThrownBy(() -> f.control.withConnection(c -> fail("callback"))).isInstanceOf(IllegalStateException.class);
                assertThat(f.runtimeAttest).hasValue(0); verify(f.c.connection).rollback();
            }
        }
    }

    @Test void runtimeTailRejectsWrongNamespaceMissingNullDuplicateAndDifferentDatabase() throws Exception {
        for (String fault : List.of("null-rows", "missing-first", "missing-second", "missing-third", "restore-null", "restore-duplicate", "namespace-null", "namespace-wrong", "namespace-duplicate", "identity-null", "identity-false", "identity-duplicate", "extra", "update-count")) {
            try (var f = new Fixture()) {
                f.r.tailFault = fault;
                assertThatThrownBy(() -> f.control.withConnection(c -> fail("callback"))).isInstanceOf(IllegalStateException.class);
                verify(f.c.connection).rollback(); verify(f.r.connection).rollback();
            }
        }
    }

    @Test void attestationFailurePreventsIdentityTailAndPreservesPrimaryWhenRestoreFails() throws Exception {
        for (Throwable primary : List.of(new SQLException("identity"), new IllegalStateException("role"), new AssertionError("catalog"))) {
            try (var f = new Fixture()) {
                f.controlFailure = primary; f.c.restoreFailure = new SQLException("restore");
                assertThatThrownBy(() -> f.control.withConnection(c -> fail("callback"))).satisfies(failure -> {
                    if (primary instanceof SQLException) assertThat(failure.getCause()).isSameAs(primary);
                    else assertThat(failure).isSameAs(primary);
                });
                assertThat(primary.getSuppressed()).contains(f.c.restoreFailure);
                assertThat(f.c.tailSql).isNull(); assertThat(f.runtimeAttest).hasValue(0);
                verify(f.c.connection).rollback();
            }
        }
    }

    @Test void runtimeAttestationRejectionNeverReachesNamespaceOrControlCallback() throws Exception {
        try (var f = new Fixture()) {
            f.runtimeFailure = new IllegalStateException("Bulk connection identity is not an allowlisted PostgreSQL role");
            assertThatThrownBy(() -> f.control.withConnection(c -> fail("callback"))).isSameAs(f.runtimeFailure);
            assertThat(f.r.tailSql).isNull(); verify(f.c.connection).rollback(); verify(f.r.connection).rollback();
        }
    }

    @Test void outerDeadlineIncludesNestedRuntimeWorkAndPreventsControlCallback() throws Exception {
        try (var f = new Fixture()) {
            f.r.afterDecode = () -> expire(f.c.source);
            assertThatThrownBy(() -> f.control.withConnection(c -> fail("callback"))).isInstanceOf(TransactionTimedOutException.class);
            verify(f.r.connection).commit(); verify(f.c.connection).rollback();
        }
    }

    @Test void runtimeDeadlineAfterDecodingPreventsCallbackAndRollsBackBothTransactions() throws Exception {
        try (var f = new Fixture()) {
            f.r.afterDecode = () -> expire(f.r.source);
            assertThatThrownBy(() -> f.control.withConnection(c -> fail("callback"))).isInstanceOf(TransactionTimedOutException.class);
            verify(f.r.connection).rollback(); verify(f.c.connection).rollback();
        }
    }

    @Test void expiredDeadlineAfterCallbackCannotCommitOrReturnSuccess() throws Exception {
        try (var f = new Fixture()) {
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> f.control.withConnection(c -> { calls.incrementAndGet(); expire(f.c.source); return "no"; }))
                    .isInstanceOf(TransactionTimedOutException.class);
            assertThat(calls).hasValue(1); verify(f.c.connection).rollback();
            assertThatThrownBy(() -> f.runtime.withLifecycleRead(c -> { expire(f.r.source); return "no"; }))
                    .isInstanceOf(TransactionTimedOutException.class);
            verify(f.r.connection).rollback();
        }
    }

    @Test void parsingFailurePreservesPrimaryAndClosesResultsEvenWhenCleanupFails() throws Exception {
        try (var f = new Fixture()) {
            var primary = new SQLException("parse"); var closing = new SQLException("close");
            f.r.parseFailure = primary; f.r.closeFailure = closing; f.r.restoreFailure = new AssertionError("restore");
            assertThatThrownBy(() -> f.control.withConnection(c -> fail("callback"))).satisfies(failure -> {
                    if (primary instanceof SQLException) assertThat(failure.getCause()).isSameAs(primary);
                    else assertThat(failure).isSameAs(primary);
                });
            assertThat(primary.getSuppressed()).contains(closing, f.r.restoreFailure);
            verify(f.r.lastTail).close(); verify(f.r.lastRows.get(0)).close();
            verify(f.c.connection).rollback(); verify(f.r.connection).rollback();
        }
    }

    @Test void closeFailureAfterSuccessfulTailRejectsBeforeCallback() throws Exception {
        try (var f = new Fixture()) {
            var failure = new SQLException("close-only"); f.c.closeFailure = failure;
            assertThatThrownBy(() -> f.control.withConnection(c -> fail("callback")))
                    .satisfies(error -> assertThat(error.getCause()).isSameAs(failure));
            assertThat(f.runtimeAttest).hasValue(0); verify(f.c.connection).rollback();
        }
    }

    @Test void publishedReadyGenerationUsesRuntimeFenceWithoutComposingOrInstallingDocuments() throws Exception {
        try (var f = new Fixture();
                var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var documents = coldDocuments();
            var lifecycle = lifecycle(f, context, documents, List.of(provider(f, "crud.uniform")));
            var identity = new BulkOperationControlIdentity("ns", "crud.uniform");
            for (long generation : List.of(11L, 12L)) {
                f.r.operationRows = new Object[][] {{"READY", generation, "private-fingerprint", "private-revision"}};
                assertThat(lifecycle.readPublishedReadyGeneration(identity)).hasValue(generation);
            }
            assertThat(f.r.operationSql).contains("praxis_bulk.lock_operation_control(?, ?)");
            assertThat(f.r.operationParameters).containsAllEntriesOf(Map.of(1, "ns", 2, "crud.uniform")).hasSize(2);
            assertThat(f.runtimeAttest).hasValue(2);
            verify(f.r.connection, times(2)).commit();
            verifyNoInteractions(f.c.source, documents);
        }
    }

    @Test void publishedReadyGenerationAcceptsDeclaredDomainConfirmationToo() throws Exception {
        try (var f = new Fixture();
                var context = BulkCrudStructuralCompilerTest.context(BulkResourceOperationBindingsTest.CompleteBulkController.class)) {
            var lifecycle = lifecycle(f, context, coldDocuments(), List.of(new BulkCrudOperationalCompositionTest.Provider("items.bulk-approve", "r1",
                    BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.DOMAIN_COMMAND, f.runtime)));
            f.r.operationRows = new Object[][] {{"READY", 9L, "fingerprint", "revision"}};
            assertThat(lifecycle.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", "items.bulk-approve")))
                    .hasValue(9L);
        }
    }

    @Test void publishedReadyGenerationIsEmptyForAbsentOrNonReadyControl() throws Exception {
        try (var f = new Fixture();
                var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var lifecycle = lifecycle(f, context, coldDocuments(), List.of(provider(f, "crud.uniform")));
            // The canonical SQL function also represents stale-publication READY as no row.
            for (Object[][] rows : List.of(new Object[0][],
                    new Object[][] {{"UNCOMPOSED", 0L, null, null}},
                    new Object[][] {{"SUSPENDED", 10L, null, null}})) {
                f.r.operationRows = rows;
                assertThat(lifecycle.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", "crud.uniform")))
                        .isEmpty();
            }
            verify(f.r.connection, times(3)).commit();
        }
    }

    @Test void publishedReadyGenerationRejectsNullForeignUnknownAndNonConfirmationBeforeSql() throws Exception {
        try (var f = new Fixture();
                var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var lifecycle = lifecycle(f, context, coldDocuments(), List.of(provider(f, "crud.uniform")));
            assertThatThrownBy(() -> lifecycle.readPublishedReadyGeneration(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> lifecycle.readPublishedReadyGeneration(new BulkOperationControlIdentity("foreign", "crud.uniform")))
                    .isInstanceOf(IllegalArgumentException.class);
            for (String operation : List.of("unknown", "crud.proposal", "crud.uniform.evaluation"))
                assertThatThrownBy(() -> lifecycle.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", operation)))
                        .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(f.r.source, f.c.source);
        }
    }

    @Test void publishedReadyGenerationRejectsMalformedBindingAndMissingProviderBeforeSql() throws Exception {
        try (var f = new Fixture();
                var malformed = BulkCrudStructuralCompilerTest.context(BulkResourceOperationBindingsTest.MissingUpdateSource.class);
                var valid = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var invalid = lifecycle(f, malformed, coldDocuments(), List.of(provider(f, "crud.uniform")));
            assertThatThrownBy(() -> invalid.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", "crud.uniform")))
                    .isInstanceOf(IllegalStateException.class);
            var withoutProvider = lifecycle(f, valid, coldDocuments(), List.of(provider(f, "crud.items")));
            assertThatThrownBy(() -> withoutProvider.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", "crud.uniform")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("provider");
            verifyNoInteractions(f.r.source, f.c.source);
        }
    }

    @Test void publishedReadyGenerationRejectsAmbiguousProviderBeforeSql() throws Exception {
        try (var f = new Fixture();
                var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var lifecycle = lifecycle(f, context, coldDocuments(),
                    List.of(provider(f, "crud.uniform"), provider(f, "crud.uniform")));
            assertThatThrownBy(() -> lifecycle.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", "crud.uniform")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Multiple descriptor providers");
            verifyNoInteractions(f.r.source, f.c.source);
        }
    }

    @Test void publishedReadyGenerationRejectsProviderFromOtherInfrastructureBeforeSql() throws Exception {
        try (var f = new Fixture();
                var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var other = new BulkExecutionInfrastructure(f.r.source, new DataSourceTransactionManager(f.r.source),
                    "ns", "dep", ROLES);
            var provider = new BulkCrudOperationalCompositionTest.Provider("crud.uniform", "r1",
                    BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, other);
            var lifecycle = lifecycle(f, context, coldDocuments(), List.of(provider));
            assertThatThrownBy(() -> lifecycle.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", "crud.uniform")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("this runtime infrastructure");
            verifyNoInteractions(f.r.source, f.c.source);
        }
    }

    @Test void publishedReadyGenerationRejectsAmbientTransactionsBeforeSql() throws Exception {
        try (var f = new Fixture();
                var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var lifecycle = lifecycle(f, context, coldDocuments(), List.of(provider(f, "crud.uniform")));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> lifecycle.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", "crud.uniform")))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside transactions");
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            verifyNoInteractions(f.r.source, f.c.source);
        }
    }

    @Test void publishedReadyGenerationPropagatesSqlAndDuplicateRowFailures() throws Exception {
        for (boolean duplicate : List.of(false, true)) {
            try (var f = new Fixture();
                    var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
                var lifecycle = lifecycle(f, context, coldDocuments(), List.of(provider(f, "crud.uniform")));
                var failure = new SQLException("denied operation control");
                if (duplicate) f.r.operationRows = new Object[][] {{"READY", 11L, "f", "r"}, {"READY", 11L, "f", "r"}};
                else f.r.operationFailure = failure;
                assertThatThrownBy(() -> lifecycle.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", "crud.uniform")))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class)
                        .satisfies(error -> {
                            if (!duplicate) assertThat(error.getCause()).isSameAs(failure);
                            else assertThat(error.getCause()).hasMessageContaining("duplicate rows");
                        });
                verify(f.r.connection).rollback();
            }
        }
    }

    @Test void publishedReadyGenerationPropagatesRoleAndCatalogFailuresBeforeControlRead() throws Exception {
        for (Throwable failure : List.of(new IllegalStateException("role not allowlisted"), new SQLException("catalog unavailable"))) {
            try (var f = new Fixture();
                    var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
                var lifecycle = lifecycle(f, context, coldDocuments(), List.of(provider(f, "crud.uniform")));
                f.runtimeFailure = failure;
                assertThatThrownBy(() -> lifecycle.readPublishedReadyGeneration(new BulkOperationControlIdentity("ns", "crud.uniform")))
                        .satisfies(error -> {
                            if (failure instanceof SQLException) assertThat(error.getCause()).isSameAs(failure);
                            else assertThat(error).isSameAs(failure);
                        });
                assertThat(f.r.operationSql).isNull();
                verify(f.r.connection).rollback();
            }
        }
    }

    private static org.praxisplatform.uischema.openapi.OpenApiDocumentService coldDocuments() {
        var documents = mock(org.praxisplatform.uischema.openapi.OpenApiDocumentService.class);
        when(documents.supportsFreshBulkLifecycleComposition()).thenReturn(true);
        when(documents.supportsFreshBulkLifecyclePublicCacheCoherence()).thenReturn(true);
        return documents;
    }

    private static BulkOperationDescriptorProvider provider(Fixture f, String operation) {
        return new BulkCrudOperationalCompositionTest.Provider(operation, "r1",
                BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, f.runtime);
    }

    private static BulkOperationLifecycle lifecycle(Fixture f,
            org.springframework.web.context.support.AnnotationConfigWebApplicationContext context,
            org.praxisplatform.uischema.openapi.OpenApiDocumentService documents,
            List<BulkOperationDescriptorProvider> providers) {
        var bindings = BulkResourceOperationBindings.from(context.getBean(
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class));
        var lifecycle = new BulkOperationLifecycle(bindings,
                mock(org.praxisplatform.uischema.openapi.CanonicalOperationResolver.class), documents,
                mock(org.praxisplatform.uischema.action.ActionDefinitionRegistry.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(org.praxisplatform.uischema.schema.SchemaReferenceResolver.class), f.runtime, f.control, providers);
        clearInvocations(documents);
        return lifecycle;
    }

    private static void expire(DataSource source) {
        ((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setTimeoutInMillis(-1);
    }

    private static final class Fixture implements AutoCloseable {
        final Endpoint c = new Endpoint(true), r = new Endpoint(false);
        final AtomicInteger controlAttest = new AtomicInteger(), runtimeAttest = new AtomicInteger();
        Throwable controlFailure, runtimeFailure;
        final BulkExecutionInfrastructure runtime;
        final BulkControlPlaneInfrastructure control;
        final MockedStatic<BulkExecutionMigrator> migrator;
        Fixture() throws Exception {
            runtime = new BulkExecutionInfrastructure(r.source, new DataSourceTransactionManager(r.source), "ns", "dep", ROLES);
            control = new BulkControlPlaneInfrastructure(c.source, new DataSourceTransactionManager(c.source), "ns", "dep", "control", runtime);
            migrator = mockStatic(BulkExecutionMigrator.class, withSettings().mockMaker("mock-maker-inline").defaultAnswer(call -> {
                String name = call.getMethod().getName();
                if (name.equals("validateLiveControlPlaneRoleAccess") || name.equals("validateLiveRuntimeRoleAccess")) {
                    boolean cp = name.contains("ControlPlane");
                    assertThat(call.getArgument(1, BulkExecutionRoleConfiguration.class)).isSameAs(ROLES);
                    if (cp) assertThat(call.getArgument(2, String.class)).isEqualTo("control");
                    (cp ? controlAttest : runtimeAttest).incrementAndGet();
                    // One JDBC stand-in for the already-tested seven-result catalog submission.
                    try (var s = call.getArgument(0, Connection.class).createStatement()) { s.execute("select 1 /* live catalog stand-in */"); }
                    Throwable failure = cp ? controlFailure : runtimeFailure;
                    if (failure != null) throw failure;
                    return null;
                }
                return call.callRealMethod();
            }));
        }
        public void close() {
            migrator.close();
            assertThat(TransactionSynchronizationManager.hasResource(c.source)).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(r.source)).isFalse();
        }
    }

    private static final class Endpoint {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final AtomicInteger executions = new AtomicInteger();
        final Map<Integer,Object> tailParameters = new HashMap<>();
        String previous="75ms", clampSql, tailSql, tailFault="";
        int clampFault=-1;
        boolean autoCommit=true;
        Runnable afterDecode=()->{};
        Throwable restoreFailure;
        SQLException parseFailure,closeFailure;
        PreparedStatement lastTail;
        String operationSql;
        final Map<Integer, Object> operationParameters = new HashMap<>();
        Object[][] operationRows = new Object[0][];
        SQLException operationFailure;
        List<ResultSet> lastRows;
        Endpoint(boolean control) throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenAnswer(call->autoCommit);
            doAnswer(call->{autoCommit=call.getArgument(0);return null;}).when(connection).setAutoCommit(anyBoolean());
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(connection.createStatement()).thenAnswer(call->{
                var s=mock(Statement.class);
                when(s.execute(anyString())).thenAnswer(x->{executions.incrementAndGet();return true;});
                when(s.executeQuery(anyString())).thenAnswer(x->{
                    executions.incrementAndGet();clampSql=x.getArgument(0);
                    Object[] values={previous,"20ms","75ms"};
                    if(clampFault==0)return rows();
                    if(clampFault==1)return rows(values,values);
                    if(clampFault>=2)values[clampFault-2]=null;
                    return rows(values);
                });return s;
            });
            when(connection.prepareStatement(anyString())).thenAnswer(call->{
                String sql=call.getArgument(0);var p=mock(PreparedStatement.class);
                if (sql.contains("praxis_bulk.lock_operation_control(?, ?)")) {
                    operationSql = sql;
                    doAnswer(x -> { operationParameters.put(x.getArgument(0), x.getArgument(1)); return null; })
                            .when(p).setString(anyInt(), anyString());
                    when(p.executeQuery()).thenAnswer(x -> {
                        executions.incrementAndGet();
                        if (operationFailure != null) throw operationFailure;
                        return rows(operationRows);
                    });
                    return p;
                }
                if(!sql.contains(";")){
                    if(restoreFailure!=null)throw restoreFailure;
                    when(p.execute()).thenAnswer(x->{executions.incrementAndGet();return true;});return p;
                }
                tailSql=sql;lastTail=p;tailParameters.clear();
                doAnswer(x->{tailParameters.put(x.getArgument(0),x.getArgument(1));return null;}).when(p).setString(anyInt(),anyString());
                doAnswer(x->{tailParameters.put(x.getArgument(0),x.getArgument(1));return null;}).when(p).setInt(anyInt(),anyInt());
                doAnswer(x->{tailParameters.put(x.getArgument(0),x.getArgument(1));return null;}).when(p).setLong(anyInt(),anyLong());
                var list=new ArrayList<ResultSet>();list.add(valueRows("restore",previous));
                if(control)list.add(valueRows("lock",true));
                else {list.add(valueRows("namespace","dep"));if(sql.contains("pg_locks"))list.add(valueRows("identity",true));}
                if(tailFault.equals("extra"))list.add(rows(new Object[]{true}));
                lastRows=list;var index=new AtomicInteger();
                when(p.execute()).thenAnswer(x->{executions.incrementAndGet();return !tailFault.equals("missing-first");});
                when(p.getResultSet()).thenAnswer(x->tailFault.equals("null-rows")?null:list.get(index.get()));
                when(p.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenAnswer(x->{
                    int i=index.incrementAndGet();
                    if(tailFault.equals("missing-second")&&i==1 || tailFault.equals("missing-third")&&i==2)return false;
                    if(i==list.size())afterDecode.run();
                    return i<list.size();
                });
                when(p.getUpdateCount()).thenReturn(tailFault.equals("update-count")?0:-1);
                if(parseFailure!=null)when(list.get(0).next()).thenThrow(parseFailure);
                if(closeFailure!=null)doThrow(closeFailure).when(p).close();
                return p;
            });
        }
        private ResultSet valueRows(String name,Object value) throws Exception {
            if(tailFault.equals(name+"-null"))value=null;
            if(tailFault.equals(name+"-false"))value=false;
            if(tailFault.equals(name+"-wrong"))value="wrong";
            return tailFault.equals(name+"-duplicate")?rows(new Object[]{value},new Object[]{value}):rows(new Object[]{value});
        }
    }
    private static ResultSet rows(Object[]... values) throws Exception {
        var r=mock(ResultSet.class);var cursor=new AtomicInteger(-1);var wasNull=new boolean[1];
        when(r.next()).thenAnswer(c->cursor.incrementAndGet()<values.length);
        when(r.getString(anyInt())).thenAnswer(c->{Object v=values[cursor.get()][(int)c.getArgument(0)-1];wasNull[0]=v==null;return v==null?null:v.toString();});
        when(r.getLong(anyInt())).thenAnswer(c -> ((Number) values[cursor.get()][(int)c.getArgument(0)-1]).longValue());
        when(r.getBoolean(anyInt())).thenAnswer(c->{Object v=values[cursor.get()][(int)c.getArgument(0)-1];wasNull[0]=v==null;return Boolean.TRUE.equals(v);});
        when(r.wasNull()).thenAnswer(c->wasNull[0]);return r;
    }
}
