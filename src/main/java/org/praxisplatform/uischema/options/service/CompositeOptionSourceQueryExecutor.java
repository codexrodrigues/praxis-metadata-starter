package org.praxisplatform.uischema.options.service;

import jakarta.persistence.EntityManager;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.service.jpa.JpaOptionSourceHostContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;

/**
 * Compatibility executor facade that delegates option-source execution to providers.
 */
public class CompositeOptionSourceQueryExecutor implements OptionSourceQueryExecutor {

    private final OptionSourceProviderRegistry providerRegistry;
    private final OptionSourceRequestValidator requestValidator;
    private final OptionSourceContextResolver contextResolver;

    public CompositeOptionSourceQueryExecutor(OptionSourceProviderRegistry providerRegistry) {
        this(providerRegistry, new OptionSourceRequestValidator(), new DefaultOptionSourceContextResolver());
    }

    public CompositeOptionSourceQueryExecutor(
            OptionSourceProviderRegistry providerRegistry,
            OptionSourceRequestValidator requestValidator
    ) {
        this(providerRegistry, requestValidator, new DefaultOptionSourceContextResolver());
    }

    public CompositeOptionSourceQueryExecutor(
            OptionSourceProviderRegistry providerRegistry,
            OptionSourceRequestValidator requestValidator,
            OptionSourceContextResolver contextResolver
    ) {
        this.providerRegistry = providerRegistry;
        this.requestValidator = requestValidator;
        this.contextResolver = contextResolver;
    }

    @Override
    public <E> Page<OptionDTO<Object>> filterOptions(
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
    ) {
        OptionSourceExecutionContext context = contextResolver.resolve(descriptor, OptionSourceOperation.FILTER);
        OptionSourceExecutionRequest<E> request = new OptionSourceExecutionRequest<>(
                new JpaOptionSourceHostContext<>(entityManager, entityClass, specification),
                filterPayload,
                descriptor,
                search,
                filters,
                sortKey,
                pageable,
                includeIds,
                List.of(),
                context
        );
        requestValidator.validate(request);
        return providerRegistry.resolve(descriptor, context, OptionSourceOperation.FILTER).filter(request);
    }

    @Override
    public <E> List<OptionDTO<Object>> byIdsOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            OptionSourceDescriptor descriptor,
            Collection<Object> ids
    ) {
        OptionSourceExecutionContext context = contextResolver.resolve(descriptor, OptionSourceOperation.BY_IDS);
        OptionSourceExecutionRequest<E> request = new OptionSourceExecutionRequest<>(
                new JpaOptionSourceHostContext<>(entityManager, entityClass, null),
                null,
                descriptor,
                null,
                List.of(),
                null,
                null,
                List.of(),
                ids,
                context
        );
        requestValidator.validate(request);
        return providerRegistry.resolve(descriptor, context, OptionSourceOperation.BY_IDS).byIds(request);
    }

}
