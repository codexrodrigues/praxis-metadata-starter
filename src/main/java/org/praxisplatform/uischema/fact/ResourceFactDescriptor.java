package org.praxisplatform.uischema.fact;

import java.util.*;

/** Metadata only: providers describe facts without evaluating them. */
public record ResourceFactDescriptor(String key, String resourceKey, String label, String description,
        String dataType, String unit, Map<String, Object> parameters, boolean deterministic,
        String sourceKind, Map<String, Object> governance,
        String icon, String tone, String valuePresentation, List<Map<String, Object>> thresholds) {
    public ResourceFactDescriptor(String key, String resourceKey, String label, String description,
            String dataType, String unit, Map<String, Object> parameters, boolean deterministic,
            String sourceKind, Map<String, Object> governance) {
        this(key, resourceKey, label, description, dataType, unit, parameters, deterministic,
                sourceKind, governance, "", "", "", List.of());
    }
    public ResourceFactDescriptor {
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid resource fact key");
        if (resourceKey == null || resourceKey.isBlank())
            throw new IllegalArgumentException("Resource fact resourceKey is required");
        if (dataType == null || dataType.isBlank())
            throw new IllegalArgumentException("Resource fact dataType is required");
        parameters = parameters == null ? Map.of() : immutableMap(parameters);
        governance = governance == null ? Map.of() : immutableMap(governance);
        icon = icon == null ? "" : icon;
        tone = tone == null ? "" : tone;
        valuePresentation = valuePresentation == null ? "" : valuePresentation;
        thresholds = thresholds == null ? List.of() : thresholds.stream().map(ResourceFactDescriptor::immutableMap).toList();
    }
    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> copy.put(Objects.requireNonNull(key), immutableValue(item)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, immutableValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) return collection.stream().map(ResourceFactDescriptor::immutableValue).toList();
        if (value != null && value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) copy.add(immutableValue(java.lang.reflect.Array.get(value, i)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
