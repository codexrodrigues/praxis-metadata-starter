package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsDimensionExtraction;

import java.time.LocalDate;
import java.util.List;

/**
 * Contrato canonico de request para agregacoes {@code group-by} (1D ou multidimensional 2D/ND).
 *
 * <p>
 * O request combina o filtro principal do recurso com a definicao do(s) campo(s) de agrupamento
 * ({@code field} para o modo 1D simples ou {@code dimensions} para agrupamento com extracoes
 * temporais/categoricas e Cross-Tab 2D/ND) e das metricas agregadas ({@code metric} ou
 * {@code metrics}).
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
        List<StatsMetricRequest> metrics,
        List<StatsDimensionRequest> dimensions,
        String timezone,
        ComparisonPeriodPreset periodPreset,
        LocalDate from,
        LocalDate to,
        Boolean fillGaps,
        String periodField
) {
    /**
     * Construtor para o modo de metrica unica 1D.
     */
    public GroupByStatsRequest(
            FD filter,
            String field,
            StatsMetricRequest metric,
            Integer limit,
            StatsBucketOrder orderBy
    ) {
        this(filter, field, metric, limit, orderBy, null, null, null, null, null, null, null, null);
    }

    /**
     * Construtor para o modo multi-metrica 1D.
     */
    public GroupByStatsRequest(
            FD filter,
            String field,
            StatsMetricRequest metric,
            Integer limit,
            StatsBucketOrder orderBy,
            List<StatsMetricRequest> metrics
    ) {
        this(filter, field, metric, limit, orderBy, metrics, null, null, null, null, null, null, null);
    }

    /**
     * Retorna a lista efetiva de dimensoes solicitadas.
     *
     * <p>
     * Quando {@code dimensions} estiver preenchido, ele prevalece. Caso contrario, faz fallback
     * para {@code field} com extracao {@link StatsDimensionExtraction#NONE}.
     * </p>
     */
    public List<StatsDimensionRequest> effectiveDimensions() {
        if (dimensions != null && !dimensions.isEmpty()) {
            return List.copyOf(dimensions);
        }
        if (field != null && !field.isBlank()) {
            return List.of(new StatsDimensionRequest(field.trim(), StatsDimensionExtraction.NONE, field.trim()));
        }
        return List.of();
    }

    /**
     * Indica se o request utiliza a estrutura explicita de {@code dimensions[]} (ex.: 2D/ND ou extracao).
     */
    public boolean hasExplicitDimensions() {
        return dimensions != null && !dimensions.isEmpty();
    }

    /**
     * Indica se o request possui janela temporal explicita ({@code periodPreset} ou {@code from}/{@code to}).
     */
    public boolean hasTemporalWindow() {
        return periodPreset != null || from != null || to != null;
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
