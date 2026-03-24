package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical description of a metadata-driven option source.
 */
public record OptionSourceDescriptor(
        String key,
        OptionSourceType type,
        String resourcePath,
        String filterField,
        String propertyPath,
        String labelPropertyPath,
        String valuePropertyPath,
        List<String> dependsOn,
        OptionSourcePolicy policy
) {
    public OptionSourceDescriptor {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Option source key is required.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Option source type is required.");
        }
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("Option source resourcePath is required.");
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        policy = policy == null ? OptionSourcePolicy.defaults() : policy;
        filterField = normalize(filterField);
        propertyPath = normalize(propertyPath);
        labelPropertyPath = normalize(labelPropertyPath);
        valuePropertyPath = normalize(valuePropertyPath);
    }

    public String effectiveFilterField() {
        return filterField != null ? filterField : key;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("type", type.name());
        metadata.put("resourcePath", resourcePath);
        if (filterField != null) {
            metadata.put("filterField", filterField);
        }
        if (!dependsOn.isEmpty()) {
            metadata.put("dependsOn", dependsOn);
        }
        metadata.put("excludeSelfField", policy.excludeSelfField());
        metadata.put("searchMode", policy.searchMode());
        metadata.put("pageSize", policy.defaultPageSize());
        metadata.put("includeIds", policy.allowIncludeIds());
        metadata.put("cachePolicy", policy.cacheable() ? "request-scope" : "none");
        return metadata;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
