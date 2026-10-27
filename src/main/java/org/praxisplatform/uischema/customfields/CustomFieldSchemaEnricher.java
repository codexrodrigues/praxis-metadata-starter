package org.praxisplatform.uischema.customfields;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.util.OpenApiUiUtils;
import java.util.*;

/** Projects one immutable provider snapshot into the canonical structural schema. */
public final class CustomFieldSchemaEnricher {
    private final CustomFieldDefinitionProvider provider;
    private final ObjectMapper mapper;

    public CustomFieldSchemaEnricher(CustomFieldDefinitionProvider provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    /** Returns the fingerprint of the exact definitions used for this projection. */
    public String enrich(Map<String, Object> schema, String resourceKey, String tenant, String schemaType) {
        return enrich(schema, resourceKey, tenant, schemaType, false);
    }

    /** Filter projection is selected from the resolved MVC body type, independently of provider role. */
    @SuppressWarnings("unchecked")
    public String enrich(Map<String, Object> schema, String resourceKey, String tenant, String schemaType,
            boolean filterRequest) {
        if (!"request".equalsIgnoreCase(schemaType) && !"response".equalsIgnoreCase(schemaType))
            throw new IllegalArgumentException("schemaType must be request or response");
        String role = schemaType.toLowerCase(Locale.ROOT);
        if (filterRequest && !"request".equals(role))
            throw new IllegalArgumentException("Filter projection requires a request schema");
        List<CustomFieldDescriptor> definitions = resourceKey == null || resourceKey.isBlank()
                ? List.of() : List.copyOf(provider.findDefinitions(resourceKey, tenant, role));
        Set<String> definitionKeys = new HashSet<>();
        for (CustomFieldDescriptor definition : definitions) {
            if (!definitionKeys.add(definition.key()))
                throw new IllegalArgumentException("Duplicate custom field definition: " + definition.key());
        }
        if (filterRequest) definitions = definitions.stream().filter(CustomFieldDescriptor::filterable).toList();
        else if ("request".equals(role)) definitions = definitions.stream().filter(d -> !d.readOnly()).toList();
        if (definitions.isEmpty()) {
            boolean hadContainer = schema.get("properties") instanceof Map<?, ?> existing && existing.containsKey("customFields");
            if (hadContainer) ((Map<String, Object>) schema.get("properties")).remove("customFields");
            boolean requiredContainer = schema.get("required") instanceof Collection<?> required && required.contains("customFields");
            if (hadContainer || requiredContainer) requireContainer(schema, false);
            return "";
        }
        Map<String, Object> properties = (Map<String, Object>) schema.computeIfAbsent("properties", ignored -> new LinkedHashMap<>());
        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (CustomFieldDescriptor descriptor : definitions.stream()
                .sorted(Comparator.comparingInt(CustomFieldDescriptor::order).thenComparing(CustomFieldDescriptor::key)).toList()) {
            Map<String, Object> projected = filterRequest ? filterSchema(descriptor) : fieldSchema(descriptor);
            if (fields.putIfAbsent(descriptor.key(), projected) != null) {
                throw new IllegalArgumentException("Duplicate custom field definition: " + descriptor.key());
            }
            if (!filterRequest && descriptor.required() && !("request".equals(role) && descriptor.readOnly()))
                required.add(descriptor.key());
        }
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("type", "object");
        container.put("additionalProperties", false);
        container.put("x-ui", Map.of("customFieldsContainer", true));
        container.put("properties", fields);
        if (!required.isEmpty()) container.put("required", required);
        properties.put("customFields", container);
        boolean staticallyRequired = schema.get("required") instanceof Collection<?> existing
                && existing.contains("customFields");
        requireContainer(schema, !filterRequest && (staticallyRequired || !required.isEmpty()));
        return SchemaHashUtil.sha256Hex(new org.praxisplatform.uischema.hash.SchemaCanonicalizer().canonicalize(mapper.valueToTree(container)));
    }

    private static void requireContainer(Map<String, Object> schema, boolean required) {
        List<Object> keys = new ArrayList<>();
        if (schema.get("required") instanceof Collection<?> existing)
            existing.stream().filter(key -> !"customFields".equals(key)).forEach(keys::add);
        if (required) keys.add("customFields");
        if (keys.isEmpty()) schema.remove("required");
        else schema.put("required", keys);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterSchema(CustomFieldDescriptor d) {
        Map<String, Object> projected = fieldSchema(d);
        projected.keySet().removeAll(Set.of("minimum", "maximum", "minLength", "maxLength", "pattern", "default", "readOnly"));
        Map<String, Object> ui = (Map<String, Object>) projected.get("x-ui");
        ui.keySet().removeAll(Set.of("min", "max", "minLength", "maxLength", "pattern", "patternMessage",
                "requiredMessage", "defaultValue"));
        ui.put("required", false);
        ui.put("readOnly", false);
        Map<String, Object> operand = new LinkedHashMap<>();
        for (String key : List.of("type", "format", "items", "enum"))
            if (projected.containsKey(key)) operand.put(key, projected.remove(key));
        // Null omits a predicate. IN accepts an empty collection and validates each scalar item.
        // Enum membership remains part of the runtime scalar type, including LIKE operands.
        boolean scalarSupported = d.dataType() != CustomFieldDataType.ARRAY;
        Map<String, Object> value = switch (d.filterOperation()) {
            case IS_NULL -> Map.of("type", "boolean");
            case IN -> scalarSupported ? Map.of("type", "array", "items", operand) : null;
            case EQUAL -> scalarSupported ? operand : null;
            case LIKE -> d.dataType() == CustomFieldDataType.STRING || d.dataType() == CustomFieldDataType.ENUM ? operand : null;
            case GREATER_OR_EQUAL, LESS_OR_EQUAL -> scalarSupported && d.dataType() != CustomFieldDataType.BOOLEAN ? operand : null;
            default -> null;
        };
        if (d.filterOperation() == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.IS_NULL) {
            ui.remove("options");
            ui.remove("optionSource");
            ui.put("controlType", org.praxisplatform.uischema.FieldControlType.CHECKBOX.getValue());
        }
        if (value == null) projected.put("type", "null");
        else {
            projected.putAll(value);
            projected.put("type", List.of(value.get("type"), "null"));
            if (value.get("enum") instanceof Collection<?> values) {
                List<Object> nullableValues = new ArrayList<>(values);
                nullableValues.add(null);
                projected.put("enum", nullableValues);
            }
        }
        if (value != null && d.controlType() == FieldControlType.AUTO)
            ui.put("controlType", inferredControlType(value));
        return projected;
    }

    public Map<String, Object> fieldSchema(CustomFieldDescriptor d) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", switch (d.dataType()) {
            case INTEGER -> "integer";
            case DECIMAL -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY -> "array";
            default -> "string";
        });
        if (d.dataType() == CustomFieldDataType.DATE) field.put("format", "date");
        if (d.dataType() == CustomFieldDataType.DATE_TIME) field.put("format", "date-time");
        if (d.dataType() == CustomFieldDataType.ARRAY) field.put("items", Map.of());
        put(field, "description", d.description());
        put(field, "minimum", d.minimum()); put(field, "maximum", d.maximum());
        put(field, "minLength", d.minLength()); put(field, "maxLength", d.maxLength());
        put(field, "pattern", d.pattern()); put(field, "default", d.defaultValue());
        field.put("readOnly", d.readOnly());
        if (!d.enumValues().isEmpty()) field.put("enum", d.enumValues());
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("customField", true); ui.put("customFieldKey", d.key()); ui.put("customFieldScope", "tenant");
        put(ui, "label", d.label()); put(ui, "description", d.description());
        ui.put("controlType", d.controlType() == FieldControlType.AUTO
                ? inferredControlType(field) : d.controlType().getValue());
        put(ui, "group", d.group()); ui.put("order", d.order()); ui.put("required", d.required());
        ui.put("readOnly", d.readOnly()); ui.put("filterable", d.filterable());
        ui.put("sortable", d.sortable()); ui.put("exportable", d.exportable());
        put(ui, "min", d.minimum()); put(ui, "max", d.maximum());
        put(ui, "minLength", d.minLength()); put(ui, "maxLength", d.maxLength());
        put(ui, "pattern", d.pattern()); put(ui, "patternMessage", d.patternMessage());
        put(ui, "requiredMessage", d.requiredMessage()); put(ui, "defaultValue", d.defaultValue());
        if (!d.options().isEmpty()) ui.put("options", d.options());
        if (d.optionSource() != null) ui.put("optionSource", d.optionSource().toMetadataMap());
        if (!d.governance().isEmpty()) field.put("x-domain-governance", d.governance());
        field.put("x-ui", ui);
        return field;
    }

    private static String inferredControlType(Map<String, Object> schema) {
        Object items = schema.get("items");
        boolean itemEnum = items instanceof Map<?, ?> item && item.get("enum") instanceof Collection<?> values
                && !values.isEmpty();
        return OpenApiUiUtils.determineBasicControlType((String) schema.get("type"),
                (String) schema.get("format"), schema.get("enum") instanceof Collection<?> values && !values.isEmpty(),
                (Integer) schema.get("maxLength"), "array".equals(schema.get("type")), itemEnum);
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
