package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateRangeShortcutSchemaContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fieldSchemaShouldPublishStaticDateRangeShortcutContract() throws Exception {
        JsonNode schema = readProjectJson("docs/spec/x-ui-field.schema.json");
        JsonNode properties = schema.path("properties");
        JsonNode definitions = schema.path("definitions");

        assertTrue(properties.has("shortcuts"), "field schema should expose date range shortcuts");
        assertTrue(properties.has("inlineQuickPresets"), "field schema should expose inline quick preset composition");
        assertTrue(properties.has("inlineOverlay"), "field schema should expose inline overlay actions");

        JsonNode staticPreset = definitions.path("staticDateRangePreset");
        assertEquals(false, staticPreset.path("additionalProperties").asBoolean(true),
                "static presets must stay closed to executable metadata such as calculateRange");
        assertTrue(staticPreset.path("required").toString().contains("startDate"));
        assertTrue(staticPreset.path("required").toString().contains("endDate"));
        assertTrue(staticPreset.path("properties").has("timeZone"));
        assertTrue(staticPreset.path("properties").has("effectiveFrom"));
        assertFalse(staticPreset.path("properties").has("calculateRange"),
                "JSON metadata must not publish frontend callbacks");

        JsonNode builtInShortcutIds = definitions.path("dateRangeShortcut")
                .path("oneOf")
                .get(0)
                .path("enum");
        assertEquals(List.of("today", "yesterday", "thisWeek", "lastWeek", "thisMonth", "lastMonth", "thisYear", "lastYear"),
                readArray(builtInShortcutIds));

        JsonNode quickPresetProperties = definitions.path("inlineQuickPresets").path("properties");
        assertEquals(List.of("enabled", "maxVisible", "position"), readFieldNames(quickPresetProperties),
                "inlineQuickPresets should stay closed to controlled composition options");

        JsonNode positionEnum = definitions.path("inlineQuickPresets")
                .path("properties")
                .path("position")
                .path("enum");
        assertEquals(List.of("auto", "footer", "start", "end"), readArray(positionEnum));
    }

    @Test
    void dateRangeShortcutExamplesShouldDocumentValidAndInvalidShapes() throws Exception {
        JsonNode validExample = readProjectJson("docs/spec/examples/x-ui-field-date-range-shortcuts.valid.json");
        JsonNode invalidExample = readProjectJson("docs/spec/examples/x-ui-field-date-range-shortcuts.invalid.json");

        assertEquals("inlineDateRange", validExample.path("controlType").asText());
        assertEquals("today", validExample.path("shortcuts").get(0).asText());
        assertEquals("periodo-votacao-2026", validExample.path("shortcuts").get(1).path("id").asText());
        assertEquals("auto", validExample.path("inlineQuickPresets").path("position").asText());

        assertTrue(invalidExample.path("shortcuts").get(0).has("calculateRange"),
                "invalid fixture should prove executable shortcut metadata is outside the public contract");
    }

    @Test
    void resolverShouldMaterializeMixedStaticShortcutCatalogFromExtraProperties() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string").format("date");
        property.setName("periodo");

        Annotation uiSchema = DateRangeShortcutDummy.class.getDeclaredField("periodo").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(FieldControlType.INLINE_DATE_RANGE.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertTrue(xui.get(FieldConfigProperties.SHORTCUTS.getValue()) instanceof List<?>);

        List<?> shortcuts = (List<?>) xui.get(FieldConfigProperties.SHORTCUTS.getValue());
        assertEquals("today", shortcuts.get(0));
        assertTrue(shortcuts.get(1) instanceof Map<?, ?>);

        Map<?, ?> staticPreset = (Map<?, ?>) shortcuts.get(1);
        assertEquals("periodo-votacao-2026", staticPreset.get("id"));
        assertEquals("2026-07-06", staticPreset.get("startDate"));
        assertEquals("2026-10-04", staticPreset.get("endDate"));
        assertFalse(staticPreset.containsKey("calculateRange"));

        assertTrue(xui.get(FieldConfigProperties.INLINE_QUICK_PRESETS.getValue()) instanceof Map<?, ?>);
        Map<?, ?> quickPresets = (Map<?, ?>) xui.get(FieldConfigProperties.INLINE_QUICK_PRESETS.getValue());
        assertEquals("start", quickPresets.get("position"));

        assertTrue(xui.get(FieldConfigProperties.INLINE_OVERLAY.getValue()) instanceof Map<?, ?>);
        Map<?, ?> inlineOverlay = (Map<?, ?>) xui.get(FieldConfigProperties.INLINE_OVERLAY.getValue());
        assertEquals("explicit", inlineOverlay.get("applyMode"));
    }

    private static class DateRangeShortcutDummy {
        @UISchema(
                controlType = FieldControlType.INLINE_DATE_RANGE,
                extraProperties = {
                        @ExtensionProperty(
                                name = "shortcuts",
                                value = "[\"today\",{\"id\":\"periodo-votacao-2026\",\"label\":\"Periodo de votacao 2026\",\"description\":\"Janela oficial resolvida pelo dominio eleitoral.\",\"startDate\":\"2026-07-06\",\"endDate\":\"2026-10-04\",\"timeZone\":\"America/Sao_Paulo\",\"icon\":\"how_to_vote\",\"tone\":\"info\"}]"
                        ),
                        @ExtensionProperty(
                                name = "inlineQuickPresets",
                                value = "{\"enabled\":true,\"maxVisible\":4,\"position\":\"start\"}"
                        ),
                        @ExtensionProperty(
                                name = "inlineOverlay",
                                value = "{\"applyMode\":\"explicit\",\"actions\":{\"apply\":{\"label\":\"Aplicar\",\"appearance\":\"filled\",\"colorRole\":\"primary\"},\"cancel\":{\"label\":\"Cancelar\",\"appearance\":\"text\",\"colorRole\":\"neutral\"}}}"
                        )
                }
        )
        String periodo;
    }

    private JsonNode readProjectJson(String relativePath) throws Exception {
        Path path = resolveProjectFile(relativePath);
        assertTrue(Files.exists(path), relativePath + " should exist");
        return objectMapper.readTree(Files.readString(path));
    }

    private static List<String> readArray(JsonNode node) {
        assertTrue(node.isArray(), "schema metadata node should be an array");
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static List<String> readFieldNames(JsonNode node) {
        assertTrue(node.isObject(), "schema metadata node should be an object");
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(values::add);
        return values;
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
