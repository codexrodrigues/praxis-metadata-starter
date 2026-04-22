package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import org.praxisplatform.uischema.surface.SurfaceDefinition;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.praxisplatform.uischema.surface.SurfaceKind;
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
                        "human-resources.folhas-pagamento.estado.programada",
                        "human-resources.folhas-pagamento.policy.supplier.selection"
                );
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
                .contains("has_action", "has_surface", "has_field", "allowed_in_state", "selectable_when", "blocked_when");
        assertThat(response.bindings()).extracting(DomainCatalogResponse.DomainBindingItem::bindingType)
                .contains("workflow_action", "ui_surface", "dto_field", "option_source");
        assertThat(response.evidence()).extracting(DomainCatalogResponse.DomainEvidenceItem::evidenceType)
                .contains("annotation", "dto_schema", "option_source");
        assertThat(response.governance())
                .filteredOn(item -> "human-resources.folhas-pagamento.field.valor-liquido".equals(item.nodeKey()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.annotationType()).isEqualTo("privacy");
                    assertThat(item.classification()).isEqualTo("confidential");
                    assertThat(item.dataCategory()).isEqualTo("financial");
                    assertThat(item.complianceTags()).containsExactly("LGPD", "INTERNAL_POLICY");
                    assertThat(item.aiUsage())
                            .containsEntry("visibility", "mask")
                            .containsEntry("trainingUse", "deny")
                            .containsEntry("ruleAuthoring", "review_required")
                            .containsEntry("reasoningUse", "allow");
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
                    List.of("workflow", "payroll")
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
                            "/api/human-resources/folhas-pagamento/{id}/actions/mark-paid", Map.of(
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
                                            "properties", Map.of(
                                                    "id", Map.of("type", "integer", "format", "int32"),
                                                    "valorLiquido", Map.of(
                                                            "type", "number",
                                                            "format", "double",
                                                            "description", "Valor liquido da folha"
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
