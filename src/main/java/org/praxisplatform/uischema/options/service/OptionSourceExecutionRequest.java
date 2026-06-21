package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

/**
 * Internal request passed from the compatibility executor facade to an option-source provider.
 */
public record OptionSourceExecutionRequest<E>(
        Object hostContext,
        Object filterPayload,
        OptionSourceDescriptor descriptor,
        String search,
        List<LookupFilterRequest> filters,
        String sortKey,
        Pageable pageable,
        Collection<Object> includeIds,
        Collection<Object> ids,
        OptionSourceExecutionContext context
) {
    public OptionSourceExecutionRequest {
        filters = filters == null ? List.of() : List.copyOf(filters);
        includeIds = includeIds == null ? List.of() : List.copyOf(includeIds);
        ids = ids == null ? List.of() : List.copyOf(ids);
    }

    public <T> T requireHostContext(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Host context type is required.");
        }
        if (!type.isInstance(hostContext)) {
            throw new IllegalStateException("Option source host context is not compatible with provider.");
        }
        return type.cast(hostContext);
    }
}
