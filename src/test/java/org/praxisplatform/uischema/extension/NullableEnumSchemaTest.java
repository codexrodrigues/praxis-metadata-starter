package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.SpecVersion;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.bulk.BulkEditableFields;
import org.praxisplatform.uischema.bulk.BulkFieldChange;
import org.praxisplatform.uischema.bulk.BulkFieldChanges;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.util.OpenApiUiUtils;

import static org.junit.jupiter.api.Assertions.*;

class NullableEnumSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generatedInlineNullableEnumPermitsSdkClearWithoutAddingAUiChoice() throws Exception {
        ObjectNode schema = generated(NullableUpdate.class, false);
        JsonNode value = schema.path("properties").path("value");
        assertEquals("string", value.path("type").asText());
        assertTrue(value.path("nullable").asBoolean());
        assertEquals(6, value.path("enum").size());
        assertEquals(1, nullCount(value.path("enum")));
        assertEquals(5, value.path("x-ui").path("options").size());
        for (JsonNode option : value.path("x-ui").path("options")) assertTrue(option.path("value").isTextual());

        BulkEditableFields fields = compile(NullableUpdate.class, schema, SpecVersion.V30);
        for (BulkMode mode : List.of(BulkMode.UNIFORM_UPDATE, BulkMode.PER_ITEM_UPDATE)) {
            assertEquals(Set.of("value"), fields.clearableFields(mode));
            ObjectNode current = mapper.createObjectNode().put("value", "OK");
            ObjectNode candidate = BulkFieldChanges.applyTo(current, List.of(BulkFieldChange.clear("value")),
                    fields.writableFields(mode), fields.clearableFields(mode));
            assertTrue(candidate.path("value").isNull());
            assertNull(mapper.treeToValue(candidate, NullableUpdate.class).value);
            assertEquals("OK", current.path("value").asText());
        }
    }

    @Test
    void theSameJavaEnumRemainsNonNullableInAnotherProperty() {
        ObjectNode schema = generated(MixedUpdate.class, false);
        JsonNode nullable = schema.path("properties").path("nullableValue");
        JsonNode required = schema.path("properties").path("ordinaryValue");
        assertEquals(1, nullCount(nullable.path("enum")));
        assertEquals(0, nullCount(required.path("enum")));
        assertEquals(5, required.path("enum").size());
        assertEquals(required.path("x-ui").path("options"), nullable.path("x-ui").path("options"));
        assertEquals(required.path("x-ui").path("controlType"), nullable.path("x-ui").path("controlType"));
    }

    @Test
    void explicitEnumsAndContradictoryNullabilityRemainRejectedByTheSdk() {
        for (Class<?> type : List.of(NonNullableUpdate.class, ExplicitUpdate.class, NotNullUpdate.class,
                NotBlankUpdate.class, NotEmptyUpdate.class, ReadOnlyUpdate.class, ImplementationUpdate.class)) {
            ObjectNode schema = generated(type, false);
            assertEquals(0, nullCount(schema.path("properties").path("value").path("enum")), type.getSimpleName());
            assertThrows(IllegalArgumentException.class, () -> compile(type, schema, SpecVersion.V30),
                    type.getSimpleName());
        }
    }

    @Test
    void requiredPresenceDoesNotForbidAnExplicitlyNullableEnum() throws Exception {
        for (Class<?> type : List.of(RequiredUpdate.class, LegacyRequiredUpdate.class,
                ParentRequiredUpdate.class, RequiredRecord.class, UiRequiredUpdate.class)) {
            ObjectNode schema = generated(type, false);
            JsonNode value = schema.path("properties").path("value");
            assertEquals(1, nullCount(value.path("enum")), type.getSimpleName());
            if (type == UiRequiredUpdate.class) {
                assertTrue(value.path("x-ui").path("required").asBoolean());
            } else {
                assertTrue(schema.path("required").isArray(), type.getSimpleName());
                assertTrue(java.util.stream.StreamSupport.stream(schema.path("required").spliterator(), false)
                        .anyMatch(name -> "value".equals(name.asText())), type.getSimpleName());
            }
            BulkEditableFields fields = compile(type, schema, SpecVersion.V30);
            ObjectNode candidate = BulkFieldChanges.applyTo(mapper.createObjectNode().put("value", "OK"),
                    List.of(BulkFieldChange.clear("value")), fields.writableFields(BulkMode.UNIFORM_UPDATE),
                    fields.clearableFields(BulkMode.UNIFORM_UPDATE));
            assertTrue(candidate.has("value"));
            assertTrue(candidate.path("value").isNull());
            JsonNode decoded = mapper.valueToTree(mapper.treeToValue(candidate, type));
            assertTrue(decoded.path("value").isNull(), type.getSimpleName());
        }
    }

    @Test
    void jacksonNullRejectionStillPreventsSdkClearForNullableRequiredInputs() throws Exception {
        ObjectNode setterSchema = generated(NullRejectedUpdate.class, false);
        assertEquals(1, nullCount(setterSchema.path("properties").path("value").path("enum")));
        assertThrows(IllegalArgumentException.class,
                () -> compile(NullRejectedUpdate.class, setterSchema, SpecVersion.V30));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue("{\"value\":null}", NullRejectedUpdate.class));

        ObjectMapper rejectNullCreator = mapper.copy().enable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
        ObjectNode creatorSchema = generated(RequiredRecord.class, false);
        assertThrows(IllegalArgumentException.class, () -> BulkEditableFields.compile(rejectNullCreator,
                rejectNullCreator.constructType(RequiredRecord.class), creatorSchema, SpecVersion.V30, Set.of()));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> rejectNullCreator.readValue("{\"value\":null}", RequiredRecord.class));
    }

    @Test
    void openApi31IsNotInferredFromAnOpenApi30NullableDeclaration() {
        ObjectNode schema = generated(NullableUpdate.class, true);
        assertEquals(0, nullCount(schema.path("properties").path("value").path("enum")));
        assertThrows(IllegalArgumentException.class, () -> compile(NullableUpdate.class, schema, SpecVersion.V31));
    }

    @Test
    void localNullabilityDoesNotWidenAReferencedEnumComponent() {
        ResolvedSchema resolved = resolve(ReferencedUpdate.class, false);
        JsonNode schema = Json.mapper().valueToTree(resolved.schema);
        assertTrue(schema.path("properties").path("value").has("$ref")
                || schema.path("properties").path("value").has("allOf"));
        assertFalse(resolved.referencedSchemas.isEmpty());
        for (var component : resolved.referencedSchemas.values()) {
            JsonNode raw = Json.mapper().valueToTree(component);
            assertEquals(0, nullCount(raw.path("enum")));
        }
        assertThrows(IllegalArgumentException.class,
                () -> compile(ReferencedUpdate.class, schema, SpecVersion.V30));
    }

    @Test
    void globalEnumReferencesAreNotExpandedByLocalNullability() {
        boolean original = io.swagger.v3.core.jackson.ModelResolver.enumsAsRef;
        try {
            io.swagger.v3.core.jackson.ModelResolver.enumsAsRef = true;
            ResolvedSchema resolved = resolve(NullableUpdate.class, false);
            assertNotNull(resolved.referencedSchemas.get("Outcome"));
            assertEquals(0, nullCount(Json.mapper().valueToTree(resolved.referencedSchemas.get("Outcome")).path("enum")));
            assertThrows(IllegalArgumentException.class, () -> compile(NullableUpdate.class,
                    Json.mapper().valueToTree(resolved.schema), SpecVersion.V30));
        } finally {
            io.swagger.v3.core.jackson.ModelResolver.enumsAsRef = original;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizationCopiesTheAutomaticEnumAndIsIdempotent() throws Exception {
        ResolvedSchema baseline = new ModelConverters(false).resolveAsResolvedSchema(
                new AnnotatedType(NullableUpdate.class).resolveAsRef(false));
        io.swagger.v3.oas.models.media.Schema property =
                (io.swagger.v3.oas.models.media.Schema) baseline.schema.getProperties().get("value");
        List<?> original = property.getEnum();
        var type = new AnnotatedType(Outcome.class)
                .ctxAnnotations(NullableUpdate.class.getField("value").getAnnotations());
        var resolver = new CustomOpenApiResolver(mapper);
        resolver.resolveSchemaMembers(property, type, null, java.util.Collections.emptyIterator());
        resolver.resolveSchemaMembers(property, type, null, java.util.Collections.emptyIterator());
        assertEquals(5, original.size());
        assertFalse(original.contains(null));
        JsonNode wire = Json.mapper().valueToTree(property);
        assertEquals(6, wire.path("enum").size());
        assertEquals(1, nullCount(wire.path("enum")));
    }

    @Test
    void automaticOptionsSkipJsonNullAndPreserveExplicitOptions() {
        var ui = new LinkedHashMap<String, Object>();
        OpenApiUiUtils.populateUiOptionsFromEnum(ui,
                Arrays.asList("OK", null, "FERIDO", mapper.nullNode(), "DESAPARECIDO", "MORTO", "NA"), mapper);
        JsonNode options = mapper.valueToTree(ui.get("options"));
        assertEquals(5, options.size());
        for (JsonNode option : options) assertNotEquals("null", option.path("value").asText());
        var manual = mapper.createArrayNode();
        manual.addObject().put("value", "OWN");
        ui.put("options", manual);
        OpenApiUiUtils.populateUiOptionsFromEnum(ui, Arrays.asList("OK", null), mapper);
        assertSame(manual, ui.get("options"));
    }

    private BulkEditableFields compile(Class<?> type, JsonNode schema, SpecVersion version) {
        return BulkEditableFields.compile(mapper, mapper.constructType(type), schema, version, Set.of());
    }

    private ObjectNode generated(Class<?> type, boolean v31) {
        ResolvedSchema resolved = resolve(type, v31);
        assertNotNull(resolved.schema);
        return (ObjectNode) (v31 ? Json31.mapper() : Json.mapper()).valueToTree(resolved.schema);
    }

    private ResolvedSchema resolve(Class<?> type, boolean v31) {
        ModelConverters converters = new ModelConverters(v31);
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(mapper);
        resolver.setOpenapi31(v31);
        converters.addConverter(resolver);
        return converters.resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(false));
    }

    private static long nullCount(JsonNode values) {
        long count = 0;
        for (JsonNode value : values) if (value.isNull()) count++;
        return count;
    }

    enum Outcome { OK, FERIDO, DESAPARECIDO, MORTO, NA }

    static class NullableUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true) @UISchema
        public Outcome value;
    }

    static class MixedUpdate {
        @Schema(nullable = true) @UISchema public Outcome nullableValue;
        @UISchema public Outcome ordinaryValue;
    }

    static class NonNullableUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = false) public Outcome value;
    }

    static class ExplicitUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true, allowableValues = {"OK", "NA"})
        public Outcome value;
    }

    static class NotNullUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true) @NotNull public Outcome value;
    }

    static class NotBlankUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true)
        @jakarta.validation.constraints.NotBlank public Outcome value;
    }

    static class NotEmptyUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true)
        @jakarta.validation.constraints.NotEmpty public Outcome value;
    }

    static class RequiredUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        public Outcome value;
    }

    static class LegacyRequiredUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true, required = true) public Outcome value;
    }

    record RequiredRecord(@BulkEditable(allowClear = true) @Schema(nullable = true)
                          @com.fasterxml.jackson.annotation.JsonProperty(required = true) Outcome value) { }

    static class UiRequiredUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true) @UISchema(required = true)
        public Outcome value;
    }

    static class NullRejectedUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
        public Outcome value;
    }

    static class ReadOnlyUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true, accessMode = Schema.AccessMode.READ_ONLY)
        public Outcome value;
    }

    @Schema(requiredProperties = "value")
    static class ParentRequiredUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true) public Outcome value;
    }

    static class ImplementationUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true, implementation = Outcome.class)
        public Outcome value;
    }

    static class ReferencedUpdate {
        @BulkEditable(allowClear = true) @Schema(nullable = true, enumAsRef = true) public Outcome value;
    }
}
