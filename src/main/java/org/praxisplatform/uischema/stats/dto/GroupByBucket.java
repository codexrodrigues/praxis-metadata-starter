package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Group-by bucket in stats responses (supports both 1D buckets and 2D/ND Cross-Tab cells).
 */
public record GroupByBucket(
        Object key,
        String label,
        Number value,
        long count,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<String, Number> values,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<Object> keys,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<String> labels,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<String, Object> coordinates
) {
    public GroupByBucket(Object key, String label, Number value, long count) {
        this(key, label, value, count, null, null, null, null);
    }

    public GroupByBucket(
            Object key,
            String label,
            Number value,
            long count,
            Map<String, Number> values
    ) {
        this(key, label, value, count, values, null, null, null);
    }
}
