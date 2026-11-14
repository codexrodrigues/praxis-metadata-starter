package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleParameterDefinition;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterType;
import org.praxisplatform.rules.contract.RuleParameterization;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.violation.RuleErrorCatalog;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupSelectionPolicy;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.surface.RelatedResourceSurface;
import org.praxisplatform.uischema.surface.SurfaceDefinition;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceResponseCardinality;
import org.praxisplatform.uischema.surface.SurfaceScope;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticDomainCatalogServiceTest {

    @Test
    void projectsMandatoryControlDependenciesFromTheCanonicalSlot() {
        String resource = "human-resources.folhas-pagamento";
        var condition = new ObjectMapper().createObjectNode().put("var", "context.allowed");
        var slots = List.of(
                new DecisionSlot("guard", DecisionStage.PROTECTED_GUARD, SlotCardinality.SINGLE,
                        OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, List.of()),
                new DecisionSlot("rule", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                        OverridePolicy.REPLACEABLE, DecisionAggregationPolicy.SINGLE_RESULT, null, List.of("product.guard")));
        var bindings = List.of(
                new DecisionBinding("product.guard", "guard", DecisionSource.PRODUCT, null,
                        RuleExecutorRef.jsonLogic(condition),
                        List.of(), 0, true, RuleDecision.NOT_APPLICABLE, "OUTSIDE_SCOPE", List.of("context.allowed"), null),
                new DecisionBinding("product.rule", "rule", DecisionSource.PRODUCT, null,
                        RuleExecutorRef.jsonLogic(condition),
                        List.of("product.guard"), 1, true, RuleDecision.DENY, "RULE_DENIED", List.of("context.allowed"), null));
        var definition = RuleSetDefinition.withInferredRoots(
                new RuleSetRef("human-resources", resource, resource + ".validate", "CREATE", 1),
                slots, bindings, RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
        var descriptor = DomainRuleSetDescriptor.of(resource, definition, List.of(), Map.of());
        var service = new SemanticDomainCatalogService(new StubActionDefinitionRegistry(),
                new StubSurfaceDefinitionRegistry(), optionSources(), new StubOpenApiDocumentService(), null,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-api-quickstart", "Praxis API Quickstart", "test", null, null, null,
                (key, tenant) -> resource.equals(key) ? List.of(descriptor) : List.of());
        var node = service.findByResourceKey(resource).nodes().stream()
                .filter(item -> (resource + ".validate").equals(item.metadata().get("ruleSetKey")))
                .findFirst().orElseThrow();
        assertThat(node.metadata().get("slots")).asList().hasSize(2);
        assertThat(node.metadata().get("slots")).asList().filteredOn(item ->
                "rule".equals(((Map<?, ?>) item).get("slotKey"))).singleElement().satisfies(item ->
                assertThat(((Map<?, ?>) item).get("requiredControlDependencies"))
                        .isEqualTo(List.of("product.guard")));
    }

    @Test
    void indexesAllHostDeclaredJavaReasonsOnlyForEnabledSlots() {
        String resource = "human-resources.folhas-pagamento";
        var slots = List.of("payroll.java", "payroll.disabled").stream().map(key ->
                new DecisionSlot(key, DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                        OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, java.util.List.of())).toList();
        var bindings = slots.stream().map(slot -> new DecisionBinding(
                "product." + slot.slotKey(), slot.slotKey(), DecisionSource.PRODUCT, null,
                RuleExecutorRef.java("payroll:" + slot.slotKey(), "1.0"), List.of(), 1,
                slot.slotKey().equals("payroll.java"), null, null, List.of("draft.valorLiquido"), null)).toList();
        var definition = RuleSetDefinition.withInferredRoots(
                new RuleSetRef("human-resources", resource, resource + ".validate", "CREATE", 1),
                slots, bindings, RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
        var reasons = List.of(
                new DomainRuleSetDescriptor.RuleReasonDescriptor("JAVA-001", "payroll.java", "valorLiquido",
                        "Liquid amount exceeds the payable balance.", "payroll:balance@1.0", "balance comparison"),
                new DomainRuleSetDescriptor.RuleReasonDescriptor("JAVA-002", "payroll.java", "valorBruto",
                        "Gross amount exceeds the authorized ceiling.", "payroll:balance@1.0", "ceiling comparison"),
                new DomainRuleSetDescriptor.RuleReasonDescriptor("JAVA-OFF", "payroll.disabled", "valorLiquido",
                        "Disabled decision.", "payroll:disabled@1.0", "disabled"));
        var descriptor = DomainRuleSetDescriptor.of(resource, definition, reasons, Map.of());
        var service = new SemanticDomainCatalogService(new StubActionDefinitionRegistry(),
                new StubSurfaceDefinitionRegistry(), optionSources(), new StubOpenApiDocumentService(), null,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-api-quickstart", "Praxis API Quickstart", "test", null, null, null,
                (key, tenant) -> resource.equals(key) ? List.of(descriptor) : List.of());
        var node = service.findByResourceKey(resource).nodes().stream()
                .filter(item -> (resource + ".validate").equals(item.metadata().get("ruleSetKey")))
                .findFirst().orElseThrow();
        assertThat(node.metadata()).containsEntry("reasonCodes", List.of("JAVA-001", "JAVA-002"));
        assertThat(node.metadata().get("targetFields")).asList().contains("valorLiquido", "valorBruto");
        assertThat(bindings).allSatisfy(binding -> assertThat(binding.falseReasonCode()).isNull());
    }

    @Test
    void buildsSemanticCatalogFromActionsSurfacesAndOptionSources() {
        SemanticDomainCatalogService service = new SemanticDomainCatalogService(
                new StubActionDefinitionRegistry(),
                new StubSurfaceDefinitionRegistry(),
                optionSources(),
                new StubOpenApiDocumentService(),
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-api-quickstart",
                "Praxis API Quickstart",
                "test"
        );

        DomainCatalogResponse response = service.findByResourceKey("human-resources.folhas-pagamento");

        assertThat(response.schemaVersion()).isEqualTo("praxis.domain-catalog/v0.2");
        assertThat(response.release().sourceHash()).hasSize(64);
        assertThat(response.release().releaseKey())
                .startsWith("praxis-api-quickstart:human-resources.folhas-pagamento:")
                .doesNotContain("2026-04-21T10:30:00Z");
        assertThat(response.resourceKey()).isEqualTo("human-resources.folhas-pagamento");
        assertThat(service.findByResourceKey("human-resources.folhas-pagamento"))
                .isSameAs(response);
        assertThat(response.contexts()).extracting(DomainCatalogResponse.DomainContextItem::contextKey)
                .containsExactly("human-resources");
        assertThat(response.contexts()).singleElement()
                .satisfies(context -> {
                    assertThat(context.lifecycle()).isEqualTo("active");
                    assertThat(context.semanticOwner()).isEqualTo("human-resources");
                    assertThat(context.businessGlossary()).containsEntry("preferredTerm", "Human Resources");
                });
        assertThat(response.nodes()).extracting(DomainCatalogResponse.DomainNodeItem::nodeKey)
                .contains(
                        "human-resources.folhas-pagamento",
                        "human-resources.folhas-pagamento.action.mark-paid",
                        "human-resources.folhas-pagamento.field.id",
                        "human-resources.folhas-pagamento.field.valor-liquido",
                        "human-resources.folhas-pagamento.surface.payment-schedule",
                        "human-resources.folhas-pagamento.stats.group-by",
                        "human-resources.folhas-pagamento.stats.time-series",
                        "human-resources.folhas-pagamento.estado.programada",
                        "human-resources.folhas-pagamento.policy.supplier.selection"
                );
        assertThat(response.nodes()).filteredOn(node -> "stats".equals(node.nodeType()))
                .extracting(DomainCatalogResponse.DomainNodeItem::nodeKey)
                .containsExactlyInAnyOrder(
                        "human-resources.folhas-pagamento.stats.group-by",
                        "human-resources.folhas-pagamento.stats.time-series"
                );
        assertThat(response.nodes()).filteredOn(node -> "human-resources.folhas-pagamento.stats.group-by".equals(node.nodeKey()))
                .singleElement()
                .satisfies(node -> assertThat(node.metadata())
                        .containsEntry("capabilityKey", "stats.groupBy")
                        .containsEntry("path", "/api/human-resources/folhas-pagamento/stats/group-by")
                        .containsEntry("method", "POST"));
        assertThat(response.nodes()).filteredOn(node -> "human-resources.folhas-pagamento".equals(node.nodeKey()))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.description()).isEqualTo("Folha de pagamento calculada para acompanhamento financeiro.");
                    assertThat(node.businessGlossary())
                            .containsEntry("description", "Folha de pagamento calculada para acompanhamento financeiro.");
                });
        assertThat(response.nodes()).filteredOn(node -> "policy_hint".equals(node.nodeType()))
                .singleElement()
                .satisfies(node -> assertThat(node.metadata())
                        .containsEntry("key", "supplier")
                        .containsEntry("resourcePath", "/api/human-resources/folhas-pagamento"));
        assertThat(response.nodes()).filteredOn(node -> "human-resources.folhas-pagamento.field.valor-liquido".equals(node.nodeKey()))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.lifecycle()).isEqualTo("active");
                    assertThat(node.semanticOwner()).isEqualTo("human-resources");
                    assertThat(node.sourceEvidenceKeys()).containsExactly("evidence:human-resources.folhas-pagamento.field.valor-liquido:canonical-response-id");
                    assertThat(node.businessGlossary())
                            .containsEntry("preferredTerm", "Valor Liquido")
                            .containsEntry("description", "Valor liquido da folha");
                    assertThat(node.resolution())
                            .containsEntry("canonicalKey", "human-resources.folhas-pagamento.field.valor-liquido")
                            .containsEntry("ambiguityPolicy", "exact-key-or-alias");
                    assertThat(node.metadata())
                            .containsEntry("fieldName", "valorLiquido")
                            .containsEntry("schemaId", "canonical-response-id")
                            .containsEntry("type", "number")
                            .containsEntry("format", "double");
                });
        assertThat(response.aliases()).extracting(DomainCatalogResponse.DomainAliasItem::alias)
                .contains("valorLiquido", "Valor Liquido", "mark-paid", "Marcar como paga");
        assertThat(response.edges()).extracting(DomainCatalogResponse.DomainEdgeItem::edgeType)
                .contains("has_action", "has_surface", "has_field", "has_stats", "allowed_in_state", "selectable_when", "blocked_when");
        assertThat(response.bindings()).extracting(DomainCatalogResponse.DomainBindingItem::bindingType)
                .contains("workflow_action", "ui_surface", "dto_field", "option_source", "stats_endpoint");
        assertThat(response.evidence()).extracting(DomainCatalogResponse.DomainEvidenceItem::evidenceType)
                .contains("annotation", "dto_schema", "option_source", "openapi_stats");
        assertThat(response.governance())
                .filteredOn(item -> "human-resources.folhas-pagamento.field.valor-liquido".equals(item.nodeKey()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.annotationType()).isEqualTo("privacy");
                    assertThat(item.classification()).isEqualTo("confidential");
                    assertThat(item.dataCategory()).isEqualTo("financial");
                    assertThat(item.complianceTags()).containsExactly("LGPD", "INTERNAL_POLICY");
                    assertThat(item.exportable()).isFalse();
                    assertThat(item.aiUsage())
                            .containsEntry("visibility", "mask")
                            .containsEntry("trainingUse", "deny")
                            .containsEntry("ruleAuthoring", "review_required")
                            .containsEntry("reasoningUse", "allow");
                    assertThat(item.source()).isEqualTo("java.annotation");
                });
        assertThat(response.governance())
                .filteredOn(item -> "human-resources.folhas-pagamento.field.risco-operacional".equals(item.nodeKey()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.annotationType()).isEqualTo("security");
                    assertThat(item.classification()).isEqualTo("confidential");
                    assertThat(item.dataCategory()).isEqualTo("operational");
                    assertThat(item.complianceTags()).containsExactly("INTERNAL_POLICY");
                    assertThat(item.aiUsage())
                            .containsEntry("visibility", "summarize_only")
                            .containsEntry("trainingUse", "deny")
                            .containsEntry("ruleAuthoring", "review_required")
                            .containsEntry("reasoningUse", "review_required");
                });
        assertThat(response.governance())
                .filteredOn(item -> "human-resources.folhas-pagamento.field.jurisdicao".equals(item.nodeKey()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.annotationType()).isEqualTo("compliance");
                    assertThat(item.classification()).isEqualTo("internal");
                    assertThat(item.dataCategory()).isEqualTo("legal");
                    assertThat(item.complianceTags()).containsExactly("INTERNAL_POLICY", "REGULATORY");
                    assertThat(item.aiUsage())
                            .containsEntry("visibility", "allow")
                            .containsEntry("trainingUse", "deny")
                            .containsEntry("ruleAuthoring", "review_required")
                            .containsEntry("reasoningUse", "allow");
                });
    }

    @Test
    void omitsUnsupportedStatsAndPublishesCanonicalConceptCapabilitiesWhenResolverIsPresent() {
        org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilityResolver =
                new org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver(
                        new StubOpenApiDocumentService(),
                        ignored -> new org.praxisplatform.uischema.capability.ResourceStructuralCapabilities(
                                true,
                                true,
                                true,
                                false,
                                false,
                                false,
                                false,
                                false,
                                org.praxisplatform.uischema.stats.StatsCapability.empty(),
                                null
                        )
                );
        SemanticDomainCatalogService service = new SemanticDomainCatalogService(
                new StubActionDefinitionRegistry(),
                new StubSurfaceDefinitionRegistry(),
                optionSources(),
                new StubOpenApiDocumentService(),
                capabilityResolver,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-api-quickstart",
                "Praxis API Quickstart",
                "test"
        );

        DomainCatalogResponse response = service.findByResourceKey("human-resources.folhas-pagamento");

        assertThat(response.nodes()).filteredOn(node -> "stats".equals(node.nodeType()))
                .extracting(DomainCatalogResponse.DomainNodeItem::nodeKey)
                .containsExactly("human-resources.folhas-pagamento.stats.group-by");
        assertThat(response.nodes()).filteredOn(node -> "human-resources.folhas-pagamento".equals(node.nodeKey()))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.metadata()).containsEntry("readOnly", true);
                    @SuppressWarnings("unchecked")
                    Map<String, Boolean> capabilities = (Map<String, Boolean>) node.metadata().get("capabilities");
                    assertThat(capabilities)
                            .containsEntry("statsGroupBy", true)
                            .containsEntry("statsTimeSeries", false);
                });
    }

    @Test
    void synthesizesKnowledgeGraphEdgesUiAnalyticsProjectionsAndDataProductsAutomatically() {
        SurfaceDefinition vinculosDetail = new SurfaceDefinition(
                "ferias-do-vinculo",
                "ergon.vinculos",
                "/api/ergon/vinculos",
                "ergon",
                SurfaceKind.READ_PROJECTION,
                SurfaceScope.ITEM,
                "Períodos Aquisitivos de Férias",
                "Histórico de períodos aquisitivos de férias do vínculo.",
                "ferias-vinculo",
                "response",
                SurfaceResponseCardinality.COLLECTION,
                new CanonicalOperationRef("ergon", "getVinculoById", "/api/ergon/vinculos/{id}", "GET"),
                new CanonicalSchemaRef("VinculoResponse", "response", "/schemas/filtered?schemaType=response"),
                20,
                List.of(),
                List.of("ATIVO"),
                List.of("vinculo", "ferias"),
                new RelatedResourceSurface(
                        "ergon.vinculos",
                        "id",
                        "ergon.periodos-aquisitivos-ferias",
                        "/api/ergon/periodos-aquisitivos-ferias",
                        "vinculoId",
                        false,
                        "id",
                        List.of()
                )
        );
        SurfaceDefinition vinculosCreate = new SurfaceDefinition(
                "create",
                "ergon.vinculos",
                "/api/ergon/vinculos",
                "ergon",
                SurfaceKind.PARTIAL_FORM,
                SurfaceScope.COLLECTION,
                "Novo Vínculo",
                "Cadastro inicial de vínculo funcional.",
                "create",
                "request",
                SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("ergon", "createVinculo", "/api/ergon/vinculos", "POST"),
                new CanonicalSchemaRef("VinculoCreateRequest", "request", "/schemas/filtered?schemaType=request"),
                5,
                List.of(),
                List.of(),
                List.of("vinculo")
        );
        SurfaceDefinition vinculosBaselineDetail = new SurfaceDefinition(
                "detail",
                "ergon.vinculos",
                "/api/ergon/vinculos",
                "ergon",
                SurfaceKind.VIEW,
                SurfaceScope.ITEM,
                "Detalhe do Vínculo",
                "Visão principal do vínculo funcional.",
                "detail",
                "response",
                SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("ergon", "getVinculoById", "/api/ergon/vinculos/{id}", "GET"),
                new CanonicalSchemaRef("VinculoResponse", "response", "/schemas/filtered?schemaType=response"),
                10,
                List.of(),
                List.of(),
                List.of("vinculo")
        );
        SurfaceDefinition radarDetail = new SurfaceDefinition(
                "detail",
                "ergon.radar-ferias-prescricao",
                "/api/ergon/radar-ferias-prescricao",
                "ergon",
                SurfaceKind.READ_PROJECTION,
                SurfaceScope.ITEM,
                "Visão 360° do Alerta de Prescrição",
                "Projeção analítica read-only de férias vencidas e risco prescricional.",
                "detail",
                "response",
                SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("ergon", "getRadarById", "/api/ergon/radar-ferias-prescricao/{id}", "GET"),
                new CanonicalSchemaRef("RadarFeriasResponse", "response", "/schemas/filtered?schemaType=response"),
                10,
                List.of(),
                List.of(),
                List.of("radar", "ferias")
        );

        SurfaceDefinitionRegistry surfaceRegistry = new SurfaceDefinitionRegistry() {
            @Override
            public List<SurfaceDefinition> findByResourceKey(String resourceKey) {
                return List.of(vinculosCreate, vinculosBaselineDetail, vinculosDetail, radarDetail).stream()
                        .filter(surface -> surface.resourceKey().equals(resourceKey))
                        .toList();
            }

            @Override
            public List<SurfaceDefinition> findByGroup(String group) {
                return List.of(vinculosCreate, vinculosBaselineDetail, vinculosDetail, radarDetail);
            }
        };

        ActionDefinitionRegistry emptyActions = new ActionDefinitionRegistry() {
            @Override
            public List<ActionDefinition> findByResourceKey(String resourceKey) {
                return List.of();
            }

            @Override
            public List<ActionDefinition> findByGroup(String group) {
                return List.of();
            }
        };

        OpenApiDocumentService kgOpenApi = new OpenApiDocumentService() {
            private final ObjectMapper mapper = new ObjectMapper();

            @Override
            public String resolveGroupFromPath(String path) {
                return "ergon";
            }

            @Override
            public JsonNode getDocumentForGroup(String groupName) {
                return mapper.valueToTree(Map.of(
                        "paths", Map.of(
                                "/api/ergon/vinculos", Map.of(
                                        "post", Map.of("operationId", "createVinculo", "responses", Map.of("201", Map.of()))
                                ),
                                "/api/ergon/vinculos/{id}", Map.of(
                                        "get", Map.of("operationId", "getVinculoById", "responses", Map.of("200", Map.of()))
                                ),
                                "/api/ergon/radar-ferias-prescricao/filter", Map.of(
                                        "post", Map.of(
                                                "operationId", "filterRadarFerias",
                                                "x-ui", Map.of(
                                                        "analytics", Map.of(
                                                                "projections", List.of(Map.of(
                                                                        "id", "saldo-por-situacao",
                                                                        "label", "Saldo por Situação",
                                                                        "description", "Distribuição de dias pendentes por risco prescricional.",
                                                                        "intent", "risk",
                                                                        "preferredVisualization", Map.of("defaultFamily", "bar"),
                                                                        "source", Map.of(
                                                                                "kind", "praxis.stats",
                                                                                "operation", "group-by",
                                                                                "path", "/api/ergon/radar-ferias-prescricao/stats/group-by"
                                                                        ),
                                                                        "semantics", Map.of(
                                                                                "primaryDimension", Map.of("field", "situacaoPrescricao", "label", "Situação"),
                                                                                "primaryMetrics", List.of(Map.of("field", "saldoDiasPendentes", "aggregation", "SUM"))
                                                                        ),
                                                                        "interactions", Map.of(
                                                                                "recordOpen", Map.of(
                                                                                        "target", Map.of(
                                                                                                "resourceKey", "ergon.periodos-aquisitivos-ferias",
                                                                                                "surfaceId", "detail"
                                                                                        )
                                                                                )
                                                                        )
                                                                ))
                                                        )
                                                ),
                                                "responses", Map.of("200", Map.of())
                                        )
                                ),
                                "/api/ergon/radar-ferias-prescricao/stats/group-by", Map.of(
                                        "post", Map.of("operationId", "radarStatsGroupBy", "responses", Map.of("200", Map.of()))
                                )
                        ),
                        "components", Map.of(
                                "schemas", Map.of(
                                        "VinculoCreateRequest", Map.of(
                                                "type", "object",
                                                "description", "Payload de criação de vínculo funcional.",
                                                "properties", Map.of(
                                                        "cargoId", Map.of(
                                                                "type", "integer",
                                                                "description", "Cargo de provimento."
                                                        )
                                                )
                                        ),
                                        "VinculoResponse", Map.of(
                                                "type", "object",
                                                "description", "Vínculo funcional ativo do servidor público.",
                                                "properties", Map.of(
                                                        "id", Map.of("type", "integer"),
                                                        "lotacaoNome", Map.of(
                                                                "type", "string",
                                                                "description", "Unidade organizacional de lotação.",
                                                                "x-ui", Map.of(
                                                                        "resourceReference", Map.of(
                                                                                "resourceKey", "ergon.setores",
                                                                                "valueField", "lotacaoId",
                                                                                "surfaceId", "detail",
                                                                                "previewSurfaceId", "detail"
                                                                        )
                                                                )
                                                        ),
                                                        "cargoId", Map.of(
                                                                "type", "integer",
                                                                "description", "Cargo de provimento efetivo ou comissionado.",
                                                                "x-ui", Map.of(
                                                                        "optionSource", Map.of(
                                                                                "key", "cargo",
                                                                                "type", "RESOURCE_ENTITY",
                                                                                "resourcePath", "/api/ergon/cargos",
                                                                                "entityKey", "cargo"
                                                                        )
                                                                )
                                                        )
                                                )
                                        ),
                                        "RadarFeriasResponse", Map.of(
                                                "type", "object",
                                                "description", "Radar analítico de prescrição e vencimento de períodos aquisitivos de férias.",
                                                "properties", Map.of(
                                                        "id", Map.of("type", "integer"),
                                                        "servidorNome", Map.of(
                                                                "type", "string",
                                                                "x-ui", Map.of(
                                                                        "resourceReference", Map.of(
                                                                                "resourceKey", "ergon.vinculos",
                                                                                "valueField", "vinculoId",
                                                                                "surfaceId", "detail",
                                                                                "previewSurfaceId", "detail"
                                                                        )
                                                                )
                                                        ),
                                                        "periodoRotulo", Map.of(
                                                                "type", "string",
                                                                "x-ui", Map.of(
                                                                        "resourceReference", Map.of(
                                                                                "resourceKey", "ergon.periodos-aquisitivos-ferias",
                                                                                "valueField", "id",
                                                                                "surfaceId", "detail",
                                                                                "previewSurfaceId", "detail"
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ));
            }

            @Override
            public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
                return "hash:" + schemaId;
            }

            @Override
            public void clearCaches() {
            }
        };

        SemanticDomainCatalogService service = new SemanticDomainCatalogService(
                emptyActions,
                surfaceRegistry,
                OptionSourceRegistry.empty(),
                kgOpenApi,
                Clock.fixed(Instant.parse("2026-04-06T12:00:00Z"), ZoneOffset.UTC),
                "ergon-host",
                "Ergon Showcase",
                "1.0.0"
        );

        DomainCatalogResponse catalog = service.findByGroup("ergon");

        assertThat(catalog.edges())
                .extracting(
                        DomainCatalogResponse.DomainEdgeItem::edgeKey,
                        DomainCatalogResponse.DomainEdgeItem::sourceNodeKey,
                        DomainCatalogResponse.DomainEdgeItem::targetNodeKey,
                        DomainCatalogResponse.DomainEdgeItem::edgeType
                )
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "ergon.vinculos.field.lotacao-nome.references.ergon.setores",
                                "ergon.vinculos.field.lotacao-nome",
                                "ergon.setores",
                                "references"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "ergon.vinculos.references.ergon.setores",
                                "ergon.vinculos",
                                "ergon.setores",
                                "references"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "ergon.vinculos.references-child.ergon.periodos-aquisitivos-ferias",
                                "ergon.vinculos",
                                "ergon.periodos-aquisitivos-ferias",
                                "references"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "ergon.radar-ferias-prescricao.has-stats.projection.saldo-por-situacao",
                                "ergon.radar-ferias-prescricao",
                                "ergon.radar-ferias-prescricao.stats.projection.saldo-por-situacao",
                                "has_stats"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "ergon.radar-ferias-prescricao.stats.projection.saldo-por-situacao.references.ergon.periodos-aquisitivos-ferias",
                                "ergon.radar-ferias-prescricao.stats.projection.saldo-por-situacao",
                                "ergon.periodos-aquisitivos-ferias",
                                "references"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "ergon.radar-ferias-prescricao.materializes.ergon.vinculos",
                                "ergon.radar-ferias-prescricao",
                                "ergon.vinculos",
                                "materializes"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "ergon.radar-ferias-prescricao.materializes.ergon.periodos-aquisitivos-ferias",
                                "ergon.radar-ferias-prescricao",
                                "ergon.periodos-aquisitivos-ferias",
                                "materializes"
                        )
                );

        DomainCatalogResponse.DomainNodeItem radarConcept = catalog.nodes().stream()
                .filter(node -> "ergon.radar-ferias-prescricao".equals(node.nodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(radarConcept.metadata())
                .containsEntry("semanticRole", "analytical_data_product")
                .containsEntry("dataProduct", true)
                .containsEntry("analyzesConcepts", List.of("ergon.periodos-aquisitivos-ferias", "ergon.vinculos"));
        assertThat(radarConcept.tags()).contains("data-product", "analytical-projection");

        DomainCatalogResponse.DomainNodeItem vinculosConcept = catalog.nodes().stream()
                .filter(node -> "ergon.vinculos".equals(node.nodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(vinculosConcept.metadata())
                .containsEntry("semanticRole", "operational_resource")
                .containsEntry("childConcepts", List.of("ergon.periodos-aquisitivos-ferias"))
                .containsEntry("referencedConcepts", List.of("ergon.cargos", "ergon.periodos-aquisitivos-ferias", "ergon.setores"))
                .containsEntry("analyzedByDataProducts", List.of("ergon.radar-ferias-prescricao"))
                .doesNotContainKeys("conceptFactRootMap", "ontologicalFactRoots");

        DomainCatalogResponse.DomainNodeItem cargoField = catalog.nodes().stream()
                .filter(node -> "ergon.vinculos.field.cargo-id".equals(node.nodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(cargoField.metadata())
                .containsEntry("resourceKey", "ergon.vinculos")
                .containsEntry("referencedResourceKey", "ergon.cargos")
                .containsEntry("schemaIds", List.of("VinculoCreateRequest", "VinculoResponse"))
                .containsEntry("schemaRoles", List.of("request", "response"))
                .containsKey("optionSource");
        assertThat(cargoField.sourceEvidenceKeys()).containsExactly(
                "evidence:ergon.vinculos.field.cargo-id:vinculo-create-request",
                "evidence:ergon.vinculos.field.cargo-id:vinculo-response"
        );
        assertThat(catalog.bindings())
                .filteredOn(binding -> "binding:ergon.vinculos.field.cargo-id:dto-field".equals(binding.bindingKey()))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.schemaLinks())
                            .extracting(DomainCatalogResponse.DomainSchemaLink::schemaId, DomainCatalogResponse.DomainSchemaLink::schemaType)
                            .containsExactly(
                                    org.assertj.core.groups.Tuple.tuple("VinculoCreateRequest", "request"),
                                    org.assertj.core.groups.Tuple.tuple("VinculoResponse", "response")
                            );
                    assertThat(binding.evidenceKeys()).containsExactly(
                            "evidence:ergon.vinculos.field.cargo-id:vinculo-create-request",
                            "evidence:ergon.vinculos.field.cargo-id:vinculo-response"
                    );
                    assertThat(binding.target())
                            .containsEntry("resourceKey", "ergon.vinculos")
                            .containsEntry("referencedResourceKey", "ergon.cargos");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> representations =
                            (List<Map<String, Object>>) binding.target().get("representations");
                    assertThat(representations)
                            .extracting(rep -> rep.get("schemaId"), rep -> rep.get("schemaRole"), rep -> rep.get("fieldPath"))
                            .containsExactly(
                                    org.assertj.core.groups.Tuple.tuple("VinculoCreateRequest", "request", "/cargoId"),
                                    org.assertj.core.groups.Tuple.tuple("VinculoResponse", "response", "/cargoId")
                            );
                });

        java.util.Set<String> allNodeKeys = catalog.nodes().stream()
                .map(DomainCatalogResponse.DomainNodeItem::nodeKey)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(catalog.edges())
                .allSatisfy(edge -> {
                    assertThat(allNodeKeys).contains(edge.sourceNodeKey(), edge.targetNodeKey());
                });

        DomainCatalogResponse.DomainNodeItem feriasConcept = catalog.nodes().stream()
                .filter(node -> "ergon.periodos-aquisitivos-ferias".equals(node.nodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(feriasConcept.nodeType()).isEqualTo("concept");
        assertThat(feriasConcept.metadata())
                .containsEntry("projectionRole", "REFERENCE")
                .containsEntry("ownerServiceKey", "ergon-host")
                .doesNotContainKeys("capabilities", "semanticRole", "readOnly");
    }

    @Test
    void publishesDomainRuleSetPolicyHintsAndOntologicalFactRoots() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expression = mapper.readTree("""
                {"and":[
                  {"!=":[{"var":"draft.valorLiquido"},null]},
                  {"==":[{"var":"fornecedor.status"},"ACTIVE"]},
                  {"<=":[{"var":"neighborhood.openPayrollsCount"},1]},
                  {"!=":[{"var":"context.tenantId"},null]}
                ]}
                """);
        RuleSetDefinition ruleSetDefinition = RuleSetDefinition.withInferredRoots(
                new RuleSetRef(
                        "human-resources",
                        "human-resources.folhas-pagamento",
                        "human-resources.folhas-pagamento.validate",
                        "CREATE",
                        1
                ),
                List.of(new DecisionSlot(
                        "payroll.erg-folha-001",
                        DecisionStage.PROTECTED_GUARD,
                        SlotCardinality.SINGLE,
                        OverridePolicy.FORBIDDEN,
                        DecisionAggregationPolicy.SINGLE_RESULT,
                        null
                , java.util.List.of())),
                List.of(new DecisionBinding(
                        "product.payroll.erg-folha-001",
                        "payroll.erg-folha-001",
                        DecisionSource.PRODUCT,
                        null,
                        RuleExecutorRef.jsonLogic(expression),
                        List.of(),
                        1,
                        true,
                        RuleDecision.DENY,
                        "ERG-FOLHA-001",
                        List.of(
                                "context.tenantId",
                                "draft.valorLiquido",
                                "fornecedor.status",
                                "neighborhood.openPayrollsCount"
                        ),
                        null
                )),
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED
        );
        DomainRuleSetDescriptor descriptor = DomainRuleSetDescriptor.of(
                "human-resources.folhas-pagamento",
                ruleSetDefinition,
                "Regras de validação da folha",
                "Governa a criação da folha de pagamento com raízes ontológicas SAP/SHACL.",
                List.of(new DomainRuleSetDescriptor.RuleReasonDescriptor(
                        "ERG-FOLHA-001",
                        "payroll.erg-folha-001",
                        "valorLiquido",
                        "Valor líquido inválido ou fornecedor inativo.",
                        "PCK_FOLHA.VALIDA_FOLHA",
                        "draft.valorLiquido != null && fornecedor.status == ACTIVE"
                )),
                Map.of("fornecedor", new ConceptRootBinding("fornecedor", "praxis-api-quickstart", "partners", "partners.fornecedores"))
        );

        SemanticDomainCatalogService service = new SemanticDomainCatalogService(
                new StubActionDefinitionRegistry(),
                new StubSurfaceDefinitionRegistry(),
                optionSources(),
                new StubOpenApiDocumentService(),
                null,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-api-quickstart",
                "Praxis API Quickstart",
                "test",
                null,
                null,
                null,
                (resourceKey, tenantId) -> "human-resources.folhas-pagamento".equals(resourceKey)
                        ? List.of(descriptor)
                        : List.of()
        );

        DomainCatalogResponse response = service.findByResourceKey("human-resources.folhas-pagamento");

        DomainCatalogResponse.DomainNodeItem ruleSetNode = response.nodes().stream()
                .filter(node -> "human-resources.folhas-pagamento.validate".equals(node.metadata().get("ruleSetKey")))
                .findFirst()
                .orElseThrow();
        assertThat(ruleSetNode.nodeType()).isEqualTo("policy_hint");
        assertThat(ruleSetNode.source()).isEqualTo("rule-set");
        assertThat(ruleSetNode.metadata())
                .containsEntry("policyKind", "domain_rule_set")
                .containsEntry("resourceKey", "human-resources.folhas-pagamento")
                .containsEntry("ruleSetKey", "human-resources.folhas-pagamento.validate")
                .containsEntry("operationKey", "CREATE")
                .containsEntry("version", 1)
                .containsEntry("availableRoots", List.of("context", "draft", "fornecedor", "neighborhood"))
                .containsEntry("conceptRoots", List.of("fornecedor"))
                .containsEntry("factPathsByRoot", Map.of(
                        "context", List.of("context.tenantId"),
                        "draft", List.of("draft.valorLiquido"),
                        "fornecedor", List.of("fornecedor.status"),
                        "neighborhood", List.of("neighborhood.openPayrollsCount")
                ))
                .containsEntry("reasonCodes", List.of("ERG-FOLHA-001"))
                .containsEntry("targetFields", List.of("valorLiquido"));

        assertThat(response.edges())
                .extracting(
                        DomainCatalogResponse.DomainEdgeItem::edgeKey,
                        DomainCatalogResponse.DomainEdgeItem::sourceNodeKey,
                        DomainCatalogResponse.DomainEdgeItem::targetNodeKey,
                        DomainCatalogResponse.DomainEdgeItem::edgeType
                )
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "human-resources.folhas-pagamento.governed-by." + ruleSetNode.nodeKey().substring("ruleset:".length()),
                                "human-resources.folhas-pagamento",
                                ruleSetNode.nodeKey(),
                                "governed_by"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "human-resources.folhas-pagamento.field.valor-liquido.governed-by." + ruleSetNode.nodeKey().substring("ruleset:".length()),
                                "human-resources.folhas-pagamento.field.valor-liquido",
                                ruleSetNode.nodeKey(),
                                "governed_by"
                        )
                );

        assertThat(response.bindings())
                .filteredOn(b -> ("binding:" + ruleSetNode.nodeKey() + ":rule-set").equals(b.bindingKey()))
                .singleElement()
                .satisfies(binding -> assertThat(binding.bindingType()).isEqualTo("rule_definition"));

        DomainCatalogResponse.DomainNodeItem conceptNode = response.nodes().stream()
                .filter(node -> "human-resources.folhas-pagamento".equals(node.nodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(conceptNode.metadata())
                .containsEntry("governedRuleSets", List.of("human-resources.folhas-pagamento.validate"))
                .containsEntry("governedOperations", List.of("CREATE"))
                .containsEntry("governedReasonCodes", List.of("ERG-FOLHA-001"))
                .containsEntry("ontologicalFactRoots", List.of("draft", "current", "fornecedor", "neighborhood", "context"))
                .containsEntry("ontologicalFactPathsByRoot", Map.of(
                        "draft", List.of("draft.valorLiquido"),
                        "current", List.of(),
                        "fornecedor", List.of("fornecedor.status"),
                        "neighborhood", List.of("neighborhood.openPayrollsCount"),
                        "context", List.of("context.tenantId")
                ));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> ontologicalPathsByRoot =
                (Map<String, List<String>>) conceptNode.metadata().get("ontologicalFactPathsByRoot");
        assertThat(ontologicalPathsByRoot.keySet())
                .containsExactly("draft", "current", "fornecedor", "neighborhood", "context");
        assertThat(conceptNode.tags()).contains("rule-governed");
    }

    @Test
    void resolvesRuleErrorCatalogAndParameterizedSlotBindingsInDomainCatalog() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expression = mapper.readTree("""
                {"<=":[{"var":"draft.valorLiquido"},50000]}
                """);
        RuleParameterTemplate template = new RuleParameterTemplate(
                "payroll.max-net-amount",
                "1.0",
                RuleExecutorRef.jsonLogic(expression),
                RuleDecision.DENY,
                "ERG-FOLHA-002",
                List.of("draft.valorLiquido"),
                List.of(new RuleParameterDefinition(
                        "maxNetAmount",
                        RuleParameterType.INTEGER,
                        true,
                        false,
                        false,
                        "BRL",
                        mapper.getNodeFactory().numberNode(50000),
                        java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.valueOf(100000),
                        List.of(),
                        List.of("/<=/1")
                ))
        );
        RuleSetDefinition parameterizedDefinition = new RuleSetDefinition(
                new RuleSetRef(
                        "human-resources",
                        "human-resources.folhas-pagamento",
                        "human-resources.folhas-pagamento.validate",
                        "UPDATE",
                        1
                ),
                List.of("draft", "current", "context"),
                List.of(new DecisionSlot(
                        "payroll.erg-folha-002",
                        DecisionStage.DOMAIN_DECISION,
                        SlotCardinality.SINGLE,
                        OverridePolicy.PARAMETERIZABLE,
                        DecisionAggregationPolicy.SINGLE_RESULT,
                        template
                , java.util.List.of())),
                List.of(new DecisionBinding(
                        "customer.config.payroll.erg-folha-002",
                        "payroll.erg-folha-002",
                        DecisionSource.CUSTOMER,
                        CompositionPolicy.PARAMETERIZE,
                        null,
                        List.of(),
                        1,
                        true,
                        null,
                        null,
                        List.of(),
                        new RuleParameterization(
                                "payroll.max-net-amount",
                                "1.0",
                                mapper.createObjectNode().put("maxNetAmount", 75000)
                        )
                )),
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED
        );
        RuleErrorCatalog errorCatalog = code -> "ERG-FOLHA-002".equals(code)
                ? java.util.Optional.of(new RuleErrorDescriptor(
                        "ERG-FOLHA-002",
                        "valorLiquido",
                        "Valor líquido excede o teto parametrizado da folha.",
                        "PCK_FOLHA.VALIDA_TETO:L120",
                        "VALIDATION"
                ))
                : java.util.Optional.empty();

        SemanticDomainCatalogService service = new SemanticDomainCatalogService(
                new StubActionDefinitionRegistry(),
                new StubSurfaceDefinitionRegistry(),
                optionSources(),
                new StubOpenApiDocumentService(),
                null,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-api-quickstart",
                "Praxis API Quickstart",
                "test",
                null,
                null,
                null,
                DomainRuleSetCatalogProvider.ofDefinitions(List.of(parameterizedDefinition), errorCatalog)
        );

        DomainCatalogResponse response = service.findByResourceKey("human-resources.folhas-pagamento");

        DomainCatalogResponse.DomainNodeItem ruleSetNode = response.nodes().stream()
                .filter(node -> "human-resources.folhas-pagamento.validate".equals(node.metadata().get("ruleSetKey")))
                .findFirst()
                .orElseThrow();
        assertThat(ruleSetNode.metadata())
                .containsEntry("operationKey", "UPDATE")
                .containsEntry("reasonCodes", List.of("ERG-FOLHA-002"))
                .containsEntry("targetFields", List.of("valorLiquido"))
                .containsEntry("referencedFactPaths", List.of("draft.valorLiquido"))
                .containsEntry("factPathsByRoot", Map.of(
                        "context", List.of(),
                        "current", List.of(),
                        "draft", List.of("draft.valorLiquido")
                ));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) ruleSetNode.metadata().get("rules");
        assertThat(rules).singleElement().satisfies(rule -> {
            assertThat(rule).containsEntry("bindingKey", "customer.config.payroll.erg-folha-002");
            assertThat(rule).containsEntry("compositionPolicy", "PARAMETERIZE");
            assertThat(rule).containsEntry("parameterized", true);
            assertThat(rule).containsEntry("falseDecision", "DENY");
            assertThat(rule).containsEntry("reasonCode", "ERG-FOLHA-002");
            assertThat(rule).containsEntry("targetField", "valorLiquido");
            assertThat(rule).containsEntry("message", "Valor líquido excede o teto parametrizado da folha.");
            assertThat(rule).containsEntry("lineage", "PCK_FOLHA.VALIDA_TETO:L120");
            assertThat(rule).containsEntry("requiredFactPaths", List.of("draft.valorLiquido"));
            assertThat(rule).containsEntry("referencedFactPaths", List.of("draft.valorLiquido"));
        });
    }

    @Test
    void explicitAnnotationGovernancePrevailsOverHeuristicAndResolvesMultiFacetLatticeWithoutCrashing() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode explicitPersonal = mapper.valueToTree(new DomainCatalogResponse.DomainGovernanceItem(
                "governance:ergon.servidores.field.conta-salario:privacy",
                "ergon.servidores.field.conta-salario",
                "privacy",
                "confidential",
                "personal",
                List.of("LGPD"),
                false,
                "Gestão de Pessoas",
                "DPO",
                "10-years",
                Map.of(
                        "visibility", "mask",
                        "trainingUse", "deny",
                        "ruleAuthoring", "review_required",
                        "reasoningUse", "allow",
                        "reason", "explicit-domain-governance",
                        "schemaId", "ServidorResponse",
                        "schemaRole", "response"
                ),
                "java.annotation",
                1.0d
        ));
        JsonNode heuristicFinancial = mapper.valueToTree(new DomainCatalogResponse.DomainGovernanceItem(
                "governance:ergon.servidores.field.conta-salario:security",
                "ergon.servidores.field.conta-salario",
                "security",
                "internal",
                "financial",
                List.of("INTERNAL_POLICY"),
                null,
                null,
                null,
                null,
                Map.of(
                        "visibility", "allow",
                        "trainingUse", "deny",
                        "ruleAuthoring", "review_required",
                        "reasoningUse", "review_required",
                        "reason", "financial-field-name",
                        "schemaId", "ServidorFilter",
                        "schemaRole", "request"
                ),
                "dto-field-heuristic",
                0.72d
        ));

        for (JsonNode mergedNode : List.of(
                org.praxisplatform.uischema.schema.DomainSchemaGovernance.merge(explicitPersonal, heuristicFinancial),
                org.praxisplatform.uischema.schema.DomainSchemaGovernance.merge(heuristicFinancial, explicitPersonal))) {
            DomainCatalogResponse.DomainGovernanceItem merged =
                    mapper.convertValue(mergedNode, DomainCatalogResponse.DomainGovernanceItem.class);

            assertThat(merged.source()).isEqualTo("java.annotation");
            assertThat(merged.confidence()).isEqualTo(1.0d);
            assertThat(merged.annotationType()).isEqualTo("privacy");
            assertThat(merged.governanceKey()).isEqualTo("governance:ergon.servidores.field.conta-salario:privacy");
            assertThat(merged.dataCategory()).isEqualTo("personal");
            assertThat(merged.classification()).isEqualTo("confidential");
            assertThat(merged.exportable()).isFalse();
            assertThat(merged.owner()).isEqualTo("Gestão de Pessoas");
            assertThat(merged.steward()).isEqualTo("DPO");
            assertThat(merged.retentionPolicy()).isEqualTo("10-years");
            assertThat(merged.complianceTags()).containsExactly("INTERNAL_POLICY", "LGPD");
            assertThat(merged.aiUsage())
                    .containsEntry("visibility", "mask")
                    .containsEntry("reasoningUse", "review_required")
                    .containsEntry("reason", "explicit-domain-governance")
                    .containsEntry("schemaId", "ServidorResponse")
                    .containsEntry("schemaRole", "response");
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                org.praxisplatform.uischema.schema.DomainSchemaGovernance.merge(
                        mapper.readTree("{\"dataCategory\":\"financial\"}"),
                        mapper.readTree("{\"dataCategory\":\"personal\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting domain governance dataCategory");
    }

    @Test
    void strictCatalogResolutionRequiresExactGroupDocumentAndCompleteSchemas() {
        SemanticDomainCatalogService strictOkService = new SemanticDomainCatalogService(
                new StubActionDefinitionRegistry(),
                new StubSurfaceDefinitionRegistry(),
                optionSources(),
                new StubOpenApiDocumentService(),
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-api-quickstart",
                "Praxis API Quickstart",
                "test"
        );
        DomainCatalogResponse strictCatalog = strictOkService.findByResourceKeyStrict("human-resources.folhas-pagamento");
        assertThat(strictCatalog.resourceKey()).isEqualTo("human-resources.folhas-pagamento");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strictOkService.findByResourceKeyStrict(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Explicit resourceKey is required");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strictOkService.findByGroupStrict(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Explicit OpenAPI group is required");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strictOkService.findByResourceKeyStrict("unknown.resource"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no registered actions, surfaces, or determination forms");

        OpenApiDocumentService missingSchemaOpenApi = new OpenApiDocumentService() {
            private final ObjectMapper mapper = new ObjectMapper();

            @Override
            public String resolveGroupFromPath(String path) {
                return "human-resources";
            }

            @Override
            public JsonNode getDocumentForGroup(String groupName) {
                return mapper.valueToTree(Map.of(
                        "paths", Map.of(),
                        "components", Map.of("schemas", Map.of())
                ));
            }

            @Override
            public JsonNode getDocumentForGroupStrict(String groupName) {
                return getDocumentForGroup(groupName);
            }

            @Override
            public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
                return "hash:" + schemaId;
            }

            @Override
            public void clearCaches() {
            }
        };
        SemanticDomainCatalogService strictMissingSchemaService = new SemanticDomainCatalogService(
                new StubActionDefinitionRegistry(),
                new StubSurfaceDefinitionRegistry(),
                optionSources(),
                missingSchemaOpenApi,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-api-quickstart",
                "Praxis API Quickstart",
                "test"
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                strictMissingSchemaService.findByResourceKeyStrict("human-resources.folhas-pagamento"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved canonical schema in strict domain catalog: WorkflowRequest");
    }

    @Test
    void synthesizesDomainLineageAcrossRequestResponseFilterAliasesAndDerivedFields() {
        SurfaceDefinition createSurface = new SurfaceDefinition(
                "create", "ergon.pessoas", "/api/ergon/pessoas", "ergon",
                SurfaceKind.PARTIAL_FORM, SurfaceScope.COLLECTION, "Nova Pessoa", "Inclusao cadastral",
                "create", "request", SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("ergon", "createPessoa", "/api/ergon/pessoas", "POST"),
                new CanonicalSchemaRef("PessoaCreateRequest", "request", "/schemas/filtered?path=/api/ergon/pessoas&operation=post&schemaType=request"),
                10, List.of(), List.of(), List.of("pessoas")
        );
        SurfaceDefinition detailSurface = new SurfaceDefinition(
                "detail", "ergon.pessoas", "/api/ergon/pessoas", "ergon",
                SurfaceKind.VIEW, SurfaceScope.ITEM, "Detalhe Pessoa", "Visualizacao cadastral",
                "detail", "response", SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("ergon", "getPessoaById", "/api/ergon/pessoas/{id}", "GET"),
                new CanonicalSchemaRef("PessoaDetailResponse", "response", "/schemas/filtered?path=/api/ergon/pessoas/{id}&operation=get&schemaType=response"),
                20, List.of(), List.of(), List.of("pessoas")
        );
        SurfaceDefinition filterSurface = new SurfaceDefinition(
                "filter", "ergon.pessoas", "/api/ergon/pessoas", "ergon",
                SurfaceKind.VIEW, SurfaceScope.COLLECTION, "Filtro Pessoa", "Pesquisa cadastral",
                "filter", "filter", SurfaceResponseCardinality.COLLECTION,
                new CanonicalOperationRef("ergon", "filterPessoas", "/api/ergon/pessoas/filter", "POST"),
                new CanonicalSchemaRef("PessoaFilterDTO", "filter", "/schemas/filtered?path=/api/ergon/pessoas/filter&operation=post&schemaType=request"),
                30, List.of(), List.of(), List.of("pessoas")
        );

        Map<String, Object> cpfLineageGov = Map.of(
                "annotationType", "privacy",
                "classification", "confidential",
                "dataCategory", "personal",
                "complianceTags", List.of("LGPD", "PII_CPF"),
                "exportable", false,
                "aiUsage", Map.of("visibility", "mask", "trainingUse", "deny", "ruleAuthoring", "review_required", "reasoningUse", "allow"),
                "reason", "CPF oficial protegido pela LGPD.",
                "source", "java.lineage",
                "confidence", 1.0d,
                "lineage", Map.of(
                        "attributeId", "ergon.pessoa.cpf",
                        "sourceClass", "br.com.praxis.ergonshowcase.domain.entity.Pessoa",
                        "sourceSimpleName", "Pessoa",
                        "sourceResourceKey", "ergon.pessoas",
                        "sourcePaths", List.of("cpf"),
                        "transformation", "alias",
                        "provenance", "declared_lineage"
                )
        );
        Map<String, Object> cidAfastamentoGov = Map.of(
                "annotationType", "privacy",
                "classification", "restricted",
                "dataCategory", "sensitive_personal",
                "complianceTags", List.of("LGPD", "LGPD_ART_11"),
                "exportable", false,
                "aiUsage", Map.of("visibility", "deny", "trainingUse", "deny", "ruleAuthoring", "deny", "reasoningUse", "deny"),
                "reason", "Codigo CID de afastamento clinico protegido pelo Art. 11 da LGPD.",
                "source", "java.annotation",
                "confidence", 1.0d
        );
        Map<String, Object> derivedRestricaoGov = Map.of(
                "annotationType", "privacy",
                "classification", "restricted",
                "dataCategory", "sensitive_personal",
                "complianceTags", List.of("LGPD", "LGPD_ART_11"),
                "exportable", false,
                "aiUsage", Map.of("visibility", "deny", "trainingUse", "deny", "ruleAuthoring", "deny", "reasoningUse", "deny"),
                "reason", "Indicador derivado de CID de afastamento.",
                "source", "java.lineage",
                "confidence", 1.0d,
                "lineage", Map.of(
                        "attributeId", "ergon.pessoa.possui-restricao-clinica",
                        "sourceClass", "br.com.praxis.ergonshowcase.domain.entity.Pessoa",
                        "sourceSimpleName", "Pessoa",
                        "sourceResourceKey", "ergon.pessoas",
                        "sourcePaths", List.of("cidAfastamento"),
                        "transformation", "derived",
                        "provenance", "declared_lineage"
                )
        );

        OpenApiDocumentService lineageOpenApi = new OpenApiDocumentService() {
            private final ObjectMapper mapper = new ObjectMapper();
            @Override public String resolveGroupFromPath(String path) { return "ergon"; }
            @Override public JsonNode getDocumentForGroup(String groupName) {
                return mapper.valueToTree(Map.of(
                        "paths", Map.of(),
                        "components", Map.of("schemas", Map.of(
                                "PessoaCreateRequest", Map.of(
                                        "type", "object",
                                        "required", List.of("cpf"),
                                        "properties", Map.of(
                                                "cpf", Map.of("type", "string", "x-domain-governance", cpfLineageGov)
                                        )
                                ),
                                "PessoaDetailResponse", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "documentoFiscal", Map.of("type", "string", "x-domain-governance", cpfLineageGov),
                                                "cidAfastamento", Map.of("type", "string", "x-domain-governance", cidAfastamentoGov),
                                                "possuiRestricaoClinica", Map.of("type", "boolean", "x-domain-governance", derivedRestricaoGov)
                                        )
                                ),
                                "PessoaFilterDTO", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "documentoFiscalFiltro", Map.of("type", "string", "x-domain-governance", cpfLineageGov)
                                        )
                                )
                        ))
                ));
            }
            @Override public JsonNode getDocumentForGroupStrict(String groupName) { return getDocumentForGroup(groupName); }
            @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) { return "hash:" + schemaId; }
            @Override public void clearCaches() { }
        };

        SemanticDomainCatalogService service = new SemanticDomainCatalogService(
                new ActionDefinitionRegistry() {
                    @Override public List<ActionDefinition> findByResourceKey(String rk) { return List.of(); }
                    @Override public List<ActionDefinition> findByGroup(String g) { return List.of(); }
                },
                new SurfaceDefinitionRegistry() {
                    @Override public List<SurfaceDefinition> findByResourceKey(String rk) { return List.of(createSurface, detailSurface, filterSurface); }
                    @Override public List<SurfaceDefinition> findByGroup(String g) { return List.of(createSurface, detailSurface, filterSurface); }
                },
                OptionSourceRegistry.empty(),
                lineageOpenApi,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-ergon-showcase", "Ergon Showcase", "test"
        );

        DomainCatalogResponse catalog = service.findByResourceKeyStrict("ergon.pessoas");
        assertThat(catalog.edges()).filteredOn(e -> "same_as".equals(e.edgeType()))
                .extracting(DomainCatalogResponse.DomainEdgeItem::sourceNodeKey)
                .containsExactlyInAnyOrder(
                        "ergon.pessoas.field.documento-fiscal",
                        "ergon.pessoas.field.documento-fiscal-filtro"
                );
        assertThat(catalog.edges()).filteredOn(e -> "materializes".equals(e.edgeType()))
                .extracting(DomainCatalogResponse.DomainEdgeItem::sourceNodeKey)
                .contains("ergon.pessoas.field.documento-fiscal", "ergon.pessoas.field.documento-fiscal-filtro");
        assertThat(catalog.edges()).filteredOn(e -> "depends_on".equals(e.edgeType())
                        && "ergon.pessoas.field.possui-restricao-clinica".equals(e.sourceNodeKey()))
                .singleElement()
                .satisfies(edge -> assertThat(edge.targetNodeKey()).isEqualTo("ergon.pessoas.field.cid-afastamento"));

        DomainCatalogResponse.DomainNodeItem aliasNode = catalog.nodes().stream()
                .filter(n -> "ergon.pessoas.field.documento-fiscal".equals(n.nodeKey()))
                .findFirst().orElseThrow();
        assertThat(aliasNode.metadata()).containsEntry("canonicalAttributeId", "ergon.pessoa.cpf");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sharedReps = (List<Map<String, Object>>) aliasNode.metadata().get("sharedRepresentations");
        assertThat(sharedReps).hasSize(3);
        assertThat(sharedReps).extracting(r -> r.get("schemaRole"))
                .containsExactlyInAnyOrder("request", "response", "filter");
        assertThat(catalog.aliases()).filteredOn(a -> "domain-lineage-attribute-id".equals(a.source()))
                .extracting(DomainCatalogResponse.DomainAliasItem::alias)
                .contains("ergon.pessoa.cpf", "ergon.pessoa.possui-restricao-clinica");
    }

    @Test
    void rejectsHomonymLineageConflictAndCyclicDerivedLineageInDomainCatalog() {
        SurfaceDefinition reqSurface = new SurfaceDefinition(
                "create", "ergon.conflito", "/api/ergon/conflito", "ergon",
                SurfaceKind.PARTIAL_FORM, SurfaceScope.COLLECTION, "Criar", "Criar",
                "create", "request", SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("ergon", "createConflito", "/api/ergon/conflito", "POST"),
                new CanonicalSchemaRef("ConflitoRequest", "request", "/schemas/filtered?schemaType=request"),
                10, List.of(), List.of(), List.of()
        );
        SurfaceDefinition resSurface = new SurfaceDefinition(
                "detail", "ergon.conflito", "/api/ergon/conflito", "ergon",
                SurfaceKind.VIEW, SurfaceScope.ITEM, "Detalhe", "Detalhe",
                "detail", "response", SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("ergon", "getConflito", "/api/ergon/conflito/{id}", "GET"),
                new CanonicalSchemaRef("ConflitoResponse", "response", "/schemas/filtered?schemaType=response"),
                20, List.of(), List.of(), List.of()
        );

        OpenApiDocumentService homonymConflictOpenApi = new OpenApiDocumentService() {
            private final ObjectMapper mapper = new ObjectMapper();
            @Override public String resolveGroupFromPath(String path) { return "ergon"; }
            @Override public JsonNode getDocumentForGroup(String groupName) {
                return mapper.valueToTree(Map.of(
                        "paths", Map.of(),
                        "components", Map.of("schemas", Map.of(
                                "ConflitoRequest", Map.of("type", "object", "properties", Map.of(
                                        "codigo", Map.of("type", "string", "x-domain-governance", Map.of(
                                                "annotationType", "privacy", "classification", "internal", "dataCategory", "operational",
                                                "lineage", Map.of("attributeId", "ergon.empresa.codigo", "transformation", "direct")
                                        ))
                                )),
                                "ConflitoResponse", Map.of("type", "object", "properties", Map.of(
                                        "codigo", Map.of("type", "string", "x-domain-governance", Map.of(
                                                "annotationType", "privacy", "classification", "internal", "dataCategory", "operational",
                                                "lineage", Map.of("attributeId", "ergon.setor.codigo", "transformation", "direct")
                                        ))
                                ))
                        ))
                ));
            }
            @Override public JsonNode getDocumentForGroupStrict(String groupName) { return getDocumentForGroup(groupName); }
            @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) { return "hash:" + schemaId; }
            @Override public void clearCaches() { }
        };

        SemanticDomainCatalogService conflictService = new SemanticDomainCatalogService(
                new ActionDefinitionRegistry() {
                    @Override public List<ActionDefinition> findByResourceKey(String rk) { return List.of(); }
                    @Override public List<ActionDefinition> findByGroup(String g) { return List.of(); }
                },
                new SurfaceDefinitionRegistry() {
                    @Override public List<SurfaceDefinition> findByResourceKey(String rk) { return List.of(reqSurface, resSurface); }
                    @Override public List<SurfaceDefinition> findByGroup(String g) { return List.of(reqSurface, resSurface); }
                },
                OptionSourceRegistry.empty(),
                homonymConflictOpenApi,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-ergon-showcase", "Ergon Showcase", "test"
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> conflictService.findByResourceKeyStrict("ergon.conflito"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting domain lineage for homonym field 'codigo'");

        OpenApiDocumentService cyclicOpenApi = new OpenApiDocumentService() {
            private final ObjectMapper mapper = new ObjectMapper();
            @Override public String resolveGroupFromPath(String path) { return "ergon"; }
            @Override public JsonNode getDocumentForGroup(String groupName) {
                return mapper.valueToTree(Map.of(
                        "paths", Map.of(),
                        "components", Map.of("schemas", Map.of(
                                "ConflitoRequest", Map.of("type", "object", "properties", Map.of()),
                                "ConflitoResponse", Map.of("type", "object", "properties", Map.of(
                                        "saldoA", Map.of("type", "number", "x-domain-governance", Map.of(
                                                "annotationType", "privacy", "classification", "internal", "dataCategory", "financial",
                                                "lineage", Map.of("attributeId", "ergon.conflito.saldo-a", "sourcePaths", List.of("saldoB"), "transformation", "derived")
                                        )),
                                        "saldoB", Map.of("type", "number", "x-domain-governance", Map.of(
                                                "annotationType", "privacy", "classification", "internal", "dataCategory", "financial",
                                                "lineage", Map.of("attributeId", "ergon.conflito.saldo-b", "sourcePaths", List.of("saldoA"), "transformation", "derived")
                                        ))
                                ))
                        ))
                ));
            }
            @Override public JsonNode getDocumentForGroupStrict(String groupName) { return getDocumentForGroup(groupName); }
            @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) { return "hash:" + schemaId; }
            @Override public void clearCaches() { }
        };

        SemanticDomainCatalogService cyclicService = new SemanticDomainCatalogService(
                new ActionDefinitionRegistry() {
                    @Override public List<ActionDefinition> findByResourceKey(String rk) { return List.of(); }
                    @Override public List<ActionDefinition> findByGroup(String g) { return List.of(); }
                },
                new SurfaceDefinitionRegistry() {
                    @Override public List<SurfaceDefinition> findByResourceKey(String rk) { return List.of(reqSurface, resSurface); }
                    @Override public List<SurfaceDefinition> findByGroup(String g) { return List.of(reqSurface, resSurface); }
                },
                OptionSourceRegistry.empty(),
                cyclicOpenApi,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-ergon-showcase", "Ergon Showcase", "test"
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cyclicService.findByResourceKeyStrict("ergon.conflito"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic domain lineage derivation detected");
    }

    @Test
    void publishesLegalTreatmentPurposeAndLegalBasisRefOnSurfaceAndWorkflowActionNodesAndBindings() {
        SurfaceDefinition healthSurface = new SurfaceDefinition(
                "laudo-pericial", "ergon.afastamentos", "/api/ergon/afastamentos", "ergon",
                SurfaceKind.READ_PROJECTION, SurfaceScope.ITEM, "Laudo Pericial", "Ficha clinica de pericia medica",
                "laudo-pericial", "medical_services", "response", SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("ergon", "getLaudoPericial", "/api/ergon/afastamentos/{id}/laudo", "GET"),
                new CanonicalSchemaRef("AfastamentoResponse", "response", "/schemas/filtered?path=/api/ergon/afastamentos/{id}/laudo"),
                10, List.of("ROLE_PERITO_MEDICO"), List.of("EM_ANALISE"), List.of("saude", "pericia"),
                null,
                org.praxisplatform.uischema.annotation.LegalTreatmentPurpose.OFFICIAL_HEALTH_EXPERTISE.wireValue(),
                "LGPD Art. 11, II, 'a'; Lei 8.112/1990 Art. 202"
        );
        ActionDefinition homologarAction = new ActionDefinition(
                "homologar-pericia", "ergon.afastamentos", "/api/ergon/afastamentos", "ergon",
                ActionScope.ITEM, "Homologar Pericia", "Homologa parecer medico pericial de afastamento",
                new CanonicalOperationRef("ergon", "homologarPericia", "/api/ergon/afastamentos/{id}/actions/homologar-pericia", "POST"),
                new CanonicalSchemaRef("HomologarRequest", "request", "/schemas/filtered?schemaType=request"),
                new CanonicalSchemaRef("AfastamentoResponse", "response", "/schemas/filtered?schemaType=response"),
                20, "Pericia homologada.", List.of("ROLE_PERITO_MEDICO"), List.of("EM_ANALISE"), List.of("pericia"),
                null,
                org.praxisplatform.uischema.annotation.LegalTreatmentPurpose.OFFICIAL_HEALTH_EXPERTISE.wireValue(),
                "LGPD Art. 11, II, 'a'; Lei 8.112/1990 Art. 202"
        );

        OpenApiDocumentService simpleOpenApi = new OpenApiDocumentService() {
            private final ObjectMapper mapper = new ObjectMapper();
            @Override public String resolveGroupFromPath(String path) { return "ergon"; }
            @Override public JsonNode getDocumentForGroup(String groupName) {
                return mapper.valueToTree(Map.of(
                        "paths", Map.of(),
                        "components", Map.of("schemas", Map.of(
                                "AfastamentoResponse", Map.of("type", "object", "properties", Map.of()),
                                "HomologarRequest", Map.of("type", "object", "properties", Map.of())
                        ))
                ));
            }
            @Override public JsonNode getDocumentForGroupStrict(String groupName) { return getDocumentForGroup(groupName); }
            @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) { return "hash:" + schemaId; }
            @Override public void clearCaches() { }
        };

        SemanticDomainCatalogService service = new SemanticDomainCatalogService(
                new ActionDefinitionRegistry() {
                    @Override public List<ActionDefinition> findByResourceKey(String rk) { return List.of(homologarAction); }
                    @Override public List<ActionDefinition> findByGroup(String g) { return List.of(homologarAction); }
                },
                new SurfaceDefinitionRegistry() {
                    @Override public List<SurfaceDefinition> findByResourceKey(String rk) { return List.of(healthSurface); }
                    @Override public List<SurfaceDefinition> findByGroup(String g) { return List.of(healthSurface); }
                },
                OptionSourceRegistry.empty(),
                simpleOpenApi,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-ergon-showcase", "Ergon Showcase", "test"
        );

        DomainCatalogResponse catalog = service.findByResourceKeyStrict("ergon.afastamentos");

        DomainCatalogResponse.DomainNodeItem surfaceNode = catalog.nodes().stream()
                .filter(n -> "ergon.afastamentos.surface.laudo-pericial".equals(n.nodeKey()))
                .findFirst().orElseThrow();
        assertThat(surfaceNode.metadata())
                .containsEntry("legalPurpose", "official_health_expertise")
                .containsEntry("legalBasisRef", "LGPD Art. 11, II, 'a'; Lei 8.112/1990 Art. 202");

        DomainCatalogResponse.DomainBindingItem surfaceBinding = catalog.bindings().stream()
                .filter(b -> "binding:ergon.afastamentos.surface.laudo-pericial:ui-surface".equals(b.bindingKey()))
                .findFirst().orElseThrow();
        assertThat(surfaceBinding.target())
                .containsEntry("legalPurpose", "official_health_expertise")
                .containsEntry("legalBasisRef", "LGPD Art. 11, II, 'a'; Lei 8.112/1990 Art. 202");

        DomainCatalogResponse.DomainNodeItem actionNode = catalog.nodes().stream()
                .filter(n -> "ergon.afastamentos.action.homologar-pericia".equals(n.nodeKey()))
                .findFirst().orElseThrow();
        assertThat(actionNode.metadata())
                .containsEntry("legalPurpose", "official_health_expertise")
                .containsEntry("legalBasisRef", "LGPD Art. 11, II, 'a'; Lei 8.112/1990 Art. 202");

        DomainCatalogResponse.DomainBindingItem actionBinding = catalog.bindings().stream()
                .filter(b -> "binding:ergon.afastamentos.action.homologar-pericia:workflow-action".equals(b.bindingKey()))
                .findFirst().orElseThrow();
        assertThat(actionBinding.target())
                .containsEntry("legalPurpose", "official_health_expertise")
                .containsEntry("legalBasisRef", "LGPD Art. 11, II, 'a'; Lei 8.112/1990 Art. 202");
    }

    @Test
    void doesNotProjectCrossResourceRelatedSurfaceSchemaOntoParentResource() {
        SurfaceDefinition afastamentosChildSurface = new SurfaceDefinition(
                "afastamentos-vinculo",
                "ergon.vinculos",
                "/api/ergon/vinculos",
                "ergon",
                SurfaceKind.READ_PROJECTION,
                SurfaceScope.ITEM,
                "Licenças e afastamentos",
                "Histórico e licenças em aberto associadas ao vínculo funcional selecionado.",
                "afastamentos-vinculo",
                "response",
                SurfaceResponseCardinality.COLLECTION,
                new CanonicalOperationRef("ergon", "childrenVinculoAfastamento", "/api/ergon/vinculos/{id}/afastamentos", "GET"),
                new CanonicalSchemaRef("AfastamentoResponse", "response", "/schemas/filtered?path=/api/ergon/vinculos/{id}/afastamentos"),
                0,
                List.of(),
                List.of(),
                List.of("vinculo", "afastamento"),
                new RelatedResourceSurface(
                        "ergon.vinculos",
                        "id",
                        "ergon.afastamentos",
                        "/api/ergon/afastamentos",
                        "vinculoId",
                        false,
                        "id",
                        List.of()
                )
        );
        SurfaceDefinition vinculosDetailSurface = new SurfaceDefinition(
                "detail",
                "ergon.vinculos",
                "/api/ergon/vinculos",
                "ergon",
                SurfaceKind.VIEW,
                SurfaceScope.ITEM,
                "Detalhe do Vínculo",
                "Assentamento funcional principal do servidor.",
                "detail",
                "response",
                SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("ergon", "getVinculoById", "/api/ergon/vinculos/{id}", "GET"),
                new CanonicalSchemaRef("VinculoResponse", "response", "/schemas/filtered?schemaType=response"),
                10,
                List.of(),
                List.of(),
                List.of("vinculo")
        );

        OpenApiDocumentService homonymRelatedOpenApi = new OpenApiDocumentService() {
            private final ObjectMapper mapper = new ObjectMapper();
            @Override public String resolveGroupFromPath(String path) { return "ergon"; }
            @Override public JsonNode getDocumentForGroup(String groupName) {
                return mapper.valueToTree(Map.of(
                        "paths", Map.of(),
                        "components", Map.of("schemas", Map.of(
                                "AfastamentoResponse", Map.of(
                                        "type", "object",
                                        "description", "Afastamento funcional.",
                                        "properties", Map.of(
                                                "situacao", Map.of(
                                                        "type", "string",
                                                        "x-domain-governance", Map.of(
                                                                "annotationType", "privacy",
                                                                "classification", "internal",
                                                                "lineage", Map.of(
                                                                        "attributeId", "ergon.afastamentos.situacao",
                                                                        "sourceClass", "Afastamento"
                                                                )
                                                        )
                                                ),
                                                "diasAfastados", Map.of("type", "integer")
                                        )
                                ),
                                "VinculoResponse", Map.of(
                                        "type", "object",
                                        "description", "Vínculo funcional.",
                                        "properties", Map.of(
                                                "situacao", Map.of(
                                                        "type", "string",
                                                        "x-domain-governance", Map.of(
                                                                "annotationType", "privacy",
                                                                "classification", "internal",
                                                                "lineage", Map.of(
                                                                        "attributeId", "ergon.vinculos.situacao",
                                                                        "sourceClass", "Vinculo"
                                                                )
                                                        )
                                                )
                                        )
                                )
                        ))
                ));
            }
            @Override public JsonNode getDocumentForGroupStrict(String groupName) { return getDocumentForGroup(groupName); }
            @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) { return "hash:" + schemaId; }
            @Override public void clearCaches() { }
        };

        SemanticDomainCatalogService service = new SemanticDomainCatalogService(
                new ActionDefinitionRegistry() {
                    @Override public List<ActionDefinition> findByResourceKey(String rk) { return List.of(); }
                    @Override public List<ActionDefinition> findByGroup(String g) { return List.of(); }
                },
                new SurfaceDefinitionRegistry() {
                    @Override public List<SurfaceDefinition> findByResourceKey(String rk) { return List.of(afastamentosChildSurface, vinculosDetailSurface); }
                    @Override public List<SurfaceDefinition> findByGroup(String g) { return List.of(afastamentosChildSurface, vinculosDetailSurface); }
                },
                OptionSourceRegistry.empty(),
                homonymRelatedOpenApi,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "praxis-ergon-showcase", "Ergon Showcase", "test"
        );

        DomainCatalogResponse catalog = service.findByResourceKeyStrict("ergon.vinculos");

        DomainCatalogResponse.DomainNodeItem vinculosConcept = catalog.nodes().stream()
                .filter(node -> "ergon.vinculos".equals(node.nodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(vinculosConcept.description()).isEqualTo("Vínculo funcional.");

        DomainCatalogResponse.DomainNodeItem situacaoField = catalog.nodes().stream()
                .filter(node -> "ergon.vinculos.field.situacao".equals(node.nodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(situacaoField.metadata())
                .containsEntry("canonicalAttributeId", "ergon.vinculos.situacao");

        assertThat(catalog.nodes())
                .noneMatch(node -> "ergon.vinculos.field.dias-afastados".equals(node.nodeKey()));
        assertThat(catalog.nodes())
                .anyMatch(node -> "ergon.vinculos.surface.afastamentos-vinculo".equals(node.nodeKey()));
        assertThat(catalog.edges())
                .anyMatch(edge -> "ergon.vinculos.references-child.ergon.afastamentos".equals(edge.edgeKey()));
    }

    private OptionSourceRegistry optionSources() {
        return OptionSourceRegistry.builder()
                .add(TestResource.class, new OptionSourceDescriptor(
                        "supplier",
                        OptionSourceType.RESOURCE_ENTITY,
                        "/api/human-resources/folhas-pagamento",
                        "supplierId",
                        "id",
                        "legalName",
                        "id",
                        List.of("companyId"),
                        Map.of("companyId", "companyId"),
                        new OptionSourcePolicy(true, true, "contains", 0, 25, 100, true, false, "label"),
                        new EntityLookupDescriptor(
                                "supplier",
                                "code",
                                List.of("documentNumber"),
                                "status",
                                null,
                                "disabledReason",
                                List.of("code", "legalName"),
                                Map.of("companyId", "companyId"),
                                new LookupSelectionPolicy(
                                        null,
                                        "status",
                                        List.of("ACTIVE", "APPROVED"),
                                        List.of("INACTIVE", "BLOCKED"),
                                        true,
                                        null,
                                        null
                                ),
                                null,
                                null
                        )
                ))
                .build();
    }

    private static final class StubActionDefinitionRegistry implements ActionDefinitionRegistry {

        @Override
        public List<ActionDefinition> findByResourceKey(String resourceKey) {
            if ("unknown.resource".equals(resourceKey)) {
                return List.of();
            }
            return List.of(new ActionDefinition(
                    "mark-paid",
                    resourceKey,
                    "/api/human-resources/folhas-pagamento",
                    "human-resources",
                    ActionScope.ITEM,
                    "Marcar como paga",
                    "Confirma o pagamento da folha.",
                    new CanonicalOperationRef(
                            "human-resources",
                            "markPaid",
                            "/api/human-resources/folhas-pagamento/{id}/actions/mark-paid",
                            "POST"
                    ),
                    new CanonicalSchemaRef("WorkflowRequest", "request", "/schemas/filtered?schemaType=request"),
                    new CanonicalSchemaRef(
                            "canonical-response-id",
                            "response",
                            "/schemas/filtered?path=%2Fapi%2Fhuman-resources%2Ffolhas-pagamento%2F%7Bid%7D%2Factions%2Fmark-paid&operation=post&schemaType=response"
                    ),
                    110,
                    "Folha marcada como paga",
                    List.of(),
                    List.of("PROGRAMADA"),
                    List.of("workflow", "payroll"),
                    org.praxisplatform.uischema.action.ActionExecutionContract.defaults(ActionScope.ITEM)
            ));
        }

        @Override
        public List<ActionDefinition> findByGroup(String group) {
            return findByResourceKey("human-resources.folhas-pagamento");
        }
    }

    private static final class StubSurfaceDefinitionRegistry implements SurfaceDefinitionRegistry {

        @Override
        public List<SurfaceDefinition> findByResourceKey(String resourceKey) {
            if ("unknown.resource".equals(resourceKey)) {
                return List.of();
            }
            return List.of(new SurfaceDefinition(
                    "payment-schedule",
                    resourceKey,
                    "/api/human-resources/folhas-pagamento",
                    "human-resources",
                    SurfaceKind.PARTIAL_FORM,
                    SurfaceScope.ITEM,
                    "Ajustar agenda de pagamento",
                    "Reagenda a data de pagamento.",
                    "payroll-scheduling",
                    "request",
                    SurfaceResponseCardinality.VOID,
                    new CanonicalOperationRef(
                            "human-resources",
                            "schedulePayment",
                            "/api/human-resources/folhas-pagamento/{id}/payment-schedule",
                            "PATCH"
                    ),
                    new CanonicalSchemaRef("SchedulePayment", "request", "/schemas/filtered?schemaType=request"),
                    30,
                    List.of(),
                    List.of("AGUARDANDO_EVENTOS", "PROGRAMADA"),
                    List.of("payroll", "schedule")
            ));
        }

        @Override
        public List<SurfaceDefinition> findByGroup(String group) {
            return findByResourceKey("human-resources.folhas-pagamento");
        }
    }

    private static final class TestResource {
    }

    private static final class StubOpenApiDocumentService implements OpenApiDocumentService {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String resolveGroupFromPath(String path) {
            return "human-resources";
        }

        @Override
        public JsonNode getDocumentForGroup(String groupName) {
            return objectMapper.valueToTree(Map.of(
                    "paths", Map.of(
                            "/api/human-resources/folhas-pagamento/{folhaId}/actions/mark-paid", Map.of(
                                    "post", Map.of(
                                            "responses", Map.of(
                                                    "200", Map.of(
                                                            "content", Map.of(
                                                                    "application/json", Map.of(
                                                                            "schema", Map.of(
                                                                                    "$ref", "#/components/schemas/RestApiResponseWorkflowResponse"
                                                                            )
                                                                    )
                                                            )
                                                    )
                                            )
                                    )
                            ),
                            "/api/human-resources/folhas-pagamento/stats/group-by", Map.of(
                                    "post", Map.of(
                                            "operationId", "folhasPagamentoGroupBy",
                                            "responses", Map.of("200", Map.of())
                                    )
                            ),
                            "/api/human-resources/folhas-pagamento/stats/timeseries", Map.of(
                                    "post", Map.of(
                                            "operationId", "folhasPagamentoTimeseries",
                                            "responses", Map.of("200", Map.of())
                                    )
                            )
                    ),
                    "components", Map.of(
                            "schemas", Map.of(
                                    "WorkflowRequest", Map.of(
                                            "type", "object",
                                            "required", List.of("id"),
                                            "properties", Map.of(
                                                    "id", Map.of("type", "integer", "format", "int32"),
                                                    "observacao", Map.of("type", "string", "maxLength", 255)
                                            )
                                    ),
                                    "RestApiResponseWorkflowResponse", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "status", Map.of("type", "string"),
                                                    "data", Map.of("$ref", "#/components/schemas/WorkflowResponse")
                                            )
                                    ),
                                    "WorkflowResponse", Map.of(
                                            "type", "object",
                                            "description", "Folha de pagamento calculada para acompanhamento financeiro.",
                                            "properties", Map.of(
                                                    "id", Map.of("type", "integer", "format", "int32"),
                                                    "valorLiquido", Map.of(
                                                            "type", "number",
                                                            "format", "double",
                                                            "description", "Valor liquido da folha",
                                                            "x-domain-governance", Map.of(
                                                                    "annotationType", "privacy",
                                                                    "classification", "confidential",
                                                                    "dataCategory", "financial",
                                                                    "complianceTags", List.of("LGPD", "INTERNAL_POLICY"),
                                                                    "exportable", false,
                                                                    "aiUsage", Map.of(
                                                                            "visibility", "mask",
                                                                            "trainingUse", "deny",
                                                                            "ruleAuthoring", "review_required",
                                                                            "reasoningUse", "allow"
                                                                    ),
                                                                    "reason", "Campo financeiro explicitamente marcado.",
                                                                    "source", "java.annotation",
                                                                    "confidence", 1.0d
                                                            )
                                                    ),
                                                    "riscoOperacional", Map.of(
                                                            "type", "string",
                                                            "description", "Risco operacional associado ao processamento"
                                                    ),
                                                    "jurisdicao", Map.of(
                                                            "type", "string",
                                                            "description", "Jurisdicao regulatoria aplicavel"
                                                    )
                                            )
                                    ),
                                    "SchedulePayment", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "dataPagamento", Map.of("type", "string", "format", "date")
                                            )
                                    )
                            )
                    )
            ));
        }

        @Override
        public JsonNode getDocumentForGroupStrict(String groupName) {
            return getDocumentForGroup(groupName);
        }

        @Override
        public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
            return "hash:" + schemaId;
        }

        @Override
        public void clearCaches() {
        }
    }
}
