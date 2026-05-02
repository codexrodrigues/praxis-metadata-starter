package org.praxisplatform.uischema.options;

import java.util.List;
import java.util.Map;

/**
 * Canonical structured filter request item for entity lookups.
 */
public record LookupFilterRequest(
        String field,
        String operator,
        Object value
) {

    public LookupFilterRequest {
        field = normalizeRequired(field, "Lookup filter request field is required.");
        operator = normalizeRequired(operator, "Lookup filter request operator is required.");
    }

    @SuppressWarnings("unchecked")
    public List<Object> values() {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (value.getClass().isArray()) {
            return List.of((Object[]) value);
        }
        if (value instanceof Map<?, ?> map) {
            Object from = map.get("from");
            Object to = map.get("to");
            if (from != null || to != null) {
                return List.of(from, to);
            }
        }
        return List.of(value);
    }

    private static String normalizeRequired(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
