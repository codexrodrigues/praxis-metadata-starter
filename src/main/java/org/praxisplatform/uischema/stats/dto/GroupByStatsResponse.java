package org.praxisplatform.uischema.stats.dto;

import java.util.List;

/**
 * Canonical response contract for group-by stats.
 */
public record GroupByStatsResponse(
        String field,
        StatsMetricRequest metric,
        List<GroupByBucket> buckets,
        List<StatsMetricRequest> metrics
) {
    public GroupByStatsResponse(String field, StatsMetricRequest metric, List<GroupByBucket> buckets) {
        this(field, metric, buckets, null);
    }

    public List<StatsMetricRequest> effectiveMetrics() {
        if (metrics != null && !metrics.isEmpty()) {
            return List.copyOf(metrics);
        }
        return metric != null ? List.of(metric) : List.of();
    }
}
