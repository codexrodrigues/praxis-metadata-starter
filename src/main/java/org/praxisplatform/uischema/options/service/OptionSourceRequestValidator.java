package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupCapabilities;
import org.praxisplatform.uischema.options.LookupFilterDefinition;
import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.LookupFilteringDescriptor;
import org.praxisplatform.uischema.options.LookupSearchStrategyDefinition;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.springframework.data.domain.Pageable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates public option-source request semantics before provider dispatch.
 */
public class OptionSourceRequestValidator {

    public void validate(OptionSourceExecutionRequest<?> request) {
        if (request == null) {
            throw new IllegalArgumentException("Option source execution request is required.");
        }
        OptionSourceDescriptor descriptor = request.descriptor();
        OptionSourceOperation operation = request.context().operation();
        validateCapability(descriptor, operation);
        if (operation == OptionSourceOperation.FILTER) {
            validateFilterRequest(request);
        } else if (operation == OptionSourceOperation.BY_IDS) {
            validateByIdsRequest(request);
        }
    }

    private void validateCapability(OptionSourceDescriptor descriptor, OptionSourceOperation operation) {
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        LookupCapabilities capabilities = lookup == null ? null : lookup.capabilities();
        if (capabilities == null) {
            return;
        }
        if (operation == OptionSourceOperation.FILTER && !capabilities.filter()) {
            throw new OptionSourceCapabilityNotSupportedException(descriptor, operation);
        }
        if (operation == OptionSourceOperation.BY_IDS && !capabilities.byIds()) {
            throw new OptionSourceCapabilityNotSupportedException(descriptor, operation);
        }
    }

    private void validateFilterRequest(OptionSourceExecutionRequest<?> request) {
        OptionSourceDescriptor descriptor = request.descriptor();
        validateDependencies(descriptor);
        if (!descriptor.policy().allowIncludeIds() && !request.includeIds().isEmpty()) {
            throw new IllegalArgumentException("includeIds is not allowed for option source: " + descriptor.key());
        }
        validateSearch(descriptor, request.search(), request.searchStrategy());
        validatePageable(descriptor, request.pageable());
        validateSort(descriptor, request.sortKey());
        validatePageableSort(descriptor, request.pageable(), request.sortKey());
        validateStructuredFilters(descriptor, request.filters());
    }

    private void validateByIdsRequest(OptionSourceExecutionRequest<?> request) {
        OptionSourceDescriptor descriptor = request.descriptor();
        validateDependencies(descriptor);
        validateStructuredFilters(descriptor, request.filters());
    }

    private void validateSearch(OptionSourceDescriptor descriptor, String search, String searchStrategy) {
        if (search == null || search.isBlank()) {
            return;
        }
        OptionSourcePolicy policy = descriptor.policy();
        if (!policy.allowSearch()) {
            throw new IllegalArgumentException("Search is not allowed for option source: " + descriptor.key());
        }
        LookupFilteringDescriptor filtering = filtering(descriptor);
        LookupSearchStrategyDefinition strategy = filtering == null
                ? null
                : filtering.resolveSearchStrategy(searchStrategy, search);
        int minimumLength = strategy == null
                ? policy.minSearchChars()
                : Math.max(policy.minSearchChars(), strategy.minSearchChars());
        int length = search.trim().length();
        if (length < minimumLength) {
            throw new IllegalArgumentException(
                    "Search term must have at least %d characters for option source: %s"
                            .formatted(minimumLength, descriptor.key())
            );
        }
    }

    private void validatePageable(OptionSourceDescriptor descriptor, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return;
        }
        int maxPageSize = descriptor.policy().maxPageSize();
        if (pageable.getPageSize() > maxPageSize) {
            throw new IllegalArgumentException(
                    "Maximum option source page size exceeded for %s: %d"
                            .formatted(descriptor.key(), maxPageSize)
            );
        }
    }

    private void validateDependencies(OptionSourceDescriptor descriptor) {
        validateDependencyMap(descriptor, descriptor.dependencyFilterMap());
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        if (lookup != null) {
            validateDependencyMap(descriptor, lookup.dependencyFilterMap());
        }
    }

    private void validateDependencyMap(
            OptionSourceDescriptor descriptor,
            Map<String, String> dependencyFilterMap
    ) {
        if (dependencyFilterMap == null || dependencyFilterMap.isEmpty()) {
            return;
        }
        for (String dependency : dependencyFilterMap.keySet()) {
            if (!descriptor.dependsOn().contains(dependency)) {
                throw new IllegalArgumentException(
                        "Option source dependencyFilterMap key must be declared in dependsOn: %s.%s"
                                .formatted(descriptor.key(), dependency)
                );
            }
        }
    }

    private void validateSort(OptionSourceDescriptor descriptor, String sortKey) {
        if (sortKey == null || sortKey.isBlank()) {
            return;
        }
        LookupFilteringDescriptor filtering = filtering(descriptor);
        if (filtering == null || filtering.sortOptions().isEmpty()) {
            if (isLegacyOptionAliasSort(sortKey)) {
                return;
            }
            throw new IllegalArgumentException("Structured sorting is not supported for option source: " + descriptor.key());
        }
        boolean supported = filtering.sortOptions().stream()
                .anyMatch(option -> sortKey.equals(option.key()));
        if (!supported) {
            throw new IllegalArgumentException("Unsupported entity lookup sort key: " + sortKey);
        }
    }

    private void validatePageableSort(OptionSourceDescriptor descriptor, Pageable pageable, String sortKey) {
        if (pageable == null || pageable.isUnpaged() || pageable.getSort().isUnsorted()) {
            return;
        }
        for (var order : pageable.getSort()) {
            String property = order.getProperty();
            if (property == null || property.isBlank()) {
                continue;
            }
            if (sortKey == null || sortKey.isBlank() || !sortKey.equals(property)) {
                throw new IllegalArgumentException(
                        "Pageable sort must match a validated option source sort key: " + property
                );
            }
            validateSort(descriptor, property);
        }
    }

    private boolean isLegacyOptionAliasSort(String sortKey) {
        return "id".equals(sortKey) || "label".equals(sortKey);
    }

    private void validateStructuredFilters(
            OptionSourceDescriptor descriptor,
            List<LookupFilterRequest> filters
    ) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        LookupFilteringDescriptor filtering = filtering(descriptor);
        if (filtering == null || filtering.availableFilters().isEmpty()) {
            throw new IllegalArgumentException("Structured filters are not supported for option source: " + descriptor.key());
        }
        Map<String, LookupFilterDefinition> available = new LinkedHashMap<>();
        filtering.availableFilters().forEach(definition -> available.putIfAbsent(definition.field(), definition));
        for (LookupFilterRequest filter : filters) {
            LookupFilterDefinition definition = available.get(filter.field());
            if (definition == null) {
                throw new IllegalArgumentException("Unsupported entity lookup filter field: " + filter.field());
            }
            if (!definition.operators().contains(filter.operator())) {
                throw new IllegalArgumentException(
                        "Unsupported entity lookup filter operator '%s' for field '%s'."
                                .formatted(filter.operator(), filter.field())
                );
            }
        }
    }

    private LookupFilteringDescriptor filtering(OptionSourceDescriptor descriptor) {
        return descriptor.effectiveFiltering();
    }
}
