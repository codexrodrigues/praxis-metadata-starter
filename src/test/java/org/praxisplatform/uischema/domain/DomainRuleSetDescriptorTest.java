package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.*;
import static org.assertj.core.api.Assertions.*;

class DomainRuleSetDescriptorTest {
    static RuleSetDefinition definition(String operation, String... roots) {
        try {
            var expression = new ObjectMapper().createArrayNode();
            for (String root : roots) expression.addObject().put("var", root + ".id");
            var logic = new ObjectMapper().createObjectNode().set("and", expression);
            return RuleSetDefinition.withInferredRoots(new RuleSetRef("crm", "crm.people", "crm.people.validate", operation, 1),
                    List.of(new DecisionSlot("guard", DecisionStage.PROTECTED_GUARD, SlotCardinality.SINGLE,
                            OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, java.util.List.of())),
                    List.of(new DecisionBinding("guard", "guard", DecisionSource.PRODUCT, null,
                            RuleExecutorRef.jsonLogic(logic), List.of(), 1, true, RuleDecision.DENY, "INVALID", List.of(), null)),
                    RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static ConceptRootBinding binding(String role, String resource) {
        return new ConceptRootBinding(role, "host", "crm", resource);
    }

    @Test void rejectsMissingBindingsAcrossAllConstructionPaths() {
        var definition = definition("CREATE", "person");
        assertThatThrownBy(() -> DomainRuleSetDescriptor.of(definition, Map.of())).hasMessageContaining("Missing concept");
        assertThatThrownBy(() -> new DomainRuleSetDescriptor("crm.people", definition, null, null, Map.of(), Map.of()))
                .hasMessageContaining("Missing concept");
        assertThatThrownBy(() -> DomainRuleSetCatalogProvider.ofDefinitions(List.of(definition))).hasMessageContaining("Missing concept");
        assertThatThrownBy(() -> DomainRuleSetDescriptor.of(definition, null)).isInstanceOf(NullPointerException.class);
        assertThat(DomainRuleSetDescriptor.of(definition("CREATE", "draft", "current", "context"), Map.of()).conceptRoots()).isEmpty();
    }

    @Test void acceptsSelfReferenceTwoRolesAndExplicitPrefixNamesWithoutNameInference() {
        var descriptor = DomainRuleSetDescriptor.of(definition("UPDATE", "employee", "manager", "activeDelegate"), Map.of(
                "employee", binding("employee", "crm.people"), "manager", binding("manager", "crm.people"),
                "activeDelegate", binding("delegate", "crm.people")));
        assertThat(descriptor.conceptRoots()).containsExactly("activeDelegate", "employee", "manager");
        assertThatThrownBy(() -> descriptor.conceptRootBindings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsUnknownStructuralAndAmbiguousRoleBindings() {
        var definition = definition("CREATE", "employee", "manager", "draft");
        assertThatThrownBy(() -> DomainRuleSetDescriptor.of(definition, Map.of("unknown", binding("unknown", "crm.people"))))
                .hasMessageContaining("Invalid concept");
        assertThatThrownBy(() -> DomainRuleSetDescriptor.of(definition, Map.of("draft", binding("draft", "crm.people"))))
                .hasMessageContaining("Invalid concept");
        assertThatThrownBy(() -> DomainRuleSetDescriptor.of(definition, Map.of(
                "employee", binding("same", "crm.people"), "manager", binding("same", "crm.people"))))
                .hasMessageContaining("Ambiguous concept role");
        assertThatThrownBy(() -> new ConceptRootBinding(" ", "host", "crm", "crm.people")).hasMessageContaining("role");
    }

    @Test void explicitDescriptorsPrecedeDerivationButDuplicatesFail() {
        var definition = definition("CREATE", "person");
        var descriptor = DomainRuleSetDescriptor.of(definition, Map.of("person", binding("person", "crm.people")));
        assertThat(DomainRuleSetCatalogProvider.of(List.of(descriptor), List.of(definition), null)
                .findRuleSets("crm.people", null)).containsExactly(descriptor);
        assertThatThrownBy(() -> DomainRuleSetCatalogProvider.ofDescriptors(List.of(descriptor, descriptor)))
                .hasMessageContaining("Duplicate RuleSet");
    }
}
