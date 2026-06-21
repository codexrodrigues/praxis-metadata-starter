package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

/**
 * Validated execution request passed from Praxis to an option-source provider.
 *
 * <p>
 * The request contains the effective public filter payload, structured filters,
 * search term, sort key, includeIds or by-ids values, and the private execution
 * context. For JPA fallback only, {@code hostContext} contains a
 * {@code JpaOptionSourceHostContext}. External providers should depend on their own
 * host context type or on {@link OptionSourceExecutionContext#attributes()}.
 * </p>
 *
 * <p>
 * Praxis validates request policy before provider resolution. Providers may rely on
 * {@link #sortKey()} and {@link #pageable()} being governed by the descriptor, but
 * should still avoid interpolating public values directly into backend-specific query
 * languages.
 * </p>
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
