package org.praxisplatform.uischema.stats.dto;

/**
 * Canonical bucket for distribution stats.
 */
public record DistributionBucket(
        Object from,
        Object to,
        Object key,
        String label,
        Number value,
        long count
) {
}
