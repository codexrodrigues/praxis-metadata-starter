package org.praxisplatform.uischema.validation;

import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.CalculationMemory;
import org.praxisplatform.uischema.annotation.CalculationTrail;
import org.praxisplatform.uischema.annotation.MicroVisualization;
import org.praxisplatform.uischema.annotation.MicroVisualizationKind;
import org.praxisplatform.uischema.annotation.Threshold;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.id.CompositeResourceId;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.springframework.mock.env.MockEnvironment;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PraxisExpressionStaticValidator & OpenApiCustomizer Tests (ISSUE-049)")
class PraxisExpressionStaticValidatorTest {

    private PraxisExpressionStaticValidator validator;
    private Set<String> validProperties;

    @BeforeEach
    void setUp() {
        validator = new PraxisExpressionStaticValidator();
        validProperties = Set.of("taxaAtingimento", "status", "valorTotal", "nome", "score");
    }

    @Test
    @DisplayName("Accepts valid ternary formula starting with '=' and valid property")
    void testValidTernaryWithEquals() {
        String expr = "= (taxaAtingimento >= 80) ? 'success' : 'danger'";
        PraxisExpressionStaticValidator.ValidationResult result = validator.validateExpression(expr, validProperties, "testField");
        assertTrue(result.valid(), "Expression should be valid: " + result.errors());
    }

    @Test
    @DisplayName("Accepts valid formula with 'row.' and 'item.' prefixes")
    void testValidWithRowAndItemPrefixes() {
        String expr1 = "= row.taxaAtingimento >= 80 ? 'success' : 'danger'";
        assertTrue(validator.validateExpression(expr1, validProperties, "field1").valid());

        String expr2 = "= item.valorTotal > 1000 ? 'warning' : 'neutral'";
        assertTrue(validator.validateExpression(expr2, validProperties, "field2").valid());

        String expr3 = "${row.score} > 50 ? 'info' : 'neutral'";
        assertTrue(validator.validateExpression(expr3, validProperties, "field3").valid());

        String expr4 = "= row.score >= 80 ? 'positive' : (row.score >= 50 ? 'secondary' : (row.score >= 20 ? 'negative' : 'critical'))";
        assertTrue(validator.validateExpression(expr4, validProperties, "field4").valid());
    }

    @Test
    @DisplayName("Accepts valid formula without '=' prefix (Issue #45 tolerance)")
    void testValidFormulaWithoutEquals() {
        String expr = "taxaAtingimento >= 80 ? 'success' : 'danger'";
        PraxisExpressionStaticValidator.ValidationResult result = validator.validateExpression(expr, validProperties, "testField");
        assertTrue(result.valid());
    }

