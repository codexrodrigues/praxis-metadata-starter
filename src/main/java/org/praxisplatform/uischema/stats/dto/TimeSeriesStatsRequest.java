package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;

import java.time.LocalDate;
import java.util.List;

/**
 * Canonical request contract for time-series stats.
 *
 * @param <FD> filter DTO type
 */
public record TimeSeriesStatsRequest<FD extends GenericFilterDTO>(
        FD filter,
        String field,
        TimeSeriesGranularity granularity,
        StatsMetricRequest metric,
        LocalDate from,
        LocalDate to,
        Boolean fillGaps,
        List<StatsMetricRequest> metrics
) {
    public TimeSeriesStatsRequest(
            FD filter,
            String field,
            TimeSeriesGranularity granularity,
            StatsMetricRequest metric,
            LocalDate from,
            LocalDate to,
            Boolean fillGaps
    ) {
        this(filter, field, granularity, metric, from, to, fillGaps, null);
    }

    public List<StatsMetricRequest> effectiveMetrics() {
        if (metrics != null && !metrics.isEmpty()) {
            return List.copyOf(metrics);
        }
        return metric != null ? List.of(metric) : List.of();
    }

    public StatsMetricRequest primaryMetric() {
        List<StatsMetricRequest> effective = effectiveMetrics();
        return effective.isEmpty() ? null : effective.get(0);
    }
}
