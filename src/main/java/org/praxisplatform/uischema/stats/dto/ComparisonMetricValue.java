package org.praxisplatform.uischema.stats.dto;

/** Values of one aggregate metric in the current and previous comparison windows. */
public record ComparisonMetricValue(
        Number current,
        Number previous,
        Number delta,
        Double deltaPercent,
        boolean baselineMissing
) { }
