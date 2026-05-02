package org.praxisplatform.uischema.options;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.util.Collection;
import java.util.List;

/**
 * Canonical request envelope for option-source filtering.
 */
public record OptionSourceFilterRequest<FD extends GenericFilterDTO>(
        FD filter,
        List<LookupFilterRequest> filters,
        String search,
        String sort,
        Collection<Object> includeIds
) {

    public OptionSourceFilterRequest {
        filters = filters == null ? List.of() : List.copyOf(filters);
        search = normalize(search);
        sort = normalize(sort);
        includeIds = includeIds == null ? List.of() : List.copyOf(includeIds);
    }

    public boolean hasStructuredFilters() {
        return !filters.isEmpty();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
