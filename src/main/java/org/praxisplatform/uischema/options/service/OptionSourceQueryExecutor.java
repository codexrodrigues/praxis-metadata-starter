package org.praxisplatform.uischema.options.service;

import jakarta.persistence.EntityManager;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.LookupFilterRequest;
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
            OptionSourceDescriptor descriptor,
            String search,
            List<LookupFilterRequest> filters,
            String sortKey,
            Pageable pageable,
            Collection<Object> includeIds
    );

    <E> List<OptionDTO<Object>> byIdsOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            OptionSourceDescriptor descriptor,
            Collection<Object> ids
    );
}
