package org.praxisplatform.uischema.bulk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public entry point with JDBC/transaction doubles, not a PostgreSQL or authorization proof. */
class BulkHistoricalReservationLookupTest {
    @Test void historicalReplaySurvivesUnavailableLifecycleAndPreservesOriginalDeadline() throws Exception {
        var f = new Fixture(false);
        var first = f.lookup("key-a").orElseThrow();
        var replay = f.lookup("another-key").orElseThrow();
        assertThat(first.executionId()).isEqualTo(f.executionId);
        assertThat(replay.executionId()).isEqualTo(first.executionId());
        assertThat(first.deadlineAt()).isEqualTo(f.deadline);
        assertThat(replay.deadlineAt()).isEqualTo(f.deadline);
        assertThat(first.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
        assertThat(first.receiptCount()).isEqualTo(1);
        assertThat(first.control().epoch()).isEqualTo(3);
        verifyNoInteractions(f.lifecycle);
        f.assertOnlyHistoricalReads();
    }

    @Test void storedAsyncUsesSameLookupWithoutCapacityOrAdmission() throws Exception {
        var f = new Fixture(true);
        assertThat(f.lookup("key-a").orElseThrow().executionId()).isEqualTo(f.executionId);
        verifyNoInteractions(f.lifecycle);
        f.assertOnlyHistoricalReads();
    }

    @Test void evaluatedProposalWithoutReservationDoesNotCreateOne() throws Exception {
        var f = new Fixture(false); f.reserved = false;
        assertThat(f.lookup("key-a")).isEmpty();
        f.assertOnlyHistoricalReads();
    }

    @Test void keyOfDifferentProposalAndChangedRevisionConflict() throws Exception {
        var f = new Fixture(false);
        f.execution.put("reservation_fingerprint", "sha256:" + "f".repeat(64));
        reason(() -> f.lookup("key-used-by-other-proposal"), BulkDurableExecutionException.Reason.CONFLICT);
        f.execution.put("reservation_fingerprint", f.fingerprint());
        reason(() -> f.kernel.findReservation(f.scope, f.proposal.id(), "key-a", "changed"),
                BulkDurableExecutionException.Reason.CONFLICT);
        f.assertOnlyHistoricalReads();
    }

    @Test void duplicateReservationIsConflict() throws Exception {
        var f = new Fixture(false); f.duplicate = true;
        reason(() -> f.lookup("key-a"), BulkDurableExecutionException.Reason.CONFLICT);
    }

    @Test void foreignOrUnknownProposalRemainsNotFound() throws Exception {
        var f = new Fixture(false);
        var foreign = new BulkFingerprintContext(f.scope.namespaceId(), "other-subject", f.scope.resourceKey(),
                f.scope.operationRef(), f.scope.schemaRevision(), f.scope.atomicity());
        reason(() -> f.kernel.findReservation(foreign, f.proposal.id(), "key-a", "structural-r1"),
                BulkDurableExecutionException.Reason.NOT_FOUND);
        reason(() -> f.kernel.findReservation(f.scope, UUID.randomUUID(), "key-a", "structural-r1"),
                BulkDurableExecutionException.Reason.NOT_FOUND);
        assertThat(f.queries).noneMatch(sql -> sql.contains("select e.*"));
    }

    @Test void eitherTombstoneStopsBeforeEvaluationAndReservation() throws Exception {
        for (boolean key : List.of(true, false)) {
            var f = new Fixture(false); f.keyPurged = key; f.proposalPurged = !key;
            reason(() -> f.lookup("key-a"), BulkDurableExecutionException.Reason.RESULT_PURGED);
            assertThat(f.queries).allMatch(sql -> sql.contains("praxis_bulk_tombstone"));
        }
    }

    @Test void sqlFailureIsUnavailableWithoutRetryOrLeakedCause() throws Exception {
        var f = new Fixture(false); f.jdbcFailure = true;
        reason(() -> f.lookup("key-a"), BulkDurableExecutionException.Reason.UNAVAILABLE);
        assertThat(f.queries).hasSize(1);
        verify(f.transactionManager).rollback(any());
    }

    @Test void invalidArgumentsAndAmbientTransactionRejectBeforeInfrastructureWork() throws Exception {
        var f = new Fixture(false);
        var foreign = new BulkFingerprintContext("other-namespace", f.scope.subjectId(), f.scope.resourceKey(),
                f.scope.operationRef(), f.scope.schemaRevision(), f.scope.atomicity());
        assertThatThrownBy(() -> f.kernel.findReservation(foreign, f.proposal.id(), "key", "r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.lookup(" key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.kernel.findReservation(f.scope, f.proposal.id(), "key", ""))
                .isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> f.lookup("key")).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        TransactionSynchronizationManager.initSynchronization();
        try { assertThatThrownBy(() -> f.lookup("key")).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
        verifyNoInteractions(f.connection, f.transactionManager, f.lifecycle);
    }

    private static void reason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            BulkDurableExecutionException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason)).hasNoCause();
    }

    private static final class Fixture {
        final BulkFingerprintContext scope = new BulkFingerprintContext("namespace-a", "subject-a", "employees",
                new CanonicalOperationRef("admin", "employee-bulk-edit", "/employees/bulk", "PATCH"),
                "structural-r1", ActionCollectionAtomicity.PER_ITEM);
        final BulkStoredProposal proposal;
        final BulkEvaluationSnapshot evaluation;
        final UUID executionId = UUID.randomUUID();
        final Instant deadline = Instant.parse("2026-09-13T12:10:00Z"); // Deliberately expired history.
        final Map<Object, Object> execution = new HashMap<>();
        final Map<Object, Object> storedEvaluation = new HashMap<>();
        final Connection connection = mock(Connection.class);
        final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        final BulkOperationLifecycle lifecycle = mock(BulkOperationLifecycle.class,
                withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        final List<String> queries = new ArrayList<>();
        final List<PreparedStatement> statements = new ArrayList<>();
        final JdbcBulkDurableExecution kernel;
        boolean reserved = true, duplicate, keyPurged, proposalPurged, jdbcFailure;

        Fixture(boolean async) throws Exception {
            var input = BulkSnapshotStorageCodecTest.snapshot(scope, BulkMode.UNIFORM_UPDATE,
                    BulkIdentityCodecs.strings(), "\"101\"", "1.0");
            if (async) {
                var request = new BulkProtocolReader<>(BulkIdentityCodecs.strings()).readUniform(
                        BulkSnapshotStorageCodecTest.bytes("{\"executionMode\":\"ASYNC\",\"selection\":{\"mode\":\"EXPLICIT\",\"targets\":[{\"id\":\"101\",\"expectedVersion\":\"v1\"}]},\"changes\":[{\"field\":\"amount\",\"operator\":\"SET\",\"value\":1}]}"));
                input = BulkIntentSnapshot.uniform(scope, BulkIdentityCodecs.strings(), request,
                        com.fasterxml.jackson.databind.JsonNode::deepCopy);
                proposal = BulkStoredProposal.asynchronous(UUID.randomUUID(), Instant.parse("2026-09-13T12:00:00Z"),
                        Instant.parse("2026-09-13T12:15:00Z"), input, BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
            } else proposal = BulkSnapshotStorageCodecTest.proposal(input);
            evaluation = BulkEvaluationSnapshotTest.evaluation(proposal);
            var expectation = proposal.controlExpectation();
            storedEvaluation.putAll(Map.of(1, proposal.createdAt().atOffset(ZoneOffset.UTC),
                    2, proposal.expiresAt().atOffset(ZoneOffset.UTC), 3, input.fingerprint(),
                    4, BulkSnapshotStorageCodec.encode(input), 5, expectation.generation(),
                    6, expectation.descriptorFingerprint(), 7, expectation.structuralRevision(),
                    8, evaluation.fingerprint(), 9, BulkEvaluationStorageCodec.encode(evaluation), 10, "PER_ITEM"));
            storedEvaluation.put(11, (short) 2); storedEvaluation.put(12, async ? "ASYNC" : "SYNC");
            execution.putAll(Map.of("execution_id", executionId, "proposal_id", proposal.id(),
                    "namespace_id", scope.namespaceId(), "subject_id", scope.subjectId(),
                    "resource_key", scope.resourceKey(), "operation_id", scope.operationRef().operationId(),
                    "reservation_fingerprint", fingerprint(), "input_fingerprint", input.fingerprint(),
                    "evaluation_fingerprint", evaluation.fingerprint(), "structural_revision", "structural-r1"));
            execution.putAll(Map.of("control_generation", 1L, "control_descriptor_fingerprint", expectation.descriptorFingerprint(),
                    "owner_id", "historic-owner", "owner_epoch", 3L, "status", "COMPLETED", "next_ordinal", 1,
                    "target_count", 1, "deadline_at", deadline.atOffset(ZoneOffset.UTC), "receipt_count", 1,
                    "admission_count", 0));
            execution.put("atomicity", "PER_ITEM"); execution.put("protocol_version", (short) 2);
            execution.put("execution_mode", async ? "ASYNC" : "SYNC");
            var infrastructure = mock(BulkExecutionInfrastructure.class,
                    withSettings().mockMaker(org.mockito.MockMakers.INLINE));
            when(infrastructure.namespace()).thenReturn(scope.namespaceId());
            when(infrastructure.transactionManager()).thenReturn(transactionManager);
            when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            when(infrastructure.withConnection(any())).thenAnswer(call -> {
                ConnectionCallback<?> work = call.getArgument(0); return work.doInConnection(connection);
            });
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0); queries.add(sql);
                if (jdbcFailure) throw new SQLException("private JDBC detail", "08006");
                var statement = mock(PreparedStatement.class); statements.add(statement);
                Map<Integer,Object> bound = new HashMap<>();
                doAnswer(inv -> { bound.put(inv.getArgument(0),inv.getArgument(1)); return null; }).when(statement).setString(anyInt(),any());
                doAnswer(inv -> { bound.put(inv.getArgument(0),inv.getArgument(1)); return null; }).when(statement).setObject(anyInt(),any());
                when(statement.executeQuery()).thenAnswer(inv -> {
                    if (sql.contains("praxis_bulk_tombstone"))
                        return rows(Map.of(1,1), (sql.contains("idempotency_key_digest") ? keyPurged : proposalPurged) ? 1 : 0);
                    if (sql.contains("select p.created_at"))
                        return rows(storedEvaluation, proposal.id().equals(bound.get(1)) && scope.subjectId().equals(bound.get(3)) ? 1 : 0);
                    if (sql.contains("select e.*")) return rows(execution, reserved ? duplicate ? 2 : 1 : 0);
                    throw new AssertionError("Unexpected SQL in historical lookup");
                });
                return statement;
            });
            kernel = new JdbcBulkDurableExecution(infrastructure, lifecycle);
        }
        Optional<BulkExecutionSnapshot> lookup(String key) {
            return kernel.findReservation(scope, proposal.id(), key, "structural-r1");
        }
        // Independent framing reference; does not call private production fingerprint code.
        String fingerprint() throws Exception {
            var digest = MessageDigest.getInstance("SHA-256");
            var expectation = proposal.controlExpectation();
            for (String part : List.of("praxis.bulk.reservation/2", proposal.id().toString(),
                    proposal.snapshot().fingerprint(), evaluation.fingerprint(), scope.namespaceId(), scope.subjectId(),
                    scope.resourceKey(), scope.operationRef().operationId(), Long.toString(expectation.generation()),
                    expectation.descriptorFingerprint(), expectation.structuralRevision(), "structural-r1")) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        }
        void assertOnlyHistoricalReads() throws SQLException {
            assertThat(queries).allMatch(sql -> sql.stripLeading().startsWith("select "));
            assertThat(queries).noneMatch(sql -> sql.contains("clock_timestamp") || sql.contains("capacity_slot")
                    || sql.contains("lock_operation_control") || sql.contains("set_config"));
            for (var statement : statements) { verify(statement, never()).executeUpdate(); verify(statement).close(); }
        }
        static ResultSet rows(Map<Object,Object> values, int count) throws SQLException {
            var index = new AtomicInteger();
            var rows = mock(ResultSet.class, invocation -> {
                String method = invocation.getMethod().getName();
                if (method.equals("next")) return index.incrementAndGet() <= count;
                if (method.startsWith("get") && invocation.getArguments().length > 0) {
                    Object value = values.get(invocation.getArgument(0));
                    if (value != null) return value;
                    if (method.equals("getInt")) return 0;
                    if (method.equals("getLong")) return 0L;
                    if (method.equals("getShort")) return (short)0;
                    return null;
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
            return rows;
        }
    }
}
