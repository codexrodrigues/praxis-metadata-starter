package org.praxisplatform.uischema.options;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builder helpers for declaring governed option-source catalogs without per-service boilerplate.
 */
public final class GovernedOptionSourceCatalog {

    private GovernedOptionSourceCatalog() {
    }

    public static OptionSourceDescriptor providerBackedLookup(
            String key,
            String resourcePath,
            String labelPropertyPath,
            String valuePropertyPath
    ) {
        return providerBackedLookup(
                key,
                resourcePath,
                null,
                labelPropertyPath,
                valuePropertyPath,
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults()
        );
    }

    public static OptionSourceDescriptor providerBackedLookup(
            String key,
            String resourcePath,
            String filterField,
            String labelPropertyPath,
            String valuePropertyPath,
            List<String> dependsOn,
            Map<String, String> dependencyFilterMap,
            OptionSourcePolicy policy
    ) {
        return new OptionSourceDescriptor(
                key,
                OptionSourceType.LIGHT_LOOKUP,
                resourcePath,
                filterField,
                null,
                labelPropertyPath,
                valuePropertyPath,
                dependsOn,
                dependencyFilterMap,
                policy,
                null,
                OptionSourceExecutionMode.PROVIDER_REQUIRED,
                OptionSourceRuntimeContract.canonical(resourcePath, key)
        );
    }

    public static OptionSourceDescriptor providerBackedLookup(
            String key,
            String resourcePath,
            String filterField,
            String labelPropertyPath,
            String valuePropertyPath,
            List<String> dependsOn,
            Map<String, String> dependencyFilterMap,
            OptionSourcePolicy policy,
            EntityLookupDescriptor entityLookup
    ) {
        return providerBackedLookup(
                key,
                resourcePath,
                filterField,
                null,
                labelPropertyPath,
                valuePropertyPath,
                dependsOn,
                dependencyFilterMap,
                policy,
                entityLookup
        );
    }

    public static OptionSourceDescriptor providerBackedLookup(
            String key,
            String resourcePath,
            String filterField,
            String propertyPath,
            String labelPropertyPath,
            String valuePropertyPath,
            List<String> dependsOn,
            Map<String, String> dependencyFilterMap,
            OptionSourcePolicy policy,
            EntityLookupDescriptor entityLookup
    ) {
        return new OptionSourceDescriptor(
                key,
                OptionSourceType.RESOURCE_ENTITY,
                resourcePath,
                filterField,
                propertyPath,
                labelPropertyPath,
                valuePropertyPath,
                dependsOn,
                dependencyFilterMap,
                policy,
                Objects.requireNonNull(entityLookup, "entityLookup is required for a rich provider-backed lookup."),
                OptionSourceExecutionMode.PROVIDER_REQUIRED,
                OptionSourceRuntimeContract.canonical(resourcePath, key)
        );
    }

    public static OptionSourceRegistry registry(
            Class<?> resourceClass,
            OptionSourceDescriptor... descriptors
    ) {
        OptionSourceRegistry.Builder builder = OptionSourceRegistry.builder();
        if (descriptors != null) {
            for (OptionSourceDescriptor descriptor : descriptors) {
                builder.add(resourceClass, descriptor);
            }
        }
        return builder.build();
    }
}
