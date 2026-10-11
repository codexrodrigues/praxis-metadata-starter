package org.praxisplatform.uischema.validation;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
