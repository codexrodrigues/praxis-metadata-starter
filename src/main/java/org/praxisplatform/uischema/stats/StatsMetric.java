package org.praxisplatform.uischema.stats;

/**
 * Supported aggregate metrics for filtered stats.
 */
public enum StatsMetric {
    COUNT,
    DISTINCT_COUNT,
    SUM,
    AVG,
    MIN,
    MAX,
    MEDIAN,
    PERCENTILE_90,
    PERCENTILE_95,
    /**
     * Media normalizada pelo numero real de ocorrencias do bucket temporal dentro da janela
     * resolvida {@code [from, to]} na {@code timezone} declarada.
     */
    AVG_PER_CYCLE_OCCURRENCE;

    public boolean isPercentile() {
        return this == MEDIAN || this == PERCENTILE_90 || this == PERCENTILE_95;
    }

    public double percentileQuantile() {
        return switch (this) {
            case MEDIAN -> 0.50d;
            case PERCENTILE_90 -> 0.90d;
            case PERCENTILE_95 -> 0.95d;
            default -> throw new IllegalStateException("Metric is not a percentile: " + this);
        };
    }
}
