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
        BulkTargetEvidence<?> failed = ReflectionTestUtils.invokeMethod(engine,"evaluateTarget",snapshot,new BulkTarget<>("101","v1"));
        assertFalse(failed.eligibility().orElseThrow().isExecutable());
        BulkTargetEvidence<?> next = ReflectionTestUtils.invokeMethod(engine,"evaluateTarget",snapshot,new BulkTarget<>("102","v1"));
        assertTrue(next.eligibility().orElseThrow().isExecutable());
        verify(domain,times(2)).discardEvaluationState();
    }

    private static PraxisRuleViolationException rule(String category, RuleDecision decision) {
        return new PraxisRuleViolationException(new SlotViolationDetail("STATE",null,null,null,null,decision,null,"Operação indisponível",null,category));
    }
}
