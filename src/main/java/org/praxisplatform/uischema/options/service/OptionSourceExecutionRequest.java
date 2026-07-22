package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.LookupFilteringDescriptor;
import org.praxisplatform.uischema.options.LookupSearchStrategyDefinition;
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
        String searchStrategy,
        List<LookupFilterRequest> filters,
        String sortKey,
        Pageable pageable,
        Collection<Object> includeIds,
        Collection<Object> ids,
        OptionSourceExecutionContext context
) {
    /** Compatibility constructor for providers compiled against the former request shape. */
    public OptionSourceExecutionRequest(
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
        this(hostContext, filterPayload, descriptor, search, null, filters, sortKey, pageable, includeIds, ids, context);
    }

    public OptionSourceExecutionRequest {
        search = normalize(search);
        searchStrategy = normalize(searchStrategy);
        LookupFilteringDescriptor filtering = descriptor == null ? null : descriptor.effectiveFiltering();
        if (filtering != null) {
            LookupSearchStrategyDefinition strategy = filtering.resolveSearchStrategy(searchStrategy, search);
            if (strategy != null) {
                searchStrategy = strategy.key();
                search = strategy.normalizeSearch(search);
            }
        } else if (searchStrategy != null) {
            throw new IllegalArgumentException("Lookup searchStrategy is not declared for this option source.");
        }
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

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
