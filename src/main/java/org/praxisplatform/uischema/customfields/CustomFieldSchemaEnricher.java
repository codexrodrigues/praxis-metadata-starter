package org.praxisplatform.uischema.customfields;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
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
    @SuppressWarnings("unchecked")
    public String enrich(Map<String, Object> schema, String resourceKey, String tenant, String schemaType) {
        List<CustomFieldDescriptor> definitions = resourceKey == null || resourceKey.isBlank()
                ? List.of() : List.copyOf(provider.findDefinitions(resourceKey, tenant, schemaType));
        if (definitions.isEmpty() && (!(schema.get("properties") instanceof Map<?, ?> existing)
                || !existing.containsKey("customFields"))) return "";
        Map<String, Object> properties = (Map<String, Object>) schema.computeIfAbsent("properties", ignored -> new LinkedHashMap<>());
        if (definitions.isEmpty()) {
            properties.remove("customFields");
            if (schema.get("required") instanceof List<?> required) {
                List<?> remaining = required.stream().filter(key -> !"customFields".equals(key)).toList();
                if (remaining.isEmpty()) schema.remove("required");
                else schema.put("required", remaining);
            }
            return "";
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (CustomFieldDescriptor descriptor : definitions.stream()
                .sorted(Comparator.comparingInt(CustomFieldDescriptor::order).thenComparing(CustomFieldDescriptor::key)).toList()) {
            Map<String, Object> projected = fieldSchema(descriptor);
            if ("request".equals(schemaType) && descriptor.readOnly()) {
                ((Map<String, Object>) projected.get("x-ui")).put("required", false);
            }
            if (fields.putIfAbsent(descriptor.key(), projected) != null) {
                throw new IllegalArgumentException("Duplicate custom field definition: " + descriptor.key());
            }
            if (descriptor.required() && !("request".equals(schemaType) && descriptor.readOnly())) required.add(descriptor.key());
        }
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("type", "object");
        container.put("additionalProperties", false);
        container.put("x-ui", Map.of("customFieldsContainer", true));
        container.put("properties", fields);
        if (!required.isEmpty()) container.put("required", required);
        properties.put("customFields", container);
        return SchemaHashUtil.sha256Hex(new org.praxisplatform.uischema.hash.SchemaCanonicalizer().canonicalize(mapper.valueToTree(container)));
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
        if (d.controlType() != null) ui.put("controlType", d.controlType().getValue());
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

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
