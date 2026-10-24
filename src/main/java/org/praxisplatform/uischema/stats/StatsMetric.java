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
    /**
     * Media normalizada pelo numero real de ocorrencias do bucket temporal dentro da janela
     * resolvida {@code [from, to]} na {@code timezone} declarada.
     */
    AVG_PER_CYCLE_OCCURRENCE
}
