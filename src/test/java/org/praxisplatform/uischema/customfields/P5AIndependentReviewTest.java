package org.praxisplatform.uischema.customfields;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Review probes: assertions describe the required safe behavior. */
class P5AIndependentReviewTest {
    @Test void entityScalarRuleMustNotTraverseJpaAssociation() {
        var entity = new AssociatedEntity(); entity.related = entity;
        var rule = P2CustomFieldContextTest.guarded();
        var validator = new CustomFieldValidator((r,t,s) -> List.of(rule));
        assertDoesNotThrow(() -> PraxisFactTreeBuilder.toScalarObjectNode(entity));
        assertEquals(0, entity.associationReads, "scalar helper itself is safe");
        var prepared = validator.prepare(new P2CustomFieldContextTest.Request(Map.of("amount",8)),
                "p2.requests", "a", "request");
        assertDoesNotThrow(() -> prepared.validateForUpdate(entity), "OPEN is permitted; association is unrelated");
        var contextual = CustomFieldAdoptionRegressionTest.amount(Map.of("and",List.of(
                Map.of("==",List.of(Map.of("var","entity.status"),"OPEN")),
                Map.of("==",List.of(Map.of("var","entity.customFields.amount"),8)),
                Map.of("<=",List.of(Map.of("var","value"),Map.of("var","draft.limit"))))));
        var withDraft = new CustomFieldValidator((r,t,s) -> List.of(contextual)).prepare(
                new P2CustomFieldContextTest.Request(Map.of("amount",8)),"p2.requests","a","request");
        assertDoesNotThrow(() -> withDraft.validateForUpdate(entity,Map.of("limit",10)));
        assertEquals(0, entity.associationReads);
    }

    @Test void optionalEmptyValueDoesNotMaskAnotherNullRootOrIteratorScope() {
        for (var expression : List.of(
                Map.of("<",List.of(Map.of("var","draft.limit"),10)),
                Map.of("<",List.of(Map.of("var","value"),Map.of("var","draft.limit"))),
                Map.of("all",List.of(Map.of("var","draft.rows"),
                        Map.of("<",List.of(Map.of("var","value"),10)))))) {
            var rule = CustomFieldAdoptionRegressionTest.amount(Map.of("and",List.of(
                    Map.of("==",List.of(Map.of("var","draft.enabled"),true)),expression)));
            var draft = new HashMap<String,Object>(); draft.put("limit",null); draft.put("enabled",true);
            draft.put("rows",List.of(Collections.singletonMap("value",null)));
            assertThrows(IllegalStateException.class, () -> new CustomFieldValidator((r,t,s) -> List.of(rule))
                    .validateValues(Map.of(),List.of(rule),draft,null));
        }
    }

    @Test void emptyOptionalValueMustNotSuppressDivisionByZeroInAnotherOperand() {
        var rule = CustomFieldAdoptionRegressionTest.amount(Map.of("<=", List.of(
                Map.of("/", List.of(Map.of("var", "draft.limit"), 0)), 10)));
        var validator = new CustomFieldValidator((r,t,s) -> List.of(rule));
        // The same invalid rule correctly fails when the optional value is present.
        assertThrows(IllegalStateException.class, () -> validator.validateValues(
                Map.of("amount",8), List.of(rule), Map.of("limit",10), null));
        assertThrows(IllegalStateException.class, () -> validator.validateValues(
                Map.of(), List.of(rule), Map.of("limit",10), null),
                "division by zero does not involve value=null");
    }

    @Test void emptyOptionalValueMustNotSuppressAnInvalidDraftType() {
        var rule = CustomFieldAdoptionRegressionTest.amount(Map.of("<", List.of(Map.of("var", "draft.limit"), 10)));
        var validator = new CustomFieldValidator((r,t,s) -> List.of(rule));
        assertThrows(IllegalStateException.class, () -> validator.validateValues(
                Map.of(), List.of(rule), Map.of("limit","invalid numeric operand"), null));
    }

    @SuppressWarnings("unchecked")
    @Test void versionedCommandMustPreserveTheSameCrossFieldInvariantAsPut() {
        var rule = CustomFieldAdoptionRegressionTest.amount(Map.of("<=", List.of(
                Map.of("var","value"), Map.of("var","draft.owner"))));
        var audit = CustomFieldAdoptionRegressionTest.field("audit",false,true,Map.of());
        var validator = new CustomFieldValidator((r,t,s) -> List.of(rule,audit));
        var repository = (BaseCrudRepository<P5ACustomFieldRegressionTest.Entity,Long>)mock(BaseCrudRepository.class);
        var entity = new P5ACustomFieldRegressionTest.Entity(); entity.owner = 10;
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(call -> { entity.revision++; return entity; });
        var service = new CommandService(repository);
        ReflectionTestUtils.setField(service,"customFieldValidator",validator);
        assertThrows(CustomFieldValidationException.class, () -> service.update(1L,
                new P5ACustomFieldRegressionTest.Write("old",5,entity.customFields)));
        assertEquals(10,entity.owner);
        var etags = new ResourceVersionEtagService("independent-review-secret");
        var precondition = new ResourceVersionUpdatePrecondition<Long>(etags,
                etags.create("allocations",1L,4),"allocations",1L);
        assertThrows(CustomFieldValidationException.class, () -> service.lowerOwner(precondition));
        verify(repository,never()).save(any());
    }

    static class AssociatedEntity {
        String status = "OPEN";
        @CustomFields Map<String,Object> customFields = Map.of("amount",8);
        @jakarta.persistence.ManyToOne AssociatedEntity related;
        transient int associationReads;
        public String getStatus() { return status; }
        public AssociatedEntity getRelated() { associationReads++; return related; }
    }
    static class CommandService extends P5ACustomFieldRegressionTest.Service {
        CommandService(BaseCrudRepository<P5ACustomFieldRegressionTest.Entity,Long> repository) { super(repository); }
        void lowerOwner(ResourceVersionUpdatePrecondition<Long> precondition) {
            executeVersionedMutation(1L,precondition,entity -> entity.owner = 5);
        }
    }
}
