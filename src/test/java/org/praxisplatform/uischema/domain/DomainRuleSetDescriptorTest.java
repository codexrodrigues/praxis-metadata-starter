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

    @Test void normalizesAndSerializesRuleDocumentationAndLegalBasisDescriptors() throws Exception {
        assertThatThrownBy(() -> RuleLegalBasisDescriptor.of("   ", "Norma", "Resumo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");

        var basis = RuleLegalBasisDescriptor.of(
                " CF/88 Art. 37, XVI ",
                " Constituição Federal de 1988 ",
                " Art. 37, XVI ",
                " Federal ",
                " Veda acumulação remunerada de cargos públicos. ",
                "   "
        );
        assertThat(basis.code()).isEqualTo("CF/88 Art. 37, XVI");
        assertThat(basis.title()).isEqualTo("Constituição Federal de 1988");
        assertThat(basis.url()).isNull();

        ObjectMapper mapper = new ObjectMapper();
        var serializedBasis = mapper.valueToTree(basis);
        assertThat(serializedBasis.has("url")).isFalse();
        assertThat(serializedBasis.path("code").asText()).isEqualTo("CF/88 Art. 37, XVI");

        var baseDoc = RuleDocumentationDescriptor.of(
                " Acumulação de cargos ",
                " Verifica se o servidor já possui vínculo ativo incompatível. ",
                " Ao criar ou alterar um vínculo funcional. ",
                " Garante observância ao teto constitucional de acumulação. ",
                List.of(basis),
                " Parametrizável apenas em casos previstos em lei. "
        );
        var overrideDoc = RuleDocumentationDescriptor.of(
                " Acumulação (Órgão) ",
                " Verifica compatibilidade de carga horária no órgão. ",
                null,
                null,
                List.of(),
                null
        );
        var merged = baseDoc.mergeWith(overrideDoc);
        assertThat(merged.title()).isEqualTo("Acumulação (Órgão)");
        assertThat(merged.summary()).isEqualTo("Verifica compatibilidade de carga horária no órgão.");
        assertThat(merged.whenApplies()).isEqualTo("Ao criar ou alterar um vínculo funcional.");
        assertThat(merged.rationale()).isEqualTo("Garante observância ao teto constitucional de acumulação.");
        assertThat(merged.legalBasis()).containsExactly(basis);
        assertThat(merged.customizationGuidance()).isEqualTo("Parametrizável apenas em casos previstos em lei.");

        var emptyDoc = RuleDocumentationDescriptor.of(" ", null, "", "  ", List.of(), null);
        assertThat(emptyDoc.isEmpty()).isTrue();
        assertThat(mapper.valueToTree(emptyDoc).isEmpty()).isTrue();
    }

    @Test void preservesBackwardCompatibilityAndAddsDidacticDocumentationFluently() {
        var definition = definition("CREATE", "person");
        var legacy = new DomainRuleSetDescriptor(
                "crm.people",
                definition,
                null,
                null,
                Map.of(),
                Map.of("person", binding("person", "crm.people"))
        );
        assertThat(legacy.slotDocumentation()).isEmpty();
        assertThat(legacy.ruleDocumentation()).isEmpty();
        assertThat(legacy.ruleSetDocumentation()).isNull();

        var doc = RuleDocumentationDescriptor.of(
                "Identificação obrigatória",
                "Verifica se a pessoa possui identificador válido.",
                "Na criação do cadastro.",
                "Evita registros órfãos.",
                List.of(RuleLegalBasisDescriptor.of("POL-01", "Política de Cadastro", "Exige ID válido.")),
                "Regra fixa de integridade."
        );
        var emptyDoc = RuleDocumentationDescriptor.of(null, " ", null, null, List.of(), null);
        var enriched = legacy.withDocumentation(
                Map.of("guard", doc, "ignored", emptyDoc),
                Map.of("INVALID", doc),
                doc
        );
        assertThat(enriched.slotDocumentation()).containsOnlyKeys("guard");
        assertThat(enriched.ruleDocumentation()).containsOnlyKeys("INVALID");
        assertThat(enriched.ruleSetDocumentation()).isEqualTo(doc);
        assertThat(enriched.conceptRootBindings()).containsKey("person");
        assertThatThrownBy(() -> enriched.slotDocumentation().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> enriched.ruleDocumentation().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void foldsDefinitionErrorDescriptorsIncludingWhenErrorCatalogIsNull() {
        var logic = new ObjectMapper().createObjectNode().put("var", "draft.status");
        var customerError = new org.praxisplatform.rules.violation.RuleErrorDescriptor(
                "CUST-001",
                "status",
                "Mensagem customizada pelo cliente.",
                "POLICY_STUDIO_RULE",
                "PRAXIS_POLICY_STUDIO"
        );
        var definition = RuleSetDefinition.withInferredRoots(
                new RuleSetRef("crm", "crm.people", "crm.people.validate", "CREATE", 1),
                List.of(new DecisionSlot("guard", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                        OverridePolicy.REPLACEABLE, DecisionAggregationPolicy.SINGLE_RESULT, null, List.of())),
                List.of(new DecisionBinding("customer.t_1.replace.guard", "guard", DecisionSource.CUSTOMER,
                        CompositionPolicy.REPLACE_EXACT, RuleExecutorRef.jsonLogic(logic), List.of(), 1, true,
                        RuleDecision.DENY, "CUST-001", List.of(), null)),
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED,
                Map.of("customer.t_1.replace.guard", customerError)
        );

        var withoutCatalog = DomainRuleSetDescriptor.of(
                "crm.people", definition, "Regras", "Descrição", (org.praxisplatform.rules.violation.RuleErrorCatalog) null, Map.of());
        assertThat(withoutCatalog.reasonDescriptors()).containsKey("CUST-001");
        assertThat(withoutCatalog.reasonDescriptors().get("CUST-001"))
                .satisfies(reason -> {
                    assertThat(reason.slotKey()).isEqualTo("guard");
                    assertThat(reason.targetField()).isEqualTo("status");
                    assertThat(reason.message()).isEqualTo("Mensagem customizada pelo cliente.");
                    assertThat(reason.origin()).isEqualTo("PRAXIS_POLICY_STUDIO");
                    assertThat(reason.lineage()).isEqualTo("POLICY_STUDIO_RULE");
                });

        org.praxisplatform.rules.violation.RuleErrorCatalog productCatalog = code -> "CUST-001".equals(code)
                ? java.util.Optional.of(new org.praxisplatform.rules.violation.RuleErrorDescriptor(
                        "CUST-001", "otherField", "Mensagem base", "BASE", "PRODUCT"))
                : java.util.Optional.empty();
        var withCatalog = DomainRuleSetDescriptor.of(
                "crm.people", definition, "Regras", "Descrição", productCatalog, Map.of());
        assertThat(withCatalog.reasonDescriptors().get("CUST-001").message())
                .isEqualTo("Mensagem customizada pelo cliente.");
        assertThat(withCatalog.reasonDescriptors().get("CUST-001").targetField())
                .isEqualTo("status");
    }
}
