package org.praxisplatform.uischema.customfields;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.validation.FieldError;

/** Validates writes against the active operation and tenant definitions before resource hooks. */
public class CustomFieldValidator {
    private final CustomFieldDefinitionProvider definitions;
    public CustomFieldValidator(CustomFieldDefinitionProvider definitions) { this.definitions = Objects.requireNonNull(definitions); }
    public void validate(Object dto, String resourceKey, String tenantId, String schemaType) {
        if (dto == null || CustomFieldSupport.property(dto.getClass()).isEmpty()) return;
        String effectiveResource = CustomFieldSupport.resourceKey(dto.getClass(), resourceKey);
        validateValues(CustomFieldSupport.read(dto), definitions.findDefinitions(effectiveResource, tenantId, schemaType));
    }
    public void validate(Object dto, String resourceKey, String tenantId) { validate(dto, resourceKey, tenantId, null); }
    public void validateValues(Map<String, Object> values, List<CustomFieldDescriptor> active) {
        Map<String, CustomFieldDescriptor> byKey = new LinkedHashMap<>();
        for (CustomFieldDescriptor descriptor : active) {
            if (byKey.putIfAbsent(descriptor.key(), descriptor) != null) throw new IllegalStateException("Duplicate custom field definition: " + descriptor.key());
        }
        List<FieldError> errors = new ArrayList<>();
        values.forEach((key, value) -> { if (!byKey.containsKey(key)) error(errors, key, "Unknown custom field"); });
        for (CustomFieldDescriptor d : byKey.values()) {
            Object value = values.get(d.key());
            if (d.readOnly()) {
                if (values.containsKey(d.key())) error(errors, d.key(), "Custom field is read-only");
                continue;
            }
            if (value == null) {
                if (d.required()) error(errors, d.key(), message(d.requiredMessage(), "Required custom field"));
                continue;
            }
            if (!matchesType(d, value)) { error(errors, d.key(), "Invalid custom field type: " + d.dataType()); continue; }
            if (value instanceof Number number) {
                BigDecimal decimal = new BigDecimal(number.toString());
                if (d.minimum() != null && decimal.compareTo(d.minimum()) < 0) error(errors, d.key(), "Value is below minimum");
                if (d.maximum() != null && decimal.compareTo(d.maximum()) > 0) error(errors, d.key(), "Value exceeds maximum");
            }
            if (value instanceof String text) {
                int length = text.codePointCount(0, text.length());
                if (d.minLength() != null && length < d.minLength()) error(errors, d.key(), "Value is shorter than minLength");
                if (d.maxLength() != null && length > d.maxLength()) error(errors, d.key(), "Value exceeds maxLength");
                if (d.pattern() != null && !Pattern.compile(d.pattern()).matcher(text).matches())
                    error(errors, d.key(), message(d.patternMessage(), "Value does not match pattern"));
            }
        }
        if (!errors.isEmpty()) throw new CustomFieldValidationException(errors);
    }
    public static boolean matchesType(CustomFieldDescriptor d, Object value) {
        try {
            return switch (d.dataType()) {
                case STRING -> value instanceof String;
                case INTEGER -> value instanceof Number n && new BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0;
                case DECIMAL -> value instanceof Number n && new BigDecimal(n.toString()) != null;
                case BOOLEAN -> value instanceof Boolean;
                case DATE -> value instanceof LocalDate || value instanceof String s && LocalDate.parse(s) != null;
                case DATE_TIME -> value instanceof Instant || value instanceof OffsetDateTime || value instanceof LocalDateTime
                        || value instanceof String s && validDateTime(s);
                case ENUM -> value instanceof String s && d.enumValues().contains(s);
                case ARRAY -> value instanceof Collection<?> || value.getClass().isArray();
            };
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) { return false; }
    }
    private static boolean validDateTime(String value) {
        try { OffsetDateTime.parse(value); return true; }
        catch (java.time.format.DateTimeParseException ex) { LocalDateTime.parse(value); return true; }
    }
    private static String message(String configured, String fallback) { return configured == null || configured.isBlank() ? fallback : configured; }
    private static void error(List<FieldError> errors, String key, String message) {
        errors.add(new FieldError("customFields", "customFields." + key, message));
    }
}
