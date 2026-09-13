package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.SpecVersion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkEditableFieldsTest {

    @Test
    void compilesResolvedModelConverterSchemaUsingWireNamesInheritanceAndNamingStrategy() {
        ObjectMapper mapper = snakeCaseMapper();
        ObjectNode schema = resolvedSchema(mapper, ChildUpdate.class);

        BulkEditableFields fields = compile(mapper, ChildUpdate.class, schema, Set.of());

        assertEquals(Set.of("display-name", "schedule_date"), fields.writableFields(BulkMode.UNIFORM_UPDATE));
        assertEquals(Set.of("display-name"), fields.clearableFields(BulkMode.UNIFORM_UPDATE));
        assertEquals(Set.of("display-name", "item_note"), fields.writableFields(BulkMode.PER_ITEM_UPDATE));
        assertEquals(Set.of("display-name"), fields.clearableFields(BulkMode.PER_ITEM_UPDATE));
        assertFalse(fields.writableFields(BulkMode.UNIFORM_UPDATE).contains("displayName"));

        ObjectNode current = JsonNodeFactory.instance.objectNode();
        current.put("display-name", "before");
        current.put("schedule_date", "2026-01-01");
        ObjectNode candidate = BulkFieldChanges.applyTo(
                current,
                Arrays.asList(BulkFieldChange.clear("display-name"),
                        BulkFieldChange.set("schedule_date", JsonNodeFactory.instance.textNode("2026-02-01"))),
                fields.writableFields(BulkMode.UNIFORM_UPDATE),
                fields.clearableFields(BulkMode.UNIFORM_UPDATE)
        );

        assertTrue(candidate.path("display-name").isNull());
        assertEquals("2026-02-01", candidate.path("schedule_date").asText());
        assertEquals("before", current.path("display-name").asText());
        assertThrows(UnsupportedOperationException.class,
                () -> fields.writableFields(BulkMode.UNIFORM_UPDATE).add("other"));
        assertThrows(UnsupportedOperationException.class,
                () -> fields.clearableFields(BulkMode.PER_ITEM_UPDATE).add("other"));
        assertInvalid(() -> fields.writableFields(BulkMode.DOMAIN_COMMAND));
        assertInvalid(() -> fields.clearableFields(BulkMode.DOMAIN_COMMAND));
    }

    @Test
    void compilesRecordComponentThroughItsJacksonWireProperty() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = resolvedSchema(mapper, RecordUpdate.class);

        BulkEditableFields fields = compile(mapper, RecordUpdate.class, schema, Set.of());

        assertEquals(Set.of("record-value"), fields.writableFields(BulkMode.UNIFORM_UPDATE));
        assertEquals(Set.of("record-value"), fields.clearableFields(BulkMode.UNIFORM_UPDATE));
        assertTrue(fields.writableFields(BulkMode.PER_ITEM_UPDATE).isEmpty());
    }

    @Test
    void allowsOnlyExplicitOas30OrOas31NullabilityForClear() {
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode oas30Schema = resolvedSchema(mapper, NullableUpdate.class);
        assertEquals("string", property(oas30Schema, "value").path("type").asText());
        BulkEditableFields oas30 = compile(mapper, NullableUpdate.class, oas30Schema, Set.of());
        assertEquals(Set.of("value"), oas30.clearableFields(BulkMode.UNIFORM_UPDATE));

        ObjectNode oas31Schema = resolvedSchema(mapper, NullableUpdate.class);
        ObjectNode property = property(oas31Schema, "value");
        property.remove("nullable");
        property.remove("type");
        property.putArray("type").add("string").add("null");
        BulkEditableFields oas31 = compile(mapper, NullableUpdate.class, oas31Schema, SpecVersion.V31, Set.of());
        assertEquals(Set.of("value"), oas31.clearableFields(BulkMode.UNIFORM_UPDATE));

        ObjectNode v31NullableOnly = resolvedSchema(mapper, NullableUpdate.class);
        assertInvalid(() -> compile(mapper, NullableUpdate.class, v31NullableOnly, SpecVersion.V31, Set.of()));

        ObjectNode v31UnionWithoutNull = resolvedSchema(mapper, NullableUpdate.class);
        ObjectNode v31UnionProperty = property(v31UnionWithoutNull, "value");
        v31UnionProperty.remove("type");
        v31UnionProperty.put("nullable", true);
        v31UnionProperty.putArray("type").add("string");
        assertInvalid(() -> compile(mapper, NullableUpdate.class, v31UnionWithoutNull, SpecVersion.V31, Set.of()));

        ObjectNode v30TypeArray = resolvedSchema(mapper, NullableUpdate.class);
        ObjectNode v30ArrayProperty = property(v30TypeArray, "value");
        v30ArrayProperty.remove("type");
        v30ArrayProperty.put("nullable", true);
        v30ArrayProperty.putArray("type").add("string").add("null");
        assertInvalid(() -> compile(mapper, NullableUpdate.class, v30TypeArray, SpecVersion.V30, Set.of()));

        assertInvalid(() -> compile(mapper, RequiredOnlyUpdate.class, resolvedSchema(mapper, RequiredOnlyUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, NotNullUpdate.class, resolvedSchema(mapper, NotNullUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, NotBlankUpdate.class, resolvedSchema(mapper, NotBlankUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, NotEmptyUpdate.class, resolvedSchema(mapper, NotEmptyUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, PrimitiveUpdate.class, resolvedSchema(mapper, PrimitiveUpdate.class), Set.of()));
    }

    @Test
    void rejectsEnumAndConstThatDoNotPermitNullEvenWhenSchemaClaimsNullable() {
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode enumSchema = resolvedSchema(mapper, NullableUpdate.class);
        ObjectNode enumProperty = property(enumSchema, "value");
        enumProperty.putArray("enum").add("A").add("B");
        assertInvalid(() -> compile(mapper, NullableUpdate.class, enumSchema, Set.of()));

        ObjectNode constSchema = resolvedSchema(mapper, NullableUpdate.class);
        property(constSchema, "value").put("const", "fixed");
        assertInvalid(() -> compile(mapper, NullableUpdate.class, constSchema, Set.of()));
    }

    @Test
    void rejectsIgnoredHiddenReadOnlyAndProtectedDeclarations() {
        ObjectMapper mapper = new ObjectMapper();

        assertInvalid(() -> compile(mapper, IgnoredUpdate.class, resolvedSchema(mapper, IgnoredUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, HiddenUpdate.class, resolvedSchema(mapper, HiddenUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, UiHiddenUpdate.class, resolvedSchema(mapper, UiHiddenUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, UiFormHiddenUpdate.class, resolvedSchema(mapper, UiFormHiddenUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, SchemaReadOnlyUpdate.class, resolvedSchema(mapper, SchemaReadOnlyUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, UiReadOnlyUpdate.class, resolvedSchema(mapper, UiReadOnlyUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, JsonReadOnlyUpdate.class, resolvedSchema(mapper, JsonReadOnlyUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, HiddenRecordUpdate.class,
                resolvedSchema(mapper, RecordUpdate.class), Set.of()));

        ObjectNode getterNotNullSchema = resolvedSchema(mapper, GetterNotNullUpdate.class);
        property(getterNotNullSchema, "value").put("nullable", true);
        assertInvalid(() -> compile(mapper, GetterNotNullUpdate.class, getterNotNullSchema, Set.of()));

        ObjectNode schemaReadOnly = resolvedSchema(mapper, PlainUpdate.class);
        property(schemaReadOnly, "value").put("readOnly", true);
        assertInvalid(() -> compile(mapper, PlainUpdate.class, schemaReadOnly, Set.of()));

        ObjectNode xUiReadOnly = resolvedSchema(mapper, PlainUpdate.class);
        property(xUiReadOnly, "value").putObject("x-ui").put("readOnly", true);
        assertInvalid(() -> compile(mapper, PlainUpdate.class, xUiReadOnly, Set.of()));

        Set<String> protectedFields = new HashSet<>(Set.of("wire-id"));
        assertInvalid(() -> compile(mapper, ProtectedUpdate.class, resolvedSchema(mapper, ProtectedUpdate.class), protectedFields));
        protectedFields.clear();
        BulkEditableFields fields = compile(mapper, ProtectedUpdate.class, resolvedSchema(mapper, ProtectedUpdate.class), protectedFields);
        assertEquals(Set.of("wire-id"), fields.writableFields(BulkMode.UNIFORM_UPDATE));
    }

    @Test
    void rejectsMissingOrUnresolvedSchemaShapesWithoutNormalizingThem() {
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode missing = resolvedSchema(mapper, PlainUpdate.class);
        missing.with("properties").remove("value");
        assertInvalid(() -> compile(mapper, PlainUpdate.class, missing, Set.of()));

        for (String keyword : Arrays.asList("$ref", "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "dependentSchemas")) {
            ObjectNode root = resolvedSchema(mapper, PlainUpdate.class);
            unresolved(root, keyword);
            assertInvalid(() -> compile(mapper, PlainUpdate.class, root, Set.of()));

            ObjectNode field = resolvedSchema(mapper, PlainUpdate.class);
            unresolved(property(field, "value"), keyword);
            assertInvalid(() -> compile(mapper, PlainUpdate.class, field, Set.of()));
        }
    }

    @Test
    void rejectsDomainCommandEmptyAndDuplicateModeDeclarations() {
        ObjectMapper mapper = new ObjectMapper();

        assertInvalid(() -> compile(mapper, DomainCommandModeUpdate.class,
                resolvedSchema(mapper, DomainCommandModeUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, EmptyModesUpdate.class, resolvedSchema(mapper, EmptyModesUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, DuplicateModesUpdate.class,
                resolvedSchema(mapper, DuplicateModesUpdate.class), Set.of()));
    }

    @Test
    void rejectsClearWhenJacksonWouldRejectSkipOrCoerceNull() {
        ObjectMapper mapper = new ObjectMapper();

        assertInvalid(() -> compile(mapper, NullsFailUpdate.class,
                resolvedSchema(mapper, NullsFailUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, NullsSkipUpdate.class,
                resolvedSchema(mapper, NullsSkipUpdate.class), Set.of()));
        assertInvalid(() -> compile(mapper, NullsAsEmptyUpdate.class,
                resolvedSchema(mapper, NullsAsEmptyUpdate.class), Set.of()));

        ObjectMapper defaultNullsMapper = new ObjectMapper();
        defaultNullsMapper.setDefaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.SKIP));
        assertInvalid(() -> compile(defaultNullsMapper, DefaultNullsUpdate.class,
                resolvedSchema(defaultNullsMapper, DefaultNullsUpdate.class), Set.of()));

        BulkEditableFields explicitSet = compile(defaultNullsMapper, ExplicitSetNullsUpdate.class,
                resolvedSchema(defaultNullsMapper, ExplicitSetNullsUpdate.class), Set.of());
        assertEquals(Set.of("value"), explicitSet.clearableFields(BulkMode.UNIFORM_UPDATE));
    }

    @Test
    void rejectsClearForCreatorPropertiesWhenMapperFailsOnNull() {
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

        assertInvalid(() -> compile(mapper, StrictCreatorRecordUpdate.class,
                resolvedSchema(mapper, StrictCreatorRecordUpdate.class), Set.of()));
    }

    @Test
    void requiresGenericSuperclassDeclarationsToBeBound() {
        ObjectMapper mapper = new ObjectMapper();

        assertInvalid(() -> compile(mapper, GenericParentUpdate.class,
                resolvedSchema(mapper, GenericParentUpdate.class), Set.of()));

        BulkEditableFields fields = compile(mapper, ConcreteGenericChildUpdate.class,
                resolvedSchema(mapper, ConcreteGenericChildUpdate.class), Set.of());
        assertEquals(Set.of("value"), fields.writableFields(BulkMode.UNIFORM_UPDATE));
    }

    @Test
    void requiresCompleteInputsAndDoesNotRetainCallerSchemaOrProtections() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = resolvedSchema(mapper, PlainUpdate.class);

        assertInvalid(() -> BulkEditableFields.compile(null, mapper.constructType(PlainUpdate.class), schema, SpecVersion.V30, Set.of()));
        assertInvalid(() -> BulkEditableFields.compile(mapper, null, schema, SpecVersion.V30, Set.of()));
        assertInvalid(() -> BulkEditableFields.compile(mapper, mapper.constructType(PlainUpdate.class), null, SpecVersion.V30, Set.of()));
        assertInvalid(() -> BulkEditableFields.compile(mapper, mapper.constructType(PlainUpdate.class), schema, null, Set.of()));
        assertInvalid(() -> BulkEditableFields.compile(mapper, mapper.constructType(PlainUpdate.class), schema, SpecVersion.V30, null));
        assertInvalid(() -> BulkEditableFields.compile(mapper, mapper.constructType(PlainUpdate.class), schema, SpecVersion.V30, Set.of(" ")));

        BulkEditableFields fields = compile(mapper, PlainUpdate.class, schema, Set.of());
        property(schema, "value").put("readOnly", true);
        assertEquals(Set.of("value"), fields.writableFields(BulkMode.UNIFORM_UPDATE));
        assertTrue(BulkFieldChanges.applyTo(JsonNodeFactory.instance.objectNode(),
                Arrays.asList(BulkFieldChange.clear("value")), fields.writableFields(BulkMode.UNIFORM_UPDATE),
                fields.clearableFields(BulkMode.UNIFORM_UPDATE)).get("value").isNull());
    }

    private static BulkEditableFields compile(ObjectMapper mapper, Class<?> type, JsonNode schema, Set<String> protectedFields) {
        return compile(mapper, type, schema, SpecVersion.V30, protectedFields);
    }

    private static BulkEditableFields compile(ObjectMapper mapper, Class<?> type, JsonNode schema,
            SpecVersion specVersion, Set<String> protectedFields) {
        JavaType javaType = mapper.constructType(type);
        return BulkEditableFields.compile(mapper, javaType, schema, specVersion, protectedFields);
    }

    /** Produces the resolved schema that an operation binding must pass to the SDK. */
    private static ObjectNode resolvedSchema(ObjectMapper mapper, Class<?> type) {
        ModelConverters converters = new ModelConverters(false);
        converters.addConverter(new CustomOpenApiResolver(mapper));
        ResolvedSchema resolved = converters.resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(false));
        ObjectMapper schemaMapper = Json.mapper().copy();
        Map<String, JsonNode> components = new HashMap<>();
        resolved.referencedSchemas.forEach((name, schema) -> components.put(name, schemaMapper.valueToTree(schema)));
        JsonNode raw = resolved.schema == null ? components.get(type.getSimpleName()) : schemaMapper.valueToTree(resolved.schema);
        assertTrue(raw != null && raw.isObject(), "ModelConverters must generate an object schema");
        JsonNode materialized = materialize(raw, components, new HashSet<>());
        assertTrue(materialized.isObject(), "Resolved update schema must be an object");
        assertFalse(materialized.has("$ref"));
        assertFalse(materialized.has("allOf"));
        return (ObjectNode) materialized;
    }

    private static JsonNode materialize(JsonNode schema, Map<String, JsonNode> components, Set<String> resolving) {
        if (!schema.isObject()) {
            return schema.deepCopy();
        }
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            assertTrue(resolving.add(name), "Schema reference cycle in test fixture");
            JsonNode component = components.get(name);
            assertTrue(component != null, "ModelConverters emitted " + ref
                    + " without matching a component among " + components.keySet());
            JsonNode result = materialize(component, components, resolving);
            resolving.remove(name);
            return result;
        }
        ObjectNode result = ((ObjectNode) schema).deepCopy();
        if (result.has("allOf")) {
            ArrayNode allOf = (ArrayNode) result.remove("allOf");
            ObjectNode merged = JsonNodeFactory.instance.objectNode();
            for (JsonNode member : allOf) {
                JsonNode resolved = materialize(member, components, resolving);
                assertTrue(resolved.isObject(), "allOf member must resolve to an object in test fixture");
                mergeObject(merged, (ObjectNode) resolved);
            }
            mergeObject(merged, result);
            result = merged;
        }
        return result;
    }

    private static void mergeObject(ObjectNode target, ObjectNode source) {
        source.fields().forEachRemaining(entry -> {
            if ("properties".equals(entry.getKey()) && entry.getValue().isObject()) {
                ObjectNode targetProperties = target.with("properties");
                entry.getValue().fields().forEachRemaining(property -> {
                    if (targetProperties.has(property.getKey())) {
                        assertEquals(targetProperties.get(property.getKey()), property.getValue(),
                                "Conflicting property schema while resolving test fixture");
                    } else {
                        targetProperties.set(property.getKey(), property.getValue().deepCopy());
                    }
                });
            } else if ("required".equals(entry.getKey()) && entry.getValue().isArray()) {
                ArrayNode required = target.withArray("required");
                entry.getValue().forEach(value -> {
                    if (!contains(required, value)) {
                        required.add(value.deepCopy());
                    }
                });
            } else {
                if (target.has(entry.getKey())) {
                    assertEquals(target.get(entry.getKey()), entry.getValue(),
                            "Conflicting schema keyword while resolving test fixture");
                } else {
                    target.set(entry.getKey(), entry.getValue().deepCopy());
                }
            }
        });
    }

    private static boolean contains(ArrayNode nodes, JsonNode expected) {
        for (JsonNode node : nodes) {
            if (node.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode property(ObjectNode schema, String name) {
        JsonNode property = schema.path("properties").path(name);
        assertTrue(property.isObject(), "Missing generated property " + name);
        return (ObjectNode) property;
    }

    private static void unresolved(ObjectNode schema, String keyword) {
        if ("$ref".equals(keyword)) {
            schema.put(keyword, "#/components/schemas/Other");
        } else if (Arrays.asList("allOf", "anyOf", "oneOf").contains(keyword)) {
            schema.putArray(keyword).addObject().put("type", "string");
        } else {
            schema.putObject(keyword);
        }
    }

    private static ObjectMapper snakeCaseMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }

    private static void assertInvalid(ThrowingRunnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static class ParentUpdate {
        @BulkEditable(allowClear = true)
        @JsonProperty("display-name")
        @Schema(nullable = true)
        public String displayName;
    }

    private static final class ChildUpdate extends ParentUpdate {
        @BulkEditable(modes = BulkMode.UNIFORM_UPDATE)
        public String scheduleDate;

        @BulkEditable(modes = BulkMode.PER_ITEM_UPDATE)
        @JsonProperty("item_note")
        public String itemNote;
    }

    private record RecordUpdate(
            @BulkEditable(modes = BulkMode.UNIFORM_UPDATE, allowClear = true)
            @JsonProperty("record-value")
            @Schema(nullable = true) String value
    ) {
    }

    private record HiddenRecordUpdate(
            @BulkEditable
            @JsonProperty("record-value")
            @UISchema(hidden = true) String value
    ) {
    }

    private static final class NullableUpdate {
        @BulkEditable(allowClear = true)
        @Schema(nullable = true)
        public String value;
    }

    private static final class RequiredOnlyUpdate {
        @BulkEditable(allowClear = true)
        @NotNull
        public String value;
    }

    private static final class NotNullUpdate {
        @BulkEditable(allowClear = true)
        @NotNull
        @Schema(nullable = true)
        public String value;
    }

    private static final class NotBlankUpdate {
        @BulkEditable(allowClear = true)
        @NotBlank
        @Schema(nullable = true)
        public String value;
    }

    private static final class NotEmptyUpdate {
        @BulkEditable(allowClear = true)
        @NotEmpty
        @Schema(nullable = true)
        public String value;
    }

    private static final class PrimitiveUpdate {
        @BulkEditable(allowClear = true)
        @Schema(nullable = true)
        public int value;
    }

    private static final class NullsFailUpdate {
        @BulkEditable(allowClear = true)
        @Schema(nullable = true)
        public String value;

        @JsonSetter(nulls = Nulls.FAIL)
        public void setValue(String value) {
            this.value = value;
        }
    }

    private static final class NullsSkipUpdate {
        @BulkEditable(allowClear = true)
        @Schema(nullable = true)
        public String value;

        @JsonSetter(nulls = Nulls.SKIP)
        public void setValue(String value) {
            this.value = value;
        }
    }

    private static final class NullsAsEmptyUpdate {
        @BulkEditable(allowClear = true)
        @Schema(nullable = true)
        public String value;

        @JsonSetter(nulls = Nulls.AS_EMPTY)
        public void setValue(String value) {
            this.value = value;
        }
    }

    private static final class DefaultNullsUpdate {
        @BulkEditable(allowClear = true)
        @Schema(nullable = true)
        public String value;
    }

    private static final class ExplicitSetNullsUpdate {
        @BulkEditable(allowClear = true)
        @JsonSetter(nulls = Nulls.SET)
        @Schema(nullable = true)
        public String value;
    }

    private record StrictCreatorRecordUpdate(
            @BulkEditable(allowClear = true)
            @Schema(nullable = true) String value
    ) {
    }

    private static class GenericParentUpdate<T> {
        @BulkEditable
        public T value;
    }

    private static final class ConcreteGenericChildUpdate extends GenericParentUpdate<String> {
    }

    private static final class IgnoredUpdate {
        @BulkEditable
        @JsonIgnore
        public String value;
    }

    private static final class HiddenUpdate {
        @BulkEditable
        @Schema(hidden = true)
        public String value;
    }

    private static final class SchemaReadOnlyUpdate {
        @BulkEditable
        @Schema(accessMode = Schema.AccessMode.READ_ONLY)
        public String value;
    }

    private static final class UiReadOnlyUpdate {
        @BulkEditable
        @UISchema(readOnly = true)
        public String value;
    }

    private static final class UiHiddenUpdate {
        @BulkEditable
        @UISchema(hidden = true)
        public String value;
    }

    private static final class UiFormHiddenUpdate {
        @BulkEditable
        @UISchema(formHidden = true)
        public String value;
    }

    private static final class JsonReadOnlyUpdate {
        @BulkEditable
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String value;
    }

    private static final class GetterNotNullUpdate {
        @BulkEditable(allowClear = true)
        @Schema(nullable = true)
        private String value;

        @NotNull
        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    private static final class ProtectedUpdate {
        @BulkEditable
        @JsonProperty("wire-id")
        public String id;
    }

    private static final class PlainUpdate {
        @BulkEditable(allowClear = true)
        @Schema(nullable = true)
        public String value;
    }

    private static final class DomainCommandModeUpdate {
        @BulkEditable(modes = BulkMode.DOMAIN_COMMAND)
        public String value;
    }

    private static final class EmptyModesUpdate {
        @BulkEditable(modes = {})
        public String value;
    }

    private static final class DuplicateModesUpdate {
        @BulkEditable(modes = {BulkMode.UNIFORM_UPDATE, BulkMode.UNIFORM_UPDATE})
        public String value;
    }
}
