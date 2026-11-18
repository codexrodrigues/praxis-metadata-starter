package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;

import java.util.List;

/**
 * Contrato canonico de resposta para series temporais agregadas.
 *
 * <p>
 * A resposta devolve o campo temporal de referencia, a granularidade aplicada e a serie de pontos
 * resultante. Em cenarios multi-metrica, a lista {@code metrics} expoe as metricas efetivamente
 * processadas, preservando {@code metric} como campo principal da resposta. Quando ha politica de
 * k-anonymity ativa ou supressao LGPD aplicada, publica tambem os metadados explicitos de supressao.
 * </p>
 */
public record TimeSeriesStatsResponse(
        String field,
        TimeSeriesGranularity granularity,
        StatsMetricRequest metric,
        List<TimeSeriesPoint> points,
        List<StatsMetricRequest> metrics,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean suppressed,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer suppressedBucketCount,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer minBucketCount
) {
    /**
     * Construtor para respostas de metrica unica.
     */
    public TimeSeriesStatsResponse(
            String field,
            TimeSeriesGranularity granularity,
            StatsMetricRequest metric,
            List<TimeSeriesPoint> points
    ) {
        this(field, granularity, metric, points, null, null, null, null);
    }

    /**
     * Construtor para respostas multi-metrica sem metadado de supressao.
     */
    public TimeSeriesStatsResponse(
            String field,
            TimeSeriesGranularity granularity,
            StatsMetricRequest metric,
            List<TimeSeriesPoint> points,
            List<StatsMetricRequest> metrics
    ) {
        this(field, granularity, metric, points, metrics, null, null, null);
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