    @Test
    @DisplayName("Rejects formula with syntax error: unmatched parenthesis")
    void testSyntaxErrorUnmatchedParenthesis() {
        String expr = "= (taxaAtingimento >= 80 ? 'success' : 'danger'";
        PraxisExpressionStaticValidator.ValidationResult result = validator.validateExpression(expr, validProperties, "testField");
        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("Unclosed delimiter") || result.errors().get(0).contains("delimiter"));
    }

    @Test
    @DisplayName("Rejects formula with syntax error: unbalanced ternary operator")
    void testSyntaxErrorUnbalancedTernary() {
        String expr = "= taxaAtingimento >= 80 ? 'success'";
        PraxisExpressionStaticValidator.ValidationResult result = validator.validateExpression(expr, validProperties, "testField");
        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("Unbalanced ternary operator"));
    }

    @Test
    @DisplayName("Rejects formula referencing non-existent domain property")
    void testUnknownPropertyReference() {
        String expr = "= (taxaInexistente >= 80) ? 'success' : 'danger'";
        PraxisExpressionStaticValidator.ValidationResult result = validator.validateExpression(expr, validProperties, "HeroDTO.taxa");
        assertFalse(result.valid());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("taxaInexistente"));
        assertTrue(result.errors().get(0).contains("HeroDTO.taxa"));
    }

    @Test
    @DisplayName("Validates JsonLogic conditions and variable references against schema")
    void testJsonLogicValidation() {
        Map<String, Object> validLogic = Map.of(
                "==", List.of(Map.of("var", "status"), "ACTIVE")
        );
        PraxisExpressionStaticValidator.ValidationResult validRes = validator.validateJsonLogic(validLogic, validProperties, "rules.when");
        assertTrue(validRes.valid(), "JsonLogic should be valid");

        Map<String, Object> invalidVarLogic = Map.of(
                "==", List.of(Map.of("var", "campoInexistente"), "ACTIVE")
        );
        PraxisExpressionStaticValidator.ValidationResult invalidRes = validator.validateJsonLogic(invalidVarLogic, validProperties, "rules.when");
        assertFalse(invalidRes.valid());
        assertTrue(invalidRes.errors().get(0).contains("campoInexistente"));
    }

    @Test
    @DisplayName("Validates full schema with MicroVisualization toneExpr in strict mode")
    void testSchemaValidationStrictModeThrows() {
        Schema<?> schema = new Schema<>();
        Schema<String> taxaProp = new StringSchema();
        taxaProp.setName("taxa");

        Map<String, Object> uiExtension = new LinkedHashMap<>();
        Map<String, Object> presentation = new LinkedHashMap<>();
        Map<String, Object> visualization = new LinkedHashMap<>();
        visualization.put("toneExpr", "= taxaQuebrada >= 80 ? 'success' : 'danger'");
        presentation.put("visualization", visualization);
        uiExtension.put("presentation", presentation);
        taxaProp.addExtension("x-ui", uiExtension);

        schema.addProperty("taxa", taxaProp);
        schema.addProperty("nome", new StringSchema());

        // In strict mode, should throw InvalidDomainExpressionException
        InvalidDomainExpressionException ex = assertThrows(InvalidDomainExpressionException.class, () ->
                validator.validateSchemaExpressions("HeroDTO", schema, true)
        );
        assertTrue(ex.getMessage().contains("HeroDTO"));
        assertTrue(ex.getMessage().contains("taxaQuebrada"));

        // In non-strict mode, should not throw and return error list
        List<String> errors = validator.validateSchemaExpressions("HeroDTO", schema, false);
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("taxaQuebrada"));
    }

    @Test
    @DisplayName("PraxisExpressionValidationOpenApiCustomizer integrates with OpenAPI and enforces strict profile governance")
    void testOpenApiCustomizerIntegration() {
        OpenAPI openApi = new OpenAPI();
        Components components = new Components();

        Schema<?> heroSchema = new Schema<>();
        Schema<String> scoreProp = new StringSchema();
        scoreProp.setName("score");

        Map<String, Object> uiExtension = new LinkedHashMap<>();
        Map<String, Object> presentation = new LinkedHashMap<>();
        Map<String, Object> visualization = new LinkedHashMap<>();
        visualization.put("toneExpr", "= (scoreInexistente > 90) ? 'success' : 'info'");
        presentation.put("visualization", visualization);
        uiExtension.put("presentation", presentation);
        scoreProp.addExtension("x-ui", uiExtension);

        heroSchema.addProperty("score", scoreProp);
        heroSchema.addProperty("status", new StringSchema());
        components.addSchemas("HeroDTO", heroSchema);
        openApi.setComponents(components);

        // 1. In 'test' profile (strict by default): throws
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("test");
        PraxisExpressionValidationOpenApiCustomizer customizerDev =
                new PraxisExpressionValidationOpenApiCustomizer(new ExpressionValidationProperties(true, false), devEnv, validator);

        assertThrows(InvalidDomainExpressionException.class, () -> customizerDev.customise(openApi));

        // 2. In 'prod' profile without strict: does NOT throw
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        PraxisExpressionValidationOpenApiCustomizer customizerProd =
                new PraxisExpressionValidationOpenApiCustomizer(new ExpressionValidationProperties(true, false), prodEnv, validator);

        assertDoesNotThrow(() -> customizerProd.customise(openApi));

        // 3. When disabled (enabled=false): does NOT throw
        PraxisExpressionValidationOpenApiCustomizer customizerDisabled =
                new PraxisExpressionValidationOpenApiCustomizer(new ExpressionValidationProperties(false, true), devEnv, validator);

        assertDoesNotThrow(() -> customizerDisabled.customise(openApi));
    }

    @Test
    @DisplayName("Accepts expressions with global JS utility functions (parseInt, parseFloat, isNaN, isFinite, encodeURIComponent)")
    void testGlobalJsUtilityFunctionsAllowedInExpressions() {
        String expr1 = "= parseInt(row.score) > 10 ? 'success' : 'neutral'";
        assertTrue(validator.validateExpression(expr1, validProperties, "field1").valid());

        String expr2 = "= !isNaN(row.valorTotal) ? 'info' : 'danger'";
        assertTrue(validator.validateExpression(expr2, validProperties, "field2").valid());

        String expr3 = "parseFloat(item.taxaAtingimento) >= 80.5 ? 'success' : 'danger'";
        assertTrue(validator.validateExpression(expr3, validProperties, "field3").valid());

        String expr4 = "= isFinite(form.valorTotal) ? 'success' : 'warning'";
        assertTrue(validator.validateExpression(expr4, validProperties, "field4").valid());

        String expr5 = "${encodeURIComponent(data.nome)} != null ? 'info' : 'warning'";
        assertTrue(validator.validateExpression(expr5, validProperties, "field5").valid());
    }

    @Test
    @DisplayName("Normalizes 'data.' and 'form.' prefixes in expressions and JsonLogic")
    void testPrefixesDataAndFormNormalized() {
        String expr1 = "${data.status} === 'ACTIVE' ? 'success' : 'neutral'";
        assertTrue(validator.validateExpression(expr1, validProperties, "field1").valid());

        String expr2 = "= form.valorTotal > 500 ? 'warning' : 'neutral'";
        assertTrue(validator.validateExpression(expr2, validProperties, "field2").valid());

        String expr3 = "= data.taxaAtingimento >= 80 ? 'success' : 'danger'";
        assertTrue(validator.validateExpression(expr3, validProperties, "field3").valid());

        Map<String, Object> dataVarLogic = Map.of(
                "==", List.of(Map.of("var", "data.status"), "ACTIVE")
        );
        assertTrue(validator.validateJsonLogic(dataVarLogic, validProperties, "rules.when").valid());

        Map<String, Object> formVarLogic = Map.of(
                "==", List.of(Map.of("var", "form.status"), "ACTIVE")
        );
        assertTrue(validator.validateJsonLogic(formVarLogic, validProperties, "rules.when").valid());
    }

    @Test
    @DisplayName("Validates schema rules: visible, disabled, required, and computed")
    void testSchemaValidationRulesCoverage() {
        Schema<?> schema = new Schema<>();
        Schema<String> statusProp = new StringSchema();
        statusProp.setName("status");

        Map<String, Object> uiExtension = new LinkedHashMap<>();
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("visible", "= row.status == 'ACTIVE'");
        rules.put("required", true); // boolean should be ignored
        rules.put("disabled", Map.of("==", List.of(Map.of("var", "score"), 100)));
        rules.put("computed", "= row.valorTotal * 2");
        uiExtension.put("rules", rules);
        statusProp.addExtension("x-ui", uiExtension);

        schema.addProperty("status", statusProp);
        schema.addProperty("score", new StringSchema());
        schema.addProperty("valorTotal", new StringSchema());

        List<String> validErrors = validator.validateSchemaExpressions("HeroDTO", schema, false);
        assertTrue(validErrors.isEmpty(), "Schema rules should be valid: " + validErrors);

        // Invalid rules.visible
        rules.put("visible", "= row.campoInexistente == 'ACTIVE'");
        List<String> invalidErrors = validator.validateSchemaExpressions("HeroDTO", schema, false);
        assertFalse(invalidErrors.isEmpty());
        assertTrue(invalidErrors.get(0).contains("campoInexistente"));
        assertTrue(invalidErrors.get(0).contains("rules.visible"));

        // Strict mode throws
        assertThrows(InvalidDomainExpressionException.class, () ->
                validator.validateSchemaExpressions("HeroDTO", schema, true)
        );
    }

    @Test
    @DisplayName("Validates all 16 MicroVisualization *Expr keys in x-ui.presentation.visualization")
    void testAllSixteenMicroVisualizationExpressionKeysValidated() {
        List<String> allExprKeys = List.of(
                "toneExpr", "valueExpr", "targetExpr", "targetLabelExpr", "totalExpr", "baselineExpr",
                "minExpr", "maxExpr", "pointsExpr", "segmentsExpr", "itemsExpr",
                "thresholdsExpr", "titleExpr", "ariaLabelExpr", "fallbackTextExpr", "valueSuffixExpr"
        );

        for (String exprKey : allExprKeys) {
            Schema<?> schema = new Schema<>();
            Schema<String> metricProp = new StringSchema();
            metricProp.setName("ocupacao");

            Map<String, Object> uiExtension = new LinkedHashMap<>();
            Map<String, Object> presentation = new LinkedHashMap<>();
            Map<String, Object> visualization = new LinkedHashMap<>();
            visualization.put(exprKey, "= row.propriedadeInexistente + 1");
            presentation.put("visualization", visualization);
            uiExtension.put("presentation", presentation);
            metricProp.addExtension("x-ui", uiExtension);

            schema.addProperty("ocupacao", metricProp);
            schema.addProperty("vagasAutorizadas", new StringSchema());

            List<String> errors = validator.validateSchemaExpressions("CargoDTO", schema, false);
            assertEquals(1, errors.size(), "Expected validation error for key " + exprKey);
            assertTrue(errors.get(0).contains("CargoDTO.ocupacao." + exprKey),
                    "Error message should reference key " + exprKey + " but was: " + errors.get(0));
            assertTrue(errors.get(0).contains("propriedadeInexistente"));
        }
    }

    @Test
    @DisplayName("validateDtoClass and validateDtoClasses resolve @MicroVisualization via CustomOpenApiResolver")
    void testValidateDtoClassResolvesMicroVisualizationAnnotations() {
        assertTrue(validator.validateDtoClass(ValidMicroVizDTO.class, false).isEmpty());
        assertTrue(validator.validateDtoClasses(List.of(ValidMicroVizDTO.class), true).isEmpty());

        List<String> errors = validator.validateDtoClass(InvalidMicroVizDTO.class, false);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("tituloInexistente")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("metaInexistente")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("rotuloInexistente")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("fallbackInexistente")));

        assertThrows(InvalidDomainExpressionException.class, () ->
                validator.validateDtoClass(InvalidMicroVizDTO.class, true)
        );
    }

    @Test
    @DisplayName("Validates calculation trail targetExpr and targetHintExpr and rejects unknown properties")
    void testValidateCalculationTrailTargetExpressions() {
        assertTrue(validator.validateDtoClass(ValidCalculationTargetDTO.class, true).isEmpty());

        List<String> errors = validator.validateDtoClass(InvalidCalculationTargetDTO.class, false);
        assertEquals(2, errors.size(), () -> "Expected errors for targetExpr and targetHintExpr, got: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("targetExpr") && e.contains("metaFantasma")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("targetHintExpr") && e.contains("hintFantasma")));

        assertThrows(InvalidDomainExpressionException.class, () ->
                validator.validateDtoClass(InvalidCalculationTargetDTO.class, true)
        );
    }

    @Test
    @DisplayName("Rejects calculation trails where additionExpr or deductionExpr duplicates baseExpr")
    void testRejectsDuplicateBaseAndAdjustmentInCalculationTrail() {
        List<String> errors = validator.validateDtoClass(DuplicateCalculationParcelDTO.class, false);
        assertEquals(2, errors.size(), () -> "Expected duplicate base parcel errors, got: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("additionExpr") && e.contains("duplicates baseExpr")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("deductionExpr") && e.contains("duplicates baseExpr")));
        assertThrows(InvalidDomainExpressionException.class, () ->
                validator.validateDtoClass(DuplicateCalculationParcelDTO.class, true)
        );
    }

    @CalculationMemory(
            title = "Duplicacao de Parcela",
            trails = {
                    @CalculationTrail(
                            id = "adicao-duplicada",
                            title = "Adicao Duplicada",
                            baseLabel = "Base",
                            baseExpr = "= coalesce(row.carenciaApurada, 0)",
                            additionLabel = "Acrescimo Fantasma",
                            additionExpr = "= coalesce(row.carenciaApurada, 0)",
                            resultLabel = "Total",
                            resultExpr = "= coalesce(row.carenciaApurada, 0)"
                    ),
                    @CalculationTrail(
                            id = "deducao-duplicada",
                            title = "Deducao Duplicada",
                            baseLabel = "Base",
                            baseExpr = "= coalesce(row.carenciaApurada, 0)",
                            deductionLabel = "Desconto Fantasma",
                            deductionExpr = "=coalesce(row.carenciaApurada,0)",
                            resultLabel = "Total",
                            resultExpr = "= coalesce(row.carenciaApurada, 0)"
                    )
            }
    )
    public static class DuplicateCalculationParcelDTO {
        public Integer carenciaApurada;
    }

    @CalculationMemory(
            title = "Meta Mínima",
            trails = {
                    @CalculationTrail(
                            id = "tempo-minimo",
                            title = "Tempo Mínimo",
                            targetLabel = "Piso Mínimo",
                            targetExpr = "= coalesce(row.carenciaMinima, 1095)",
                            targetFormat = "integer",
                            targetHintExpr = "'Regra ' + coalesce(row.nomeRegra, 'Padrao')",
                            resultLabel = "Apurado",
                            resultExpr = "= coalesce(row.carenciaApurada, 0)"
                    )
            }
    )
    public static class ValidCalculationTargetDTO {
        public Integer carenciaMinima;
        public String nomeRegra;
        public Integer carenciaApurada;
    }

    @CalculationMemory(
            title = "Meta Mínima Inválida",
            trails = {
                    @CalculationTrail(
                            id = "tempo-minimo-invalido",
                            title = "Tempo Mínimo",
                            targetLabel = "Piso Mínimo",
                            targetExpr = "= row.metaFantasma",
                            targetHintExpr = "= row.hintFantasma",
                            resultLabel = "Apurado",
                            resultExpr = "= coalesce(row.carenciaApurada, 0)"
                    )
            }
    )
    public static class InvalidCalculationTargetDTO {
        public Integer carenciaApurada;
    }

    public static class ValidMicroVizDTO {
        public Integer vagasOcupadas;
        public Integer vagasAutorizadas;
        public String cargoNome;
        public String rotuloMeta;

        @UISchema(label = "Ocupação")
        @MicroVisualization(
                kind = MicroVisualizationKind.BULLET,
                titleExpr = "= row.cargoNome",
                valueExpr = "= row.vagasOcupadas",
                targetExpr = "= row.vagasAutorizadas",
                targetLabelExpr = "= row.rotuloMeta",
                totalExpr = "= row.vagasAutorizadas",
                fallbackTextExpr = "= row.vagasAutorizadas == null ? 'Sem quadro' : 'OK'",
                thresholds = {
                        @Threshold(min = 0, max = 100, tone = "success", label = "Normal")
                }
        )
        public Double taxaOcupacao;
    }

    public static class InvalidMicroVizDTO {
        public Integer vagasOcupadas;

        @UISchema(label = "Ocupação")
        @MicroVisualization(
                kind = MicroVisualizationKind.BULLET,
                titleExpr = "= row.tituloInexistente",
                valueExpr = "= row.vagasOcupadas",
                targetExpr = "= row.metaInexistente",
                targetLabelExpr = "= row.rotuloInexistente",
                fallbackTextExpr = "= row.fallbackInexistente"
        )
        public Double taxaOcupacao;
    }

    @Test
    @DisplayName("Validates x-ui.presentationRules JsonLogic variable references against DTO properties")
    void testPresentationRulesStaticValidation() {
        List<String> validErrors = validator.validateDtoClass(ValidPresentationRulesDTO.class, true);
        assertTrue(validErrors.isEmpty(), "Expected no errors for valid presentationRules, got: " + validErrors);

        InvalidDomainExpressionException ex = assertThrows(
                InvalidDomainExpressionException.class,
                () -> validator.validateDtoClass(InvalidPresentationRulesDTO.class, true));
        assertTrue(ex.getMessage().contains("situacaoInexistente"), ex.getMessage());
        assertTrue(ex.getMessage().contains("presentationRules[0].when"), ex.getMessage());
    }

    public static class ValidPresentationRulesDTO {
        public String situacao;

        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\"==\":[{\"var\":\"situacao\"},\"AFASTADO\"]},\"set\":{\"tone\":\"neutral\",\"suffix\":\"h · afast.\"}},{\"when\":{\"==\":[{\"var\":\"value\"},0]},\"set\":{\"tone\":\"muted\"}}]"))
        public Integer jornadaSemanal;
    }

    public static class InvalidPresentationRulesDTO {
        public String situacao;

        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\"==\":[{\"var\":\"situacaoInexistente\"},\"AFASTADO\"]},\"set\":{\"tone\":\"neutral\"}}]"))
        public Integer jornadaSemanal;
    }

    @Test
    @DisplayName("Validates @UiSurface relatedChildParentField compatibility with CompositeResourceId and FilterDTO")
    void testValidateControllerSurfacesCompatibility() {
        List<String> incompatibleErrors = validator.validateControllerSurfaces(
                List.of(IncompatibleCompositeParentController.class, ChildTargetController.class));
        assertFalse(incompatibleErrors.isEmpty());
        assertTrue(incompatibleErrors.stream().anyMatch(err ->
                err.contains("CompositeKey") && err.contains("numericParentRef")));

        List<String> validStringErrors = validator.validateControllerSurfaces(
                List.of(ValidCompositeToStringParentController.class, ChildTargetController.class));
        assertTrue(validStringErrors.isEmpty(), () -> "Expected no errors but got: " + validStringErrors);

        List<String> validSameCompositeErrors = validator.validateControllerSurfaces(
                List.of(ValidCompositeToCompositeParentController.class, ChildTargetController.class));
        assertTrue(validSameCompositeErrors.isEmpty(), () -> "Expected no errors but got: " + validSameCompositeErrors);

        List<String> validScalarSelectionErrors = validator.validateControllerSurfaces(
                List.of(ValidScalarSelectionParentController.class, ChildTargetController.class));
        assertTrue(validScalarSelectionErrors.isEmpty(), () -> "Expected no errors but got: " + validScalarSelectionErrors);

        List<String> missingChildFieldErrors = validator.validateControllerSurfaces(
                List.of(MissingChildFilterFieldParentController.class, ChildTargetController.class));
        assertFalse(missingChildFieldErrors.isEmpty());
        assertTrue(missingChildFieldErrors.stream().anyMatch(err -> err.contains("campoFantasma")));

        List<String> missingSelectionErrors = validator.validateControllerSurfaces(
                List.of(MissingSelectionKeyParentController.class, ChildTargetController.class));
        assertFalse(missingSelectionErrors.isEmpty());
        assertTrue(missingSelectionErrors.stream().anyMatch(err -> err.contains("selectionInexistente")));

        List<String> scalarToCompositeErrors = validator.validateControllerSurfaces(
                List.of(ScalarToCompositeChildParentController.class, ChildTargetController.class));
        assertFalse(scalarToCompositeErrors.isEmpty());
        assertTrue(scalarToCompositeErrors.stream().anyMatch(err -> err.contains("compositeParentRef")));

        List<String> strictMissingChild = validator.validateControllerSurfaces(
                List.of(UnknownChildParentController.class), true);
        assertFalse(strictMissingChild.isEmpty());

        List<String> nonStrictMissingChild = validator.validateControllerSurfaces(
                List.of(UnknownChildParentController.class), false);
        assertTrue(nonStrictMissingChild.isEmpty());
    }

    public record CompositeKey(Long orgId, Integer seq) implements CompositeResourceId {}

    public static class ParentSurfaceDTO {
        public CompositeKey id;
        public Long numfunc;
    }

    public static class ChildSurfaceDTO {
        public Long id;
    }

    public static class ChildSurfaceFilterDTO implements GenericFilterDTO {
        public String compositeParentWire;
        public CompositeKey compositeParentRef;
        public Long numericParentRef;
    }

    @ApiResource(value = "/api/test/child-targets", resourceKey = "test.child-targets")
    public static class ChildTargetController
            extends AbstractResourceQueryController<ChildSurfaceDTO, Long, ChildSurfaceFilterDTO> {
        @Override protected BaseResourceQueryService<ChildSurfaceDTO, Long, ChildSurfaceFilterDTO> getService() { return null; }
        @Override protected Long getResponseId(ChildSurfaceDTO dto) { return dto.id; }
    }

    @ApiResource(value = "/api/test/parent-incompatible", resourceKey = "test.parent-incompatible")
    public static class IncompatibleCompositeParentController
            extends AbstractResourceQueryController<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> {
        @Override protected BaseResourceQueryService<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> getService() { return null; }
        @Override protected CompositeKey getResponseId(ParentSurfaceDTO dto) { return dto.id; }

        @UiSurface(id = "bad-numeric", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM, title = "Bad",
                relatedChildResourceKey = "test.child-targets", relatedChildParentField = "numericParentRef")
        public void badSurface() {}
    }

    @ApiResource(value = "/api/test/parent-valid-string", resourceKey = "test.parent-valid-string")
    public static class ValidCompositeToStringParentController
            extends AbstractResourceQueryController<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> {
        @Override protected BaseResourceQueryService<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> getService() { return null; }
        @Override protected CompositeKey getResponseId(ParentSurfaceDTO dto) { return dto.id; }

        @UiSurface(id = "good-wire", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM, title = "Good Wire",
                relatedChildResourcePath = "/api/test/child-targets", relatedChildParentField = "compositeParentWire")
        public void goodWireSurface() {}
    }

    @ApiResource(value = "/api/test/parent-valid-composite", resourceKey = "test.parent-valid-composite")
    public static class ValidCompositeToCompositeParentController
            extends AbstractResourceQueryController<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> {
        @Override protected BaseResourceQueryService<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> getService() { return null; }
        @Override protected CompositeKey getResponseId(ParentSurfaceDTO dto) { return dto.id; }

        @UiSurface(id = "good-composite", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM, title = "Good Composite",
                relatedChildResourceKey = "test.child-targets", relatedChildParentField = "compositeParentRef")
        public void goodCompositeSurface() {}
    }

    @ApiResource(value = "/api/test/parent-valid-scalar-sel", resourceKey = "test.parent-valid-scalar-sel")
    public static class ValidScalarSelectionParentController
            extends AbstractResourceQueryController<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> {
        @Override protected BaseResourceQueryService<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> getService() { return null; }
        @Override protected CompositeKey getResponseId(ParentSurfaceDTO dto) { return dto.id; }

        @UiSurface(id = "good-scalar-sel", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM, title = "Good Scalar Sel",
                relatedChildResourceKey = "test.child-targets",
                relatedSelectionKeyField = "numfunc",
                relatedChildParentField = "numericParentRef")
        public void goodScalarSelectionSurface() {}
    }

    @ApiResource(value = "/api/test/parent-missing-child-field", resourceKey = "test.parent-missing-child-field")
    public static class MissingChildFilterFieldParentController
            extends AbstractResourceQueryController<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> {
        @Override protected BaseResourceQueryService<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> getService() { return null; }
        @Override protected CompositeKey getResponseId(ParentSurfaceDTO dto) { return dto.id; }

        @UiSurface(id = "missing-field", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM, title = "Missing Field",
                relatedChildResourceKey = "test.child-targets", relatedChildParentField = "campoFantasma")
        public void missingChildFieldSurface() {}
    }

    @ApiResource(value = "/api/test/parent-missing-sel", resourceKey = "test.parent-missing-sel")
    public static class MissingSelectionKeyParentController
            extends AbstractResourceQueryController<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> {
        @Override protected BaseResourceQueryService<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> getService() { return null; }
        @Override protected CompositeKey getResponseId(ParentSurfaceDTO dto) { return dto.id; }

        @UiSurface(id = "missing-sel", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM, title = "Missing Sel",
                relatedChildResourceKey = "test.child-targets",
                relatedSelectionKeyField = "selectionInexistente",
                relatedChildParentField = "numericParentRef")
        public void missingSelectionKeySurface() {}
    }

    @ApiResource(value = "/api/test/parent-scalar-to-composite", resourceKey = "test.parent-scalar-to-composite")
    public static class ScalarToCompositeChildParentController
            extends AbstractResourceQueryController<ChildSurfaceDTO, Long, ChildSurfaceFilterDTO> {
        @Override protected BaseResourceQueryService<ChildSurfaceDTO, Long, ChildSurfaceFilterDTO> getService() { return null; }
        @Override protected Long getResponseId(ChildSurfaceDTO dto) { return dto.id; }

        @UiSurface(id = "scalar-to-composite", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM, title = "Scalar to Composite",
                relatedChildResourceKey = "test.child-targets", relatedChildParentField = "compositeParentRef")
        public void scalarToCompositeSurface() {}
    }

    @ApiResource(value = "/api/test/parent-unknown-child", resourceKey = "test.parent-unknown-child")
    public static class UnknownChildParentController
            extends AbstractResourceQueryController<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> {
        @Override protected BaseResourceQueryService<ParentSurfaceDTO, CompositeKey, ChildSurfaceFilterDTO> getService() { return null; }
        @Override protected CompositeKey getResponseId(ParentSurfaceDTO dto) { return dto.id; }

        @UiSurface(id = "unknown-child", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM, title = "Unknown Child",
                relatedChildResourceKey = "test.non-existent", relatedChildParentField = "compositeParentWire")
        public void unknownChildSurface() {}
    }
}
