package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.stats.TimeSeriesGranularity;

import java.util.List;

/**
 * Canonical response contract for time-series stats.
 */
public record TimeSeriesStatsResponse(
        String field,
        TimeSeriesGranularity granularity,
        StatsMetricRequest metric,
        List<TimeSeriesPoint> points,
        List<StatsMetricRequest> metrics
) {
    public TimeSeriesStatsResponse(
            String field,
            TimeSeriesGranularity granularity,
            StatsMetricRequest metric,
            List<TimeSeriesPoint> points
    ) {
        this(field, granularity, metric, points, null);
    }

    public List<StatsMetricRequest> effectiveMetrics() {
        if (metrics != null && !metrics.isEmpty()) {
            return List.copyOf(metrics);
        }
        return metric != null ? List.of(metric) : List.of();
    }
}
