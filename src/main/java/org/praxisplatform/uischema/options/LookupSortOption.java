package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sort option published by an entity lookup for structured search flows.
 */
public record LookupSortOption(
        String key,
        String field,
        String direction,
        String label
) {

    public LookupSortOption {
        key = normalizeRequired(key, "Lookup sort key is required.");
        field = normalizeRequired(field, "Lookup sort field is required.");
        direction = normalize(direction);
        direction = direction == null ? "asc" : direction;
        if (!"asc".equals(direction) && !"desc".equals(direction)) {
            throw new IllegalArgumentException("Lookup sort direction must be 'asc' or 'desc'.");
        }
        label = normalize(label);
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("field", field);
        metadata.put("direction", direction);
        if (label != null) {
            metadata.put("label", label);
        }
        return metadata;
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
