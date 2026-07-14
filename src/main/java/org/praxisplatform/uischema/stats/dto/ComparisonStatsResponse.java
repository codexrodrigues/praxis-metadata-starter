package org.praxisplatform.uischema.stats.dto;

import java.util.List;

/** Canonical response for a governed period-over-period comparison. */
public record ComparisonStatsResponse(
        String field,
        String periodField,
        List<StatsMetricRequest> metrics,
        ComparisonPeriodWindow currentPeriod,
        ComparisonPeriodWindow previousPeriod,
        List<ComparisonBucket> buckets
) { }
