package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;

import java.time.LocalDate;
import java.util.List;

/**
 * Contrato canonico de request para series temporais agregadas.
 *
 * <p>
 * O request define o campo temporal, a granularidade, o intervalo de datas e a metrica agregada,
 * sempre combinado ao filtro principal do recurso. Assim, dashboards e componentes derivados podem
 * operar sobre uma superficie estatistica consistente entre modulos da plataforma.
 * </p>
 *
 * @param <FD> tipo do filtro do recurso
 */
public record TimeSeriesStatsRequest<FD extends GenericFilterDTO>(
        FD filter,
        String field,
        TimeSeriesGranularity granularity,
        StatsMetricRequest metric,
        LocalDate from,
        LocalDate to,
        Boolean fillGaps,
        List<StatsMetricRequest> metrics
) {
    /**
     * Construtor para o modo de metrica unica.
     */
    public TimeSeriesStatsRequest(
            FD filter,
            String field,
            TimeSeriesGranularity granularity,
            StatsMetricRequest metric,
            LocalDate from,
            LocalDate to,
            Boolean fillGaps
    ) {
        this(filter, field, granularity, metric, from, to, fillGaps, null);
    }

    /**
     * Retorna a lista efetiva de metricas solicitadas.
     *
     * @return lista efetiva de metricas; usa {@code metrics} quando presente, com fallback para {@code metric}
     */
    public List<StatsMetricRequest> effectiveMetrics() {
        if (metrics != null && !metrics.isEmpty()) {
            return List.copyOf(metrics);
        }
        return metric != null ? List.of(metric) : List.of();
    }

    /**
     * Retorna a metrica primaria para consumidores de metrica unica.
     *
     * @return primeira metrica efetiva ou {@code null}
     */
    public StatsMetricRequest primaryMetric() {
        List<StatsMetricRequest> effective = effectiveMetrics();
        return effective.isEmpty() ? null : effective.get(0);
    }
}
