package org.praxisplatform.uischema.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.CalculationMemory;
import org.praxisplatform.uischema.annotation.CalculationTrail;
import org.praxisplatform.uischema.annotation.DeterminationInput;
import org.praxisplatform.uischema.annotation.DeterminationOutput;
import org.praxisplatform.uischema.annotation.ReactiveDetermination;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.controller.docs.ApiDocsController;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.determination.AnnotationDrivenReactiveDeterminationDefinitionProvider;
import org.praxisplatform.uischema.determination.DefaultReactiveDeterminationDefinitionRegistry;
import org.praxisplatform.uischema.determination.ReactiveDeterminationDefinition;
import org.praxisplatform.uischema.determination.ReactiveDeterminationFormMode;
import org.praxisplatform.uischema.determination.ReactiveDeterminationTriggerMode;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class CalculationMemoryAndReactiveDeterminationPlatformTest {

    @CalculationMemory(
            title = "Memória de Cálculo Explicável — Abate-Teto",
            subtitle = "Decomposição determinística constitucional",
            legalBasis = "CF/88, art. 37, XI",
            trails = {
                    @CalculationTrail(
                            id = "abate-teto",
                            title = "Trilha do Abate-Teto Constitucional",
                            ruleCode = "TETO-CF88-37-XI",
                            formula = "max(0, Base Sujeita ao Teto - Limite Constitucional)",
                            visibleWhen = "coalesce(row.baseTetoConstitucional, 0) > 0",
                            baseLabel = "Base Sujeita ao Teto",
                            baseExpr = "coalesce(row.baseTetoConstitucional, 0)",
                            baseFormat = "currency:BRL",
                            capLabel = "Limite Constitucional",
                            capExpr = "coalesce(row.valorTetoAplicado, 46366.19)",
                            capFormat = "currency:BRL",
                            resultLabel = "Glosa Retida",
                            resultExpr = "max(0, coalesce(row.valorAbateTeto, 0))",
                            resultFormat = "currency:BRL",
                            legalBasis = "CF/88, art. 37, XI e § 12",
                            legalPurpose = "Auditar o corte remuneratório constitucional."
                    )
            }
    )
    static class ValidPayslipResponseDTO {
        public BigDecimal baseTetoConstitucional;
        public BigDecimal valorTetoAplicado;
        public BigDecimal valorAbateTeto;
    }

    @CalculationMemory(
            title = "Memória Inválida",
            trails = {
                    @CalculationTrail(
                            id = "broken-trail",
                            title = "Trilha com Campo Fantasma",
                            baseExpr = "coalesce(row.campoInexistenteFantasma, 0)",
                            resultExpr = "row.valorAbateTeto"
                    )
            }
    )
    static class BrokenPayslipResponseDTO {
        public BigDecimal valorAbateTeto;
    }

    @RestController
    static class AnnotatedPayslipController {

        @PostMapping("/api/test/payslips/determinations/preview")
        @Operation(operationId = "determinePayslipPreview")
        @ReactiveDetermination(
                id = "test.payslip.preview",
                createOperationId = "createPayslip",
                updateOperationId = "updatePayslip",
                triggerMode = ReactiveDeterminationTriggerMode.ON_INIT_AND_CHANGE,
                provenanceSource = "test-payroll-determinations",
                inputs = {
                        @DeterminationInput(field = "valorBaseCalculo", request = "vencimentoBasico"),
                        @DeterminationInput("totalDescontos")
                },
                outputs = {
                        @DeterminationOutput(response = "valorLiquidoPrevisto", field = "valorLiquidoPrevisto"),
                        @DeterminationOutput("valorAbateTetoPrevisto")
                }
        )
        public Map<String, Object> preview(Map<String, Object> draft) {
            return draft;
        }
    }

    @RestController
    static class MissingOperationIdController {

        @PostMapping("/api/test/broken")
        @ReactiveDetermination(
                id = "test.broken",
                createOperationId = "createBroken",
                inputs = {@DeterminationInput("campo")},
                outputs = {@DeterminationOutput("saida")}
        )
        public Map<String, Object> preview(Map<String, Object> draft) {
            return draft;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesTypedCalculationMemoryInOpenApiAndValidatesDtoPropertiesStatically() {
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomOpenApiResolver(new ObjectMapper().findAndRegisterModules()));
        ResolvedSchema resolved = converters.resolveAsResolvedSchema(
                new AnnotatedType(ValidPayslipResponseDTO.class).resolveAsRef(false)
        );
        assertNotNull(resolved);
        assertNotNull(resolved.schema);
        Map<String, Object> xUi = (Map<String, Object>) resolved.schema.getExtensions().get("x-ui");
        assertNotNull(xUi);
        Map<String, Object> calcMemory = (Map<String, Object>) xUi.get("calculationMemory");
        assertNotNull(calcMemory);
        assertEquals("calculationMemory", calcMemory.get("type"));
        assertEquals("Memória de Cálculo Explicável — Abate-Teto", calcMemory.get("title"));
        List<Map<String, Object>> trails = (List<Map<String, Object>>) calcMemory.get("trails");
        assertEquals(1, trails.size());
        assertEquals("abate-teto", trails.get(0).get("id"));
        assertFalse(trails.get(0).containsKey("additionFormat"), "Unused steps must not emit default format keys");

        PraxisExpressionStaticValidator validator = new PraxisExpressionStaticValidator();
        List<String> validErrors = validator.validateDtoClass(ValidPayslipResponseDTO.class, true);
        assertTrue(validErrors.isEmpty(), () -> "Expected zero errors, got: " + validErrors);

        InvalidDomainExpressionException ex = assertThrows(
                InvalidDomainExpressionException.class,
                () -> validator.validateDtoClass(BrokenPayslipResponseDTO.class, true)
        );
        assertTrue(ex.getMessage().contains("campoInexistenteFantasma"));
    }

    @Test
    void discoversAnnotatedReactiveDeterminationsAndFailsClosedWithoutOperationId() {
        var provider = AnnotationDrivenReactiveDeterminationDefinitionProvider.fromControllerClasses(
                AnnotatedPayslipController.class
        );
        var registry = new DefaultReactiveDeterminationDefinitionRegistry(List.of(provider));
        assertEquals(2, registry.schemaOperationIds().size());

        List<ReactiveDeterminationDefinition> createDefs = registry.findBySchemaOperationId("createPayslip");
        assertEquals(1, createDefs.size());
        ReactiveDeterminationDefinition def = createDefs.get(0);
        assertEquals("test.payslip.preview", def.id());
        assertEquals("determinePayslipPreview", def.operationId());
        assertEquals(List.of("/valorBaseCalculo", "/totalDescontos"), def.sourcePaths());
        assertEquals(2, def.inputs().size());
        assertEquals("/valorBaseCalculo", def.inputs().get(0).fieldPath());
        assertEquals("/vencimentoBasico", def.inputs().get(0).requestPath());
        assertEquals("/totalDescontos", def.inputs().get(1).fieldPath());
        assertEquals("/totalDescontos", def.inputs().get(1).requestPath());
        assertEquals(2, def.outputs().size());
        assertEquals("/valorLiquidoPrevisto", def.outputs().get(0).responsePath());
        assertEquals("/valorLiquidoPrevisto", def.outputs().get(0).fieldPath());
        assertEquals(ReactiveDeterminationFormMode.CREATE, def.scopes().get(0).formMode());
        assertEquals(ReactiveDeterminationFormMode.EDIT, def.scopes().get(1).formMode());

        var brokenProvider = AnnotationDrivenReactiveDeterminationDefinitionProvider.fromControllerClasses(
                MissingOperationIdController.class
        );
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class, brokenProvider::definitions);
        assertTrue(err.getMessage().contains("@Operation"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filteredSchemasEndpointPreservesCalculationMemoryOnResponseAndStripsItOnRequest() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OpenApiDocsSupport docsSupport = new OpenApiDocsSupport();
        Map<String, JsonNode> docs = new ConcurrentHashMap<>();
        SchemaCanonicalizer canonicalizer = new SchemaCanonicalizer();
        OpenApiDocumentService docService = new OpenApiDocumentService() {
            @Override
            public String resolveGroupFromPath(String path) {
                return docsSupport.resolveGroupFromPath(path);
            }

            @Override
            public JsonNode getDocumentForGroup(String groupName) {
                return docs.get(groupName);
            }

            @Override
            public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
                return SchemaHashUtil.sha256Hex(canonicalizer.canonicalize(payloadSupplier.get()));
            }

            @Override
            public void clearCaches() {
                docs.clear();
            }
        };

        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomOpenApiResolver(mapper));
        ResolvedSchema resolvedResponse = converters.resolveAsResolvedSchema(
                new AnnotatedType(ValidPayslipResponseDTO.class).resolveAsRef(false)
        );

        String filterPath = "/api/ergon/fichas-financeiras/filter";
        String group = docsSupport.resolveGroupFromPath(filterPath);

        ObjectNode serializedDtoSchema = (ObjectNode) Json.mapper().valueToTree(resolvedResponse.schema);
        ObjectNode root = buildPayslipOpenApiDoc(mapper, filterPath, serializedDtoSchema);
        docs.put(group, root);

        ApiDocsController controller = new ApiDocsController();
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "openApiDocsSupport", docsSupport);
        ReflectionTestUtils.setField(controller, "openApiDocumentService", docService);
        ReflectionTestUtils.setField(controller, "canonicalOperationResolver", new OpenApiCanonicalOperationResolver(docService, null));
        ReflectionTestUtils.setField(controller, "schemaReferenceResolver", new FilteredSchemaReferenceResolver());
        ReflectionTestUtils.setField(controller, "canonicalCapabilityResolver", new OpenApiCanonicalCapabilityResolver(docService));

        ResponseEntity<Map<String, Object>> responseSchema = controller.getFilteredSchema(
                filterPath, "post", true, "response", null, null, Locale.ROOT
        );
        assertEquals(200, responseSchema.getStatusCode().value());
        Map<String, Object> responseXUi = (Map<String, Object>) responseSchema.getBody().get("x-ui");
        assertNotNull(responseXUi);
        Map<String, Object> calcMemory = (Map<String, Object>) responseXUi.get("calculationMemory");
        assertNotNull(calcMemory, "/schemas/filtered?schemaType=response must preserve DTO x-ui.calculationMemory");
        assertEquals("Memória de Cálculo Explicável — Abate-Teto", calcMemory.get("title"));
        String hashWithCalcMemory = responseSchema.getHeaders().getFirst("X-Schema-Hash");
        assertNotNull(hashWithCalcMemory);

        ResponseEntity<Map<String, Object>> requestSchema = controller.getFilteredSchema(
                filterPath, "post", true, "request", null, null, Locale.ROOT
        );
        assertEquals(200, requestSchema.getStatusCode().value());
        Map<String, Object> requestXUi = (Map<String, Object>) requestSchema.getBody().get("x-ui");
        assertNotNull(requestXUi);
        assertFalse(requestXUi.containsKey("calculationMemory"), "Request/filter schemas must never leak response calculationMemory");

        // Verify structural ETag/X-Schema-Hash changes when calculationMemory changes
        ObjectNode modifiedSchema = serializedDtoSchema.deepCopy();
        ((ObjectNode) modifiedSchema.path("x-ui").path("calculationMemory")).put("title", "Memória Alterada");
        docs.put(group, buildPayslipOpenApiDoc(mapper, filterPath, modifiedSchema));

        ResponseEntity<Map<String, Object>> modifiedResponse = controller.getFilteredSchema(
                filterPath, "post", true, "response", null, null, Locale.ROOT
        );
        String hashAfterChange = modifiedResponse.getHeaders().getFirst("X-Schema-Hash");
        assertNotEquals(hashWithCalcMemory, hashAfterChange, "X-Schema-Hash/ETag must track calculationMemory mutations");
    }

    private static ObjectNode buildPayslipOpenApiDoc(ObjectMapper mapper, String filterPath, JsonNode responseDtoNode) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode op = root.putObject("paths").putObject(filterPath).putObject("post");
        ObjectNode opXUi = op.putObject("x-ui");
        opXUi.put("responseSchema", "ValidPayslipResponseDTO");
        opXUi.put("requestSchema", "PayslipFilterDTO");
        op.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/PayslipFilterDTO");
        op.putObject("responses").putObject("200").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/ValidPayslipResponseDTO");

        ObjectNode schemas = root.putObject("components").putObject("schemas");
        schemas.set("ValidPayslipResponseDTO", responseDtoNode);
        ObjectNode filterDto = schemas.putObject("PayslipFilterDTO");
        filterDto.put("type", "object");
        filterDto.putObject("properties").putObject("competenciaFolha").put("type", "string");
        filterDto.putObject("x-ui").putObject("calculationMemory").put("title", "Accidental Leak");
        return root;
    }
}
