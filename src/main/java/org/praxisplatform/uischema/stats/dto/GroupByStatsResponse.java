package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Contrato canonico de resposta para agregacoes {@code group-by} (1D ou multidimensional 2D/ND).
 *
 * <p>
 * A resposta informa o campo agrupado primario ({@code field}), a metrica principal e a colecao de
 * buckets retornados. Quando a operacao roda em modo multi-metrica, {@code metrics} carrega a lista
 * completa de metricas aplicadas. Quando executada com {@code dimensions[]} ou janela temporal,
 * publica tambem os descritores de dimensao em {@code dimensions} e a janela resolvida em
 * {@code window}.
 * </p>
 */
public record GroupByStatsResponse(
        String field,
        StatsMetricRequest metric,
        List<GroupByBucket> buckets,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<StatsMetricRequest> metrics,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<StatsDimensionRequest> dimensions,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ComparisonPeriodWindow window
) {
    /**
     * Construtor para respostas de metrica unica 1D.
     */
    public GroupByStatsResponse(String field, StatsMetricRequest metric, List<GroupByBucket> buckets) {
        this(field, metric, buckets, null, null, null);
    }

    /**
     * Construtor para respostas 1D com suporte a multi-metrica.
     */
    public GroupByStatsResponse(
            String field,
            StatsMetricRequest metric,
            List<GroupByBucket> buckets,
            List<StatsMetricRequest> metrics
    ) {
        this(field, metric, buckets, metrics, null, null);
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
