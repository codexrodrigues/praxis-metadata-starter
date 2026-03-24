package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.stats.TimeSeriesGranularity;

import java.util.List;

/**
 * Contrato canonico de resposta para series temporais agregadas.
 *
 * <p>
 * A resposta devolve o campo temporal de referencia, a granularidade aplicada e a serie de pontos
 * resultante. Em cenarios multi-metrica, a lista {@code metrics} expõe as metricas efetivamente
 * processadas, mantendo compatibilidade com o campo principal {@code metric}.
 * </p>
 */
public record TimeSeriesStatsResponse(
        String field,
        TimeSeriesGranularity granularity,
        StatsMetricRequest metric,
        List<TimeSeriesPoint> points,
        List<StatsMetricRequest> metrics
) {
    /**
     * Construtor de compatibilidade para respostas de metrica unica.
     */
    public TimeSeriesStatsResponse(
            String field,
            TimeSeriesGranularity granularity,
            StatsMetricRequest metric,
            List<TimeSeriesPoint> points
    ) {
        this(field, granularity, metric, points, null);
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
