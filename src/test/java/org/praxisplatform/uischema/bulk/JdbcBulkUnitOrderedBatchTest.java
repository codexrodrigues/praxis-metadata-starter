package org.praxisplatform.uischema.bulk;

import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Protocol/JDBC tests only; actual locks, SQL gates and rollback require the assigned Neon proof. */
class JdbcBulkUnitOrderedBatchTest {
    @Test void freshSyncAttemptUsesThreeOrderedExecutionsAndExactBindings() throws Throwable {
        var f = new Fixture();
        f.apply();
        assertThat(f.admissions).hasValue(1); assertThat(f.mutations).hasValue(1);
        assertThat(f.queries).hasSize(3);
        assertThat(f.queries.get(0).split(";", -1)).hasSize(13);
        assertThat(f.queries.get(1).split(";", -1)).hasSize(3);
        assertThat(f.queries.get(2).split(";", -1)).hasSize(3);
        String prefix = f.queries.getFirst();
        assertThat(prefix.indexOf("lock_capacity_marker")).isLessThan(prefix.indexOf("lock_operation_control"));
        assertThat(prefix.indexOf("lock_operation_control")).isLessThan(prefix.indexOf("for update of e"));
        assertThat(prefix).contains("owner_epoch=?", "active_attempt_epoch=?", "cancel_requested_at is null",
                "not exists", "join praxis_bulk.praxis_bulk_proposal", "from fresh", "case when millis>0");
        assertThat(f.queries.get(2)).contains("and exists (select 1 from praxis_bulk.praxis_bulk_item_receipt",
                "r.expected_version=?", "r.attempt_id=?", "r.owner_epoch=?", "r.unit_deadline_at=");
        for (int i=0; i<f.queries.size(); i++) {
            long placeholders=f.queries.get(i).chars().filter(c -> c=='?').count();
            assertThat(f.bindings.get(i)).hasSize((int)placeholders);
            assertThat(f.bindings.get(i).keySet()).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.IntStream.rangeClosed(1,(int)placeholders).boxed().toList());
            verify(f.statements.get(i)).execute(); verify(f.statements.get(i)).close();
        }
        assertThat(f.bindings.getFirst().get(1)).isEqualTo(f.executionId);
        assertThat(f.bindings.getFirst().get(2)).isEqualTo(f.context.namespaceId());
        verify(f.connection, times(3)).prepareStatement(anyString());
    }

    @Test void asyncCapacityRefusalCannotInstallStandaloneClampOrReachCallbacks() throws Throwable {
        for (boolean rejectedBinding : new boolean[] {true, false}) {
            var f = new Fixture(Duration.ofSeconds(5), true);
            f.capacityActive = !rejectedBinding; f.capacitySlotPresent = rejectedBinding;
            assertReason(f::apply, rejectedBinding ? BulkDurableExecutionException.Reason.FENCED
                    : BulkDurableExecutionException.Reason.CORRUPT);
            assertThat(f.admissions).hasValue(0); assertThat(f.mutations).hasValue(0);
            assertThat(f.queries.stream().filter(query -> query.stripLeading().startsWith("with remaining as"))).isEmpty();
            assertThat(f.events).doesNotContain("clamp", "admission", "mutation");
        }
    }

    @Test void asyncCapacityChecksPrecedeTheStandaloneClampOfTheOriginalDeadline() throws Throwable {
        var f = new Fixture(Duration.ofSeconds(5), true);
        f.apply();
        assertThat(f.prefixRows.get(12).next()).isFalse();
        // The empty mock alone cannot prove GUC behavior. Pin the production SQL gating as well;
        // this remains structural/JDBC evidence, not a physical PostgreSQL timeout proof.
        String prefix = f.queries.getFirst();
        int remaining = prefix.lastIndexOf(", remaining as materialized");
        assertThat(remaining).isGreaterThanOrEqualTo(0);
        String clamp = prefix.substring(remaining);
        assertThat(clamp).contains("from fresh where execution_mode='SYNC')",
                "case when millis>0 then set_config('statement_timeout'",
                "case when millis>0 then set_config('lock_timeout'",
                "case when millis>0 then set_config('idle_in_transaction_session_timeout'");
        assertThat(prefix.substring(0, remaining)).doesNotContain("set_config(");
        assertThat(clamp.split("set_config\\(", -1)).hasSize(4);
        assertThat(clamp.indexOf("execution_mode='SYNC'")).isLessThan(clamp.indexOf("set_config("));
        assertThat(f.events.subList(0, 4)).containsExactly("capacity-binding", "capacity-slot", "clamp", "admission");
        assertThat(f.admissions).hasValue(1); assertThat(f.mutations).hasValue(1);
        assertThat(f.queries).hasSize(6);
        var order = inOrder(f.statements.get(1), f.statements.get(2), f.statements.get(3));
        order.verify(f.statements.get(1)).executeQuery(); order.verify(f.statements.get(1)).close();
        order.verify(f.statements.get(2)).executeQuery(); order.verify(f.statements.get(2)).close();
        order.verify(f.statements.get(3)).executeQuery();
        assertThat(f.bindings.get(3).get(1)).isEqualTo(f.unitDeadline.atOffset(ZoneOffset.UTC));
        assertThat(f.bindings.get(3).get(2)).isEqualTo(1000L);
    }

    @Test void asyncCapacityTimeDoesNotRenewTheOriginalMonotonicDeadline() throws Throwable {
        var f = new Fixture(Duration.ofMillis(40), true);
        f.capacityDelayMillis = 80;
        assertReason(f::apply, BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        assertThat(f.events).containsExactly("capacity-binding", "capacity-slot", "clamp");
        assertThat(f.bindings.get(3).get(1)).isEqualTo(f.unitDeadline.atOffset(ZoneOffset.UTC));
        assertThat(f.admissions).hasValue(0); assertThat(f.mutations).hasValue(0);
        assertThat(f.queries).hasSize(4);
    }

    @Test void perItemPrepareReturnsPersistedDeadlineAndFreshClockWithoutAnotherClockQuery() throws Throwable {
        for (boolean asynchronous : new boolean[] {false, true}) {
            var f = new Fixture(Duration.ofSeconds(5), asynchronous);
            Object attempt = f.prepare();
            var accessor = attempt.getClass().getDeclaredMethod("unitDeadline"); accessor.setAccessible(true);
            assertThat(accessor.invoke(attempt)).isEqualTo(f.unitDeadline);
            String update = f.queries.getLast();
            assertThat(update).contains("least(e.deadline_at, attempt_clock.started_at + (? * interval '1 millisecond'))",
                    "returning e.active_unit_deadline_at, clock_timestamp() as observed_at",
                    "e.owner_id=? and e.owner_epoch=?", "e.status='RUNNING' and e.next_ordinal=?");
            assertThat(f.queries.stream().filter(query -> query.equals("select clock_timestamp()"))).hasSize(1);
            var bindings = f.bindings.getLast();
            assertThat(bindings).hasSize(9);
            assertThat(bindings.get(4)).isEqualTo(5000L);
            assertThat(bindings.get(5)).isEqualTo(f.executionId);
            assertThat(bindings.get(6)).isEqualTo(f.context.namespaceId());
            assertThat(bindings.get(7)).isEqualTo("owner"); assertThat(bindings.get(8)).isEqualTo(1L);
            assertThat(bindings.get(9)).isEqualTo(0);
            assertThat(f.admissions).hasValue(0); assertThat(f.mutations).hasValue(0);
        }
    }

    @Test void prepareAnchorAccountsForExecuteDecodeAndCloseWithoutRenewingTheReturnedDeadline() throws Exception {
        var f = new TimingFixture();
        long began = f.nano.get();
        when(f.statement.executeQuery()).thenAnswer(call -> {
            assertThat(f.nanoReads).hasValue(1);
            f.nano.addAndGet(Duration.ofMillis(150).toNanos()); return f.rows;
        });
        when(f.rows.getObject(1,OffsetDateTime.class)).thenAnswer(call -> {
            f.nano.addAndGet(Duration.ofMillis(100).toNanos()); return f.deadline.atOffset(ZoneOffset.UTC);
        });
        when(f.rows.getObject(2,OffsetDateTime.class)).thenAnswer(call -> {
            f.nano.addAndGet(Duration.ofMillis(250).toNanos()); return f.observed.atOffset(ZoneOffset.UTC);
        });
        doAnswer(call -> { f.nano.addAndGet(Duration.ofMillis(50).toNanos()); return null; }).when(f.rows).close();
        var timing = f.read();
        assertThat(timing.unitDeadline()).isEqualTo(f.deadline);
        assertThat(timing.monotonicDeadlineNanos()).isEqualTo(began + Duration.ofSeconds(5).toNanos());
        assertThat(timing.monotonicDeadlineNanos()-f.nano.get()).isEqualTo(Duration.ofMillis(4450).toNanos());
        assertThat(f.nanoReads).hasValue(2);
        verify(f.statement).executeQuery(); verify(f.rows).close();
        var shorter = new TimingFixture();
        Instant executionDeadline = shorter.observed.plusSeconds(2);
        when(shorter.rows.getObject(1,OffsetDateTime.class)).thenReturn(executionDeadline.atOffset(ZoneOffset.UTC));
        var bounded = JdbcBulkDurableExecution.readAttemptTiming(shorter.statement,executionDeadline,shorter.nano::get);
        assertThat(bounded.unitDeadline()).isEqualTo(executionDeadline);
        assertThat(bounded.monotonicDeadlineNanos()).isEqualTo(shorter.nano.get()+Duration.ofSeconds(2).toNanos());
    }

    @Test void prepareMissingExtraNullAndCorruptDeadlineFailClosed() throws Exception {
        var missing = new TimingFixture(); when(missing.rows.next()).thenReturn(false);
        assertReason(missing::read,BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        var extra = new TimingFixture(); when(extra.rows.next()).thenReturn(true,true);
        assertReason(extra::read,BulkDurableExecutionException.Reason.CORRUPT);
        for (int column : new int[] {1,2}) {
            var absent = new TimingFixture(); when(absent.rows.getObject(column,OffsetDateTime.class)).thenReturn(null);
            assertReason(absent::read,BulkDurableExecutionException.Reason.CORRUPT);
        }
        var beyondExecution = new TimingFixture();
        when(beyondExecution.rows.getObject(1,OffsetDateTime.class)).thenReturn(beyondExecution.executionDeadline.plusSeconds(1).atOffset(ZoneOffset.UTC));
        assertReason(beyondExecution::read,BulkDurableExecutionException.Reason.CORRUPT);
    }

    @Test void prepareLateReturnedClockAndElapsedTransportOrDecodeCannotCreateValidAttempt() throws Exception {
        for (int delay : new int[] {0,1,2}) {
            var f = new TimingFixture();
            if (delay==0) when(f.rows.getObject(2,OffsetDateTime.class)).thenReturn(f.deadline.atOffset(ZoneOffset.UTC));
            if (delay==1) when(f.statement.executeQuery()).thenAnswer(call -> {
                f.nano.addAndGet(Duration.ofSeconds(5).toNanos()); return f.rows;
            });
            if (delay==2) when(f.rows.getObject(2,OffsetDateTime.class)).thenAnswer(call -> {
                f.nano.addAndGet(Duration.ofSeconds(5).toNanos()); return f.observed.atOffset(ZoneOffset.UTC);
            });
            assertReason(f::read,BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
            verify(f.statement).executeQuery(); verify(f.rows).close();
        }
    }

    @Test void prepareJdbcAndCloseFailuresPreserveTheOriginalFailure() throws Exception {
        var sql = new TimingFixture(); var primary = new SQLException("private execute", "57014");
        when(sql.statement.executeQuery()).thenThrow(primary);
        assertThatThrownBy(sql::read).isSameAs(primary);
        var close = new TimingFixture(); var closeFailure = new SQLException("private close", "57014");
        doThrow(closeFailure).when(close.rows).close();
        assertThatThrownBy(close::read).isSameAs(closeFailure);
        var both = new TimingFixture(); when(both.rows.next()).thenReturn(false);
        doThrow(closeFailure).when(both.rows).close();
        assertThatThrownBy(both::read).isInstanceOfSatisfying(BulkDurableExecutionException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
            assertThat(failure.getSuppressed()).containsExactly(closeFailure);
        });
    }

    @Test void ownerEpochAttemptStateAndReadyFenceFailBeforeCallbacks() throws Throwable {
        for (Map<String,Object> change : List.of(Map.<String,Object>of("owner_id","other"),
                Map.<String,Object>of("owner_epoch",2L), Map.<String,Object>of("active_attempt_epoch",2L),
                Map.<String,Object>of("active_attempt_id",UUID.randomUUID()), Map.<String,Object>of("status","RUNNING"))) {
            var f=new Fixture(); f.execution.putAll(change);
            assertThatThrownBy(f::apply).isInstanceOf(BulkDurableExecutionException.class);
            assertThat(f.admissions).hasValue(0); assertThat(f.mutations).hasValue(0);
        }
        var f=new Fixture(); when(f.prefixRows.get(10).getString(1)).thenReturn("SUSPENDED");
        assertReason(f::apply,BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        assertThat(f.admissions).hasValue(0); assertThat(f.mutations).hasValue(0);
    }

    @Test void presentReceiptRetainsCorruptPriorityWithoutDecodingAdmission() throws Throwable {
        var f=new Fixture();
        f.prefixRows.set(4,single(Map.of(1,0,2,f.digest,3,"v1",4,f.attemptId,5,1L,6,"CONFIRMED",
                7,f.now.atOffset(ZoneOffset.UTC),8,f.unitDeadline.atOffset(ZoneOffset.UTC))));
        when(f.prefixRows.get(5).next()).thenThrow(new SQLException("private admission", "57014"));
        assertReason(f::apply,BulkDurableExecutionException.Reason.CORRUPT);
        verify(f.prefixRows.get(5),never()).next();
        assertThat(f.admissions).hasValue(0); assertThat(f.mutations).hasValue(0);
    }

    @Test void invalidHistoricalPrefixRetainsReconciliationClassificationBeforeCallbacks() throws Throwable {
        var f=new Fixture();
        f.prefixRows.set(8,single(Map.of(1,3,2,f.digest,3,"v1",4,f.attemptId,5,1L,6,"CONFIRMED",
                7,f.now.atOffset(ZoneOffset.UTC),8,f.unitDeadline.atOffset(ZoneOffset.UTC))));
        assertReason(f::apply,BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
        assertThat(f.admissions).hasValue(0); assertThat(f.mutations).hasValue(0);
    }

    @Test void malformedProtectedEvaluationRetainsCorruptClassification() throws Throwable {
        var f=new Fixture(); when(f.prefixRows.get(7).getBytes(4)).thenReturn(new byte[]{'{' });
        assertReason(f::apply,BulkDurableExecutionException.Reason.CORRUPT);
        assertThat(f.admissions).hasValue(0); assertThat(f.mutations).hasValue(0);
    }

    @Test void latePrefixTransportOrDecodeCannotAdmitAfterTheOriginalMonotonicDeadline() throws Throwable {
        var f=new Fixture(Duration.ofMillis(40));
        doAnswer(invocation -> { Thread.sleep(70); return true; }).when(f.statements.getFirst()).execute();
        assertReason(f::apply,BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        assertThat(f.admissions).hasValue(0); assertThat(f.mutations).hasValue(0);
        assertThat(f.queries).hasSize(1);
        var decoding=new Fixture(Duration.ofMillis(40));
        doAnswer(invocation -> { Thread.sleep(70); return BulkSnapshotStorageCodec.encode(decoding.proposal.snapshot()); })
                .when(decoding.prefixRows.get(7)).getBytes(4);
        assertReason(decoding::apply,BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        assertThat(decoding.admissions).hasValue(0); assertThat(decoding.mutations).hasValue(0);
    }

    @Test void latePostAdmissionTransportCannotMutateEvenWhenServerClocksWereBeforeDeadline() throws Throwable {
        var f=new Fixture(Duration.ofMillis(400));
        f.postAdmissionDelayMillis=450;
        assertReason(f::apply,BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        assertThat(f.admissions).hasValue(1); assertThat(f.mutations).hasValue(0);
        assertThat(f.queries).hasSize(2);
    }

    @Test void missingReceiptOrPendingTransitionCannotProduceSuccessfulUnitWrite() throws Throwable {
        var missing=new Fixture(); when(missing.receiptRows.next()).thenReturn(false);
        assertReason(missing::apply,BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
        assertThat(missing.mutations).hasValue(1);
        var fenced=new Fixture(); fenced.pendingCount=0;
        assertReason(fenced::apply,BulkDurableExecutionException.Reason.FENCED);
    }

    @Test void unexpectedResultOrLateSqlFailureNeverReachesCallbacks() throws Throwable {
        var extra=new Fixture(); extra.prefixExtra=true;
        assertReason(extra::apply,BulkDurableExecutionException.Reason.CORRUPT);
        assertThat(extra.admissions).hasValue(0); assertThat(extra.mutations).hasValue(0);
        var failed=new Fixture(); var primary=new SQLException("private", "57014");
        when(failed.statements.getFirst().execute()).thenThrow(primary);
        assertThatThrownBy(failed::apply).isSameAs(primary);
        assertThat(failed.admissions).hasValue(0); assertThat(failed.mutations).hasValue(0);
        verify(failed.statements.getFirst()).close();
    }

    @Test void batchRequiresResultKindsAndExhaustionInOrder() throws Exception {
        var statement=mock(PreparedStatement.class); var rows=mock(ResultSet.class);
        when(statement.execute()).thenReturn(true); when(statement.getResultSet()).thenReturn(rows);
        when(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenReturn(false,false);
        when(statement.getUpdateCount()).thenReturn(1,-1);
        var batch=new JdbcBulkUnitBatch(statement);
        batch.rows(value -> { assertThat(value).isSameAs(rows); return null; });
        assertThat(batch.updated()).isEqualTo(1); batch.end();
        verify(rows).close(); verify(statement,never()).close(); // Statement belongs to the surrounding TX phase.
        var missing=mock(PreparedStatement.class); when(missing.execute()).thenReturn(false);
        assertReason(() -> new JdbcBulkUnitBatch(missing).rows(value -> null), BulkDurableExecutionException.Reason.CORRUPT);
        var extra=mock(PreparedStatement.class); when(extra.execute()).thenReturn(true);
        assertReason(() -> new JdbcBulkUnitBatch(extra).end(), BulkDurableExecutionException.Reason.CORRUPT);
    }

    @Test void postAdmissionPreservesBothFreshClocksAndRefusesExpiryInEitherResult() throws Throwable {
        for(int expired=1;expired<=2;expired++) {
            var connection=mock(Connection.class); var statement=mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            var rows=List.of(single(Map.of(1,1L)), single(Map.of(1,OffsetDateTime.now().minusSeconds(1))),
                    single(Map.of(1,OffsetDateTime.now().minusSeconds(1))));
            when(rows.get(expired).getObject(1,OffsetDateTime.class)).thenReturn(OffsetDateTime.now().plusSeconds(10));
            configure(statement,rows,false,null);
            assertReason(() -> JdbcBulkDurableExecution.afterAdmission(connection,Instant.now().plusSeconds(5),Instant.now().plusSeconds(5)),
                    BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED);
            verify(connection).prepareStatement(anyString()); verify(statement).execute(); verify(statement).close();
        }
    }

    private static void assertReason(Throwing work,BulkDurableExecutionException.Reason reason) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(reason));
    }
    @FunctionalInterface interface Throwing { void run() throws Throwable; }

    private static ResultSet single(Map<?,?> values) throws Exception {
        var rows=mock(ResultSet.class, invocation -> {
            if(invocation.getMethod().getName().startsWith("get") && invocation.getArguments().length>0)
                return values.get(invocation.getArgument(0));
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(rows.next()).thenReturn(true,false); return rows;
    }
    private static ResultSet empty() throws Exception { return mock(ResultSet.class); }
    private static void configure(PreparedStatement statement,List<ResultSet> rows,boolean extra,AtomicInteger current) throws Exception {
        AtomicInteger index=current==null ? new AtomicInteger() : current;
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenAnswer(call -> index.get()<rows.size() ? rows.get(index.get()) : null);
        when(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenAnswer(call -> index.incrementAndGet()<rows.size() || extra);
        when(statement.getUpdateCount()).thenReturn(-1);
    }

    private static final class TimingFixture {
        final Instant observed=Instant.parse("2026-10-12T15:00:00Z");
        final Instant deadline=observed.plusSeconds(5), executionDeadline=observed.plusSeconds(30);
        final java.util.concurrent.atomic.AtomicLong nano=new java.util.concurrent.atomic.AtomicLong(10_000_000_000L);
        final AtomicInteger nanoReads=new AtomicInteger();
        final PreparedStatement statement=mock(PreparedStatement.class);
        final ResultSet rows=mock(ResultSet.class);
        TimingFixture() throws Exception {
            when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true,false);
            when(rows.getObject(1,OffsetDateTime.class)).thenReturn(deadline.atOffset(ZoneOffset.UTC));
            when(rows.getObject(2,OffsetDateTime.class)).thenReturn(observed.atOffset(ZoneOffset.UTC));
        }
        JdbcBulkDurableExecution.AttemptTiming read() throws SQLException {
            return JdbcBulkDurableExecution.readAttemptTiming(statement,executionDeadline,() -> {
                nanoReads.incrementAndGet(); return nano.get();
            });
        }
    }

    private static final class Fixture {
        final UUID executionId=UUID.randomUUID(), attemptId=UUID.randomUUID();
        final Instant now=Instant.now(), deadline=now.plusSeconds(30), unitDeadline=now.plusSeconds(5);
        final BulkFingerprintContext context=new BulkFingerprintContext("namespace-a","subject-a","employees",
                new CanonicalOperationRef("admin","employee-bulk-edit","/employees/bulk","PATCH"),"structural-r1",ActionCollectionAtomicity.PER_ITEM);
        final BulkStoredProposal proposal;
        final BulkEvaluationSnapshot evaluation;
        final String digest;
        final BulkCapacityBinding capacity;
        final Map<String,Object> execution=new HashMap<>();
        final Connection connection=mock(Connection.class);
        final List<String> queries=new ArrayList<>();
        final List<String> events=new ArrayList<>();
        final List<Map<Integer,Object>> bindings=new ArrayList<>();
        final List<PreparedStatement> statements=new ArrayList<>();
        final List<ResultSet> prefixRows=new ArrayList<>();
        final AtomicInteger admissions=new AtomicInteger(),mutations=new AtomicInteger();
        final Class<?> attemptType;
        final Duration remaining;
        final JdbcBulkDurableExecution service;
        final ResultSet receiptRows;
        int pendingCount=1; boolean prefixExtra; long postAdmissionDelayMillis;
        boolean capacityActive=true, capacitySlotPresent=true; long capacityDelayMillis;
        Fixture() throws Exception { this(Duration.ofSeconds(5), false); }
        Fixture(Duration remaining) throws Exception { this(remaining, false); }
        Fixture(Duration remaining, boolean asynchronous) throws Exception {
            if (asynchronous) {
                var request = new BulkProtocolReader<>(BulkIdentityCodecs.strings()).readUniform(
                        BulkSnapshotStorageCodecTest.bytes("{\"executionMode\":\"ASYNC\",\"selection\":{\"mode\":\"EXPLICIT\",\"targets\":[{\"id\":\"101\",\"expectedVersion\":\"v1\"}]},\"changes\":[{\"field\":\"amount\",\"operator\":\"SET\",\"value\":1.0}]}"));
                var input = BulkIntentSnapshot.uniform(context, BulkIdentityCodecs.strings(), request,
                        com.fasterxml.jackson.databind.JsonNode::deepCopy);
                proposal = BulkStoredProposal.asynchronous(UUID.randomUUID(), Instant.parse("2026-09-13T12:00:00Z"),
                        Instant.parse("2026-09-13T12:15:00Z"), input, BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
                // Explicit mock identity; no database provisioning or ASYNC readiness claim.
                capacity = new BulkCapacityBinding("unit-batch-test", "fixture-tenant", "test", "fixture-binding",
                        1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
            } else {
                proposal = BulkSnapshotStorageCodecTest.proposal(BulkSnapshotStorageCodecTest.snapshot(context,
                        BulkMode.UNIFORM_UPDATE, BulkIdentityCodecs.strings(), "\"101\"", "1.0"));
                capacity = null;
            }
            evaluation = BulkEvaluationSnapshotTest.evaluation(proposal);
            digest = BulkTargetDigest.of(evaluation.fingerprint(),0,"101","v1");
            var dataSource=mock(javax.sql.DataSource.class);
            var infrastructure=new BulkExecutionInfrastructure(dataSource,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),
                    context.namespaceId(), "unit-batch-test", new BulkExecutionRoleConfiguration("fixture-owner",Set.of("fixture-runtime"),Set.of(),Set.of("fixture-control")));
            service=new JdbcBulkDurableExecution(infrastructure, null, capacity);
            attemptType=Class.forName(JdbcBulkDurableExecution.class.getName()+"$Attempt");
            this.remaining=remaining;
            execution.putAll(Map.of("execution_id",executionId,"proposal_id",proposal.id(),"namespace_id",context.namespaceId(),"subject_id",context.subjectId(),"resource_key",context.resourceKey(),"operation_id",context.operationRef().operationId(),"input_fingerprint",proposal.snapshot().fingerprint(),"evaluation_fingerprint",evaluation.fingerprint(),"structural_revision","structural-r1","control_generation",1L));
            execution.putAll(Map.of("control_descriptor_fingerprint",BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION.descriptorFingerprint(),"owner_id","owner","owner_epoch",1L,"status","UNIT_IN_FLIGHT","next_ordinal",0,"target_count",1,"deadline_at",deadline.atOffset(ZoneOffset.UTC),"active_attempt_id",attemptId,"active_attempt_ordinal",0,"active_target_digest",digest));
            execution.putAll(Map.of("active_attempt_epoch",1L,"active_unit_deadline_at",unitDeadline.atOffset(ZoneOffset.UTC),"receipt_count",0,"admission_count",0,"atomicity","PER_ITEM","protocol_version",(short)2,"execution_mode",asynchronous ? "ASYNC" : "SYNC"));
            Map<Integer,Object> control=Map.of(1,"READY",2,1L,3,BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION.descriptorFingerprint(),4,"structural-r1");
            Map<Integer,Object> protectedRow=new HashMap<>(Map.of(1,proposal.createdAt().atOffset(ZoneOffset.UTC),2,proposal.expiresAt().atOffset(ZoneOffset.UTC),3,proposal.snapshot().fingerprint(),4,BulkSnapshotStorageCodec.encode(proposal.snapshot()),5,1L,6,BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION.descriptorFingerprint(),7,"structural-r1",8,evaluation.fingerprint(),9,BulkEvaluationStorageCodec.encode(evaluation),10,"PER_ITEM"));
            protectedRow.put(11,(short)2); protectedRow.put(12,asynchronous ? "ASYNC" : "SYNC");
            prefixRows.addAll(List.of(single(Map.of(1,"ACTIVE")),single(Map.of(1,context.operationRef().operationId())),single(control),single(execution),empty(),empty(),single(Map.of(1,now.atOffset(ZoneOffset.UTC))),single(protectedRow),empty(),empty(),single(control),single(Map.of(1,now.atOffset(ZoneOffset.UTC))),asynchronous ? empty() : single(Map.of(1,4500L))));
            var first=mock(PreparedStatement.class); statements.add(first);
            configure(first,prefixRows,false,null);
            var cursor=new AtomicInteger();
            when(first.getResultSet()).thenAnswer(call -> cursor.get()<13 ? prefixRows.get(cursor.get()) : null);
            when(first.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenAnswer(call -> cursor.incrementAndGet()<13 || prefixExtra);
            receiptRows=single(Map.of(1,now.atOffset(ZoneOffset.UTC),2,unitDeadline.atOffset(ZoneOffset.UTC)));
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                int phase=queries.size(); String query=call.getArgument(0); queries.add(query);
                PreparedStatement statement;
                if(phase==0) statement=first;
                else if(query.contains("select m.database_id,m.deployment_id")) {
                    statement=mock(PreparedStatement.class); statements.add(statement);
                    var rows=single(Map.of(1,capacity.databaseId(),2,capacity.deploymentId(),3,capacity.tenantId(),
                            4,capacity.environment(),5,capacity.bindingId(),6,capacity.generation(),7,capacity.attestationId(),
                            8,capacity.authorityId(),9,capacity.authorityEpoch(),10,capacityActive ? "ACTIVE" : "SUSPENDED"));
                    when(statement.executeQuery()).thenAnswer(ignored -> {
                        events.add("capacity-binding");
                        if(capacityDelayMillis>0) Thread.sleep(capacityDelayMillis);
                        return rows;
                    });
                } else if(query.contains("from praxis_bulk.praxis_bulk_capacity_slot s")) {
                    statement=mock(PreparedStatement.class); statements.add(statement);
                    var rows=capacitySlotPresent ? single(Map.of(1,1)) : empty();
                    when(statement.executeQuery()).thenAnswer(ignored -> { events.add("capacity-slot"); return rows; });
                } else if(query.stripLeading().startsWith("with remaining as") && !query.contains(";")) {
                    statement=mock(PreparedStatement.class); statements.add(statement);
                    var rows=single(Map.of(1,4500L));
                    when(statement.executeQuery()).thenAnswer(ignored -> { events.add("clamp"); return rows; });
                } else if(query.stripLeading().startsWith("with remaining as")) {
                    statement=mock(PreparedStatement.class); statements.add(statement);
                    configure(statement,List.of(single(Map.of(1,3000L)),single(Map.of(1,now.atOffset(ZoneOffset.UTC))),single(Map.of(1,now.atOffset(ZoneOffset.UTC)))),false,null);
                    when(statement.execute()).thenAnswer(ignored -> { if(postAdmissionDelayMillis>0) Thread.sleep(postAdmissionDelayMillis); return true; });
                } else {
                    statement=mock(PreparedStatement.class); statements.add(statement);
                    var index=new AtomicInteger(); when(statement.execute()).thenReturn(true);
                    when(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenAnswer(ignored -> index.incrementAndGet()==2);
                    when(statement.getUpdateCount()).thenAnswer(ignored -> index.get()==1 ? pendingCount : -1);
                    when(statement.getResultSet()).thenAnswer(ignored -> index.get()==0 ? receiptRows : single(execution));
                }
                Map<Integer,Object> values=new HashMap<>(); bindings.add(values);
                doAnswer(inv -> { values.put(inv.getArgument(0),inv.getArgument(1)); return null; }).when(statement).setObject(anyInt(),any());
                doAnswer(inv -> { values.put(inv.getArgument(0),inv.getArgument(1)); return null; }).when(statement).setString(anyInt(),any());
                doAnswer(inv -> { values.put(inv.getArgument(0),inv.getArgument(1)); return null; }).when(statement).setLong(anyInt(),anyLong());
                doAnswer(inv -> { values.put(inv.getArgument(0),inv.getArgument(1)); return null; }).when(statement).setInt(anyInt(),anyInt());
                return statement;
            });
        }
        Object prepare() throws Throwable {
            execution.put("status","RUNNING");
            for (String key : List.of("active_attempt_id","active_attempt_ordinal","active_target_digest",
                    "active_attempt_epoch","active_unit_deadline_at")) execution.put(key,null);
            var controlQueries=new AtomicInteger();
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String query=call.getArgument(0); queries.add(query);
                var statement=mock(PreparedStatement.class);
                ResultSet rows;
                if(query.equals("select praxis_bulk.lock_capacity_marker()")) rows=prefixRows.get(0);
                else if(query.contains("select operation_id from")) rows=prefixRows.get(1);
                else if(query.contains("from praxis_bulk.lock_operation_control")) rows=prefixRows.get(controlQueries.getAndIncrement()==0 ? 2 : 10);
                else if(query.contains("select e.*")) rows=prefixRows.get(3);
                else if(query.contains("from praxis_bulk.praxis_bulk_item_receipt")) rows=prefixRows.get(query.contains("order by") ? 8 : 4);
                else if(query.contains("from praxis_bulk.praxis_bulk_admission")) rows=prefixRows.get(query.contains("order by") ? 9 : 5);
                else if(query.contains("select p.created_at")) rows=prefixRows.get(7);
                else if(query.contains("select m.database_id,m.deployment_id")) rows=single(Map.of(1,capacity.databaseId(),2,capacity.deploymentId(),3,capacity.tenantId(),4,capacity.environment(),5,capacity.bindingId(),6,capacity.generation(),7,capacity.attestationId(),8,capacity.authorityId(),9,capacity.authorityEpoch(),10,"ACTIVE"));
                else if(query.contains("from praxis_bulk.praxis_bulk_capacity_slot s")) rows=single(Map.of(1,1));
                else if(query.equals("select clock_timestamp()")) rows=single(Map.of(1,now.atOffset(ZoneOffset.UTC)));
                else if(query.contains("returning e.active_unit_deadline_at, clock_timestamp() as observed_at")) rows=single(Map.of(1,unitDeadline.atOffset(ZoneOffset.UTC),2,now.atOffset(ZoneOffset.UTC)));
                else throw new AssertionError("Unexpected prepare SQL shape");
                when(statement.executeQuery()).thenReturn(rows);
                Map<Integer,Object> values=new HashMap<>();bindings.add(values);
                doAnswer(inv -> { values.put(inv.getArgument(0),inv.getArgument(1));return null; }).when(statement).setObject(anyInt(),any());
                doAnswer(inv -> { values.put(inv.getArgument(0),inv.getArgument(1));return null; }).when(statement).setString(anyInt(),any());
                doAnswer(inv -> { values.put(inv.getArgument(0),inv.getArgument(1));return null; }).when(statement).setLong(anyInt(),anyLong());
                doAnswer(inv -> { values.put(inv.getArgument(0),inv.getArgument(1));return null; }).when(statement).setInt(anyInt(),anyInt());
                return statement;
            });
            var method=JdbcBulkDurableExecution.class.getDeclaredMethod("prepare",Connection.class,BulkExecutionControl.class,int.class);
            method.setAccessible(true);
            Object preparation;
            try { preparation=method.invoke(service,connection,new BulkExecutionControl(executionId,"owner",1L),0); }
            catch(InvocationTargetException failure) { throw failure.getCause(); }
            var accessor=preparation.getClass().getDeclaredMethod("attempt");accessor.setAccessible(true);
            return accessor.invoke(preparation);
        }
        void apply() throws Throwable {
            var constructor=attemptType.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            var method=JdbcBulkDurableExecution.class.getDeclaredMethod("applyAndReceipt",Connection.class,BulkExecutionControl.class,attemptType,BulkUnitAdmissionCallback.class,BulkUnitMutationCallback.class);
            method.setAccessible(true);
            Object attempt=constructor.newInstance(attemptId,0,digest,1L,unitDeadline,System.nanoTime()+remaining.toNanos());
            try { method.invoke(service,connection,new BulkExecutionControl(executionId,"owner",1L),attempt,
                    (BulkUnitAdmissionCallback)unit -> { events.add("admission"); admissions.incrementAndGet(); return BulkUnitAdmission.admit(); },
                    (BulkUnitMutationCallback)unit -> { events.add("mutation"); mutations.incrementAndGet(); return BulkUnitMutationResult.confirmed(); }); }
            catch(InvocationTargetException error) { throw error.getCause(); }
        }
    }
}
