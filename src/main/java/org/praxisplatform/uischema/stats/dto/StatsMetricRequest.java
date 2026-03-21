package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.stats.StatsMetric;

/**
 * Requested aggregate metric.
 *
 * @param operation aggregate operation
 * @param field optional numeric field for future metrics
 */
public record StatsMetricRequest(
        StatsMetric operation,
        String field
) {
}
