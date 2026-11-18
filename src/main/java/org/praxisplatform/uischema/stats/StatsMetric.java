package org.praxisplatform.uischema.stats;

/**
 * Supported aggregate metrics for filtered stats.
 */
public enum StatsMetric {
    COUNT("count"),
    DISTINCT_COUNT("distinct-count"),
    SUM("sum"),
    AVG("avg"),
    MIN("min"),
    MAX("max"),
    PERCENTILE_25("percentile-25"),
    MEDIAN("median"),
    PERCENTILE_75("percentile-75"),
    PERCENTILE_90("percentile-90"),
    PERCENTILE_95("percentile-95"),
    /**
     * Media normalizada pelo numero real de ocorrencias do bucket temporal dentro da janela
     * resolvida {@code [from, to]} na {@code timezone} declarada.
     */
    AVG_PER_CYCLE_OCCURRENCE("avg-per-cycle-occurrence");

    private final String wireValue;

    StatsMetric(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static StatsMetric fromWireOrName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("StatsMetric cannot be blank");
        }
        String normalized = raw.trim();
        for (StatsMetric metric : values()) {
            if (metric.wireValue.equalsIgnoreCase(normalized) || metric.name().equalsIgnoreCase(normalized)) {
                return metric;
            }
        }
        throw new IllegalArgumentException("Unsupported stats metric: " + raw);
    }

    public boolean isPercentile() {
        return this == PERCENTILE_25
                || this == MEDIAN
                || this == PERCENTILE_75
                || this == PERCENTILE_90
                || this == PERCENTILE_95;
    }

    public double percentileQuantile() {
        return switch (this) {
            case PERCENTILE_25 -> 0.25d;
            case MEDIAN -> 0.50d;
            case PERCENTILE_75 -> 0.75d;
            case PERCENTILE_90 -> 0.90d;
            case PERCENTILE_95 -> 0.95d;
            default -> throw new IllegalStateException("Metric is not a percentile: " + this);
        };
    }
}
