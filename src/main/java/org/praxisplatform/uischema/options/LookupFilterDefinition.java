package org.praxisplatform.uischema.options;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Structured filter definition published by an entity lookup.
 */
public record LookupFilterDefinition(
        String field,
        String label,
        String type,
        List<String> operators,
        String defaultOperator,
        String optionsSource,
        List<Map<String, Object>> options,
        Map<String, Object> optionSource,
        List<String> dependsOn,
        Map<String, String> dependencyFilterMap,
        boolean required,
        boolean hidden,
        String targetPropertyPath
) {

    private static final Map<String, List<String>> ALLOWED_OPERATORS_BY_TYPE = Map.of(
            "text", List.of("contains", "startsWith", "equals"),
            "enum", List.of("equals", "in"),
            "date", List.of("before", "after", "between"),
            "number", List.of("equals", "gt", "gte", "lt", "lte", "between"),
            "reference", List.of("equals", "in")
    );

    public LookupFilterDefinition(
            String field,
            String label,
            String type,
            List<String> operators,
            String defaultOperator,
            String optionsSource,
            boolean required,
            boolean hidden
    ) {
        this(
                field,
                label,
                type,
                operators,
                defaultOperator,
                optionsSource,
                List.of(),
                Map.of(),
                List.of(),
                Map.of(),
                required,
                hidden,
                null
        );
    }

    public LookupFilterDefinition(
            String field,
            String label,
            String type,
            List<String> operators,
            String defaultOperator,
            String optionsSource,
            List<Map<String, Object>> options,
            Map<String, Object> optionSource,
            List<String> dependsOn,
            Map<String, String> dependencyFilterMap,
            boolean required,
            boolean hidden
    ) {
        this(
                field,
                label,
                type,
                operators,
                defaultOperator,
                optionsSource,
                options,
                optionSource,
                dependsOn,
                dependencyFilterMap,
                required,
                hidden,
                null
        );
    }

    public LookupFilterDefinition {
        field = normalizeRequired(field, "Lookup filter field is required.");
        label = normalize(label);
        type = normalizeRequired(type, "Lookup filter type is required.");
        type = normalizeType(type);
        operators = normalizeOperators(type, operators);
        defaultOperator = normalizeDefaultOperator(type, operators, defaultOperator);
        optionsSource = normalize(optionsSource);
        options = normalizeOptions(options);
        optionSource = normalizeObjectMap(optionSource);
        dependsOn = normalizeList(dependsOn);
        dependencyFilterMap = normalizeStringMap(dependencyFilterMap);
        validateDependencyMap(field, dependsOn, dependencyFilterMap);
        targetPropertyPath = normalize(targetPropertyPath);
    }

    /**
     * Returns the JPA entity property path used when evaluating this filter against Criteria queries,
     * falling back to {@link #field()} when no explicit {@code targetPropertyPath} was bound.
     */
    public String effectiveEntityPropertyPath() {
        return targetPropertyPath != null ? targetPropertyPath : field;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("field", field);
        if (label != null) {
            metadata.put("label", label);
        }
        metadata.put("type", type);
        metadata.put("operators", operators);
        metadata.put("defaultOperator", defaultOperator);
        if (optionsSource != null) {
            metadata.put("optionsSource", optionsSource);
        }
        if (!options.isEmpty()) {
            metadata.put("options", options);
        }
        if (!optionSource.isEmpty()) {
            metadata.put("optionSource", optionSource);
        }
        if (!dependsOn.isEmpty()) {
            metadata.put("dependsOn", dependsOn);
        }
        if (!dependencyFilterMap.isEmpty()) {
            metadata.put("dependencyFilterMap", dependencyFilterMap);
        }
        metadata.put("required", required);
        metadata.put("hidden", hidden);
        return metadata;
    }

    private static void validateDependencyMap(
            String field,
            List<String> dependsOn,
            Map<String, String> dependencyFilterMap
    ) {
        if (dependencyFilterMap.isEmpty()) {
            return;
        }
        for (String key : dependencyFilterMap.keySet()) {
            if (!dependsOn.contains(key)) {
                throw new IllegalArgumentException(
                        "Lookup filter dependencyFilterMap key must be declared in dependsOn: " + field + "." + key
                );
            }
        }
    }

    private static List<Map<String, Object>> normalizeOptions(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new java.util.ArrayList<>();
        for (Map<String, Object> entry : values) {
            if (entry != null && !entry.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>();
                entry.forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null) {
                        copy.put(k.trim(), v);
                    }
                });
                if (!copy.isEmpty()) {
                    normalized.add(Collections.unmodifiableMap(copy));
                }
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private static Map<String, Object> normalizeObjectMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            String key = normalize(k);
            if (key != null && v != null) {
                copy.put(key, v);
            }
        });
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalize(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private static Map<String, String> normalizeStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((rawKey, rawValue) -> {
            String key = normalize(rawKey);
            String value = normalize(rawValue);
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        });
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private static String normalizeType(String value) {
        if (!ALLOWED_OPERATORS_BY_TYPE.containsKey(value)) {
            throw new IllegalArgumentException("Unsupported lookup filter type: " + value);
        }
        return value;
    }

    private static List<String> normalizeOperators(String type, List<String> values) {
        List<String> allowed = ALLOWED_OPERATORS_BY_TYPE.get(type);
        if (values == null || values.isEmpty()) {
            return allowed;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String operator = normalizeRequired(value, "Lookup filter operator is required.");
            if (!allowed.contains(operator)) {
                throw new IllegalArgumentException(
                        "Unsupported lookup filter operator '%s' for type '%s'.".formatted(operator, type)
                );
            }
            normalized.add(operator);
        }
        return List.copyOf(normalized);
    }

    private static String normalizeDefaultOperator(String type, List<String> operators, String value) {
        if (value == null || value.isBlank()) {
            return operators.get(0);
        }
        String normalized = value.trim();
        if (!operators.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Default operator '%s' is not allowed for type '%s'.".formatted(normalized, type)
            );
        }
        return normalized;
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
