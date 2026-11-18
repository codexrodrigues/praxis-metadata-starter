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
        int maxComparisonPeriodDays,
        int maxDimensions,
        int maxCells,
        int maxTimeSeriesWindowDays,
        int defaultMinBucketCount,
        int maxInMemorySampleRows
) {
    public static final int DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS = 100_000;

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
        if (maxDimensions <= 0) {
            maxDimensions = 2;
        }
        if (maxCells <= 0) {
            maxCells = 500;
        }
        if (maxTimeSeriesWindowDays <= 0) {
            maxTimeSeriesWindowDays = 366;
        }
        if (defaultMinBucketCount < 0) {
            defaultMinBucketCount = 0;
        }
        if (maxInMemorySampleRows <= 0) {
            maxInMemorySampleRows = DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS;
        }
        defaultMode = defaultMode == null ? StatsSupportMode.DISABLED : defaultMode;
    }

    public StatsProperties(
            boolean enabled,
            int maxBuckets,
            int maxSeriesPoints,
            StatsSupportMode defaultMode,
            int maxComparisonCandidates,
            int maxComparisonPeriodDays,
            int maxDimensions,
            int maxCells,
            int maxTimeSeriesWindowDays,
            int defaultMinBucketCount
    ) {
        this(
                enabled,
                maxBuckets,
                maxSeriesPoints,
                defaultMode,
                maxComparisonCandidates,
                maxComparisonPeriodDays,
                maxDimensions,
                maxCells,
                maxTimeSeriesWindowDays,
                defaultMinBucketCount,
                DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
        );
    }

    public StatsProperties(
            boolean enabled,
            int maxBuckets,
            int maxSeriesPoints,
            StatsSupportMode defaultMode,
            int maxComparisonCandidates,
            int maxComparisonPeriodDays,
            int maxDimensions,
            int maxCells
    ) {
        this(
                enabled,
                maxBuckets,
                maxSeriesPoints,
                defaultMode,
                maxComparisonCandidates,
                maxComparisonPeriodDays,
                maxDimensions,
                maxCells,
                366,
                0,
                DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
        );
    }

    public StatsProperties(
            boolean enabled,
            int maxBuckets,
            int maxSeriesPoints,
            StatsSupportMode defaultMode,
            int maxComparisonCandidates,
            int maxComparisonPeriodDays
    ) {
        this(enabled, maxBuckets, maxSeriesPoints, defaultMode, maxComparisonCandidates, maxComparisonPeriodDays, 2, 500, 366, 0, DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS);
    }

    public StatsProperties(boolean enabled, int maxBuckets, int maxSeriesPoints, StatsSupportMode defaultMode) {
        this(enabled, maxBuckets, maxSeriesPoints, defaultMode, 1_000, 366, 2, 500, 366, 0, DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS);
    }

    public static StatsProperties defaults() {
        return new StatsProperties(false, 20, 100, StatsSupportMode.DISABLED, 1_000, 366, 2, 500, 366, 0, DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS);
    }
}
