package org.praxisplatform.uischema.stats;

import java.util.Set;

/**
 * Describes a field that is eligible for filtered stats.
 *
 * @param field canonical API field
 * @param propertyPath JPA property path used in aggregation
 * @param metrics allowed aggregate metrics
 * @param groupByEligible whether the field can be used as a group-by bucket
 * @param timeSeriesEligible whether the field can be used in time-series
 * @param distributionTermsEligible whether the field can be used in terms distribution
 * @param distributionHistogramEligible whether the field can be used in histogram distribution
 * @param metricFieldEligible whether the field can be used as metric.field
 */
public record StatsFieldDescriptor(
        String field,
        String propertyPath,
        Set<StatsMetric> metrics,
        boolean groupByEligible,
        boolean timeSeriesEligible,
        boolean distributionTermsEligible,
        boolean distributionHistogramEligible,
        boolean metricFieldEligible
) {
    public StatsFieldDescriptor {
        metrics = metrics == null ? Set.of() : Set.copyOf(metrics);
    }

    public StatsFieldDescriptor(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        this(field, propertyPath, metrics, true, true, true, true, true);
    }

    public static StatsFieldDescriptor groupByBucket(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        return new StatsFieldDescriptor(field, propertyPath, metrics, true, false, true, false, false);
    }

    public static StatsFieldDescriptor timeSeriesField(
            String field,
            String propertyPath
    ) {
        return new StatsFieldDescriptor(field, propertyPath, Set.of(StatsMetric.COUNT), false, true, false, false, false);
    }

    public static StatsFieldDescriptor metricField(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        return new StatsFieldDescriptor(field, propertyPath, metrics, false, false, false, false, true);
    }

    public static StatsFieldDescriptor histogramField(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        return new StatsFieldDescriptor(field, propertyPath, metrics, false, false, false, true, true);
    }

    public static StatsFieldDescriptor distributionTermsBucket(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        return new StatsFieldDescriptor(field, propertyPath, metrics, false, false, true, false, false);
    }

    public static StatsFieldDescriptor categoricalGroupByBucket(
            String field,
            String propertyPath
    ) {
        return groupByBucket(field, propertyPath, Set.of(StatsMetric.COUNT));
    }

    public static StatsFieldDescriptor categoricalTermsBucket(
            String field,
            String propertyPath
    ) {
        return distributionTermsBucket(field, propertyPath, Set.of(StatsMetric.COUNT));
    }

    public static StatsFieldDescriptor temporalTimeSeriesField(
            String field,
            String propertyPath
    ) {
        return timeSeriesField(field, propertyPath);
    }

    public static StatsFieldDescriptor numericMeasureField(
            String field,
            String propertyPath
    ) {
        return metricField(field, propertyPath, Set.of(StatsMetric.SUM, StatsMetric.AVG, StatsMetric.MIN, StatsMetric.MAX));
    }

    public static StatsFieldDescriptor numericHistogramMeasureField(
            String field,
            String propertyPath
    ) {
        return histogramField(field, propertyPath, Set.of(StatsMetric.COUNT, StatsMetric.SUM, StatsMetric.AVG, StatsMetric.MIN, StatsMetric.MAX));
    }

    public boolean supports(StatsMetric metric) {
        return metric != null && metrics.contains(metric);
    }
}
