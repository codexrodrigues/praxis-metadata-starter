package org.praxisplatform.uischema.stats.dto;

import java.util.List;

/**
 * Canonical response contract for group-by stats.
 */
public record GroupByStatsResponse(
        String field,
        StatsMetricRequest metric,
        List<GroupByBucket> buckets
) {
}
