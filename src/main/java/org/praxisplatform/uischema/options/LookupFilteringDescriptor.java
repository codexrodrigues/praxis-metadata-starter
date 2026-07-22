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
        String searchPlaceholder,
        List<LookupSearchStrategyDefinition> searchStrategies
) {
    public LookupFilteringDescriptor(
            List<LookupFilterDefinition> availableFilters, Map<String, Object> defaultFilters,
            List<LookupSortOption> sortOptions, String defaultSort, List<String> quickFilterFields,
            String searchPlaceholder) {
        this(availableFilters, defaultFilters, sortOptions, defaultSort, quickFilterFields, searchPlaceholder, List.of());
    }

    public LookupFilteringDescriptor {
        availableFilters = availableFilters == null ? List.of() : List.copyOf(availableFilters);
        defaultFilters = defaultFilters == null || defaultFilters.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(defaultFilters));
        sortOptions = sortOptions == null ? List.of() : List.copyOf(sortOptions);
        defaultSort = normalize(defaultSort);
        quickFilterFields = normalizeList(quickFilterFields);
        searchPlaceholder = normalize(searchPlaceholder);
        searchStrategies = searchStrategies == null ? List.of() : List.copyOf(searchStrategies);
        validateStrategies(searchStrategies);
        validateDefaultSort(sortOptions, defaultSort);
    }

    public boolean isEmpty() {
        return availableFilters.isEmpty()
                && defaultFilters.isEmpty()
                && sortOptions.isEmpty()
                && defaultSort == null
                && quickFilterFields.isEmpty()
                && searchPlaceholder == null && searchStrategies.isEmpty();
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
        if (!searchStrategies.isEmpty()) {
            metadata.put("searchStrategies", searchStrategies.stream()
                    .map(LookupSearchStrategyDefinition::toMetadataMap).toList());
        }
        return metadata;
    }

    /**
     * Resolves the public strategy selected for a non-empty search term.
     * A single declared strategy is selected automatically; multiple strategies
     * require an explicit key so the provider never has to infer business intent.
     */
    public LookupSearchStrategyDefinition resolveSearchStrategy(String requestedKey, String search) {
        String key = normalize(requestedKey);
        if (search == null || search.isBlank()) {
            if (key != null) {
                throw new IllegalArgumentException("Lookup searchStrategy requires a non-empty search term.");
            }
            return null;
        }
        if (searchStrategies.isEmpty()) {
            if (key != null) {
                throw new IllegalArgumentException("Lookup searchStrategy is not declared for this option source.");
            }
            return null;
        }
        if (key == null) {
            if (searchStrategies.size() == 1) {
                return searchStrategies.getFirst();
            }
            throw new IllegalArgumentException("Lookup searchStrategy is required when multiple search strategies are declared.");
        }
        return searchStrategies.stream()
                .filter(strategy -> key.equals(strategy.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported lookup searchStrategy: " + key));
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

    private static void validateStrategies(List<LookupSearchStrategyDefinition> strategies) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (LookupSearchStrategyDefinition strategy : strategies) {
            if (strategy == null || !keys.add(strategy.key())) {
                throw new IllegalArgumentException("Lookup search strategies must be non-null and use unique keys.");
            }
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
