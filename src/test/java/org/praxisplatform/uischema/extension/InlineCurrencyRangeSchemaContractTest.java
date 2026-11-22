package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineCurrencyRangeSchemaContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fieldSchemaShouldValidateCanonicalInlineCurrencyRangeExample() throws Exception {
        JsonSchema fieldSpec = readFieldSpec();
        JsonNode validExample = readProjectJson("docs/spec/examples/x-ui-field-inline-currency-range.valid.json");

        Set<ValidationMessage> errors = fieldSpec.validate(validExample);
        assertTrue(errors.isEmpty(), () -> "Expected zero schema errors but found: " + errors);
    }

    @Test
    void fieldSchemaShouldRejectInvalidShowAnchorsAndOperatorModes() throws Exception {
        JsonSchema fieldSpec = readFieldSpec();

        ObjectNode invalidShowAnchors = objectMapper.createObjectNode()
                .put("controlType", "inlineCurrencyRange")
                .put("showAnchors", "always");
        assertFalse(fieldSpec.validate(invalidShowAnchors).isEmpty());

        ObjectNode invalidOperatorModes = objectMapper.createObjectNode()
                .put("controlType", "inlineCurrencyRange");
        invalidOperatorModes.putArray("operatorModes").add("contains");
        assertFalse(fieldSpec.validate(invalidOperatorModes).isEmpty());
    }

    @Test
    void resolverShouldMaterializeInlineCurrencyRangeContractAndEnumMappings() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(objectMapper);
        Schema<?> property = new Schema<BigDecimal>().type("number");
        property.setName("salaryRange");

        Annotation uiSchema = InlineCurrencyRangeDummy.class
                .getDeclaredField("salaryRange")
                .getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(FieldControlType.INLINE_CURRENCY_RANGE.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("Faixa salarial contratada", xui.get(FieldConfigProperties.INLINE_PANEL_TITLE.getValue()));
        assertEquals("payments", xui.get(FieldConfigProperties.INLINE_PANEL_ICON.getValue()));
        assertEquals("#0f766e", xui.get(FieldConfigProperties.INLINE_PANEL_ICON_COLOR.getValue()));
        assertEquals("auto", xui.get(FieldConfigProperties.SHOW_ANCHORS.getValue()));
        assertEquals(List.of("between", "gte", "lte", "eq"), xui.get(FieldConfigProperties.OPERATOR_MODES.getValue()));
        assertEquals("between", xui.get(FieldConfigProperties.DEFAULT_OPERATOR_MODE.getValue()));
        assertTrue(xui.get(FieldConfigProperties.MARKS.getValue()) instanceof List<?>);
        assertTrue(xui.get(FieldConfigProperties.SEMANTIC_BANDS.getValue()) instanceof List<?>);
        assertTrue(xui.get(FieldConfigProperties.INLINE_DISTRIBUTION.getValue()) instanceof List<?>);

        JsonSchema fieldSpec = readFieldSpec();
        Set<ValidationMessage> errors = fieldSpec.validate(objectMapper.valueToTree(xui));
        assertTrue(errors.isEmpty(), () -> "Expected resolved x-ui to satisfy schema but found: " + errors);

        assertEquals(FieldConfigProperties.SHOW_ANCHORS, FieldConfigProperties.fromValue("showAnchors"));
        assertEquals(FieldConfigProperties.INLINE_DISTRIBUTION, FieldConfigProperties.fromValue("inlineDistribution"));
        assertEquals(FieldConfigProperties.INLINE_PANEL_TITLE, FieldConfigProperties.fromValue("inlinePanelTitle"));
        assertEquals(FieldConfigProperties.INLINE_PANEL_ICON, FieldConfigProperties.fromValue("inlinePanelIcon"));
        assertEquals(FieldConfigProperties.INLINE_PANEL_ICON_COLOR, FieldConfigProperties.fromValue("inlinePanelIconColor"));
        assertEquals(FieldConfigProperties.SHOW_INLINE_INPUTS, FieldConfigProperties.fromValue("showInlineInputs"));
        assertEquals(FieldConfigProperties.SLIDER_STEP, FieldConfigProperties.fromValue("sliderStep"));
        assertEquals(FieldConfigProperties.SHIFT_STEP, FieldConfigProperties.fromValue("shiftStep"));
        assertEquals(FieldConfigProperties.INPUT_MIN_LABEL, FieldConfigProperties.fromValue("inputMinLabel"));
        assertEquals(FieldConfigProperties.INPUT_MAX_LABEL, FieldConfigProperties.fromValue("inputMaxLabel"));
        assertEquals(FieldConfigProperties.OPERATOR_MODES, FieldConfigProperties.fromValue("operatorModes"));
        assertEquals(FieldConfigProperties.SEMANTIC_BANDS, FieldConfigProperties.fromValue("semanticBands"));
    }

    private static class InlineCurrencyRangeDummy {
        @UISchema(
                label = "Faixa salarial",
                controlType = FieldControlType.INLINE_CURRENCY_RANGE,
                extraProperties = {
                        @ExtensionProperty(name = "currency", value = "BRL"),
                        @ExtensionProperty(name = "inlinePanelTitle", value = "Faixa salarial contratada"),
                        @ExtensionProperty(name = "inlinePanelIcon", value = "payments"),
                        @ExtensionProperty(name = "inlinePanelIconColor", value = "#0f766e"),
                        @ExtensionProperty(name = "showAnchors", value = "auto"),
                        @ExtensionProperty(name = "showInlineInputs", value = "true"),
                        @ExtensionProperty(name = "sliderStep", value = "500"),
                        @ExtensionProperty(name = "shiftStep", value = "1000"),
                        @ExtensionProperty(name = "minDistance", value = "500"),
                        @ExtensionProperty(name = "maxDistance", value = "45000"),
                        @ExtensionProperty(name = "requireTwoBounds", value = "false"),
                        @ExtensionProperty(name = "operatorModes", value = "[\"between\",\"gte\",\"lte\",\"eq\"]"),
                        @ExtensionProperty(name = "defaultOperatorMode", value = "between"),
                        @ExtensionProperty(name = "inputMinLabel", value = "De"),
                        @ExtensionProperty(name = "inputMaxLabel", value = "Ate"),
                        @ExtensionProperty(name = "inputExactLabel", value = "Valor"),
                        @ExtensionProperty(name = "inlineDistribution", value = "[10,25,40,30,12]"),
                        @ExtensionProperty(name = "marks", value = "[{\"value\":5000,\"label\":\"R$ 5k\",\"tone\":\"neutral\"}]"),
                        @ExtensionProperty(name = "semanticBands", value = "[{\"start\":0,\"end\":15000,\"label\":\"Base operacional\",\"tone\":\"info\"}]")
                }
        )
        BigDecimal salaryRange;
    }

    private JsonSchema readFieldSpec() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(readProjectJson("docs/spec/x-ui-field.schema.json"));
    }

    private JsonNode readProjectJson(String relativePath) throws Exception {
        Path path = resolveProjectFile(relativePath);
        assertTrue(Files.exists(path), relativePath + " should exist");
        return objectMapper.readTree(Files.readString(path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getXui(Schema<?> property) {
        assertNotNull(property.getExtensions(), "Extensions should not be null");
        Object xui = property.getExtensions().get("x-ui");
        assertNotNull(xui, "x-ui extension should be present");
        assertTrue(xui instanceof Map, "x-ui should be a Map");
        return (Map<String, Object>) xui;
    }

    private static Path resolveProjectFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.exists(direct)) {
                return direct;
            }

            Path nestedModule = current.resolve("praxis-metadata-starter").resolve(relativePath);
            if (Files.exists(nestedModule)) {
                return nestedModule;
            }

            current = current.getParent();
        }

        throw new IllegalStateException("Unable to locate project file: " + relativePath);
    }
}
