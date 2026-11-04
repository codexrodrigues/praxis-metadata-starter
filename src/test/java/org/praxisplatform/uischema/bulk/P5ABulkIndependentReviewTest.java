package org.praxisplatform.uischema.bulk;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.violation.*;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Review probes: evaluation must preserve the distinction used at mutation. */
class P5ABulkIndependentReviewTest {
    @Test void technicalRuleFailureMustNotBecomeTargetValidation() {
        assertThrows(PraxisRuleViolationException.class, () -> evaluate(rule(RuleDecision.TECHNICAL_ERROR,"BUSINESS_LOGIC")));
    }
    @Test void unavailableDependencyMustNotBecomeTargetValidation() {
        assertThrows(ResponseStatusException.class, () -> evaluate(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)));
    }
    @Test void stateConflictMustNotBecomeInvalidInput() {
        var evidence = evaluate(rule(RuleDecision.DENY,"CONFLICT"));
        assertTrue(evidence.eligibility().orElseThrow().diagnostics().stream()
                .noneMatch(message -> message.category() == ResourceCommandErrorCategory.VALIDATION),
                "conflict is a state outcome, not caller input validation");
    }
    @Test void vanishedTargetMustBeLocalToItsBulkUnit() {
        var evidence = assertDoesNotThrow(() -> evaluate(new jakarta.persistence.EntityNotFoundException("deleted after proposal")));
        assertFalse(evidence.eligibility().orElseThrow().isExecutable());
    }
    @SuppressWarnings("unchecked")
    private BulkTargetEvidence<?> evaluate(RuntimeException failure) {
        var domain = (BulkDomainResourceAdapter<String,Object>)mock(BulkDomainResourceAdapter.class);
        when(domain.evaluate(anyString(),anyString(),anyList(),any(),any())).thenThrow(failure);
        var engine = new JpaResourceBulkEngine<>(mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),
                mock(BulkOperationLifecycle.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),domain,
                BulkIdentityCodecs.strings(),mock(BulkEditableFields.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),
                new ObjectMapper(),mock(BulkResourcePolicy.class),
                mock(BulkReadCursorConfiguration.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),Map.of());
        var snapshot = BulkSnapshotStorageCodecTest.snapshot(BulkMode.UNIFORM_UPDATE,BulkIdentityCodecs.strings(),"\"101\"","1");
        return ReflectionTestUtils.invokeMethod(engine,"evaluateTarget",snapshot,new BulkTarget<>("101","v1"));
    }
    private static PraxisRuleViolationException rule(RuleDecision decision,String category) {
        return new PraxisRuleViolationException(new SlotViolationDetail("STATE",null,null,null,null,decision,null,"Operação indisponível",null,category));
    }
}
