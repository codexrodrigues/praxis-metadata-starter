package org.praxisplatform.uischema.stats;

/**
 * Runtime properties for filtered stats support.
 */
public record StatsProperties(
        boolean enabled,
        int maxBuckets,
        int maxSeriesPoints,
        StatsSupportMode defaultMode
) {
    public StatsProperties {
        if (maxBuckets <= 0) {
            maxBuckets = 20;
        }
        if (maxSeriesPoints <= 0) {
            maxSeriesPoints = 100;
        }
        defaultMode = defaultMode == null ? StatsSupportMode.DISABLED : defaultMode;
    }

    public static StatsProperties defaults() {
        return new StatsProperties(false, 20, 100, StatsSupportMode.DISABLED);
    }
}
