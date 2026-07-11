package org.praxisplatform.uischema.options;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical request envelope for option-source filtering.
 */
public record OptionSourceFilterRequest<FD extends GenericFilterDTO>(
        FD filter,
        List<LookupFilterRequest> filters,
        String search,
        String sort,
        Collection<Object> includeIds,
        @Schema(hidden = true)
        Map<String, Object> dependencyFilters
) {

    public OptionSourceFilterRequest(
            FD filter,
            List<LookupFilterRequest> filters,
            String search,
            String sort,
            Collection<Object> includeIds
    ) {
        this(filter, filters, search, sort, includeIds, null);
    }

    public OptionSourceFilterRequest {
        filters = filters == null ? List.of() : List.copyOf(filters);
        search = normalize(search);
        sort = normalize(sort);
        includeIds = includeIds == null ? List.of() : List.copyOf(includeIds);
        dependencyFilters = dependencyFilters == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(dependencyFilters));
    }

    public boolean hasStructuredFilters() {
        return !filters.isEmpty();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
