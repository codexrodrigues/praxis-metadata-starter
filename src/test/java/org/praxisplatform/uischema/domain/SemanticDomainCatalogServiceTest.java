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
                return List.of(vinculosCreate, vinculosDetail, radarDetail).stream()
                        .filter(surface -> surface.resourceKey().equals(resourceKey))
                        .toList();
            }

            @Override
            public List<SurfaceDefinition> findByGroup(String group) {
                return List.of(vinculosCreate, vinculosDetail, radarDetail);
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
                .containsEntry("conceptFactRootMap", Map.of(
                        "cargo", "ergon.cargos",
                        "setor", "ergon.setores"
                ))
                .containsEntry("ontologicalFactRoots", List.of(
                        "draft",
                        "current",
                        "cargo",
                        "setor",
                        "neighborhood",
                        "context"
                ));

        DomainCatalogResponse.DomainNodeItem cargoField = catalog.nodes().stream()
                .filter(node -> "ergon.vinculos.field.cargo-id".equals(node.nodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(cargoField.metadata())
                .containsEntry("resourceKey", "ergon.vinculos")
                .containsEntry("referencedResourceKey", "ergon.cargos")
                .containsKey("optionSource");
        assertThat(catalog.bindings())
                .filteredOn(binding -> "binding:ergon.vinculos.field.cargo-id:dto-field".equals(binding.bindingKey()))
                .singleElement()
                .satisfies(binding -> assertThat(binding.target())
                        .containsEntry("resourceKey", "ergon.vinculos")
                        .containsEntry("referencedResourceKey", "ergon.cargos"));

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
                .containsEntry("parentConcepts", List.of("ergon.vinculos"))
                .containsEntry("analyzedByDataProducts", List.of("ergon.radar-ferias-prescricao"));
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
                )),
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
                ))
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
                .filter(node -> "human-resources.folhas-pagamento.policy.ruleset.human-resources.folhas-pagamento.validate".equals(node.nodeKey()))
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
                                "human-resources.folhas-pagamento.governed-by.human-resources.folhas-pagamento.validate",
                                "human-resources.folhas-pagamento",
                                "human-resources.folhas-pagamento.policy.ruleset.human-resources.folhas-pagamento.validate",
                                "governed_by"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "human-resources.folhas-pagamento.field.valor-liquido.governed-by.human-resources.folhas-pagamento.validate",
                                "human-resources.folhas-pagamento.field.valor-liquido",
                                "human-resources.folhas-pagamento.policy.ruleset.human-resources.folhas-pagamento.validate",
                                "governed_by"
                        )
                );

        assertThat(response.bindings())
                .filteredOn(b -> "binding:human-resources.folhas-pagamento.policy.ruleset.human-resources.folhas-pagamento.validate:rule-set".equals(b.bindingKey()))
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
                )),
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
                .filter(node -> "human-resources.folhas-pagamento.policy.ruleset.human-resources.folhas-pagamento.validate".equals(node.nodeKey()))
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
        public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
            return "hash:" + schemaId;
        }

        @Override
        public void clearCaches() {
        }
    }
}
