package org.praxisplatform.uischema.stats.service;

import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;

/**
 * Internal execution contract for a resolved stats metric.
 *
 * @param metric request-side metric definition
 * @param descriptor resolved metric field descriptor, or {@code null} for COUNT
 */
public record ResolvedStatsMetric(
        StatsMetricRequest metric,
        StatsFieldDescriptor descriptor
) {
    public String alias() {
        return metric == null ? null : metric.effectiveAlias();
    }
}
