package org.praxisplatform.uischema.options;

/**
 * Public execution policy for a registered option source.
 */
public record OptionSourcePolicy(
        boolean excludeSelfField,
        boolean allowSearch,
        String searchMode,
        int minSearchChars,
        int defaultPageSize,
        int maxPageSize,
        boolean allowIncludeIds,
        boolean cacheable,
        String defaultSort
) {
    public OptionSourcePolicy {
        searchMode = searchMode == null || searchMode.isBlank() ? "contains" : searchMode;
        minSearchChars = Math.max(0, minSearchChars);
        defaultPageSize = defaultPageSize <= 0 ? 25 : defaultPageSize;
        maxPageSize = maxPageSize <= 0 ? 100 : maxPageSize;
        if (defaultPageSize > maxPageSize) {
            defaultPageSize = maxPageSize;
        }
        defaultSort = defaultSort == null || defaultSort.isBlank() ? "label" : defaultSort;
    }

    public static OptionSourcePolicy defaults() {
        return new OptionSourcePolicy(false, true, "contains", 0, 25, 100, true, false, "label");
    }
}
