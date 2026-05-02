package org.praxisplatform.uischema.options;

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
        boolean required,
        boolean hidden
) {

    private static final Map<String, List<String>> ALLOWED_OPERATORS_BY_TYPE = Map.of(
            "text", List.of("contains", "startsWith", "equals"),
            "enum", List.of("equals", "in"),
            "date", List.of("before", "after", "between"),
            "number", List.of("equals", "gt", "gte", "lt", "lte", "between"),
            "reference", List.of("equals", "in")
    );

    public LookupFilterDefinition {
        field = normalizeRequired(field, "Lookup filter field is required.");
        label = normalize(label);
        type = normalizeRequired(type, "Lookup filter type is required.");
        type = normalizeType(type);
        operators = normalizeOperators(type, operators);
        defaultOperator = normalizeDefaultOperator(type, operators, defaultOperator);
        optionsSource = normalize(optionsSource);
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
        metadata.put("required", required);
        metadata.put("hidden", hidden);
        return metadata;
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
