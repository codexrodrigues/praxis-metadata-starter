package org.praxisplatform.uischema.stats.dto;

import java.util.Map;

/**
 * Group-by bucket in stats responses.
 */
public record GroupByBucket(
        Object key,
        String label,
        Number value,
        long count,
        Map<String, Number> values
) {
    public GroupByBucket(Object key, String label, Number value, long count) {
        this(key, label, value, count, null);
    }
}
