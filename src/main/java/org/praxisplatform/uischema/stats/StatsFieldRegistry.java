package org.praxisplatform.uischema.stats;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of fields that are eligible for filtered stats in a resource.
 */
public final class StatsFieldRegistry {

    private static final StatsFieldRegistry EMPTY = new StatsFieldRegistry(Map.of());

    private final Map<String, StatsFieldDescriptor> fields;

    private StatsFieldRegistry(Map<String, StatsFieldDescriptor> fields) {
        this.fields = fields;
    }

    public static StatsFieldRegistry empty() {
        return EMPTY;
    }

    public static StatsFieldRegistry of(Collection<StatsFieldDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return empty();
        }
        Map<String, StatsFieldDescriptor> map = new LinkedHashMap<>();
        for (StatsFieldDescriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.field() == null || descriptor.field().isBlank()) {
                continue;
            }
            map.put(descriptor.field(), descriptor);
        }
        return new StatsFieldRegistry(Map.copyOf(map));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<StatsFieldDescriptor> resolve(String field) {
        if (field == null || field.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(fields.get(field));
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public static final class Builder {

        private final Map<String, StatsFieldDescriptor> descriptors = new LinkedHashMap<>();

        public Builder add(StatsFieldDescriptor descriptor) {
            if (descriptor == null || descriptor.field() == null || descriptor.field().isBlank()) {
                return this;
            }
            descriptors.put(descriptor.field(), descriptor);
            return this;
        }

        public Builder categoricalGroupByBucket(String field, String propertyPath) {
            return add(StatsFieldDescriptor.categoricalGroupByBucket(field, propertyPath));
        }

        public Builder categoricalTermsBucket(String field, String propertyPath) {
            return add(StatsFieldDescriptor.categoricalTermsBucket(field, propertyPath));
        }

        public Builder temporalTimeSeriesField(String field, String propertyPath) {
            return add(StatsFieldDescriptor.temporalTimeSeriesField(field, propertyPath));
        }

        public Builder numericMeasureField(String field, String propertyPath) {
            return add(StatsFieldDescriptor.numericMeasureField(field, propertyPath));
        }

        public Builder numericHistogramMeasureField(String field, String propertyPath) {
            return add(StatsFieldDescriptor.numericHistogramMeasureField(field, propertyPath));
        }

        public Builder groupByBucket(String field, String propertyPath, java.util.Set<StatsMetric> metrics) {
            return add(StatsFieldDescriptor.groupByBucket(field, propertyPath, metrics));
        }

        public Builder distributionTermsBucket(String field, String propertyPath, java.util.Set<StatsMetric> metrics) {
            return add(StatsFieldDescriptor.distributionTermsBucket(field, propertyPath, metrics));
        }

        public Builder metricField(String field, String propertyPath, java.util.Set<StatsMetric> metrics) {
            return add(StatsFieldDescriptor.metricField(field, propertyPath, metrics));
        }

        public Builder histogramField(String field, String propertyPath, java.util.Set<StatsMetric> metrics) {
            return add(StatsFieldDescriptor.histogramField(field, propertyPath, metrics));
        }

        public StatsFieldRegistry build() {
            return StatsFieldRegistry.of(descriptors.values());
        }
    }
}
