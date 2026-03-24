package org.praxisplatform.uischema.options;

import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.springframework.stereotype.Component;

/**
 * Validates and normalizes option source descriptors before execution.
 */
@Component
public class OptionSourceEligibility {

    public OptionSourceDescriptor resolveEffectiveDescriptor(
            OptionSourceDescriptor descriptor,
            StatsFieldRegistry statsFieldRegistry
    ) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Option source descriptor is required.");
        }

        StatsFieldDescriptor statsDescriptor = statsFieldRegistry == null || statsFieldRegistry.isEmpty()
                ? null
                : statsFieldRegistry.resolve(descriptor.key()).orElse(null);

        return switch (descriptor.type()) {
            case DISTINCT_DIMENSION -> resolveDistinctDimension(descriptor, statsDescriptor);
            case CATEGORICAL_BUCKET -> resolveCategoricalBucket(descriptor, statsDescriptor);
            case RESOURCE_ENTITY, LIGHT_LOOKUP, STATIC_CANONICAL -> descriptor;
        };
    }

    private OptionSourceDescriptor resolveDistinctDimension(
            OptionSourceDescriptor descriptor,
            StatsFieldDescriptor statsDescriptor
    ) {
        if (statsDescriptor != null && !statsDescriptor.groupByEligible() && !statsDescriptor.distributionTermsEligible()) {
            throw new IllegalArgumentException("Option source is not eligible as distinct dimension: " + descriptor.key());
        }
        return enrichPropertyPath(descriptor, statsDescriptor);
    }

    private OptionSourceDescriptor resolveCategoricalBucket(
            OptionSourceDescriptor descriptor,
            StatsFieldDescriptor statsDescriptor
    ) {
        if (statsDescriptor != null && !statsDescriptor.distributionTermsEligible() && !statsDescriptor.groupByEligible()) {
            throw new IllegalArgumentException("Option source is not eligible as categorical bucket: " + descriptor.key());
        }
        return enrichPropertyPath(descriptor, statsDescriptor);
    }

    private OptionSourceDescriptor enrichPropertyPath(
            OptionSourceDescriptor descriptor,
            StatsFieldDescriptor statsDescriptor
    ) {
        String propertyPath = descriptor.propertyPath();
        if ((propertyPath == null || propertyPath.isBlank()) && statsDescriptor != null) {
            propertyPath = statsDescriptor.propertyPath();
        }
        if (propertyPath == null || propertyPath.isBlank()) {
            throw new IllegalArgumentException("Option source propertyPath is required: " + descriptor.key());
        }
        return new OptionSourceDescriptor(
                descriptor.key(),
                descriptor.type(),
                descriptor.resourcePath(),
                descriptor.filterField(),
                propertyPath,
                descriptor.labelPropertyPath(),
                descriptor.valuePropertyPath(),
                descriptor.dependsOn(),
                descriptor.policy()
        );
    }
}
