package org.praxisplatform.uischema.stats.dto;

import java.util.List;

/**
 * Contrato canonico de resposta para agregacoes {@code group-by}.
 *
 * <p>
 * A resposta informa o campo agrupado, a metrica principal e a colecao de buckets retornados.
 * Quando a operacao roda em modo multi-metrica, {@code metrics} carrega a lista completa de
 * metricas efetivamente aplicadas.
 * </p>
 */
public record GroupByStatsResponse(
        String field,
        StatsMetricRequest metric,
        List<GroupByBucket> buckets,
        List<StatsMetricRequest> metrics
) {
    /**
     * Construtor para respostas de metrica unica.
     */
    public GroupByStatsResponse(String field, StatsMetricRequest metric, List<GroupByBucket> buckets) {
        this(field, metric, buckets, null);
    }

    /**
     * Retorna a lista efetiva de metricas presentes na resposta.
     *
     * @return lista efetiva de metricas, com fallback para {@code metric}
     */
    public List<StatsMetricRequest> effectiveMetrics() {
        if (metrics != null && !metrics.isEmpty()) {
            return List.copyOf(metrics);
        }
        return metric != null ? List.of(metric) : List.of();
    }
}
