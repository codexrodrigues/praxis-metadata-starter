package org.praxisplatform.uischema.options.service.jpa;

import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceExecutionMode;
import org.praxisplatform.uischema.options.service.OptionSourceExecutionContext;
import org.praxisplatform.uischema.options.service.OptionSourceExecutionRequest;
import org.praxisplatform.uischema.options.service.OptionSourceOperation;
import org.praxisplatform.uischema.options.service.OptionSourceProvider;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Default JPA provider for metadata-driven option sources.
 */
public class JpaOptionSourceProvider implements OptionSourceProvider {

    private final JpaOptionSourceQueryExecutor delegate;

    public JpaOptionSourceProvider(JpaOptionSourceQueryExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean supports(
            OptionSourceDescriptor descriptor,
            OptionSourceExecutionContext context,
            OptionSourceOperation operation
    ) {
        return descriptor != null && descriptor.executionMode() != OptionSourceExecutionMode.PROVIDER_REQUIRED;
    }

    @Override
    public Page<OptionDTO<Object>> filter(OptionSourceExecutionRequest<?> request) {
        return filterTyped(request);
    }

    @Override
    public List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request) {
        return byIdsTyped(request);
    }

    private <E> Page<OptionDTO<Object>> filterTyped(OptionSourceExecutionRequest<E> request) {
        JpaOptionSourceHostContext<E> hostContext = jpaContext(request);
        return delegate.filterOptions(
                hostContext.entityManager(),
                hostContext.entityClass(),
                hostContext.specification(),
                request.filterPayload(),
                request.descriptor(),
                request.search(),
                request.filters(),
                request.sortKey(),
                request.pageable(),
                request.includeIds()
        );
    }

    private <E> List<OptionDTO<Object>> byIdsTyped(OptionSourceExecutionRequest<E> request) {
        JpaOptionSourceHostContext<E> hostContext = jpaContext(request);
        return delegate.byIdsOptions(
                hostContext.entityManager(),
                hostContext.entityClass(),
                hostContext.specification(),
                request.filterPayload(),
                request.descriptor(),
                request.ids()
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <E> JpaOptionSourceHostContext<E> jpaContext(OptionSourceExecutionRequest<E> request) {
        return (JpaOptionSourceHostContext<E>) request.requireHostContext(JpaOptionSourceHostContext.class);
    }
}
