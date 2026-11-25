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
        Integer minBucketCount,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long populationTotalCount,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long suppressedPoolCount,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean truncated
) {
    public ComparisonStatsResponse(
            String field,
            String periodField,
            List<StatsMetricRequest> metrics,
            ComparisonPeriodWindow currentPeriod,
            ComparisonPeriodWindow previousPeriod,
            List<ComparisonBucket> buckets
    ) {
        this(field, periodField, metrics, currentPeriod, previousPeriod, buckets, null, null, null, null, null, null);
    }

    public ComparisonStatsResponse(
            String field,
            String periodField,
            List<StatsMetricRequest> metrics,
            ComparisonPeriodWindow currentPeriod,
            ComparisonPeriodWindow previousPeriod,
            List<ComparisonBucket> buckets,
            Boolean suppressed,
            Integer suppressedBucketCount,
            Integer minBucketCount
    ) {
        this(field, periodField, metrics, currentPeriod, previousPeriod, buckets, suppressed, suppressedBucketCount, minBucketCount, null, null, null);
    }
}
