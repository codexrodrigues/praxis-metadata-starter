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
        String field,
        String alias
) {
    public StatsMetricRequest(StatsMetric operation, String field) {
        this(operation, field, null);
    }

    public String effectiveAlias() {
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        if (field != null && !field.isBlank()) {
            return field;
        }
        return operation != null ? operation.name() : null;
    }
}
