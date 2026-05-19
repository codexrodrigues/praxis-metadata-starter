package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Semantic field projected inside a rich entity lookup option.
 */
public record LookupDisplayFieldDescriptor(
        String key,
        String propertyPath,
        String label,
        String icon,
        String presentation,
        String tone,
        String format
) {

    public LookupDisplayFieldDescriptor {
        key = normalize(key);
        propertyPath = normalize(propertyPath);
        label = normalize(label);
        icon = normalize(icon);
        presentation = normalize(presentation);
        tone = normalize(tone);
        format = normalize(format);
    }

    public boolean isEmpty() {
        return key == null
                && propertyPath == null
                && label == null
                && icon == null
                && presentation == null
                && tone == null
                && format == null;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(metadata, "key", key);
        putIfNotNull(metadata, "propertyPath", propertyPath);
        putIfNotNull(metadata, "label", label);
        putIfNotNull(metadata, "icon", icon);
        putIfNotNull(metadata, "presentation", presentation);
        putIfNotNull(metadata, "tone", tone);
        putIfNotNull(metadata, "format", format);
        return metadata;
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
