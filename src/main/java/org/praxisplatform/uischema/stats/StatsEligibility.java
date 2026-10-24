package org.praxisplatform.uischema.stats;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.StatsDimensionRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.service.ResolvedStatsDimension;

import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
        List<ResolvedStatsDimension> dimensions = validateGroupByDimensions(
                request,
                registry,
                maxBuckets,
                2,
                Math.max(maxBuckets, 500),
                366
        );
        return dimensions.get(0).descriptor();
    }

    public <FD extends GenericFilterDTO> List<ResolvedStatsDimension> validateGroupByDimensions(
            GroupByStatsRequest<FD> request,
            StatsFieldRegistry registry,
            int maxBuckets,
            int maxDimensions,
            int maxCells,
            int maxPeriodDays
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Stats request is required.");
        }
        if (request.filter() == null) {
            throw new IllegalArgumentException("Stats filter is required.");
        }
        if (request.field() != null && !request.field().isBlank()
                && request.dimensions() != null && !request.dimensions().isEmpty()) {
            String firstDimensionField = request.dimensions().get(0) == null ? null : request.dimensions().get(0).field();
            if (firstDimensionField == null || !request.field().trim().equals(firstDimensionField.trim())) {
                throw new IllegalArgumentException("Group-by field and dimensions[0].field must not conflict.");
            }
        }

        List<StatsDimensionRequest> requestedDimensions = request.effectiveDimensions();
        if (requestedDimensions.isEmpty()) {
            throw new IllegalArgumentException("Stats field or dimensions is required.");
        }
        if (requestedDimensions.size() > maxDimensions) {
            throw new IllegalArgumentException("Maximum number of stats dimensions exceeded: " + maxDimensions);
        }

        List<StatsMetricRequest> metrics = requireMetrics(request.effectiveMetrics());
        for (StatsMetricRequest metric : metrics) {
            resolveMetricField(metric, registry, "group-by");
        }
        if (request.primaryMetric() == null || request.primaryMetric().operation() == null) {
            throw new IllegalArgumentException("Stats metric is required.");
        }

        Set<String> dimensionAliases = new LinkedHashSet<>();
        List<ResolvedStatsDimension> resolvedDimensions = new ArrayList<>(requestedDimensions.size());
        boolean hasTemporalExtraction = false;
        boolean hasTimeSeriesEligibleDimension = false;

        for (StatsDimensionRequest dim : requestedDimensions) {
            if (dim == null || dim.field() == null || dim.field().isBlank()) {
                throw new IllegalArgumentException("Stats dimension field is required.");
            }
            String fieldName = dim.field().trim();
            StatsFieldDescriptor descriptor = registry.resolve(fieldName)
                    .orElseThrow(() -> new IllegalArgumentException("Stats field is not allowed: " + fieldName));
            StatsDimensionExtraction extraction = dim.effectiveExtraction();
            if (extraction == StatsDimensionExtraction.NONE) {
                if (!descriptor.groupByEligible()) {
                    throw new IllegalArgumentException("Stats field is not eligible for group-by: " + fieldName);
                }
            } else {
                hasTemporalExtraction = true;
                if (!descriptor.timeSeriesEligible()) {
                    throw new IllegalArgumentException("Stats field is not eligible for temporal extraction: " + fieldName);
                }
            }
            if (descriptor.timeSeriesEligible()) {
                hasTimeSeriesEligibleDimension = true;
            }
            if (request.primaryMetric().operation() == StatsMetric.COUNT
                    && !descriptor.supports(StatsMetric.COUNT)) {
                throw new IllegalArgumentException("Stats metric is not allowed for field: " + fieldName);
            }
            String alias = dim.effectiveAlias();
            if (alias.isBlank() || !dimensionAliases.add(alias)) {
                throw new IllegalArgumentException("Duplicate stats dimension alias is not allowed: " + alias);
            }
            resolvedDimensions.add(new ResolvedStatsDimension(
                    new StatsDimensionRequest(fieldName, extraction, alias),
                    descriptor
            ));
        }

        boolean hasTemporalWindow = request.hasTemporalWindow();
        if (hasTemporalExtraction || hasTemporalWindow || (request.timezone() != null && !request.timezone().isBlank())) {
            validateTimezone(request.timezone());
        }

        if (request.periodPreset() != null && (request.from() != null || request.to() != null)) {
            throw new IllegalArgumentException("Stats group-by period must use either periodPreset or both from and to dates, not both.");
        }
        if ((request.from() != null) != (request.to() != null)) {
            throw new IllegalArgumentException("Stats group-by custom period requires both from and to dates.");
        }
        if (request.from() != null && request.to() != null) {
            if (request.from().isAfter(request.to())) {
                throw new IllegalArgumentException("Stats group-by period is invalid: from must be on or before to.");
            }
            long days = ChronoUnit.DAYS.between(request.from(), request.to()) + 1;
            if (days > maxPeriodDays) {
                throw new IllegalArgumentException("Maximum stats period exceeded: " + maxPeriodDays + " days.");
            }
        }

        if (request.periodField() != null && !request.periodField().isBlank()) {
            StatsFieldDescriptor periodDescriptor = registry.resolve(request.periodField().trim())
                    .orElseThrow(() -> new IllegalArgumentException("Stats period field is not allowed: " + request.periodField()));
            if (!periodDescriptor.timeSeriesEligible()) {
                throw new IllegalArgumentException("Stats field is not eligible as period field: " + request.periodField());
            }
        } else if (hasTemporalWindow && !hasTimeSeriesEligibleDimension) {
            throw new IllegalArgumentException("Stats group-by with temporal window requires a temporal dimension or explicit periodField.");
        }

        boolean requestsCycleAverage = metrics.stream()
                .anyMatch(metric -> metric.operation() == StatsMetric.AVG_PER_CYCLE_OCCURRENCE);
        if (requestsCycleAverage) {
            if (!hasTemporalExtraction || !hasTemporalWindow) {
                throw new IllegalArgumentException(
                        "AVG_PER_CYCLE_OCCURRENCE requires at least one temporal dimension extraction, timezone, and a period window (periodPreset or from/to)."
                );
            }
        }

        if (Boolean.TRUE.equals(request.fillGaps())) {
            boolean hasLinearTruncation = resolvedDimensions.stream()
                    .anyMatch(dim -> dim.extraction().isLinearTruncation());
            if (hasLinearTruncation && !hasTemporalWindow) {
                throw new IllegalArgumentException(
                        "fillGaps with linear temporal truncation requires an explicit periodPreset or from/to window."
                );
            }
        }

        boolean usesCellCeiling = resolvedDimensions.size() > 1
                || hasTemporalExtraction
                || Boolean.TRUE.equals(request.fillGaps());
        int effectiveMax = usesCellCeiling ? Math.max(maxBuckets, maxCells) : maxBuckets;
        if (request.limit() != null && request.limit() <= 0) {
            throw new IllegalArgumentException("Stats limit must be greater than zero.");
        }
        if (request.limit() != null && request.limit() > effectiveMax) {
            throw new IllegalArgumentException("Maximum number of stats buckets exceeded: " + effectiveMax);
        }

        return List.copyOf(resolvedDimensions);
    }

    private void validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("Stats timezone is required for temporal extractions and period windows.");
        }
        try {
            ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Stats timezone is invalid: " + timezone);
        }
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

    public <FD extends GenericFilterDTO> StatsFieldDescriptor validateComparison(
            ComparisonStatsRequest<FD> request,
            StatsFieldRegistry registry,
            int maxBuckets
    ) {
        if (request == null || request.filter() == null) {
            throw new IllegalArgumentException("Stats filter is required.");
        }
        if (request.period() == null) {
            throw new IllegalArgumentException("Comparison period is required.");
        }
        for (StatsMetricRequest metric : requireMetrics(request.metrics())) {
            if (metric.operation() != StatsMetric.COUNT
                    && metric.operation() != StatsMetric.DISTINCT_COUNT
                    && metric.operation() != StatsMetric.SUM) {
                throw new IllegalArgumentException("Stats metric is not supported in comparison: " + metric.operation());
            }
            resolveMetricField(metric, registry, "comparison");
        }
        StatsFieldDescriptor descriptor = registry.resolve(request.field())
                .orElseThrow(() -> new IllegalArgumentException("Stats field is not allowed: " + request.field()));
        if (!descriptor.groupByEligible()) {
            throw new IllegalArgumentException("Stats field is not eligible for comparison: " + request.field());
        }
        StatsFieldDescriptor periodDescriptor = registry.resolve(request.periodField())
                .orElseThrow(() -> new IllegalArgumentException("Comparison period field is not allowed: " + request.periodField()));
        if (!periodDescriptor.timeSeriesEligible()) {
            throw new IllegalArgumentException("Stats field is not eligible as comparison period field: " + request.periodField());
        }
        if (request.limit() != null && (request.limit() <= 0 || request.limit() > maxBuckets)) {
            throw new IllegalArgumentException("Comparison limit must be between 1 and " + maxBuckets + ".");
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
        if (metric.operation() == StatsMetric.AVG_PER_CYCLE_OCCURRENCE) {
            if (!"group-by".equals(operationName)) {
                throw new IllegalArgumentException("AVG_PER_CYCLE_OCCURRENCE is supported only in group-by.");
            }
            if (metric.field() == null || metric.field().isBlank()) {
                return null;
            }
            StatsFieldDescriptor descriptor = registry.resolve(metric.field())
                    .orElseThrow(() -> new IllegalArgumentException("Stats metric field is not allowed: " + metric.field()));
            if (!descriptor.metricFieldEligible()) {
                throw new IllegalArgumentException("Stats field is not eligible as metric field: " + metric.field());
            }
            if (!descriptor.supports(StatsMetric.AVG_PER_CYCLE_OCCURRENCE)
                    && !descriptor.supports(StatsMetric.SUM)
                    && !descriptor.supports(StatsMetric.AVG)) {
                throw new IllegalArgumentException("Stats metric is not allowed for field: " + metric.field());
            }
            return descriptor;
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
