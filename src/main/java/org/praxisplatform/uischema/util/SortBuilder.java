package org.praxisplatform.uischema.util;

import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Objects;

/**
 * Utility to build {@link Sort} instances from request parameters.
 * <p>
 * Supports multiple sort entries in the form {@code "field,asc"} or
 * {@code "field,desc"}. When no sort parameters are provided the
 * supplied fallback is returned.
 * </p>
 *
 * @since 1.0.0
 */
public final class SortBuilder {

    private SortBuilder() { }

    /**
     * Build a {@link Sort} from request parameters or use the fallback when
     * none is provided.
     *
     * @param sort     request sort parameters, e.g. ["name,asc", "id,desc"]
     * @param fallback sort to use when the list is {@code null} or empty
     * @return resulting {@link Sort}
     */
    public static Sort from(List<String> sort, Sort fallback) {
        if (sort == null || sort.isEmpty()) {
            return fallback == null ? Sort.unsorted() : fallback;
        }

        var orders = sort.stream()
                .map(SortBuilder::toOrder)
                .filter(Objects::nonNull)
                .toList();

        if (orders.isEmpty()) {
            return fallback == null ? Sort.unsorted() : fallback;
        }
        return Sort.by(orders);
    }

    private static Sort.Order toOrder(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        String[] parts = entry.split(",", 2);
        String property = parts[0];
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1) {
            try {
                direction = Sort.Direction.fromString(parts[1]);
            } catch (IllegalArgumentException ignored) {
                // defaults to ASC
            }
        }
        if (property == null || property.isBlank()) {
            return null;
        }
        return new Sort.Order(direction, property);
    }
}
