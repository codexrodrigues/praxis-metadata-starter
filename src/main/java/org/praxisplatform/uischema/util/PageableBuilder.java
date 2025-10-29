package org.praxisplatform.uischema.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * Utility for building {@link Pageable} instances from primitive request
 * parameters while applying a fallback {@link Sort} when none is specified.
 */
public final class PageableBuilder {

    private PageableBuilder() { }

    /**
     * Compose a {@link Pageable} instance using the provided page, size and
     * optional sort directives. When no sort is provided the fallback sort is
     * applied.
     *
     * @param page     zero-based page index
     * @param size     page size
     * @param sort     sort directives (e.g. ["name,asc", "id,desc"])
     * @param fallback default sort when {@code sort} is empty
     * @return resulting {@link Pageable}
     */
    public static Pageable from(int page, int size, List<String> sort, Sort fallback) {
        Sort s = SortBuilder.from(sort, fallback);
        return PageRequest.of(page, size, s);
    }
}
