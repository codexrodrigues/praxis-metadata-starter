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
        List<TimeSeriesPoint> points
) {
}
