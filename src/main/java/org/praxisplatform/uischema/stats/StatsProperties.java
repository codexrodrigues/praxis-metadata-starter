package org.praxisplatform.uischema.stats;

/**
 * Runtime properties for filtered stats support.
 */
public record StatsProperties(
        boolean enabled,
        int maxBuckets,
        int maxSeriesPoints,
        StatsSupportMode defaultMode,
        int maxComparisonCandidates,
        int maxComparisonPeriodDays
) {
    public StatsProperties {
        if (maxBuckets <= 0) {
            maxBuckets = 20;
        }
        if (maxSeriesPoints <= 0) {
            maxSeriesPoints = 100;
        }
        if (maxComparisonCandidates <= 0) {
            maxComparisonCandidates = 1_000;
        }
        if (maxComparisonPeriodDays <= 0) {
            maxComparisonPeriodDays = 366;
        }
        defaultMode = defaultMode == null ? StatsSupportMode.DISABLED : defaultMode;
    }

    public StatsProperties(boolean enabled, int maxBuckets, int maxSeriesPoints, StatsSupportMode defaultMode) {
        this(enabled, maxBuckets, maxSeriesPoints, defaultMode, 1_000, 366);
    }

    public static StatsProperties defaults() {
        return new StatsProperties(false, 20, 100, StatsSupportMode.DISABLED, 1_000, 366);
    }
}
