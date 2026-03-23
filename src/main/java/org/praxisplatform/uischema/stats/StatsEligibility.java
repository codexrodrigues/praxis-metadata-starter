package org.praxisplatform.uischema.stats;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates whether a stats request is compatible with the governed contract.
 */
public class StatsEligibility {

    public <FD extends GenericFilterDTO> StatsFieldDescriptor validateGroupBy(
            GroupByStatsRequest<FD> request,
            StatsFieldRegistry registry,
            int maxBuckets
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Stats request is required.");
        }
        if (request.filter() == null) {
            throw new IllegalArgumentException("Stats filter is required.");
        }
        List<StatsMetricRequest> metrics = requireMetrics(request.effectiveMetrics());
        for (StatsMetricRequest metric : metrics) {
            resolveMetricField(metric, registry, "group-by");
        }
        if (request.primaryMetric() == null || request.primaryMetric().operation() == null) {
            throw new IllegalArgumentException("Stats metric is required.");
        }
        StatsFieldDescriptor descriptor = registry.resolve(request.field())
                .orElseThrow(() -> new IllegalArgumentException("Stats field is not allowed: " + request.field()));
        if (!descriptor.groupByEligible()) {
            throw new IllegalArgumentException("Stats field is not eligible for group-by: " + request.field());
        }
        if (request.limit() != null && request.limit() <= 0) {
            throw new IllegalArgumentException("Stats limit must be greater than zero.");
        }
        if (request.limit() != null && request.limit() > maxBuckets) {
            throw new IllegalArgumentException("Maximum number of stats buckets exceeded: " + maxBuckets);
        }
        return descriptor;
    }

    public <FD extends GenericFilterDTO> StatsFieldDescriptor validateTimeSeries(
            TimeSeriesStatsRequest<FD> request,
            StatsFieldRegistry registry,
            int maxPoints
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Stats request is required.");
        }
        if (request.filter() == null) {
            throw new IllegalArgumentException("Stats filter is required.");
        }
        if (request.granularity() == null) {
            throw new IllegalArgumentException("Time-series granularity is required.");
        }
        List<StatsMetricRequest> metrics = requireMetrics(request.effectiveMetrics());
        StatsMetricRequest primaryMetric = request.primaryMetric();
        if (primaryMetric == null || primaryMetric.operation() == null) {
            throw new IllegalArgumentException("Stats metric is required.");
        }
        for (StatsMetricRequest metric : metrics) {
            resolveMetricField(metric, registry, "time-series");
        }
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
            throw new IllegalArgumentException("Time-series range is invalid: from must be on or before to.");
        }
        StatsFieldDescriptor descriptor = registry.resolve(request.field())
                .orElseThrow(() -> new IllegalArgumentException("Stats field is not allowed: " + request.field()));
        if (!descriptor.timeSeriesEligible()) {
            throw new IllegalArgumentException("Stats field is not eligible for time-series: " + request.field());
        }
        if (primaryMetric.operation() == StatsMetric.COUNT && !descriptor.supports(primaryMetric.operation())) {
            throw new IllegalArgumentException("Stats metric is not allowed for field: " + request.field());
        }
        if (request.from() != null && request.to() != null) {
            long points = estimatePoints(request.from(), request.to(), request.granularity());
            if (points > maxPoints) {
                throw new IllegalArgumentException("Maximum number of time-series points exceeded: " + maxPoints);
            }
        }
        return descriptor;
    }

    public <FD extends GenericFilterDTO> StatsFieldDescriptor validateDistribution(
            DistributionStatsRequest<FD> request,
            StatsFieldRegistry registry,
            int maxBuckets
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Stats request is required.");
        }
        if (request.filter() == null) {
            throw new IllegalArgumentException("Stats filter is required.");
        }
        if (request.mode() == null) {
            throw new IllegalArgumentException("Distribution mode is required.");
        }
        StatsMetricRequest metric = request.metric();
        if (metric == null || metric.operation() == null) {
            throw new IllegalArgumentException("Stats metric is required.");
        }
        if (request.mode() == DistributionMode.TERMS) {
            if (request.bucketSize() != null || request.bucketCount() != null) {
                throw new IllegalArgumentException("Histogram parameters are not supported in TERMS mode.");
            }
        } else if (request.mode() == DistributionMode.HISTOGRAM) {
            if (metric.operation() != StatsMetric.COUNT) {
                throw new IllegalArgumentException("Only COUNT is supported in HISTOGRAM mode.");
            }
            if (metric.field() != null && !metric.field().isBlank()) {
                throw new IllegalArgumentException("Metric field is not supported in HISTOGRAM mode.");
            }
            if (request.bucketSize() == null) {
                throw new IllegalArgumentException("Histogram bucketSize is required.");
            }
            if (request.bucketSize().doubleValue() <= 0d) {
                throw new IllegalArgumentException("Histogram bucketSize must be greater than zero.");
            }
            if (request.bucketCount() != null && request.bucketCount() <= 0) {
                throw new IllegalArgumentException("Histogram bucketCount must be greater than zero.");
            }
        }
        if (request.limit() != null && request.limit() <= 0) {
            throw new IllegalArgumentException("Stats limit must be greater than zero.");
        }
        if (request.limit() != null && request.limit() > maxBuckets) {
            throw new IllegalArgumentException("Maximum number of stats buckets exceeded: " + maxBuckets);
        }
        StatsFieldDescriptor descriptor = registry.resolve(request.field())
                .orElseThrow(() -> new IllegalArgumentException("Stats field is not allowed: " + request.field()));
        if (request.mode() == DistributionMode.TERMS && !descriptor.distributionTermsEligible()) {
            throw new IllegalArgumentException("Stats field is not eligible for terms distribution: " + request.field());
        }
        if (request.mode() == DistributionMode.HISTOGRAM && !descriptor.distributionHistogramEligible()) {
            throw new IllegalArgumentException("Stats field is not eligible for histogram distribution: " + request.field());
        }
        return descriptor;
    }

    public StatsFieldDescriptor resolveMetricField(
            StatsMetricRequest metric,
            StatsFieldRegistry registry,
            String operationName
    ) {
        if (metric == null || metric.operation() == null) {
            throw new IllegalArgumentException("Stats metric is required.");
        }
        if (metric.operation() == StatsMetric.COUNT) {
            if (metric.field() != null && !metric.field().isBlank()) {
                throw new IllegalArgumentException("Metric field is not supported for COUNT in " + operationName + ".");
            }
            return null;
        }
        if (metric.field() == null || metric.field().isBlank()) {
            throw new IllegalArgumentException("Metric field is required for " + metric.operation() + " in " + operationName + ".");
        }
        StatsFieldDescriptor descriptor = registry.resolve(metric.field())
                .orElseThrow(() -> new IllegalArgumentException("Stats metric field is not allowed: " + metric.field()));
        if (!descriptor.metricFieldEligible()) {
            throw new IllegalArgumentException("Stats field is not eligible as metric field: " + metric.field());
        }
        if (!descriptor.supports(metric.operation())) {
            throw new IllegalArgumentException("Stats metric is not allowed for field: " + metric.field());
        }
        return descriptor;
    }

    private List<StatsMetricRequest> requireMetrics(List<StatsMetricRequest> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            throw new IllegalArgumentException("Stats metric is required.");
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (StatsMetricRequest metric : metrics) {
            if (metric == null || metric.operation() == null) {
                throw new IllegalArgumentException("Stats metric is required.");
            }
            String alias = metric.effectiveAlias();
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("Stats metric alias could not be resolved.");
            }
            if (!aliases.add(alias)) {
                throw new IllegalArgumentException("Duplicate stats metric alias is not allowed: " + alias);
            }
        }
        return metrics;
    }

    private long estimatePoints(java.time.LocalDate from, java.time.LocalDate to, TimeSeriesGranularity granularity) {
        if (granularity == TimeSeriesGranularity.DAY) {
            return java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        }
        if (granularity == TimeSeriesGranularity.WEEK) {
            return java.time.temporal.ChronoUnit.WEEKS.between(from, to) + 1;
        }
        return java.time.temporal.ChronoUnit.MONTHS.between(
                from.withDayOfMonth(1),
                to.withDayOfMonth(1)
        ) + 1;
    }
}
