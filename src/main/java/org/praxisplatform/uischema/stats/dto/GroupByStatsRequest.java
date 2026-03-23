package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.StatsBucketOrder;

import java.util.List;

/**
 * Canonical request contract for group-by stats.
 *
 * @param <FD> filter DTO type
 */
public record GroupByStatsRequest<FD extends GenericFilterDTO>(
        FD filter,
        String field,
        StatsMetricRequest metric,
        Integer limit,
        StatsBucketOrder orderBy,
        List<StatsMetricRequest> metrics
) {
    public GroupByStatsRequest(FD filter, String field, StatsMetricRequest metric, Integer limit, StatsBucketOrder orderBy) {
        this(filter, field, metric, limit, orderBy, null);
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
