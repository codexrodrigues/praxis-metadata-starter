package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Canonical response for a governed period-over-period comparison. */
public record ComparisonStatsResponse(
        String field,
        String periodField,
        List<StatsMetricRequest> metrics,
        ComparisonPeriodWindow currentPeriod,
        ComparisonPeriodWindow previousPeriod,
        List<ComparisonBucket> buckets,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean suppressed,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer suppressedBucketCount,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer minBucketCount
) {
    public ComparisonStatsResponse(
            String field,
            String periodField,
            List<StatsMetricRequest> metrics,
            ComparisonPeriodWindow currentPeriod,
            ComparisonPeriodWindow previousPeriod,
            List<ComparisonBucket> buckets
    ) {
        this(field, periodField, metrics, currentPeriod, previousPeriod, buckets, null, null, null);
    }
}
