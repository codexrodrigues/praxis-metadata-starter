package org.praxisplatform.uischema.options.service;

import jakarta.persistence.EntityManager;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.LookupFilteringDescriptor;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;

/**
 * Internal executor contract for registered option sources.
 */
public interface OptionSourceQueryExecutor {

    <E> Page<OptionDTO<Object>> filterOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            Object filterPayload,
            OptionSourceDescriptor descriptor,
            String search,
            List<LookupFilterRequest> filters,
            String sortKey,
            Pageable pageable,
            Collection<Object> includeIds
    );

    /**
     * Executes a filter with an explicitly selected governed search strategy.
     * Implementors that do not support strategy-aware lookups retain the legacy
     * execution path; the composite executor overrides this method.
     */
    default <E> Page<OptionDTO<Object>> filterOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            Object filterPayload,
            OptionSourceDescriptor descriptor,
            String search,
            String searchStrategy,
            List<LookupFilterRequest> filters,
            String sortKey,
            Pageable pageable,
            Collection<Object> includeIds
    ) {
        LookupFilteringDescriptor filtering = descriptor == null ? null : descriptor.effectiveFiltering();
        boolean hasGovernedSearchStrategy = filtering != null
                && filtering.resolveSearchStrategy(searchStrategy, search) != null;
        if (hasGovernedSearchStrategy) {
            throw new UnsupportedOperationException("Option source executor does not support governed search strategies.");
        }
        return filterOptions(entityManager, entityClass, specification, filterPayload, descriptor, search, filters, sortKey, pageable, includeIds);
    }

    <E> List<OptionDTO<Object>> byIdsOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            OptionSourceDescriptor descriptor,
            Collection<Object> ids
    );

    default <E> List<OptionDTO<Object>> byIdsOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            Object filterPayload,
            OptionSourceDescriptor descriptor,
            Collection<Object> ids
    ) {
        return byIdsOptions(entityManager, entityClass, specification, filterPayload, descriptor, List.of(), ids);
    }

    <E> List<OptionDTO<Object>> byIdsOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            Object filterPayload,
            OptionSourceDescriptor descriptor,
            List<LookupFilterRequest> filters,
            Collection<Object> ids
    );
}
