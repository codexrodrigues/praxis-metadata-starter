package org.praxisplatform.uischema.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.Components;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.action.EmptyWorkflowActionRequest;
import org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration;
import org.praxisplatform.uischema.controller.base.AbstractDuplicateDraftLegacyBackedResourceController;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springdoc.core.properties.SpringDocConfigProperties.ApiDocs.OpenApiVersion;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;

/** Exercises real Swagger dispatch; calling the protected hook cannot detect its ABI drift. */
class CustomOpenApiResolverSwaggerPipelineTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void factoryDialectPropagatesArrayEnumOptionsAtTheExistingSizeBoundary(boolean openApi31) {
        Schema<?> small = property(EnumArrayRequest.class, "small", openApi31);
        Schema<?> large = property(EnumArrayRequest.class, "large", openApi31);

        assertEquals("Small choices", ui(small).get("label"));
        assertEquals("Large choices", ui(large).get("label"));
        assertEquals(FieldControlType.CHIP_INPUT.getValue(), ui(small).get("controlType"));
        assertEquals(FieldControlType.MULTI_SELECT.getValue(), ui(large).get("controlType"));
        assertEquals(FieldDataType.ARRAY.getValue(), ui(small).get("type"));
        assertEquals(FieldDataType.ARRAY.getValue(), ui(large).get("type"));
        assertEquals(Set.of("array"), serializedTypes(small, openApi31));
        assertEquals(Set.of("array"), serializedTypes(large, openApi31));
        assertEquals(Set.of("string"), serializedTypes(small.getItems(), openApi31));
        assertEquals(Set.of("string"), serializedTypes(large.getItems(), openApi31));
        assertFalse(ui(small).containsKey("array"));
        assertFalse(ui(large).containsKey("array"));
        var mapper = openApi31 ? Json31.mapper() : Json.mapper();
        JsonNode smallOptions = mapper.valueToTree(ui(small).get("options"));
        JsonNode largeOptions = mapper.valueToTree(ui(large).get("options"));
        assertEquals(5, smallOptions.size());
        assertEquals(6, largeOptions.size());
        for (int index = 0; index < FiveChoices.values().length; index++) {
            assertEquals(FiveChoices.values()[index].name(), smallOptions.get(index).path("value").asText());
            assertEquals(FiveChoices.values()[index].name(), smallOptions.get(index).path("label").asText());
        }
        for (int index = 0; index < SixChoices.values().length; index++) {
            assertEquals(SixChoices.values()[index].name(), largeOptions.get(index).path("value").asText());
            assertEquals(SixChoices.values()[index].name(), largeOptions.get(index).path("label").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void referencedFinalRootRetainsArrayEnumOptionsAtTheExistingSizeBoundary(boolean openApi31) {
        Schema<?> root = schema(new AnnotatedType(EnumArrayRequest.class).resolveAsRef(true), openApi31);
        Schema<?> small = root.getProperties().get("small");
        Schema<?> large = root.getProperties().get("large");
        assertEquals(FieldControlType.CHIP_INPUT.getValue(), ui(small).get("controlType"));
        assertEquals(FieldControlType.MULTI_SELECT.getValue(), ui(large).get("controlType"));
        JsonNode smallOptions = Json.mapper().valueToTree(ui(small).get("options"));
        JsonNode largeOptions = Json.mapper().valueToTree(ui(large).get("options"));
        assertEquals(5, smallOptions.size());
        assertEquals(6, largeOptions.size());
        for (int index = 0; index < FiveChoices.values().length; index++) {
            assertEquals(FiveChoices.values()[index].name(), smallOptions.get(index).path("value").asText());
            assertEquals(FiveChoices.values()[index].name(), smallOptions.get(index).path("label").asText());
        }
        for (int index = 0; index < SixChoices.values().length; index++) {
            assertEquals(SixChoices.values()[index].name(), largeOptions.get(index).path("value").asText());
            assertEquals(SixChoices.values()[index].name(), largeOptions.get(index).path("label").asText());
        }
    }

    @Test
    void ambiguousOpenApi31UnionPreservesExplicitControlsOptionsAndExtraOverrides() {
        Schema<?> authored = property(ExplicitAmbiguousEnumArrayRequest.class, "authored", true);
        Schema<?> overridden = property(ExplicitAmbiguousEnumArrayRequest.class, "overridden", true);
        assertEquals(Set.of("array", "object"), serializedTypes(authored, true));
        assertEquals(FieldControlType.MULTI_SELECT.getValue(), ui(authored).get("controlType"));
        assertEquals(FieldDataType.ARRAY.getValue(), ui(authored).get("type"));
        JsonNode authoredOptions = Json.mapper().valueToTree(ui(authored).get("options"));
        assertEquals(1, authoredOptions.size());
        assertEquals("AUTHOR", authoredOptions.get(0).path("value").asText());
        assertEquals("Authored choice", authoredOptions.get(0).path("label").asText());
        assertEquals(Set.of("array", "object"), serializedTypes(overridden, true));
        assertEquals("authorControl", ui(overridden).get("controlType"));
        JsonNode extraOptions = Json.mapper().valueToTree(ui(overridden).get("options"));
        assertEquals(1, extraOptions.size());
        assertEquals("EXTRA", extraOptions.get(0).path("value").asText());
        assertEquals("Extra choice", extraOptions.get(0).path("label").asText());
    }

    @Test
    void ambiguousOpenApi31ArrayUnionDoesNotAcquireEnumOptionsFromOneChosenMember() {
        Schema<?> choice = property(AmbiguousEnumArrayRequest.class, "choices", true);

        String diagnostic = Json31.mapper().valueToTree(choice).toString();
        assertAll(
                () -> assertEquals(Set.of("array", "object"), serializedTypes(choice, true), diagnostic),
                () -> assertNull(ui(choice).get("controlType"), diagnostic),
                () -> assertFalse(ui(choice).containsKey("options"), diagnostic));
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conversionPreservesExplicitUiLabelAndBeanValidation(boolean openApi31) {
        Schema<?> property = property(CoverageRequest.class, "planoCobertura", openApi31);
        Map<?, ?> ui = ui(property);

        assertEquals("Plano de cobertura", ui.get("label"));
        assertEquals("textarea", ui.get("controlType"));
        assertEquals(500, property.getMaxLength());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conversionPreservesEntityLookupBinding(boolean openApi31) {
        Map<?, ?> ui = ui(property(LookupRequest.class, "acordoId", openApi31));

        assertEquals("Acordo", ui.get("label"));
        assertEquals("entityLookup", ui.get("controlType"));
        assertEquals("/api/agreements/option-sources/agreement/options/filter", ui.get("endpoint"));
        assertEquals("id", ui.get("valueField"));
        assertEquals("label", ui.get("displayField"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conversionPreservesEmptyWorkflowObjectWithoutInventedInputFields(boolean openApi31) {
        Schema<?> schema = schema(EmptyWorkflowActionRequest.class, openApi31);
        var serialized = (openApi31 ? Json31.mapper() : Json.mapper()).valueToTree(schema);

        assertEquals("object", serialized.path("type").asText());
        if (openApi31) {
            assertEquals(Set.of("object"), schema.getTypes());
        } else {
            assertEquals("object", schema.getType());
        }
        assertTrue(schema.getProperties() == null || schema.getProperties().isEmpty());
        assertTrue(schema.getRequired() == null || schema.getRequired().isEmpty());
        assertFalse(serialized.has("required"));
        assertFalse(serialized.has("additionalProperties"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conversionPreservesTheActualDuplicateDraftRequestBodyContext(boolean openApi31) throws Exception {
        var operation = AbstractDuplicateDraftLegacyBackedResourceController.class
                .getDeclaredMethod("duplicateDraft", Object.class)
                .getAnnotation(io.swagger.v3.oas.annotations.Operation.class);
        var requestBody = operation.requestBody();
        var contentSchema = requestBody.content()[0].schema();
        assertFalse(requestBody.required());
        assertEquals(EmptyWorkflowActionRequest.class, contentSchema.implementation());

        // Use Springdoc's actual @Content path; passing its implementation annotation
        // back as the same AnnotatedType's context fabricates a recursive override.
        synchronized (ModelConverters.class) {
            ModelConverters converters = ModelConverters.getInstance(openApi31);
            CustomOpenApiResolver resolver = resolver(openApi31);
            converters.addConverter(resolver);
            try {
                Components components = new Components();
                var content = SpringDocAnnotationsUtils.getContent(requestBody.content(),
                        new String[]{"application/json"}, new String[]{"application/json"},
                        null, components, null, openApi31).orElseThrow();
                Schema<?> reference = content.get("application/json").getSchema();
                assertNotNull(reference);
                assertNotNull(reference.get$ref());
                assertNotNull(components.getSchemas());
                Schema<?> schema = components.getSchemas().get(
                        reference.get$ref().substring("#/components/schemas/".length()));
                assertNotNull(schema);
                var serialized = (openApi31 ? Json31.mapper() : Json.mapper()).valueToTree(schema);
                assertEquals("object", serialized.path("type").asText());
                assertTrue(schema.getProperties() == null || schema.getProperties().isEmpty());
                assertFalse(serialized.has("required"));
                assertFalse(serialized.has("additionalProperties"));
            } finally {
                converters.removeConverter(resolver);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void factoryDialectPreservesInferredBooleanAndIntegerControls(boolean openApi31) {
        Schema<?> enabled = property(InferredScalarRequest.class, "enabled", openApi31);
        Schema<?> count = property(InferredScalarRequest.class, "count", openApi31);
        var mapper = openApi31 ? Json31.mapper() : Json.mapper();
        String diagnostics = "openApi31=" + openApi31 + "; enabled type=" + enabled.getType()
                + " types=" + enabled.getTypes() + "; count type=" + count.getType()
                + " types=" + count.getTypes();

        assertAll(
                () -> assertEquals(FieldControlType.CHECKBOX.getValue(), ui(enabled).get("controlType"), diagnostics),
                () -> assertEquals(FieldDataType.BOOLEAN.getValue(), ui(enabled).get("type"), diagnostics),
                () -> assertEquals(FieldControlType.NUMERIC_TEXT_BOX.getValue(), ui(count).get("controlType"), diagnostics),
                () -> assertEquals(FieldDataType.NUMBER.getValue(), ui(count).get("type"), diagnostics),
                () -> assertEquals("boolean", mapper.valueToTree(enabled).path("type").asText(), diagnostics),
                () -> assertEquals("integer", mapper.valueToTree(count).path("type").asText(), diagnostics));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void factoryDialectPreservesScalarAndObjectArrayInference(boolean openApi31) {
        Schema<?> tags = property(InferredArrayRequest.class, "tags", openApi31);
        Schema<?> entries = property(InferredArrayRequest.class, "entries", openApi31);

        assertEquals(FieldControlType.CHIP_INPUT.getValue(), ui(tags).get("controlType"));
        assertEquals(FieldDataType.ARRAY.getValue(), ui(tags).get("type"));
        assertEquals(Set.of("array"), serializedTypes(tags, openApi31));
        assertEquals(Set.of("string"), serializedTypes(tags.getItems(), openApi31));
        assertEquals(FieldControlType.ARRAY.getValue(), ui(entries).get("controlType"));
        assertEquals(FieldDataType.ARRAY.getValue(), ui(entries).get("type"));
        Map<?, ?> array = assertInstanceOf(Map.class, ui(entries).get("array"));
        assertEquals("object", array.get("itemType"));
        assertEquals("cards", array.get("mode"));
        assertEquals("removeFromPayload", array.get("deleteMode"));
        assertEquals(entries.getItems().get$ref(), array.get("itemSchemaRef"));
        assertNotNull(entries.getItems().get$ref());
        assertEquals(Set.of("array"), serializedTypes(entries, openApi31));
    }

    @Test
    void openApi31InfersNullableSingleTypeWithoutChoosingAnAmbiguousUnion() {
        Schema<?> enabled = property(UnionRequest.class, "enabled", true);
        Schema<?> choice = property(UnionRequest.class, "choice", true);

        assertEquals(Set.of("boolean", "null"), enabled.getTypes());
        assertEquals(Set.of("boolean", "null"), serializedTypes(enabled, true));
        assertEquals(FieldControlType.CHECKBOX.getValue(), ui(enabled).get("controlType"));
        assertEquals(FieldDataType.BOOLEAN.getValue(), ui(enabled).get("type"));
        assertEquals(Set.of("integer", "string"), choice.getTypes());
        assertEquals(Set.of("integer", "string"), serializedTypes(choice, true));
        assertNull(ui(choice).get("controlType"));
        assertEquals(FieldDataType.TEXT.getValue(), ui(choice).get("type"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void factoryDialectPreservesNumericRangeVariantsAndBoundNullability(boolean openApi31) {
        Schema<?> inclusive = property(RangeRequest.class, "countRange", openApi31);
        Schema<?> exclusive = property(RangeRequest.class, "strictCountRange", openApi31);

        assertEquals(FieldControlType.RANGE_SLIDER.getValue(), ui(inclusive).get("controlType"));
        assertEquals("range", ui(inclusive).get("mode"));
        assertNull(inclusive.getType());
        assertNull(inclusive.getTypes());
        assertFalse((openApi31 ? Json31.mapper() : Json.mapper()).valueToTree(inclusive).has("type"));
        assertEquals(2, inclusive.getOneOf().size());
        Schema<?> array = inclusive.getOneOf().get(0);
        Schema<?> object = inclusive.getOneOf().get(1);
        assertEquals(Set.of("array"), serializedTypes(array, openApi31));
        assertEquals(Set.of("object"), serializedTypes(object, openApi31));
        assertEquals(1, array.getMinItems());
        assertEquals(2, array.getMaxItems());
        assertEquals(2, array.getAnyOf().size());
        assertNotNull(array.getNot());
        assertEquals(List.of(java.util.Arrays.asList(null, null)), array.getNot().getEnum());
        assertBound(array.getItems(), openApi31, true);
        assertBound(object.getProperties().get("min"), openApi31, true);
        assertBound(object.getProperties().get("max"), openApi31, true);
        assertEquals(2, object.getAnyOf().size());
        Schema<?> requiresLower = object.getAnyOf().get(0);
        Schema<?> requiresUpper = object.getAnyOf().get(1);
        assertEquals(List.of("min"), requiresLower.getRequired());
        assertEquals(List.of("max"), requiresUpper.getRequired());
        assertBound(requiresLower.getProperties().get("min"), openApi31, false);
        assertBound(requiresUpper.getProperties().get("max"), openApi31, false);
        assertEquals(Set.of("array"), serializedTypes(array.getAnyOf().get(0), openApi31));
        assertEquals(Set.of("array"), serializedTypes(array.getNot(), openApi31));
        assertEquals(Set.of("object"), serializedTypes(object.getAnyOf().get(0), openApi31));

        assertEquals(FieldControlType.RANGE_SLIDER.getValue(), ui(exclusive).get("controlType"));
        assertNull(exclusive.getType());
        assertNull(exclusive.getTypes());
        assertEquals(2, exclusive.getOneOf().size());
        Schema<?> strictArray = exclusive.getOneOf().get(0);
        Schema<?> strictObject = exclusive.getOneOf().get(1);
        assertEquals(2, strictArray.getMinItems());
        assertEquals(2, strictArray.getMaxItems());
        assertBound(strictArray.getItems(), openApi31, false);
        assertEquals(2, strictObject.getRequired().size());
        assertEquals(Set.of("min", "max"), Set.copyOf(strictObject.getRequired()));
        assertBound(strictObject.getProperties().get("min"), openApi31, false);
        assertBound(strictObject.getProperties().get("max"), openApi31, false);
    }

    private static void assertBound(Schema<?> bound, boolean openApi31, boolean nullable) {
        assertNotNull(bound);
        assertEquals(nullable, bound.getNullable());
        assertEquals(nullable && openApi31 ? Set.of("integer", "null") : Set.of("integer"),
                serializedTypes(bound, openApi31));
        if (openApi31) {
            assertEquals(io.swagger.v3.oas.models.SpecVersion.V31, bound.getSpecVersion());
            assertEquals(nullable ? Set.of("integer", "null") : Set.of("integer"), bound.getTypes());
        } else {
            assertEquals("integer", bound.getType());
        }
    }

    private static Set<String> serializedTypes(Schema<?> schema, boolean openApi31) {
        assertNotNull(schema);
        var type = (openApi31 ? Json31.mapper() : Json.mapper()).valueToTree(schema).path("type");
        if (type.isTextual()) return Set.of(type.textValue());
        assertTrue(type.isArray(), () -> "Expected serialized type, got " + type);
        Set<String> types = new TreeSet<>();
        type.forEach(value -> types.add(value.textValue()));
        return types;
    }

    private static Schema<?> property(Class<?> dto, String name, boolean openApi31) {
        Schema<?> schema = schema(dto, openApi31);
        assertNotNull(schema.getProperties());
        Schema<?> property = schema.getProperties().get(name);
        assertNotNull(property);
        return property;
    }

    private static Schema<?> schema(Class<?> dto, boolean openApi31) {
        return schema(new AnnotatedType(dto).resolveAsRef(false), openApi31);
    }

    private static CustomOpenApiResolver resolver(boolean openApi31) {
        // Exercise the public bean factory rather than correcting its dialect inside the test.
        SpringDocConfigProperties properties = new SpringDocConfigProperties();
        properties.getApiDocs().setVersion(openApi31 ? OpenApiVersion.OPENAPI_3_1 : OpenApiVersion.OPENAPI_3_0);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("springDocConfigProperties", properties);
        CustomOpenApiResolver resolver = new OpenApiUiSchemaAutoConfiguration().modelResolver(
                Json.mapper(), beans.getBeanProvider(SpringDocConfigProperties.class));
        assertEquals(openApi31, resolver.isOpenapi31());
        return resolver;
    }

    private static Schema<?> schema(AnnotatedType type, boolean openApi31) {
        // DTO-only cases own their converter chain independently of Springdoc's content path.
        CustomOpenApiResolver resolver = resolver(openApi31);
        ModelConverters converters = new ModelConverters(openApi31);
        converters.addConverter(resolver);
        var resolved = converters.resolveAsResolvedSchema(type);
        Schema<?> schema = resolved.schema;
        assertNotNull(schema);
        if (type.isResolveAsRef()) {
            assertNotNull(schema.get$ref());
            schema = resolved.referencedSchemas.get(schema.get$ref().substring("#/components/schemas/".length()));
            assertNotNull(schema);
        }
        return schema;
    }

    private static Map<?, ?> ui(Schema<?> property) {
        assertNotNull(property.getExtensions());
        return assertInstanceOf(Map.class, property.getExtensions().get("x-ui"));
    }

    public enum FiveChoices { FIRST, SECOND, THIRD, FOURTH, FIFTH }
    public enum SixChoices { FIRST, SECOND, THIRD, FOURTH, FIFTH, SIXTH }

    public static class EnumArrayRequest {
        @UISchema(label = "Small choices")
        private List<FiveChoices> small;
        @UISchema(label = "Large choices")
        private List<SixChoices> large;

        public List<FiveChoices> getSmall() { return small; }
        public void setSmall(List<FiveChoices> value) { small = value; }
        public List<SixChoices> getLarge() { return large; }
        public void setLarge(List<SixChoices> value) { large = value; }
    }

    public static class ExplicitAmbiguousEnumArrayRequest {
        @UISchema(label = "Authored choices", type = FieldDataType.ARRAY,
                controlType = FieldControlType.MULTI_SELECT,
                options = "[{\"value\":\"AUTHOR\",\"label\":\"Authored choice\"}]")
        @io.swagger.v3.oas.annotations.media.Schema(types = {"array", "object"})
        private List<FiveChoices> authored;
        @UISchema(label = "Extra choices", controlType = FieldControlType.MULTI_SELECT,
                options = "[{\"value\":\"AUTHOR\",\"label\":\"Authored choice\"}]",
                extraProperties = {
                    @io.swagger.v3.oas.annotations.extensions.ExtensionProperty(name = "controlType", value = "authorControl"),
                    @io.swagger.v3.oas.annotations.extensions.ExtensionProperty(name = "options",
                            value = "[{\"value\":\"EXTRA\",\"label\":\"Extra choice\"}]")
                })
        @io.swagger.v3.oas.annotations.media.Schema(types = {"array", "object"})
        private List<FiveChoices> overridden;

        public List<FiveChoices> getAuthored() { return authored; }
        public void setAuthored(List<FiveChoices> value) { authored = value; }
        public List<FiveChoices> getOverridden() { return overridden; }
        public void setOverridden(List<FiveChoices> value) { overridden = value; }
    }

    public static class AmbiguousEnumArrayRequest {
        @UISchema(label = "Choices")
        @io.swagger.v3.oas.annotations.media.Schema(types = {"array", "object"})
        private List<FiveChoices> choices;

        public List<FiveChoices> getChoices() { return choices; }
        public void setChoices(List<FiveChoices> value) { choices = value; }
    }

    public static class InferredArrayRequest {
        @UISchema(label = "Tags")
        private List<String> tags;
        @UISchema(label = "Entries")
        private List<ArrayEntry> entries;

        public List<String> getTags() { return tags; }
        public void setTags(List<String> value) { tags = value; }
        public List<ArrayEntry> getEntries() { return entries; }
        public void setEntries(List<ArrayEntry> value) { entries = value; }
    }

    public static class ArrayEntry {
        private String name;
        public String getName() { return name; }
        public void setName(String value) { name = value; }
    }

    public static class UnionRequest {
        @UISchema(label = "Enabled")
        @io.swagger.v3.oas.annotations.media.Schema(types = {"boolean", "null"})
        private Boolean enabled;
        @UISchema(label = "Choice")
        @io.swagger.v3.oas.annotations.media.Schema(types = {"integer", "string"})
        private Object choice;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean value) { enabled = value; }
        public Object getChoice() { return choice; }
        public void setChoice(Object value) { choice = value; }
    }

    public static class RangeRequest {
        @UISchema(label = "Count range")
        @Filterable(operation = Filterable.FilterOperation.BETWEEN)
        private List<Integer> countRange;
        @UISchema(label = "Strict count range")
        @Filterable(operation = Filterable.FilterOperation.BETWEEN_EXCLUSIVE)
        private List<Integer> strictCountRange;

        public List<Integer> getCountRange() { return countRange; }
        public void setCountRange(List<Integer> value) { countRange = value; }
        public List<Integer> getStrictCountRange() { return strictCountRange; }
        public void setStrictCountRange(List<Integer> value) { strictCountRange = value; }
    }

    public static class InferredScalarRequest {
        @UISchema(label = "Enabled")
        private Boolean enabled;
        @UISchema(label = "Count")
        private Integer count;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean value) { enabled = value; }
        public Integer getCount() { return count; }
        public void setCount(Integer value) { count = value; }
    }

    public static class CoverageRequest {
        @Size(max = 500)
        @UISchema(label = "Plano de cobertura", controlType = FieldControlType.TEXTAREA)
        private String planoCobertura;

        public String getPlanoCobertura() { return planoCobertura; }
        public void setPlanoCobertura(String value) { planoCobertura = value; }
    }

    public static class LookupRequest {
        @UISchema(label = "Acordo", controlType = FieldControlType.ENTITY_LOOKUP,
                endpoint = "/api/agreements/option-sources/agreement/options/filter",
                valueField = "id", displayField = "label")
        private Integer acordoId;

        public Integer getAcordoId() { return acordoId; }
        public void setAcordoId(Integer value) { acordoId = value; }
    }
}
