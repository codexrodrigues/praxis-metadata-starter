package org.praxisplatform.uischema.customfields;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import com.networknt.schema.*;

class CustomFieldJsonLogicTest {
    record Draft(int limit, @CustomFields Map<String, Object> customFields) { }
    record Entity(int maximum) { }
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static CustomFieldDescriptor field(Map<String, Object> rules) {
        return field(rules, false);
    }
    private static CustomFieldDescriptor field(Map<String, Object> rules, boolean required) {
        return new CustomFieldDescriptor("amount", "Amount", "Requested allocation amount", CustomFieldDataType.INTEGER,
                null, null, 0, required, false, true, false, true, null, null, null, null, null,
                null, null, null, List.of(), List.of(), null, null, Map.of(), rules);
    }
    private Map<String, Object> rules(String expression) throws Exception {
        return Map.of("jsonLogic", mapper.readValue(expression, Map.class), "message", "Valor acima do limite.");
    }

    @Test void evaluatesAllFourExplicitRootsWithTrustedEntityAndDetachesDefinitions() throws Exception {
        var definition = field(rules("""
                {"and":[
                  {"<=":[{"var":"value"},{"var":"draft.limit"}]},
                  {"<=":[{"var":"customFields.amount"},{"var":"entity.maximum"}]}
                ]}
                """));
        var validator = new CustomFieldValidator((r,t,s) -> List.of(definition));
        assertDoesNotThrow(() -> validator.validateAndGetReadOnlyKeys(new Draft(10, Map.of("amount", 8)), "allocations", "a", "request", new Entity(9)));
        var exception = assertThrows(CustomFieldValidationException.class, () -> validator.validateAndGetReadOnlyKeys(
                new Draft(10, Map.of("amount", 11)), "allocations", "a", "request", new Entity(9)));
        assertEquals("customFields.amount", exception.getFieldErrors().getFirst().getField());
        assertEquals("Valor acima do limite.", exception.getFieldErrors().getFirst().getDefaultMessage());
        assertNull(exception.getFieldErrors().getFirst().getRejectedValue());
        assertThrows(IllegalStateException.class, () -> validator.validate(new Draft(10, Map.of("amount", 8)), "allocations", "a"));
    }

    @Test void distinguishesMissingNullAndZeroAndUsesCanonicalTruthiness() throws Exception {
        var absence = field(rules("{\"==\":[{\"var\":[\"value\",\"missing\"]},\"missing\"]}"));
        var validator = new CustomFieldValidator((r,t,s) -> List.of(absence));
        assertDoesNotThrow(() -> validator.validateValues(Map.of(), List.of(absence)));
        Map<String, Object> explicitNull = new HashMap<>(); explicitNull.put("amount", null);
        assertDoesNotThrow(() -> validator.validateValues(explicitNull, List.of(absence)));
        assertThrows(CustomFieldValidationException.class, () -> validator.validateValues(Map.of("amount", 0), List.of(absence)));
        var zero = field(rules("{\"==\":[{\"var\":\"value\"},0]}"));
        assertDoesNotThrow(() -> validator.validateValues(Map.of("amount", 0), List.of(zero)));
        var required = field(rules("{\"<=\":[{\"var\":\"value\"},10]}"), true);
        assertThrows(CustomFieldValidationException.class, () -> validator.validateValues(Map.of(), List.of(required)));
    }

    @Test void malformedUnknownRootOperatorAndOversizedDefinitionsFailAtConstruction() throws Exception {
        assertThrows(PraxisJsonLogicException.class, () -> field(rules("{\"unknown\":[1]}")));
        assertThrows(PraxisJsonLogicException.class, () -> field(rules("{\"var\":\"request.secret\"}")));
        assertThrows(PraxisJsonLogicException.class, () -> field(Map.of("jsonLogic", true)));
        assertThrows(IllegalArgumentException.class, () -> field(Map.of("message", "Missing expression")));
        assertThrows(IllegalArgumentException.class, () -> field(Map.of("jsonLogic", Map.of("var", "value"), "typo", true)));
        assertThrows(IllegalArgumentException.class, () -> field(Map.of("jsonLogic", Map.of("var", "value"), "message", " ")));
        assertThrows(IllegalArgumentException.class, () -> field(Map.of("jsonLogic", Map.of("==", List.of("x".repeat(64_001), "x")))));
    }

