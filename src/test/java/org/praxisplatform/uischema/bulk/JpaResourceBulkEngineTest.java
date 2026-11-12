package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.violation.*;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaResourceBulkEngineTest {
    @Test void evaluationUsesTheCaptureProtocolWhileMutationUnitsRemainCappedAtFiveSeconds() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenAnswer(call -> {
            var definition = (org.springframework.transaction.TransactionDefinition) call.getArgument(0);
            assertEquals(30, definition.getTimeout());
            assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ, definition.getIsolationLevel());
            return status;
        });
        var infrastructure = mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(infrastructure.transactionManager()).thenReturn(manager);
        var lifecycle = mock(BulkOperationLifecycle.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var fields = mock(BulkEditableFields.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var admission = mock(BulkOperationLifecycle.ReadyAdmission.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var profile = new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE), Set.of(BulkExecutionMode.SYNC),
                Set.of(BulkSelectionMode.EXPLICIT), 10, 4096, java.time.Duration.ofMinutes(10), java.time.Duration.ofSeconds(5));
        when(lifecycle.requireReady(any(), any(), any())).thenReturn(admission);
        when(admission.mode()).thenReturn(BulkMode.UNIFORM_UPDATE);
        when(admission.matchesScope(any())).thenReturn(true);
        when(admission.matchesCodec(any())).thenReturn(true);
        when(admission.editableFields()).thenReturn(fields);
        when(admission.profile()).thenReturn(profile);
        @SuppressWarnings("unchecked") var domain = (BulkDomainResourceAdapter<String,Object>) mock(BulkDomainResourceAdapter.class);
        var engine = new JpaResourceBulkEngine<>(infrastructure, lifecycle, domain, BulkIdentityCodecs.strings(), fields,
                new ObjectMapper(), mock(BulkResourcePolicy.class), mock(BulkReadCursorConfiguration.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)), Map.of());
        var store = mock(JdbcBulkProposalStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var stop = new IllegalStateException("capture probe stops before persistence");
        doAnswer(call -> {
            @SuppressWarnings("unchecked") var remaining = (java.util.function.Supplier<java.time.Duration>) call.getArgument(4);
            assertTrue(remaining.get().compareTo(java.time.Duration.ofSeconds(25)) > 0);
            assertTrue(remaining.get().compareTo(java.time.Duration.ofSeconds(30)) <= 0);
            assertEquals(java.time.Duration.ofSeconds(5), profile.unitDeadline());
            throw stop;
        }).when(store).captureAndInsertEvaluated(isNull(), any(), any(), any(), any());
        ReflectionTestUtils.setField(engine, "proposals", store);
        var intent = BulkSnapshotStorageCodecTest.snapshot(BulkMode.UNIFORM_UPDATE, BulkIdentityCodecs.strings(), "\"101\"", "1");
        assertSame(stop, assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(engine, "evaluate",
                intent, BulkExecutionMode.SYNC, BulkSelectionMode.EXPLICIT)));
        verify(manager).rollback(status);
        assertThrows(IllegalArgumentException.class, () -> new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE),
                Set.of(BulkExecutionMode.SYNC), Set.of(BulkSelectionMode.EXPLICIT), 10, 4096,
                java.time.Duration.ofMinutes(10), java.time.Duration.ofSeconds(6)));
    }
    @Test void expectedMutationFailuresAreLocalWhileTechnicalFailuresStillStopTheExecution() {
        assertEquals(BulkUnitReasonCode.TARGET_VERSION_CONFLICT, JpaResourceBulkEngine.targetRejection(ResourceVersionPreconditionException.stale()).reason());
        assertNull(JpaResourceBulkEngine.targetRejection(ResourceVersionPreconditionException.required()));
        assertEquals(BulkUnitReasonCode.TARGET_STATE_CONFLICT, JpaResourceBulkEngine.targetRejection(rule("CONFLICT",RuleDecision.DENY)).reason());
        assertEquals(BulkUnitReasonCode.TARGET_INVALID, JpaResourceBulkEngine.targetRejection(rule("BUSINESS_LOGIC",RuleDecision.DENY)).reason());
        assertEquals(BulkUnitReasonCode.TARGET_INVALID, JpaResourceBulkEngine.targetRejection(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY)).reason());
        assertEquals(BulkUnitReasonCode.TARGET_DENIED, JpaResourceBulkEngine.targetRejection(new ResponseStatusException(HttpStatus.FORBIDDEN)).reason());
        assertNull(JpaResourceBulkEngine.targetRejection(rule("BUSINESS_LOGIC",RuleDecision.TECHNICAL_ERROR)));
        assertNull(JpaResourceBulkEngine.targetRejection(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)));
        assertNull(JpaResourceBulkEngine.targetRejection(new IllegalStateException("technical")));
        assertNull(JpaResourceBulkEngine.targetRejection(new IllegalArgumentException("broken server mapping")));
        assertEquals(BulkUnitReasonCode.TARGET_INVALID,JpaResourceBulkEngine.targetRejection(
                new BulkFieldChangeValidationException("non-writable field")).reason());
        assertThrows(IllegalArgumentException.class, () -> new BulkUnitRejectedException(BulkUnitAdmission.admit()));
        assertThrows(IllegalArgumentException.class, () -> new BulkUnitRejectedException(BulkUnitAdmission.stop(BulkUnitReasonCode.POLICY_BLOCKED)));
    }

    @Test void candidateFailureDiscardsEvaluationStateBeforeTheNextCandidate() {
        @SuppressWarnings("unchecked") var domain = (BulkDomainResourceAdapter<String,Object>)mock(BulkDomainResourceAdapter.class);
        var dirty = new java.util.concurrent.atomic.AtomicBoolean();
        when(domain.evaluate(anyString(),anyString(),anyList(),any(),any())).thenAnswer(call -> {
            assertFalse(dirty.get(), "previous candidate leaked dirty state");
            if (call.getArgument(0).equals("101")) { dirty.set(true); throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY); }
            return new BulkDomainResourceAdapter.Candidate("v1",new ObjectMapper().createObjectNode(),new ObjectMapper().createObjectNode());
        });
        doAnswer(call -> { dirty.set(false); return null; }).when(domain).discardEvaluationState();
        var engine = new JpaResourceBulkEngine<>(mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),mock(BulkOperationLifecycle.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),domain,
                BulkIdentityCodecs.strings(),mock(BulkEditableFields.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),new ObjectMapper(),mock(BulkResourcePolicy.class),
                mock(BulkReadCursorConfiguration.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),Map.of());
        var snapshot = BulkSnapshotStorageCodecTest.snapshot(BulkMode.UNIFORM_UPDATE,BulkIdentityCodecs.strings(),"\"101\"","1");
        BulkTargetEvidence<?> failed = ReflectionTestUtils.invokeMethod(engine,"evaluateTarget",snapshot,new BulkTarget<>("101","v1"),org.praxisplatform.uischema.service.base.ResourceMutationContext.Stage.EVALUATION,java.time.Instant.EPOCH);
        assertFalse(failed.eligibility().orElseThrow().isExecutable());
        BulkTargetEvidence<?> next = ReflectionTestUtils.invokeMethod(engine,"evaluateTarget",snapshot,new BulkTarget<>("102","v1"),org.praxisplatform.uischema.service.base.ResourceMutationContext.Stage.EVALUATION,java.time.Instant.EPOCH);
        assertTrue(next.eligibility().orElseThrow().isExecutable());
        verify(domain,times(2)).discardEvaluationState();
    }

    private static PraxisRuleViolationException rule(String category, RuleDecision decision) {
        return new PraxisRuleViolationException(new SlotViolationDetail("STATE",null,null,null,null,decision,null,"Operação indisponível",null,category));
    }
}
