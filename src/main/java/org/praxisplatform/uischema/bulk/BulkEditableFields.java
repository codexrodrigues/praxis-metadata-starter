package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.SpecVersion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, structural allowlists compiled from an update DTO and its canonical schema.
 *
 * <p>The caller supplies the configured Jackson mapper, the concrete update DTO type, the
 * operation's resolved request schema, its OpenAPI version and all protected wire names (identity, persisted
 * versions and workflow-managed state). No field role is inferred from a name. References
 * and schema compositions must be resolved by the owning operation binding first.</p>
 *
 * <p>This SDK does not fetch OpenAPI, publish metadata, install a runtime, authorize an actor
 * or validate candidate values. Use the returned sets with {@link BulkFieldChanges}, then
 * perform candidate/domain validation and authorization in the actual operation.</p>
 */
public final class BulkEditableFields {
    private static final List<BulkMode> UPDATE_MODES = List.of(BulkMode.UNIFORM_UPDATE, BulkMode.PER_ITEM_UPDATE);
    private static final List<String> UNRESOLVED = List.of(
            "$ref", "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "dependentSchemas");

    private final Map<BulkMode, Set<String>> writable;
    private final Map<BulkMode, Set<String>> clearable;

    private BulkEditableFields(Map<BulkMode, Set<String>> writable, Map<BulkMode, Set<String>> clearable) {
        this.writable = freeze(writable);
        this.clearable = freeze(clearable);
    }

    /**
     * Compiles only explicitly annotated DTO fields; inputs are not mutated or retained.
     * Empty results are valid and do not imply support for a bulk operation.
     *
     * @throws IllegalArgumentException if the declaration and resolved schema cannot prove eligibility
     */
    public static BulkEditableFields compile(ObjectMapper mapper, JavaType updateType,
            JsonNode resolvedUpdateSchema, SpecVersion specVersion, Set<String> protectedFields) {
        if (mapper == null || updateType == null || specVersion == null || protectedFields == null) {
            throw invalid("mapper, concrete update type, OpenAPI version and protected wire fields are required");
        }
        if (protectedFields.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw invalid("protected wire fields must have nonblank names");
        }
        if (updateType.getRawClass().getTypeParameters().length > updateType.getBindings().size()) {
            throw invalid("update DTO generic parameters must be bound");
        }
        requireResolved(resolvedUpdateSchema, "update schema");
        JsonNode properties = resolvedUpdateSchema.path("properties");
        if (!properties.isObject()) {
            throw invalid("resolved update schema must contain concrete properties");
        }
        Map<Field, BulkEditable> declarations = new LinkedHashMap<>();
        for (Class<?> type = updateType.getRawClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            JavaType resolvedType = updateType.findSuperType(type);
            if (resolvedType == null || type.getTypeParameters().length > resolvedType.getBindings().size()) {
                throw invalid("inherited update DTO generic parameters must be bound");
            }
            for (Field field : type.getDeclaredFields()) {
                BulkEditable annotation = field.getAnnotation(BulkEditable.class);
                if (annotation != null) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        throw invalid("bulk declarations require an instance DTO field");
                    }
                    declarations.put(field, annotation);
                }
            }
        }
        Map<Field, BeanPropertyDefinition> bindings = new HashMap<>();
        try {
            for (BeanPropertyDefinition property : mapper.getDeserializationConfig().introspect(updateType).findProperties()) {
                Field member = property.getField() != null
                        && property.getField().getMember() instanceof Field field ? field : recordField(updateType, property);
                if (member != null && declarations.containsKey(member)) {
                    if (bindings.put(member, property) != null) {
                        throw invalid("a DTO declaration maps to multiple JSON properties");
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            throw invalid("Jackson cannot resolve an unambiguous update property binding");
        }
        Map<BulkMode, Set<String>> writable = emptyAllowlists();
        Map<BulkMode, Set<String>> clearable = emptyAllowlists();
        Set<String> names = new HashSet<>();
        for (Map.Entry<Field, BulkEditable> entry : declarations.entrySet()) {
            Field field = entry.getKey();
            BulkEditable annotation = entry.getValue();
            BeanPropertyDefinition property = bindings.get(field);
            if (property == null || !property.couldDeserialize()) {
                throw invalid("annotated field is absent or not writable in the Jackson update contract");
            }
            String name = property.getName();
            if (name == null || name.isBlank() || !names.add(name) || protectedFields.contains(name)) {
                throw invalid("annotated wire field is blank, ambiguous or protected");
            }
            JsonNode schema = properties.path(name);
            requireResolved(schema, "annotated property schema");
            if (isTrue(schema, "readOnly") || isTrue(schema.path("x-ui"), "readOnly")
                    || isFalse(schema.path("x-ui"), "editable") || isTrue(schema.path("x-ui"), "disabled")
                    || isTrue(schema.path("x-ui"), "hidden") || isTrue(schema.path("x-ui"), "formHidden")) {
                throw invalid("annotated property is structurally read-only");
            }
            // Check canonical Java declarations too: do not accept an accidentally permissive
            // schema when the actual DTO contract expressly forbids input or null.
            List<AnnotatedMember> members = new java.util.ArrayList<>();
            for (AnnotatedMember member : new AnnotatedMember[]{property.getField(), property.getGetter(),
                    property.getSetter(), property.getConstructorParameter()}) {
                if (member != null) {
                    members.add(member);
                }
            }
            if (forbidsInput(field.getAnnotation(Schema.class), field.getAnnotation(UISchema.class),
                    field.getAnnotation(JsonProperty.class)) || members.stream().anyMatch(BulkEditableFields::forbidsInput)) {
                throw invalid("annotated DTO property forbids writable presentation or input");
            }
            Set<BulkMode> modes = new LinkedHashSet<>();
            for (BulkMode mode : annotation.modes()) {
                requireUpdateMode(mode);
                if (!modes.add(mode)) {
                    throw invalid("bulk update modes must not repeat");
                }
            }
            if (modes.isEmpty()) {
                throw invalid("at least one bulk update mode is required");
            }
            if (annotation.allowClear() && (!explicitlyNullable(schema, specVersion) || field.getType().isPrimitive()
                    || field.isAnnotationPresent(NotNull.class) || field.isAnnotationPresent(NotBlank.class)
                    || field.isAnnotationPresent(NotEmpty.class) || members.stream().anyMatch(BulkEditableFields::forbidsNull)
                    || changesOrRejectsNull(mapper, property))) {
                throw invalid("CLEAR requires explicit schema nullability and a nullable DTO field");
            }
            for (BulkMode mode : modes) {
                writable.get(mode).add(name);
                if (annotation.allowClear()) {
                    clearable.get(mode).add(name);
                }
            }
        }
        return new BulkEditableFields(writable, clearable);
    }

    /** Exact JSON property names, for use with {@link BulkFieldChanges#validate}. */
    public Set<String> writableFields(BulkMode mode) {
        requireUpdateMode(mode);
        return writable.get(mode);
    }

    /** Subset of writableFields that structurally permits CLEAR in this update family. */
    public Set<String> clearableFields(BulkMode mode) {
        requireUpdateMode(mode);
        return clearable.get(mode);
    }

    private static void requireResolved(JsonNode schema, String description) {
        if (schema == null || !schema.isObject()) {
            throw invalid(description + " must be a resolved schema object");
        }
        if (UNRESOLVED.stream().anyMatch(schema::has)) {
            throw invalid(description + " contains unresolved references or unsupported composition");
        }
    }

    private static boolean explicitlyNullable(JsonNode schema, SpecVersion specVersion) {
        boolean declaredNullable = specVersion == SpecVersion.V30 && isTrue(schema, "nullable");
        boolean nullable = false;
        JsonNode type = schema.path("type");
        if (type.isArray() && specVersion == SpecVersion.V31) {
            // In the union form, only an explicit null member admits null. An old nullable
            // flag must not override a 3.1 type union that excludes null.
            for (JsonNode item : type) {
                if (item.isTextual() && "null".equals(item.textValue())) {
                    nullable = true;
                }
            }
        } else if (type.isTextual()) {
            nullable = (specVersion == SpecVersion.V31 && "null".equals(type.textValue())) || (declaredNullable
                    && Set.of("string", "number", "integer", "boolean", "array", "object").contains(type.textValue()));
        }
        if (!nullable || (schema.has("const") && !schema.get("const").isNull())) {
            return false;
        }
        if (schema.has("enum")) {
            JsonNode values = schema.get("enum");
            if (!values.isArray()) {
                throw invalid("enum must be an array");
            }
            boolean containsNull = false;
            for (JsonNode value : values) {
                containsNull |= value.isNull();
            }
            return containsNull;
        }
        return true;
    }

    private static Field recordField(JavaType updateType, BeanPropertyDefinition property) {
        if (!updateType.isRecordType() || property.getConstructorParameter() == null) {
            return null;
        }
        var parameter = property.getConstructorParameter();
        if (!(parameter.getOwner().getMember() instanceof Constructor<?> constructor)
                || constructor.getDeclaringClass() != updateType.getRawClass()) {
            return null;
        }
        RecordComponent[] components = updateType.getRawClass().getRecordComponents();
        Class<?>[] canonicalTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        if (!Arrays.equals(constructor.getParameterTypes(), canonicalTypes)
                || parameter.getIndex() < 0 || parameter.getIndex() >= components.length) {
            return null;
        }
        // Records are creator-backed in Jackson 2.15: deserialize introspection deliberately
        // omits their fields. Bind the canonical constructor position to the source component;
        // the wire name still comes exclusively from BeanPropertyDefinition.
        try {
            return updateType.getRawClass().getDeclaredField(components[parameter.getIndex()].getName());
        } catch (NoSuchFieldException ex) {
            throw invalid("record component has no corresponding declared field");
        }
    }

    private static boolean changesOrRejectsNull(ObjectMapper mapper, BeanPropertyDefinition property) {
        Nulls nulls = property.getMetadata().getValueNulls();
        if (nulls != null && nulls != Nulls.DEFAULT && nulls != Nulls.SET) {
            return true;
        }
        return property.hasConstructorParameter()
                && mapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    }

    private static boolean forbidsInput(AnnotatedMember member) {
        Schema schema = member.getAnnotation(Schema.class);
        UISchema ui = member.getAnnotation(UISchema.class);
        JsonProperty property = member.getAnnotation(JsonProperty.class);
        return forbidsInput(schema, ui, property);
    }

    private static boolean forbidsInput(Schema schema, UISchema ui, JsonProperty property) {
        return (schema != null && (schema.hidden() || schema.readOnly()
                || schema.accessMode() == Schema.AccessMode.READ_ONLY))
                || (ui != null && (ui.readOnly() || !ui.editable() || ui.disabled() || ui.hidden() || ui.formHidden()))
                || (property != null && property.access() == JsonProperty.Access.READ_ONLY);
    }

    private static boolean forbidsNull(AnnotatedMember member) {
        return member.hasAnnotation(NotNull.class) || member.hasAnnotation(NotBlank.class)
                || member.hasAnnotation(NotEmpty.class);
    }

    private static boolean isTrue(JsonNode node, String key) {
        if (node.has(key) && !node.get(key).isBoolean()) {
            throw invalid(key + " must be boolean");
        }
        return node.path(key).isBoolean() && node.path(key).booleanValue();
    }

    private static boolean isFalse(JsonNode node, String key) {
        return node.has(key) && !isTrue(node, key);
    }

    private static void requireUpdateMode(BulkMode mode) {
        if (mode == null || !UPDATE_MODES.contains(mode)) {
            throw invalid("only UNIFORM_UPDATE and PER_ITEM_UPDATE accept editable fields");
        }
    }

    private static Map<BulkMode, Set<String>> emptyAllowlists() {
        Map<BulkMode, Set<String>> result = new EnumMap<>(BulkMode.class);
        UPDATE_MODES.forEach(mode -> result.put(mode, new LinkedHashSet<>()));
        return result;
    }

    private static Map<BulkMode, Set<String>> freeze(Map<BulkMode, Set<String>> values) {
        Map<BulkMode, Set<String>> result = new EnumMap<>(BulkMode.class);
        values.forEach((mode, fields) -> result.put(mode, Set.copyOf(fields)));
        return Map.copyOf(result);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid bulk editable fields: " + reason);
    }
}
