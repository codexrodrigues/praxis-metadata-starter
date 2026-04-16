package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity semantics attached to an option-source when it represents a business lookup.
 */
public record EntityLookupDescriptor(
        String entityKey,
        String codePropertyPath,
        List<String> descriptionPropertyPaths,
        String statusPropertyPath,
        String disabledPropertyPath,
        String disabledReasonPropertyPath,
        List<String> searchPropertyPaths,
        Map<String, String> dependencyFilterMap,
        LookupSelectionPolicy selectionPolicy,
        LookupCapabilities capabilities,
        LookupDetailDescriptor detail
) {

    public EntityLookupDescriptor {
        entityKey = normalize(entityKey);
        codePropertyPath = normalize(codePropertyPath);
        descriptionPropertyPaths = normalizeList(descriptionPropertyPaths);
        statusPropertyPath = normalize(statusPropertyPath);
        disabledPropertyPath = normalize(disabledPropertyPath);
        disabledReasonPropertyPath = normalize(disabledReasonPropertyPath);
        searchPropertyPaths = normalizeList(searchPropertyPaths);
        dependencyFilterMap = normalizeMap(dependencyFilterMap);
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (entityKey != null) {
            metadata.put("entityKey", entityKey);
        }
        if (codePropertyPath != null) {
            metadata.put("codePropertyPath", codePropertyPath);
        }
        if (!descriptionPropertyPaths.isEmpty()) {
            metadata.put("descriptionPropertyPaths", descriptionPropertyPaths);
        }
        if (statusPropertyPath != null) {
            metadata.put("statusPropertyPath", statusPropertyPath);
        }
        if (disabledPropertyPath != null) {
            metadata.put("disabledPropertyPath", disabledPropertyPath);
        }
        if (disabledReasonPropertyPath != null) {
            metadata.put("disabledReasonPropertyPath", disabledReasonPropertyPath);
        }
        if (!searchPropertyPaths.isEmpty()) {
            metadata.put("searchPropertyPaths", searchPropertyPaths);
        }
        if (!dependencyFilterMap.isEmpty()) {
            metadata.put("dependencyFilterMap", dependencyFilterMap);
        }
        if (selectionPolicy != null && !selectionPolicy.isEmpty()) {
            metadata.put("selectionPolicy", selectionPolicy.toMetadataMap());
        }
        if (capabilities != null) {
            metadata.put("capabilities", capabilities.toMetadataMap());
        }
        if (detail != null && !detail.isEmpty()) {
            metadata.put("detail", detail.toMetadataMap());
        }
        return metadata;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(EntityLookupDescriptor::normalize)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private static Map<String, String> normalizeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = normalize(key);
            String normalizedValue = normalize(value);
            if (normalizedKey != null && normalizedValue != null) {
                normalized.put(normalizedKey, normalizedValue);
            }
        });
        return Map.copyOf(normalized);
    }
}
