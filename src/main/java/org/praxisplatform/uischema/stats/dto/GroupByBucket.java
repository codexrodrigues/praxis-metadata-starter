package org.praxisplatform.uischema.stats.dto;

/**
 * Group-by bucket in stats responses.
 */
public record GroupByBucket(
        Object key,
        String label,
        Number value,
        long count
) {
}
