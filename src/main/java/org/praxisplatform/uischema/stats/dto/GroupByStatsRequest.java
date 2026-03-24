package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.StatsBucketOrder;

import java.util.List;

/**
 * Contrato canonico de request para agregacoes {@code group-by}.
 *
 * <p>
 * O request combina o filtro principal do recurso com a definicao do campo de agrupamento e da
 * metrica agregada. A plataforma tambem suporta o modo multi-metrica via {@code metrics}, mantendo
 * compatibilidade com o campo legado {@code metric} como primary metric.
 * </p>
 *
 * @param <FD> tipo do filtro do recurso
 */
public record GroupByStatsRequest<FD extends GenericFilterDTO>(
        FD filter,
        String field,
        StatsMetricRequest metric,
        Integer limit,
        StatsBucketOrder orderBy,
        List<StatsMetricRequest> metrics
) {
    /**
     * Construtor de compatibilidade para o modo de metrica unica.
     */
    public GroupByStatsRequest(FD filter, String field, StatsMetricRequest metric, Integer limit, StatsBucketOrder orderBy) {
        this(filter, field, metric, limit, orderBy, null);
    }

    /**
     * Retorna a lista efetiva de metricas solicitadas.
     *
     * <p>
     * Quando {@code metrics} estiver preenchido, ele prevalece. Caso contrario, o metodo faz
     * fallback para a metrica unica em {@code metric}.
     * </p>
     *
     * @return lista efetiva de metricas
     */
    public List<StatsMetricRequest> effectiveMetrics() {
        if (metrics != null && !metrics.isEmpty()) {
            return List.copyOf(metrics);
        }
        return metric != null ? List.of(metric) : List.of();
    }

    /**
     * Retorna a metrica primaria para consumidores que ainda operam em modo de metrica unica.
     *
     * @return primeira metrica efetiva ou {@code null} quando nenhuma foi informada
     */
    public StatsMetricRequest primaryMetric() {
        List<StatsMetricRequest> effective = effectiveMetrics();
        return effective.isEmpty() ? null : effective.get(0);
    }
}
