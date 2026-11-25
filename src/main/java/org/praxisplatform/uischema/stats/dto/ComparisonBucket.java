package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** Unioned comparison bucket, keyed by the governed bucket identity. */
public record ComparisonBucket(
        Object key,
        String label,
        Map<String, ComparisonMetricValue> values,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean suppressed
) {
    public ComparisonBucket(Object key, String label, Map<String, ComparisonMetricValue> values) {
        this(key, label, values, null);
    }
}
