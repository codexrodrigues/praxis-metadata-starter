package org.praxisplatform.uischema.customfields;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CustomFieldSchemaProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void writeProjectionExcludesReadOnlyValuesButResponsesAndEligibleFiltersRetainThem() {
        var calculated = descriptor("calculated", CustomFieldDataType.INTEGER, FilterOperation.EQUAL, List.of(), true, true, true);
        var writable = descriptor("writable", CustomFieldDataType.INTEGER, FilterOperation.EQUAL, List.of(), true, false, true);
        var definitions = List.of(calculated, writable);
        var request = project(definitions, "request", false);
        var response = mapper.valueToTree(project(definitions, "response", false));
        var filter = mapper.valueToTree(project(definitions, "request", true));

        assertTrue(mapper.valueToTree(request).at("/properties/customFields/properties/calculated").isMissingNode());
        assertEquals(mapper.createArrayNode().add("writable"), mapper.valueToTree(request).at("/properties/customFields/required"));
        assertAccepted(contract(request), Map.of("customFields", Map.of("writable", 20)));
        assertRejected(contract(request), Map.of("customFields", Map.of("writable", 20, "calculated", 30)));
        assertTrue(response.at("/properties/customFields/properties/calculated/readOnly").asBoolean());
        assertTrue(response.at("/properties/customFields/required").toString().contains("calculated"));
        assertFalse(filter.at("/properties/customFields/properties/calculated").isMissingNode());
        assertFalse(filter.at("/properties/customFields/properties/calculated/x-ui/readOnly").asBoolean());
        assertDoesNotThrow(() -> new CustomFieldValidator((r,t,s) -> definitions)
                .validateValues(Map.of("writable", 20), definitions));
    }

    @Test void readOnlyOnlyWritesRemoveTheEntireContainerAndItsRootRequirement() {
        var calculated = descriptor("calculated", CustomFieldDataType.INTEGER, FilterOperation.EQUAL, List.of(), true, true, true);
        Map<String,Object> schema = new LinkedHashMap<>(); schema.put("required", List.of("name", "customFields"));
        new CustomFieldSchemaEnricher((r,t,s) -> List.of(calculated), mapper).enrich(schema, "people", "a", "request");
        assertEquals(List.of("name"), schema.get("required"));
        assertFalse(schema.containsKey("properties"));
        assertTrue(mapper.valueToTree(schema).at("/properties/customFields").isMissingNode());
    }

    @Test void duplicateReadOnlyDefinitionsRemainInvalidEvenWhenWritesDoNotPublishThem() {
        var calculated = descriptor("calculated", CustomFieldDataType.INTEGER, FilterOperation.EQUAL, List.of(), true, true, true);
        assertThrows(IllegalArgumentException.class, () -> project(List.of(calculated, calculated), "request", false));
    }

    @Test void autoIsResolvedFromCanonicalTypesAndExplicitControlsArePreserved() {
        Map<CustomFieldDataType,String> expected = Map.of(
                CustomFieldDataType.STRING, "input", CustomFieldDataType.INTEGER, "numericTextBox",
                CustomFieldDataType.DECIMAL, "numericTextBox", CustomFieldDataType.BOOLEAN, "checkbox",
                CustomFieldDataType.DATE, "date", CustomFieldDataType.DATE_TIME, "dateTime",
                CustomFieldDataType.ENUM, "select", CustomFieldDataType.ARRAY, "chipInput");
        expected.forEach((type, control) -> {
            var definition = descriptor("value", type, FilterOperation.EQUAL,
                    type == CustomFieldDataType.ENUM ? List.of("active") : List.of(), false, false, true);
            assertEquals(control, mapper.valueToTree(project(List.of(definition), "request", false))
                    .at("/properties/customFields/properties/value/x-ui/controlType").asText());
        });
        var explicit = new CustomFieldDescriptor("value", "Value", null, CustomFieldDataType.STRING,
                org.praxisplatform.uischema.FieldControlType.TEXTAREA, null, 0, false, false, true, false, true,
                null, null, null, null, null, null, null, null, List.of(), List.of(), null, FilterOperation.EQUAL, Map.of());
        assertEquals("textarea", mapper.valueToTree(project(List.of(explicit), "request", false))
                .at("/properties/customFields/properties/value/x-ui/controlType").asText());
    }

    @Test void autoFilterControlsFollowTheOperandShapeRatherThanTheStoredScalarShape() {
        var in = descriptor("sizes", CustomFieldDataType.INTEGER, FilterOperation.IN, List.of(), false, false, true);
        var enums = descriptor("statuses", CustomFieldDataType.ENUM, FilterOperation.IN, List.of("active"), false, false, true);
        var tree = mapper.valueToTree(project(List.of(in, enums), "request", true));
        assertEquals("chipInput", tree.at("/properties/customFields/properties/sizes/x-ui/controlType").asText());
        assertEquals("multiSelect", tree.at("/properties/customFields/properties/statuses/x-ui/controlType").asText());
    }

    @Test void filterProjectsOperationOperandsWithoutWriteConstraintsOrRequiredContainer() {
        var in = descriptor("sizes", CustomFieldDataType.INTEGER, FilterOperation.IN, List.of(), true, true, true);
        var isNull = descriptor("missing", CustomFieldDataType.INTEGER, FilterOperation.IS_NULL, List.of(), true, false, true);
        var hidden = descriptor("secret", CustomFieldDataType.STRING, FilterOperation.EQUAL, List.of(), true, false, false);
        var schema = project(List.of(in, isNull, hidden), "request", true);
        var tree = mapper.valueToTree(schema);
        assertTrue(tree.path("required").isMissingNode());
        assertTrue(tree.at("/properties/customFields/required").isMissingNode());
        assertTrue(tree.at("/properties/customFields/properties/secret").isMissingNode());
        var field = tree.at("/properties/customFields/properties/sizes");
        assertEquals(mapper.createArrayNode().add("array").add("null"), field.path("type"));
        assertEquals("integer", field.path("items").path("type").asText());
        assertFalse(field.has("minimum")); assertFalse(field.has("default")); assertFalse(field.has("readOnly"));
        assertFalse(field.path("x-ui").has("min")); assertFalse(field.path("x-ui").has("defaultValue"));
        assertFalse(field.path("x-ui").path("required").asBoolean());
        assertFalse(field.path("x-ui").path("readOnly").asBoolean());
        JsonSchema contract = contract(schema);
        assertAccepted(contract, Map.of());
        assertAccepted(contract, Map.of("customFields", Map.of("sizes", List.of(-200, 500), "missing", true)));
        assertAccepted(contract, Map.of("customFields", Map.of("sizes", List.of(), "missing", false)));
        Map<String, Object> nullOperands = new LinkedHashMap<>(); nullOperands.put("sizes", null); nullOperands.put("missing", null);
        assertAccepted(contract, Map.of("customFields", nullOperands));
        assertRejected(contract, Map.of("customFields", Map.of("sizes", 20)));
        assertRejected(contract, Map.of("customFields", Map.of("sizes", List.of("20"))));
        assertRejected(contract, Map.of("customFields", Map.of("sizes", Arrays.asList(20, null))));
        assertRejected(contract, Map.of("customFields", Map.of("missing", 20)));
        assertRejected(contract, Map.of("customFields", Map.of("secret", "value")));
    }

    @Test void requiredChildrenMakeTheRootContainerRequiredAndPreserveOtherRequirements() {
        var field = descriptor("size", CustomFieldDataType.INTEGER, FilterOperation.EQUAL, List.of(), true, false, true);
        Map<String,Object> schema = new LinkedHashMap<>(); schema.put("required", List.of("name", "customFields"));
        var enricher = new CustomFieldSchemaEnricher((r,t,s) -> List.of(field), mapper);
        enricher.enrich(schema, "people", "a", "request");
        assertEquals(List.of("name", "customFields"), schema.get("required"));
        JsonSchema contract = contract(schema);
        assertRejected(contract, Map.of("name", "Ana"));
        assertRejected(contract, Map.of("name", "Ana", "customFields", Map.of()));
        assertAccepted(contract, Map.of("name", "Ana", "customFields", Map.of("size", 20)));
        enricher.enrich(schema, "people", "a", "request", true);
        assertEquals(List.of("name"), schema.get("required"));
    }

    @Test void staticContainerRequirementIsPreservedWhileDynamicRequirementDependsOnChildren() {
        var optional = descriptor("note", CustomFieldDataType.STRING, FilterOperation.EQUAL, List.of(), false, false, true);
        var enricher = new CustomFieldSchemaEnricher((r,t,s) -> List.of(optional), mapper);
        for (String role : List.of("request", "response")) {
            Map<String,Object> declaredRequired = new LinkedHashMap<>();
            declaredRequired.put("type", "object");
            declaredRequired.put("required", List.of("customFields"));
            enricher.enrich(declaredRequired, "people", "a", role);
            assertEquals(List.of("customFields"), declaredRequired.get("required"));
            assertRejected(contract(declaredRequired), Map.of());
            assertAccepted(contract(declaredRequired), Map.of("customFields", Map.of()));
            assertFalse(project(List.of(optional), role, false).containsKey("required"));
        }
        Map<String,Object> filter = new LinkedHashMap<>(); filter.put("required", List.of("customFields"));
        enricher.enrich(filter, "people", "a", "request", true);
        assertFalse(filter.containsKey("required"));
    }

    @Test void isNullOperandsDiscardStoredOptionsAndUseBooleanControl() {
        var descriptor = new CustomFieldDescriptor("size", "Size", null, CustomFieldDataType.INTEGER,
                org.praxisplatform.uischema.FieldControlType.SELECT, null, 0, false, false, true, false, false,
                null, null, null, null, null, null, null, null, List.of(),
                List.of(Map.of("value", 20, "label", "Twenty")), null, FilterOperation.IS_NULL, Map.of());
        var tree = mapper.valueToTree(project(List.of(descriptor), "request", true));
        var operand = tree.at("/properties/customFields/properties/size");
        assertEquals(mapper.createArrayNode().add("boolean").add("null"), operand.path("type"));
        assertEquals("checkbox", operand.path("x-ui").path("controlType").asText());
        assertFalse(operand.path("x-ui").has("options"));
        assertFalse(operand.path("x-ui").has("optionSource"));
    }

    @Test void readOnlyRequirementsApplyToResponseAndNeverRequireAWriteContainer() {
        var field = descriptor("calculated", CustomFieldDataType.INTEGER, FilterOperation.EQUAL, List.of(), true, true, false);
        var request = project(List.of(field), "request", false);
        var response = project(List.of(field), "response", false);
        assertFalse(request.containsKey("required"));
        assertEquals(List.of("customFields"), response.get("required"));
        assertRejected(contract(response), Map.of());
        assertAccepted(contract(request), Map.of());
    }

    @Test void stringEnumerationAndEnumContractAgreeWithRuntimeValidation() {
        for (CustomFieldDataType type : List.of(CustomFieldDataType.STRING, CustomFieldDataType.ENUM)) {
            var field = descriptor("status", type, FilterOperation.EQUAL, List.of("active", "inactive"), true, false, true);
            var contract = contract(project(List.of(field), "request", false));
            var validator = new CustomFieldValidator((r,t,s) -> List.of(field));
            assertAccepted(contract, Map.of("customFields", Map.of("status", "active")));
            assertDoesNotThrow(() -> validator.validateValues(Map.of("status", "active"), List.of(field)));
            assertRejected(contract, Map.of("customFields", Map.of("status", "unknown")));
            assertThrows(CustomFieldValidationException.class, () -> validator.validateValues(Map.of("status", "unknown"), List.of(field)));
        }
    }

    @Test void enumInAndLikeOperandsFollowRuntimeMembershipWhilePlainStringsAcceptEmptySearch() {
        for (var operation : List.of(FilterOperation.IN, FilterOperation.LIKE)) {
            var field = descriptor("status", CustomFieldDataType.ENUM, operation, List.of("active", "inactive"), true, false, true);
            var contract = contract(project(List.of(field), "request", true));
            Object valid = operation == FilterOperation.IN ? List.of("active") : "active";
            Object invalid = operation == FilterOperation.IN ? List.of("unknown") : "act";
            assertAccepted(contract, Map.of("customFields", Map.of("status", valid)));
            assertRejected(contract, Map.of("customFields", Map.of("status", invalid)));
            Map<String,Object> nullOperand = new LinkedHashMap<>(); nullOperand.put("status", null);
            assertAccepted(contract, Map.of("customFields", nullOperand));
            if (operation == FilterOperation.IN)
                assertRejected(contract, Map.of("customFields", Map.of("status", Arrays.asList("active", null))));
        }
        var text = descriptor("search", CustomFieldDataType.STRING, FilterOperation.LIKE, List.of(), true, false, true);
        assertAccepted(contract(project(List.of(text), "request", true)), Map.of("customFields", Map.of("search", "")));
    }

    @Test void directProjectionNormalizesRoleBeforeProviderLookupAndFingerprinting() {
        List<String> roles = new ArrayList<>();
        var descriptor = descriptor("calculated", CustomFieldDataType.INTEGER, FilterOperation.EQUAL, List.of(), true, true, true);
        var enricher = new CustomFieldSchemaEnricher((r,t,s) -> { roles.add(s); return List.of(descriptor); }, mapper);
        Map<String,Object> lower = new LinkedHashMap<>(), upper = new LinkedHashMap<>();
        assertEquals(enricher.enrich(lower, "people", "a", "request"), enricher.enrich(upper, "people", "a", "REQUEST"));
        assertEquals(lower, upper); assertEquals(List.of("request", "request"), roles);
        assertFalse(upper.containsKey("required"));
    }

    @Test void descriptorRejectsInvalidEnumCombinationsWithoutUsingOptionsAsValidationAuthority() {
        for (CustomFieldDataType type : CustomFieldDataType.values()) {
            if (type == CustomFieldDataType.STRING || type == CustomFieldDataType.ENUM) continue;
            assertThrows(IllegalArgumentException.class, () -> descriptor("value", type, FilterOperation.EQUAL, List.of("x"), false, false, false));
        }
        assertThrows(IllegalArgumentException.class, () -> descriptor("value", CustomFieldDataType.ENUM, FilterOperation.EQUAL, List.of(), false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new CustomFieldDescriptor("value", null, null, CustomFieldDataType.ENUM,
                null, null, 0, false, false, false, false, false, null, null, null, null, null, null, null, null,
                List.of(), List.of(Map.of("value", "x", "label", "X")), null, null, Map.of()));
    }

    private Map<String,Object> project(List<CustomFieldDescriptor> fields, String role, boolean filter) {
        Map<String,Object> schema = new LinkedHashMap<>(); schema.put("type", "object");
        new CustomFieldSchemaEnricher((r,t,s) -> fields, mapper).enrich(schema, "people", "a", role, filter);
        return schema;
    }
    private CustomFieldDescriptor descriptor(String key, CustomFieldDataType type, FilterOperation operation,
            List<String> values, boolean required, boolean readOnly, boolean filterable) {
        return new CustomFieldDescriptor(key, key, null, type, null, null, 0, required, readOnly, filterable, false, true,
                null, BigDecimal.ONE, BigDecimal.valueOf(100), 2, 20, null, null, "Required", values, List.of(), null, operation, Map.of());
    }
    private JsonSchema contract(Map<String,Object> schema) {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(mapper.valueToTree(schema));
    }
    private void assertAccepted(JsonSchema schema, Object value) { assertTrue(schema.validate(mapper.valueToTree(value)).isEmpty()); }
    private void assertRejected(JsonSchema schema, Object value) { assertFalse(schema.validate(mapper.valueToTree(value)).isEmpty()); }
}