    @Test void checksEveryPrimitivePrerequisiteBeforeCrossFieldRulesRegardlessOfDefinitionOrder() throws Exception {
        var amount = field(rules("{\"<=\":[{\"var\":\"value\"},{\"var\":\"customFields.maximum\"}]}"));
        var maximum = CustomFieldFixtures.field("maximum", CustomFieldDataType.INTEGER);
        var validator = new CustomFieldValidator((r,t,s) -> List.of(amount, maximum));
        for (var definitions : List.of(List.of(amount, maximum), List.of(maximum, amount))) {
            var missing = assertThrows(CustomFieldValidationException.class,
                    () -> validator.validateValues(Map.of("amount", 5), definitions));
            assertEquals("customFields.maximum", missing.getFieldErrors().getFirst().getField());
            assertThrows(CustomFieldValidationException.class,
                    () -> validator.validateValues(Map.of("amount", 5, "maximum", "invalid"), definitions));
            assertDoesNotThrow(() -> validator.validateValues(Map.of("amount", 5, "maximum", 10), definitions));
        }
    }

    @Test void typeFailuresDoNotBecomeFalseBusinessValidationsAndDefinitionsAreImmutable() throws Exception {
        var expression = mapper.readTree("{\"<=\":[{\"var\":\"value\"},10]}");
        var definition = field(Map.of("jsonLogic", expression));
        ((com.fasterxml.jackson.databind.node.ObjectNode) expression).removeAll();
        var validator = new CustomFieldValidator((r,t,s) -> List.of(definition));
        assertDoesNotThrow(() -> validator.validateValues(Map.of("amount", 5), List.of(definition)));
        assertDoesNotThrow(() -> validator.validateValues(Map.of(), List.of(definition)));
        var explicitNull = new HashMap<String,Object>(); explicitNull.put("amount",null);
        assertDoesNotThrow(() -> validator.validateValues(explicitNull, List.of(definition)));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) definition.validationRules().get("jsonLogic")).clear());
        var precise = field(rules("{\"==\":[{\"var\":\"value\"},9007199254740993.125]}"));
        // Descriptor capture must not round JSON decimal literals through a double.
        var captured = org.praxisplatform.rules.facts.PraxisFactTreeBuilder.toJsonNode(precise.validationRules());
        assertEquals(new java.math.BigDecimal("9007199254740993.125"), captured.at("/jsonLogic/==/1").decimalValue());
    }

    @Test void rejectsDeepDefinitionsBeforeCopyingOrEvaluatingTheirTree() {
        Object node = Map.of("var", "value");
        for (int depth = 0; depth < 2048; depth++) node = Map.of("!", List.of(node));
        var deep = Map.of("jsonLogic", node);
        assertThrows(IllegalArgumentException.class, () -> field(deep));
    }

    @Test void schemaPublishesValidationModeWithoutPrivateRulesAndOmitsItFromFilters() throws Exception {
        var first = field(rules("{\"<=\":[{\"var\":\"value\"},10]}"));
        var second = field(rules("{\"<=\":[{\"var\":\"value\"},20]}"));
        var enricher = new CustomFieldSchemaEnricher((r,t,s) -> List.of(first), mapper);
        Map<String, Object> schema = new LinkedHashMap<>();
        String hash = enricher.enrich(schema, "allocations", "a", "request");
        var ui = mapper.valueToTree(schema).at("/properties/customFields/properties/amount/x-ui");
        assertEquals("json-logic", ui.path("validationMode").asText());
        assertFalse(ui.has("validationRules"));
        var spec = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile()));
        assertTrue(spec.validate(ui).isEmpty());
        assertFalse(spec.validate(mapper.readTree("{\"validationRules\":{\"jsonLogic\":{}}}")).isEmpty());
        Map<String, Object> changed = new LinkedHashMap<>();
        String changedHash = new CustomFieldSchemaEnricher((r,t,s) -> List.of(second), mapper).enrich(changed, "allocations", "a", "request");
        assertNotEquals(hash, changedHash);
        Map<String, Object> filter = new LinkedHashMap<>(); enricher.enrich(filter, "allocations", "a", "request", true);
        assertTrue(mapper.valueToTree(filter).at("/properties/customFields/properties/amount/x-ui/validationRules").isMissingNode());
        assertTrue(mapper.valueToTree(filter).at("/properties/customFields/properties/amount/x-ui/validationMode").isMissingNode());
    }
}
