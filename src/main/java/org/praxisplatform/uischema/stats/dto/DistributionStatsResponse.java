package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.stats.DistributionMode;

import java.util.List;

/**
 * Canonical response contract for distribution stats.
 */
public record DistributionStatsResponse(
        String field,
        DistributionMode mode,
        StatsMetricRequest metric,
        List<DistributionBucket> buckets
) {
}
