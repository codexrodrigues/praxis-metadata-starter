package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Rich filtering contract for entity lookups.
 */
public record LookupFilteringDescriptor(
        List<LookupFilterDefinition> availableFilters,
        Map<String, Object> defaultFilters,
        List<LookupSortOption> sortOptions,
        String defaultSort,
        List<String> quickFilterFields,
        String searchPlaceholder
) {

    public LookupFilteringDescriptor {
        availableFilters = availableFilters == null ? List.of() : List.copyOf(availableFilters);
        defaultFilters = defaultFilters == null || defaultFilters.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(defaultFilters));
        sortOptions = sortOptions == null ? List.of() : List.copyOf(sortOptions);
        defaultSort = normalize(defaultSort);
        quickFilterFields = normalizeList(quickFilterFields);
        searchPlaceholder = normalize(searchPlaceholder);
        validateDefaultSort(sortOptions, defaultSort);
    }

    public boolean isEmpty() {
        return availableFilters.isEmpty()
                && defaultFilters.isEmpty()
                && sortOptions.isEmpty()
                && defaultSort == null
                && quickFilterFields.isEmpty()
                && searchPlaceholder == null;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!availableFilters.isEmpty()) {
            metadata.put("availableFilters", availableFilters.stream()
                    .map(LookupFilterDefinition::toMetadataMap)
                    .toList());
        }
        if (!defaultFilters.isEmpty()) {
            metadata.put("defaultFilters", defaultFilters);
        }
        if (!sortOptions.isEmpty()) {
            metadata.put("sortOptions", sortOptions.stream()
                    .map(LookupSortOption::toMetadataMap)
                    .toList());
        }
        if (defaultSort != null) {
            metadata.put("defaultSort", defaultSort);
        }
        if (!quickFilterFields.isEmpty()) {
            metadata.put("quickFilterFields", quickFilterFields);
        }
        if (searchPlaceholder != null) {
            metadata.put("searchPlaceholder", searchPlaceholder);
        }
        return metadata;
    }

    private static void validateDefaultSort(List<LookupSortOption> sortOptions, String defaultSort) {
        if (defaultSort == null) {
            return;
        }
        boolean supported = sortOptions.stream()
                .map(LookupSortOption::key)
                .anyMatch(defaultSort::equals);
        if (!supported) {
            throw new IllegalArgumentException("Lookup defaultSort must reference a declared sort option key.");
        }
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.forEach(value -> {
            String candidate = normalize(value);
            if (candidate != null) {
                normalized.add(candidate);
            }
        });
        return List.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
