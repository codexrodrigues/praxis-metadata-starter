package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.*;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.evaluation;
import static org.praxisplatform.uischema.bulk.BulkReadBatchBoundaryTest.batch;
import static org.praxisplatform.uischema.bulk.BulkReadBatchBoundaryTest.rows;
import static org.praxisplatform.uischema.bulk.BulkReadFailureDiagnostics.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** No database: real reader/Spring boundary, canonical protected codecs and JDBC doubles. */
class BulkReadFailureDiagnosticsTest {
    @Test void graphKeepsCauseAndSqlStateWithoutMessagesFilenameOrUntrustedFrames() {
        var sql = new SQLException("password=secret SQL SELECT protected", "57014");
        sql.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("org.postgresql.core.v3.QueryExecutorImpl", "receiveErrorResponse", "private-path", 123),
                new StackTraceElement("tenant.secret.Customer", "secret", "secret", 9),
                new StackTraceElement("org.postgresql.Driver", "token=secret", "secret", 8)});
        var failure = new IllegalStateException("SESSION=secret", sql);
        failure.addSuppressed(new SQLException("cleanup-secret", "bad-state-secret"));
        var event = project(Reader.PROPOSAL, Stage.SNAPSHOT_TRANSACTION, Reason.EXCEPTION, failure);
        String json = event.toString();
        assertThat(json).doesNotContain("secret", "SELECT", "private-path", "Customer", "bad-state");
        assertThat(event.at("/nodes/0/cause").asInt()).isEqualTo(1);
        assertThat(event.at("/nodes/1/sqlState").asText()).isEqualTo("57014");
        assertThat(event.at("/nodes/1/frames")).hasSize(1);
        assertThat(event.at("/nodes/1/frames/0/methodName").asText()).isEqualTo("receiveErrorResponse");
        assertThat(event.at("/nodes/2/sqlState").isNull()).isTrue();
    }

    @Test void graphBoundsCyclesSuppressedNodesAndFrames() {
        var first = new IllegalStateException(); var second = new IllegalStateException();
        first.initCause(second); second.initCause(first);
        for (int i = 0; i < 20; i++) first.addSuppressed(new SQLException("not exported"));
        var frames = new StackTraceElement[40];
        Arrays.fill(frames, new StackTraceElement("java.sql.Connection", "commit", "private", 1));
        first.setStackTrace(frames);
        var event = project(Reader.PROPOSAL_RESULTS, Stage.PREVIEW, Reason.EXCEPTION, first);
        assertThat(event.path("referencesReused").asBoolean()).isTrue();
        assertThat(event.at("/nodes/0/frames")).hasSize(MAX_FRAMES);
        assertThat(event.at("/nodes/0/framesTruncated").asBoolean()).isTrue();
        assertThat(event.at("/nodes/0/suppressed")).hasSize(MAX_SUPPRESSED);
        assertThat(event.at("/nodes/0/suppressedTruncated").asBoolean()).isTrue();
        Throwable chain = new IllegalStateException();
        for (int i = 0; i < 30; i++) chain = new IllegalStateException(chain);
        event = project(Reader.PROPOSAL, Stage.PROJECTION, Reason.EXCEPTION, chain);
        assertThat(event.path("nodes")).hasSize(MAX_THROWABLES);
        assertThat(event.path("throwablesTruncated").asBoolean()).isTrue();
    }

    @Test void noThrowableBranchHasClosedReasonAndEmptyGraph() {
        var event = project(Reader.PROPOSAL, Stage.PROJECTION, Reason.REDACTED_INTENT_INVALID, null);
        assertThat(event.path("reason").asText()).isEqualTo("REDACTED_INTENT_INVALID");
        assertThat(event.path("throwablePresent").asBoolean()).isFalse();
        assertThat(event.path("nodes")).isEmpty();
    }

    @Test void observerProjectionAndSinkFailuresNeverEscapeOrRetry() {
        var calls = new AtomicInteger();
        assertThatCode(() -> emit(Reader.PROPOSAL, Stage.PROJECTION, Reason.EXCEPTION,
                new IllegalStateException(), s -> { calls.incrementAndGet(); throw new AssertionError("sink"); }))
                .doesNotThrowAnyException();
        assertThat(calls).hasValue(1);
        Throwable broken = new IllegalStateException() {
            @Override public StackTraceElement[] getStackTrace() { throw new AssertionError("projection"); }
        };
        assertThatCode(() -> emit(Reader.PROPOSAL, Stage.PROJECTION, Reason.EXCEPTION,
                broken, s -> calls.incrementAndGet())).doesNotThrowAnyException();
        assertThatCode(() -> emit(null, Stage.PROJECTION, Reason.EXCEPTION,
                null, s -> calls.incrementAndGet())).doesNotThrowAnyException();
        assertThat(calls).hasValue(1);
    }

    @Test void bothReadersKeepUnavailableOnPhysicalSnapshotGuardAndNeverAuthorize() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            f.jdbc.fault = "rc";
            assertThat(f.proposal.readProposal("subject", UUID.randomUUID()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);
            assertThat(f.results.readProposalResults("subject", UUID.randomUUID(), 1).state())
                    .isEqualTo(BulkAuthorizedProposalResultsReader.State.UNAVAILABLE);
            verify(f.auth, never()).preAuthorize(any(), any());
            verify(f.jdbc.connection, times(2)).rollback();
            assertThat(log.events()).hasSize(2).allSatisfy(e -> {
                assertThat(e.path("stage").asText()).isEqualTo("SNAPSHOT_TRANSACTION");
                assertThat(e.path("reason").asText()).isEqualTo("EXCEPTION");
                assertThat(e.toString()).contains("constrainSnapshotAttestationTimeouts");
            });
        }
    }

    @Test void catalogPrimaryAndRestoreSuppressedAreKeptAndSinkFailureKeepsState() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            f.jdbc.primary = new IllegalStateException("catalog-secret", new SQLException("SQL-secret", "57014"));
            f.jdbc.restoreFailure = new SQLException("restore-secret", "08006");
            assertThat(f.proposal.readProposal("subject", UUID.randomUUID()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);
            var event = log.events().getFirst();
            assertThat(event.toString()).contains("57014", "08006").doesNotContain("secret");
            assertThat(f.jdbc.primary.getSuppressed()).containsExactly(f.jdbc.restoreFailure);
        }
        try (var f = new Readers(); var logger = mockStatic(LoggerFactory.class, withSettings().mockMaker("mock-maker-inline").defaultAnswer(CALLS_REAL_METHODS))) {
            f.jdbc.fault = "rc";
            logger.when(() -> LoggerFactory.getLogger(BulkReadFailureDiagnostics.class)).thenThrow(new AssertionError("logger"));
            assertThat(f.results.readProposalResults("subject", UUID.randomUUID(), 1).state())
                    .isEqualTo(BulkAuthorizedProposalResultsReader.State.UNAVAILABLE);
            verify(f.jdbc.connection).rollback();
        }
    }

    @Test void authorizationExceptionAndNullDecisionRemainGlobalUnavailable() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            when(f.auth.preAuthorize(any(), any())).thenThrow(new IllegalStateException("secret"));
            assertThat(f.proposal.readProposal("subject", UUID.randomUUID()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.GLOBAL_UNAVAILABLE);
            doReturn(null).when(f.auth).preAuthorize(any(), any());
            assertThat(f.results.readProposalResults("subject", UUID.randomUUID(), 1).state())
                    .isEqualTo(BulkAuthorizedProposalResultsReader.State.GLOBAL_UNAVAILABLE);
            assertThat(log.events()).extracting(e -> e.path("reason").asText())
                    .containsExactly("EXCEPTION", "GLOBAL_DECISION_MISSING");
            verify(f.auth, never()).authorize(any(), anyString(), anyList(), any());
        }
    }

    @Test void denialAndInvalidCursorRemainExpectedOutcomesWithoutFailureLogOrExtraSql() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            when(f.auth.preAuthorize(any(), any())).thenReturn(BulkReadAuthorizationProvider.GlobalDecision.DENIED);
            assertThat(f.proposal.readProposal("subject", UUID.randomUUID()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.GLOBAL_DENIED);
            clearInvocations(f.jdbc.ds, f.auth);
            assertThat(f.results.readProposalResults("subject", UUID.randomUUID(), 1, "invalid").state())
                    .isEqualTo(BulkAuthorizedProposalResultsReader.State.INVALID_CURSOR);
            verifyNoInteractions(f.jdbc.ds, f.auth);
            assertThat(log.events()).isEmpty();
        }
    }

    @Test void realProtectedDecodeThenInvalidProjectionKeepsUnavailableAndNoThrowableReason() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            var p = f.seedProtectedEvaluation();
            when(f.projection.projectRedactedIntent(any())).thenReturn(null);
            assertThat(f.proposal.readProposal(CONTEXT.subjectId(), p.id()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);
            verify(f.auth).authorize(any(), eq(CONTEXT.subjectId()), argThat(t -> t.size() == 1), any());
            verify(f.jdbc.connection).commit();
            assertThat(log.events()).hasSize(1);
            assertThat(log.events().getFirst().path("reason").asText()).isEqualTo("REDACTED_INTENT_INVALID");
            assertThat(log.events().getFirst().path("nodes")).isEmpty();
        }
    }

    @Test void expiryAfterOwnedTransactionIsRecordedAfterItsCompletedDecision() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            var commits = new AtomicInteger();
            var closes = new AtomicInteger();
            doReturn(null).when(f.auth).preAuthorize(any(), any());
            doAnswer(i -> {
                assertThat(log.events()).hasSize(commits.get() * 2);
                Thread.sleep(3100);
                commits.incrementAndGet();
                return null;
            }).when(f.jdbc.connection).commit();
            doAnswer(i -> {
                assertThat(log.events()).hasSize(closes.get() * 2);
                assertThat(commits.get()).isEqualTo(closes.get() + 1);
                closes.incrementAndGet();
                return null;
            }).when(f.jdbc.connection).close();
            assertThat(f.proposal.readProposal("subject", UUID.randomUUID()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);
            assertThat(f.results.readProposalResults("subject", UUID.randomUUID(), 1).state())
                    .isEqualTo(BulkAuthorizedProposalResultsReader.State.UNAVAILABLE);
            assertThat(log.events()).extracting(e -> e.path("stage").asText())
                    .containsExactly("GLOBAL_AUTHORIZATION", "AFTER_TRANSACTION", "GLOBAL_AUTHORIZATION", "AFTER_TRANSACTION");
            assertThat(log.events()).extracting(e -> e.path("reason").asText())
                    .containsExactly("GLOBAL_DECISION_MISSING", "DEADLINE_EXPIRED", "GLOBAL_DECISION_MISSING", "DEADLINE_EXPIRED");
            assertThat(commits).hasValue(2);
            assertThat(closes).hasValue(2);
        }
    }

    @Test void slowThrowingSinkRunsAfterOwnedTransactionAndCannotChangeChosenState() throws Exception {
        try (var f = new Readers(); var loggerFactory = mockStatic(LoggerFactory.class,
                withSettings().mockMaker("mock-maker-inline").defaultAnswer(CALLS_REAL_METHODS))) {
            var logger = mock(org.slf4j.Logger.class);
            var emitted = new AtomicInteger();
            var closed = new AtomicInteger();
            doAnswer(i -> { closed.incrementAndGet(); return null; }).when(f.jdbc.connection).close();
            loggerFactory.when(() -> LoggerFactory.getLogger(BulkReadFailureDiagnostics.class)).thenReturn(logger);
            doAnswer(i -> {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(f.jdbc.ds)).isFalse();
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(closed.get()).isEqualTo(emitted.get() + 1);
                int callsBefore = mockingDetails(f.jdbc.connection).getInvocations().size();
                // Longer than the canonical three-second reader budget, after its decision.
                Thread.sleep(3100);
                assertThat(mockingDetails(f.jdbc.connection).getInvocations()).hasSize(callsBefore);
                emitted.incrementAndGet();
                throw new AssertionError("observer failure must not change the observation");
            }).when(logger).error(anyString(), any(Object.class));
            doReturn(null).when(f.auth).preAuthorize(any(), any());
            assertThat(f.proposal.readProposal("subject", UUID.randomUUID()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.GLOBAL_UNAVAILABLE);
            assertThat(f.results.readProposalResults("subject", UUID.randomUUID(), 1).state())
                    .isEqualTo(BulkAuthorizedProposalResultsReader.State.GLOBAL_UNAVAILABLE);
            assertThat(emitted).hasValue(2);
            verify(f.jdbc.connection, times(2)).commit();
            verify(f.jdbc.connection, never()).rollback();
        }
    }

    @Test void laterCommitFailureKeepsBothInnerReasonAndCausalOuterFailure() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            doReturn(null).when(f.auth).preAuthorize(any(), any());
            doThrow(new SQLException("private commit details", "08006")).when(f.jdbc.connection).commit();
            assertThat(f.proposal.readProposal("subject", UUID.randomUUID()).state())
                    .isEqualTo(BulkAuthorizedProposalReader.State.UNAVAILABLE);
            assertThat(log.events()).extracting(e -> e.path("stage").asText())
                    .containsExactly("GLOBAL_AUTHORIZATION", "SNAPSHOT_TRANSACTION");
            assertThat(log.events().get(1).toString()).contains("08006").doesNotContain("private commit details");
            verify(f.jdbc.connection).close();
        }
    }

    private static final class Events implements AutoCloseable {
        final Logger logger = (Logger) LoggerFactory.getLogger(BulkReadFailureDiagnostics.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Events() { appender.start(); logger.addAppender(appender); }
        List<JsonNode> events() throws Exception {
            List<JsonNode> result = new ArrayList<>();
            for (var event : appender.list) result.add(new ObjectMapper().readTree(event.getFormattedMessage().substring(PREFIX.length())));
            return result;
        }
        public void close() { logger.detachAppender(appender); appender.stop(); }
    }

    private static final class Readers implements AutoCloseable {
        final BulkConsistentReadBatchTest.Fixture jdbc = new BulkConsistentReadBatchTest.Fixture();
        final BulkExecutionInfrastructure infrastructure = new BulkExecutionInfrastructure(jdbc.ds,
                new DataSourceTransactionManager(jdbc.ds), CONTEXT.namespaceId(), "dep",
                new BulkExecutionRoleConfiguration("owner", Set.of("runtime"), Set.of(), Set.of("control")));
        final BulkReadAuthorizationProvider auth = mock(BulkReadAuthorizationProvider.class);
        final BulkProposalProjectionProvider projection = mock(BulkProposalProjectionProvider.class);
        final BulkAuthorizedProposalReader proposal;
        final BulkAuthorizedProposalResultsReader results;
        Readers() throws Exception {
            when(auth.executionInfrastructure()).thenReturn(infrastructure);
            when(auth.confirmationOperationId()).thenReturn(CONTEXT.operationRef().operationId());
            when(auth.preAuthorize(any(), any())).thenReturn(BulkReadAuthorizationProvider.GlobalDecision.ALLOWED);
            when(auth.authorize(any(), anyString(), anyList(), any())).thenReturn(BulkReadAuthorizationProvider.ScopeDecision.authorized(new byte[32]));
            when(projection.resourceKey()).thenReturn(CONTEXT.resourceKey());
            when(projection.confirmationOperationId()).thenReturn(CONTEXT.operationRef().operationId());
            when(projection.projectionRevision()).thenReturn("r");
            proposal = new BulkAuthorizedProposalReader(infrastructure, CONTEXT.resourceKey(), auth, projection,
                    Clock.fixed(Instant.parse("2026-09-13T12:01:00Z"), ZoneOffset.UTC));
            results = new BulkAuthorizedProposalResultsReader(infrastructure, CONTEXT.resourceKey(), auth,
                    new BulkReadCursorConfiguration("test-key", Map.of("test-key", new SecretKeySpec(new byte[32], "AES")), Duration.ofMinutes(5)));
        }
        BulkStoredProposal seedProtectedEvaluation() throws Exception {
            var p = BulkSnapshotStorageCodecTest.proposal(); var e = evaluation(p);
            var locator = rows(new Object[]{p.createdAt().atOffset(ZoneOffset.UTC), p.expiresAt().atOffset(ZoneOffset.UTC),
                    p.snapshot().fingerprint(), BulkSnapshotStorageCodec.encode(p.snapshot()), 1L,
                    CONTROL_EXPECTATION.descriptorFingerprint(), CONTROL_EXPECTATION.structuralRevision()});
            when(locator.getString("subject_id")).thenReturn(CONTEXT.subjectId());
            when(locator.getString("execution_mode")).thenReturn("SYNC");
            when(locator.getString("atomicity")).thenReturn("ATOMIC"); when(locator.getShort("protocol_version")).thenReturn((short) 2);
            var query = batch(rows(new Object[]{"200ms"}), locator); var evaluationQuery = mock(PreparedStatement.class);
            when(evaluationQuery.executeQuery()).thenAnswer(i -> rows(new Object[]{p.snapshot().fingerprint(), e.fingerprint(), BulkEvaluationStorageCodec.encode(e)}));
            when(jdbc.connection.prepareStatement(argThat(sql -> sql != null && (sql.contains("praxis_bulk_proposal") || sql.contains("praxis_bulk_evaluation")))))
                    .thenReturn(query, evaluationQuery);
            return p;
        }
        public void close() { jdbc.close(); }
    }
}
