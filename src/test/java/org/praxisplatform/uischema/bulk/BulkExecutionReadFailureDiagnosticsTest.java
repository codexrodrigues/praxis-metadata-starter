package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual facade and owned Spring RR/RO boundary, with local inline doubles only at the protected
 * correlation and summary conversion and JDBC page seams. These tests prove observation/diagnostic decisions and
 * cleanup ordering, not PostgreSQL catalog/state integrity or the remote cause of a503.
 */
class BulkExecutionReadFailureDiagnosticsTest {
    @Test void bothExecutionReadersPreserveSnapshotFailureAndPrivateSqlState() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            f.jdbc.primary = new IllegalStateException("secret catalog", new SQLException("secret", "57014"));
            f.jdbc.restoreFailure = new SQLException("secret restore", "08006");
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.UNAVAILABLE);
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.UNAVAILABLE);
            verify(f.auth, never()).preAuthorize(any(), any());
            assertThat(log.events()).hasSize(2).allSatisfy(e -> {
                assertThat(e.path("stage").asText()).isEqualTo("SNAPSHOT_TRANSACTION");
                assertThat(e.toString()).contains("57014", "08006").doesNotContain("secret");
            });
        }
    }

    @Test void thrownNullAndUnavailableGlobalDecisionsKeepTheirOriginalStatesAndReasons() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            when(f.auth.preAuthorize(any(), any())).thenThrow(new IllegalStateException("private"));
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.GLOBAL_UNAVAILABLE);
            doReturn(null).when(f.auth).preAuthorize(any(), any());
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.GLOBAL_UNAVAILABLE);
            doReturn(BulkReadAuthorizationProvider.GlobalDecision.UNAVAILABLE).when(f.auth).preAuthorize(any(), any());
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.GLOBAL_UNAVAILABLE);
            assertThat(log.events()).extracting(e -> e.path("reason").asText()).containsExactly("EXCEPTION", "GLOBAL_DECISION_MISSING", "GLOBAL_UNAVAILABLE");
            verify(f.auth, never()).authorize(any(), anyString(), anyList(), any());
        }
    }

    @Test void expectedDenialAbsenceGoneAndInvalidCursorNeverBecomeUnavailableOrFailureLogs() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            doReturn(BulkReadAuthorizationProvider.GlobalDecision.DENIED).when(f.auth).preAuthorize(any(), any());
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.GLOBAL_DENIED);
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.GLOBAL_DENIED);
            clearInvocations(f.jdbc.ds, f.auth);
            assertThat(f.results.readExecutionResults("subject", f.id, 1, "not-a-cursor").state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.INVALID_CURSOR);
            verifyNoInteractions(f.jdbc.ds, f.auth);
            doReturn(BulkReadAuthorizationProvider.GlobalDecision.ALLOWED).when(f.auth).preAuthorize(any(), any());
            f.locator.when(() -> BulkProtectedExecutionReader.locateForAuthorizedComposition(any(), anyString(), anyString(), anyString(), any())).thenReturn(null);
            f.tombstone.when(() -> JdbcBulkDurableExecution.scopedTombstone(any(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(null);
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.NOT_FOUND_OR_DENIED);
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.NOT_FOUND_OR_DENIED);
            f.tombstone.when(() -> JdbcBulkDurableExecution.scopedTombstone(any(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn("COMPLETED");
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.GONE);
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.GONE);
            assertThat(log.events()).isEmpty();
        }
    }

    @Test void protectedSqlFailureStillHidesExistenceBeforeFullAuthorization() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            f.locator.when(() -> BulkProtectedExecutionReader.locateForAuthorizedComposition(any(), anyString(), anyString(), anyString(), any())).thenThrow(new SQLException("secret", "08006"));
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.NOT_FOUND_OR_DENIED);
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.NOT_FOUND_OR_DENIED);
            verify(f.auth, never()).authorize(any(), anyString(), anyList(), any());
            assertThat(log.events()).hasSize(2).allSatisfy(e -> assertThat(e.path("stage").asText()).isEqualTo("PROTECTED_AUTHORIZATION"));
        }
    }

    @Test void ordinaryLiveSummaryAndEmptyResultPageStayCompleteWithNoDiagnostic() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            var projection = f.execution.readExecution("subject", f.id);
            assertThat(projection.state()).isEqualTo(BulkAuthorizedExecutionReader.State.COMPLETE);
            assertThat(projection.execution().status()).isEqualTo(BulkExecutionStatus.RUNNING);
            assertThat(projection.execution().executionMode()).isEqualTo(BulkExecutionMode.ASYNC);
            assertThat(projection.execution().totals().pending()).isOne();
            assertThat(projection.execution().totals().confirmed()).isZero();
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.COMPLETE);
            assertThat(log.events()).isEmpty();
        }
    }

    @Test void nonLiveSummaryMismatchAndThrownSummaryKeepDistinctPrivateReasons() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            f.summary = new BulkExecutionSummary(BulkExecutionSummary.Kind.ABSENT, null, null, null, null, null, null, null, null);
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.UNAVAILABLE);
            f.summary = f.live(UUID.randomUUID());
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.UNAVAILABLE);
            f.summaryFailure = new BulkDurableExecutionException(BulkDurableExecutionException.Reason.CORRUPT);
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.UNAVAILABLE);
            assertThat(log.events()).extracting(e -> e.path("reason").asText()).containsExactly("SUMMARY_NOT_LIVE", "SUMMARY_PROPOSAL_MISMATCH", "EXCEPTION");
            assertThat(log.events()).allSatisfy(e -> assertThat(e.path("stage").asText()).isEqualTo("SUMMARY"));
        }
    }

    @Test void nonLivePageAndThrownPageRemainUnavailableAtResultsBoundary() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            f.pageAbsent = true;
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.UNAVAILABLE);
            f.pageFailure = new SQLException("private", "57014");
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.UNAVAILABLE);
            assertThat(log.events()).extracting(e -> e.path("reason").asText()).containsExactly("RESULTS_NOT_LIVE", "EXCEPTION");
            assertThat(log.events()).allSatisfy(e -> assertThat(e.path("stage").asText()).isEqualTo("RESULTS"));
        }
    }

    @Test void laterCommitFailureRetainsInnerAndOuterAndClosesConnectionForBothReaders() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            doReturn(null).when(f.auth).preAuthorize(any(), any());
            doThrow(new SQLException("secret commit", "08006")).when(f.jdbc.connection).commit();
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.UNAVAILABLE);
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.UNAVAILABLE);
            assertThat(log.events()).extracting(e -> e.path("stage").asText()).containsExactly("GLOBAL_AUTHORIZATION", "SNAPSHOT_TRANSACTION", "GLOBAL_AUTHORIZATION", "SNAPSHOT_TRANSACTION");
            verify(f.jdbc.connection, times(2)).close();
        }
    }

    @Test void expiryAfterOwnedTransactionIsRecordedAfterItsCompletedDecision() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            doReturn(null).when(f.auth).preAuthorize(any(), any());
            doAnswer(i -> { Thread.sleep(3100); return null; }).when(f.jdbc.connection).commit();
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.UNAVAILABLE);
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.UNAVAILABLE);
            assertThat(log.events()).extracting(e -> e.path("stage").asText()).containsExactly("GLOBAL_AUTHORIZATION", "AFTER_TRANSACTION", "GLOBAL_AUTHORIZATION", "AFTER_TRANSACTION");
            assertThat(log.events().get(1).path("reason").asText()).isEqualTo("DEADLINE_EXPIRED");
        }
    }

    @Test void initialCursorExpiredAfterCommitDiffersFromExpectedExpiredContinuation() throws Exception {
        try (var f = new Readers(); var log = new Events()) {
            f.pageCursor = true;
            String next = f.results.readExecutionResults("subject", f.id, 1).page().next();
            f.clock.now = f.clock.now.plusSeconds(360);
            assertThat(f.results.readExecutionResults("subject", f.id, 1, next).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.PRECONDITION_FAILED);
            f.clock.now = Instant.parse("2026-10-13T19:00:00Z");
            doAnswer(i -> { f.clock.now = f.clock.now.plusSeconds(360); return null; }).when(f.jdbc.connection).commit();
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.UNAVAILABLE);
            assertThat(log.events()).hasSize(1);
            assertThat(log.events().getFirst().path("stage").asText()).isEqualTo("AFTER_TRANSACTION");
            assertThat(log.events().getFirst().path("reason").asText()).isEqualTo("CURSOR_EXPIRED");
        }
    }

    @Test void slowThrowingObserverRunsAfterOwnedCloseWithoutNewJdbcAndCannotChangeState() throws Exception {
        try (var f = new Readers(); var factory = mockStatic(LoggerFactory.class, withSettings().mockMaker("mock-maker-inline").defaultAnswer(CALLS_REAL_METHODS))) {
            var logger = mock(org.slf4j.Logger.class); var emitted = new AtomicInteger(); var closed = new AtomicInteger();
            doAnswer(i -> { closed.incrementAndGet(); return null; }).when(f.jdbc.connection).close();
            factory.when(() -> LoggerFactory.getLogger(BulkReadFailureDiagnostics.class)).thenReturn(logger);
            doAnswer(i -> {
                assertThat(TransactionSynchronizationManager.hasResource(f.jdbc.ds)).isFalse();
                assertThat(closed.get()).isEqualTo(emitted.get() + 1);
                int calls = mockingDetails(f.jdbc.connection).getInvocations().size(); Thread.sleep(3100);
                assertThat(mockingDetails(f.jdbc.connection).getInvocations()).hasSize(calls);
                emitted.incrementAndGet(); throw new AssertionError("observer");
            }).when(logger).error(anyString(), any(Object.class));
            doReturn(null).when(f.auth).preAuthorize(any(), any());
            assertThat(f.execution.readExecution("subject", f.id).state()).isEqualTo(BulkAuthorizedExecutionReader.State.GLOBAL_UNAVAILABLE);
            assertThat(f.results.readExecutionResults("subject", f.id, 1).state()).isEqualTo(BulkAuthorizedExecutionResultsReader.State.GLOBAL_UNAVAILABLE);
            assertThat(emitted).hasValue(2);
        }
    }

    @Test void propagatedPrimaryErrorSurvivesThrowingObserverAndOwnedCleanup() throws Exception {
        try (var f = new Readers(); var factory = mockStatic(LoggerFactory.class, withSettings().mockMaker("mock-maker-inline").defaultAnswer(CALLS_REAL_METHODS))) {
            var primary = new AssertionError("primary"); var logger = mock(org.slf4j.Logger.class);
            doReturn(null).when(f.auth).preAuthorize(any(), any());
            doThrow(primary).when(f.jdbc.connection).commit();
            factory.when(() -> LoggerFactory.getLogger(BulkReadFailureDiagnostics.class)).thenReturn(logger);
            doThrow(new AssertionError("sink")).when(logger).error(anyString(), any(Object.class));
            assertThatThrownBy(() -> f.execution.readExecution("subject", f.id)).isSameAs(primary);
            verify(f.jdbc.connection).close();
        }
    }

    private static final class Events implements AutoCloseable {
        final Logger logger = (Logger) LoggerFactory.getLogger(BulkReadFailureDiagnostics.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Events() { appender.start(); logger.addAppender(appender); }
        List<JsonNode> events() throws Exception {
            var result = new ArrayList<JsonNode>();
            for (var event : appender.list) result.add(new ObjectMapper().readTree(event.getFormattedMessage().substring(PREFIX.length())));
            return result;
        }
        public void close() { logger.detachAppender(appender); appender.stop(); }
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-13T19:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    private static ResultSet resultRows(Object[]... values) throws Exception {
        var row = mock(ResultSet.class); var index = new AtomicInteger(-1);
        when(row.next()).thenAnswer(i -> index.incrementAndGet()<values.length);
        when(row.getObject(anyInt(),any(Class.class))).thenAnswer(i -> values[index.get()][(int)i.getArgument(0)-1]);
        when(row.getString(anyInt())).thenAnswer(i -> {Object v=values[index.get()][(int)i.getArgument(0)-1];return v==null ? null : v.toString();});
        when(row.getInt(anyInt())).thenAnswer(i -> {Object v=values[index.get()][(int)i.getArgument(0)-1];return v==null ? 0 : ((Number)v).intValue();});
        when(row.getLong(anyInt())).thenAnswer(i -> {Object v=values[index.get()][(int)i.getArgument(0)-1];return v==null ? 0L : ((Number)v).longValue();});
        when(row.getBoolean(anyInt())).thenAnswer(i -> Boolean.TRUE.equals(values[index.get()][(int)i.getArgument(0)-1]));
        when(row.getBytes(anyInt())).thenAnswer(i -> values[index.get()][(int)i.getArgument(0)-1]);
        return row;
    }
    private static final class Readers implements AutoCloseable {
        final BulkConsistentReadBatchTest.Fixture jdbc = new BulkConsistentReadBatchTest.Fixture();
        final BulkExecutionInfrastructure infra = new BulkExecutionInfrastructure(jdbc.ds, new DataSourceTransactionManager(jdbc.ds), "ns", "dep", new BulkExecutionRoleConfiguration("owner", Set.of("runtime"), Set.of(), Set.of("control")));
        final BulkReadAuthorizationProvider auth = mock(BulkReadAuthorizationProvider.class);
        final UUID id = UUID.randomUUID(), proposalId = UUID.randomUUID();
        final MutableClock clock = new MutableClock();
        final BulkFingerprintContext context = new BulkFingerprintContext("ns", "subject", "participants", new CanonicalOperationRef(null, "confirm", "/bulk/update", "POST"), "r", ActionCollectionAtomicity.PER_ITEM);
        final MockedStatic<BulkProtectedExecutionReader> locator;
        final MockedStatic<BulkProtectedProposalReader> protectedReader;
        final MockedStatic<JdbcBulkDurableExecution> tombstone;
        MockedStatic<BulkExecutionSummary> summaries;
        BulkExecutionSummary summary; RuntimeException summaryFailure; SQLException pageFailure;
        boolean pageAbsent, pageCursor;
        final BulkAuthorizedExecutionReader execution;
        final BulkAuthorizedExecutionResultsReader results;
        Readers() throws Exception {
            when(auth.executionInfrastructure()).thenReturn(infra); when(auth.confirmationOperationId()).thenReturn("confirm");
            when(auth.preAuthorize(any(), any())).thenReturn(BulkReadAuthorizationProvider.GlobalDecision.ALLOWED);
            when(auth.authorize(any(), anyString(), anyList(), any())).thenReturn(BulkReadAuthorizationProvider.ScopeDecision.authorized(new byte[32]));
            var intent = mock(BulkIntentSnapshot.class, withSettings().mockMaker("mock-maker-inline"));
            when(intent.context()).thenReturn(context); when(intent.mode()).thenReturn(BulkMode.UNIFORM_UPDATE); when(intent.fingerprint()).thenReturn("input"); when(intent.intent()).thenReturn(new ObjectMapper().readTree("{\"executionMode\":\"ASYNC\"}"));
            var proposal = mock(BulkStoredProposal.class, withSettings().mockMaker("mock-maker-inline")); when(proposal.id()).thenReturn(proposalId); when(proposal.snapshot()).thenReturn(intent);
            var evaluation = mock(BulkEvaluationSnapshot.class, withSettings().mockMaker("mock-maker-inline")); when(evaluation.proposal()).thenReturn(proposal); when(evaluation.hasTypedEligibility()).thenReturn(true); when(evaluation.fingerprint()).thenReturn("evaluation"); when(evaluation.targets()).thenReturn(List.of(BulkTargetEvidence.legacy(new BulkTarget<>(1,"0"),"0",new ObjectMapper().createObjectNode(),new ObjectMapper().createObjectNode()),BulkTargetEvidence.legacy(new BulkTarget<>(2,"0"),"0",new ObjectMapper().createObjectNode(),new ObjectMapper().createObjectNode())));
            var observation = mock(BulkProtectedProposalReader.Observation.class, withSettings().mockMaker("mock-maker-inline")); when(observation.kind()).thenReturn(BulkProtectedProposalReader.Kind.EVALUATED); when(observation.evaluation()).thenReturn(evaluation);
            locator = mockStatic(BulkProtectedExecutionReader.class, withSettings().mockMaker("mock-maker-inline"));
            locator.when(() -> BulkProtectedExecutionReader.locateForAuthorizedComposition(any(), anyString(), anyString(), anyString(), any())).thenReturn(new BulkProtectedExecutionReader.Located(proposalId, "subject", "input", "evaluation"));
            protectedReader = mockStatic(BulkProtectedProposalReader.class, withSettings().mockMaker("mock-maker-inline"));
            protectedReader.when(() -> BulkProtectedProposalReader.readForAuthorizedComposition(any(), anyString(), anyString(), anyString(), any())).thenReturn(observation);
            tombstone = mockStatic(JdbcBulkDurableExecution.class, withSettings().mockMaker("mock-maker-inline"));
            summary = live(proposalId);
            // Pure summary conversion is an existing private static seam, not constructor mocking.
            // findScoped has no row in this synthetic kernel; the seam selects facade observations.
            summaries = mockStatic(BulkExecutionSummary.class, withSettings().mockMaker("mock-maker-inline"));
            summaries.when(() -> BulkExecutionSummary.from(any())).thenAnswer(i -> { if (summaryFailure != null) throw summaryFailure; return summary; });
            when(jdbc.connection.prepareStatement(argThat(sql -> sql != null && (sql.contains("select e.*") || sql.contains("select e.proposal_id") || sql.contains("select m.ordinal")))))
                    .thenAnswer(i -> {
                        String sql = i.getArgument(0); var statement = mock(PreparedStatement.class);
                        when(statement.executeQuery()).thenAnswer(ignored -> {
                            if (sql.contains("select e.*")) return resultRows();
                            if (sql.contains("select e.proposal_id")) {
                                if (pageFailure != null) throw pageFailure;
                                if (pageAbsent) return resultRows();
                                return resultRows(new Object[]{proposalId,pageCursor ? "COMPLETED" : "RUNNING",pageCursor ? 2 : 0,2,"input","evaluation",1L,clock.now.plusSeconds(30).atOffset(ZoneOffset.UTC),pageCursor ? clock.now.atOffset(ZoneOffset.UTC) : null,"input","input","evaluation","ns","subject","participants","confirm",null,null,null,null,null,null,false,"PER_ITEM",null});
                            }
                            var values = new ArrayList<Object[]>();
                            for (int ordinal=0;ordinal<2;ordinal++) {
                                byte[] wire=Integer.toString(ordinal+1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                String digest=BulkTargetDigest.of("evaluation",ordinal,ordinal+1,"0");
                                values.add(new Object[]{ordinal,"evaluation",2,wire,BulkTargetDigest.wireIdentity(wire),new byte[]{48},digest,ordinal,digest,"0",UUID.randomUUID(),1L,"CONFIRMED",clock.now.atOffset(ZoneOffset.UTC),clock.now.plusSeconds(1).atOffset(ZoneOffset.UTC),null,null,null,null,null,null,null,null});
                            }
                            return resultRows(values.toArray(Object[][]::new));
                        });return statement;
                    });
            execution = new BulkAuthorizedExecutionReader(infra, "participants", auth);
            results = new BulkAuthorizedExecutionResultsReader(infra, "participants", auth, new BulkReadCursorConfiguration("test-key", Map.of("test-key", new SecretKeySpec(new byte[32], "AES")), Duration.ofMinutes(5)), clock);
        }
        BulkExecutionSummary live(UUID pid) {
            Instant created = Instant.parse("2026-10-13T19:00:00Z");
            return new BulkExecutionSummary(BulkExecutionSummary.Kind.LIVE, id, pid, BulkExecutionStatus.RUNNING, created, created, null, new BulkExecutionTotals(1,1,0,0,0,0,0,0,0), null);
        }
        public void close() { summaries.close(); tombstone.close(); protectedReader.close(); locator.close(); jdbc.close(); }
    }
}
