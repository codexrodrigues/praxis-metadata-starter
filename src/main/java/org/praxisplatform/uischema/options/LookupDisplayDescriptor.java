package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Presentation contract for entity lookup references.
 *
 * <p>The backend publishes semantic intent, not CSS decisions. Runtimes can then
 * materialize the same lookup as a form field, inline filter, table-cell editor
 * or rich review card without each host inventing local display conventions.</p>
 */
public record LookupDisplayDescriptor(
        String preset,
        String usage,
        String density,
        String selectedLayout,
        String resultLayout,
        String primaryPropertyPath,
        List<LookupDisplayFieldDescriptor> fields,
        List<String> secondaryPropertyPaths,
        List<String> badgePropertyPaths,
        String avatarPropertyPath,
        Boolean showAvatar,
        Boolean showCode,
        Boolean showDescription,
        Boolean showStatus,
        Boolean showBadges,
        Boolean showResultCount,
        Integer maxVisibleBadges
) {

    public LookupDisplayDescriptor {
        preset = normalize(preset);
        usage = normalize(usage);
        density = normalize(density);
        selectedLayout = normalize(selectedLayout);
        resultLayout = normalize(resultLayout);
        primaryPropertyPath = normalize(primaryPropertyPath);
        fields = normalizeFields(fields);
        secondaryPropertyPaths = normalizeList(secondaryPropertyPaths);
        badgePropertyPaths = normalizeList(badgePropertyPaths);
        avatarPropertyPath = normalize(avatarPropertyPath);
    }

    public boolean isEmpty() {
        return preset == null
                && usage == null
                && density == null
                && selectedLayout == null
                && resultLayout == null
                && primaryPropertyPath == null
                && fields.isEmpty()
                && secondaryPropertyPaths.isEmpty()
                && badgePropertyPaths.isEmpty()
                && avatarPropertyPath == null
                && showAvatar == null
                && showCode == null
                && showDescription == null
                && showStatus == null
                && showBadges == null
                && showResultCount == null
                && maxVisibleBadges == null;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(metadata, "preset", preset);
        putIfNotNull(metadata, "usage", usage);
        putIfNotNull(metadata, "density", density);
        putIfNotNull(metadata, "selectedLayout", selectedLayout);
        putIfNotNull(metadata, "resultLayout", resultLayout);
        putIfNotNull(metadata, "primaryPropertyPath", primaryPropertyPath);
        if (!fields.isEmpty()) {
            metadata.put("fields", fields.stream()
                    .map(LookupDisplayFieldDescriptor::toMetadataMap)
                    .toList());
        }
        if (!secondaryPropertyPaths.isEmpty()) {
            metadata.put("secondaryPropertyPaths", secondaryPropertyPaths);
        }
        if (!badgePropertyPaths.isEmpty()) {
            metadata.put("badgePropertyPaths", badgePropertyPaths);
        }
        putIfNotNull(metadata, "avatarPropertyPath", avatarPropertyPath);
        putIfNotNull(metadata, "showAvatar", showAvatar);
        putIfNotNull(metadata, "showCode", showCode);
        putIfNotNull(metadata, "showDescription", showDescription);
        putIfNotNull(metadata, "showStatus", showStatus);
        putIfNotNull(metadata, "showBadges", showBadges);
        putIfNotNull(metadata, "showResultCount", showResultCount);
        putIfNotNull(metadata, "maxVisibleBadges", maxVisibleBadges);
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

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(LookupDisplayDescriptor::normalize)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private static List<LookupDisplayFieldDescriptor> normalizeFields(List<LookupDisplayFieldDescriptor> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isEmpty())
                .toList();
    }
}
