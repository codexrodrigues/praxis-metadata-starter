package org.praxisplatform.uischema.stats;

import java.util.Set;

/**
 * Descreve um campo elegivel para stats filtrados.
 *
 * <p>
 * O descritor conecta o nome canonico exposto na API aos paths usados internamente
 * nas agregacoes, e declara em quais superficies estatisticas o campo pode aparecer: buckets,
 * eixo temporal, distribuicao ou campo de metrica.
 * </p>
 *
 * @param field campo canonico exposto pela API
 * @param keyPropertyPath caminho da identidade do bucket ou do valor do campo
 * @param labelPropertyPath caminho opcional do label do bucket; usa a key quando ausente
 * @param metrics metricas agregadas permitidas para o campo
 * @param groupByEligible indica se o campo pode ser usado como bucket de group-by
 * @param timeSeriesEligible indica se o campo pode ser usado como eixo temporal
 * @param distributionTermsEligible indica se o campo pode ser usado em distribuicao por termos
 * @param distributionHistogramEligible indica se o campo pode ser usado em distribuicao por histograma
 * @param metricFieldEligible indica se o campo pode ser usado como {@code metric.field}
 */
public record StatsFieldDescriptor(
        String field,
        String keyPropertyPath,
        String labelPropertyPath,
        Set<StatsMetric> metrics,
        boolean groupByEligible,
        boolean timeSeriesEligible,
        boolean distributionTermsEligible,
        boolean distributionHistogramEligible,
        boolean metricFieldEligible
) {
    /**
     * Normaliza a colecao de metricas para um conjunto imutavel.
     */
    public StatsFieldDescriptor {
        metrics = metrics == null ? Set.of() : Set.copyOf(metrics);
        labelPropertyPath = labelPropertyPath == null || labelPropertyPath.isBlank()
                ? keyPropertyPath
                : labelPropertyPath;
    }

    public StatsFieldDescriptor(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        this(field, propertyPath, propertyPath, metrics, true, true, true, true, true);
    }

    public static StatsFieldDescriptor groupByBucket(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        return new StatsFieldDescriptor(field, propertyPath, propertyPath, metrics, true, false, true, false, false);
    }

    public static StatsFieldDescriptor labeledGroupByBucket(
            String field,
            String keyPropertyPath,
            String labelPropertyPath,
            Set<StatsMetric> metrics
    ) {
        return new StatsFieldDescriptor(
                field,
                keyPropertyPath,
                labelPropertyPath,
                metrics,
                true,
                false,
                true,
                false,
                false
        );
    }

    public static StatsFieldDescriptor timeSeriesField(
            String field,
            String propertyPath
    ) {
        return new StatsFieldDescriptor(field, propertyPath, propertyPath, Set.of(StatsMetric.COUNT), false, true, false, false, false);
    }

    public static StatsFieldDescriptor metricField(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        return new StatsFieldDescriptor(field, propertyPath, propertyPath, metrics, false, false, false, false, true);
    }

    public static StatsFieldDescriptor histogramField(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        return new StatsFieldDescriptor(field, propertyPath, propertyPath, metrics, false, false, false, true, true);
    }

    public static StatsFieldDescriptor distributionTermsBucket(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        return new StatsFieldDescriptor(field, propertyPath, propertyPath, metrics, false, false, true, false, false);
    }

    public static StatsFieldDescriptor categoricalGroupByBucket(
            String field,
            String propertyPath
    ) {
        return groupByBucket(field, propertyPath, Set.of(StatsMetric.COUNT));
    }

    public static StatsFieldDescriptor categoricalTermsBucket(
            String field,
            String propertyPath
    ) {
        return distributionTermsBucket(field, propertyPath, Set.of(StatsMetric.COUNT));
    }

    public static StatsFieldDescriptor temporalTimeSeriesField(
            String field,
            String propertyPath
    ) {
        return timeSeriesField(field, propertyPath);
    }

    public static StatsFieldDescriptor numericMeasureField(
            String field,
            String propertyPath
    ) {
        return metricField(field, propertyPath, Set.of(
                StatsMetric.DISTINCT_COUNT,
                StatsMetric.SUM,
                StatsMetric.AVG,
                StatsMetric.MIN,
                StatsMetric.MAX
        ));
    }

    public static StatsFieldDescriptor numericHistogramMeasureField(
            String field,
            String propertyPath
    ) {
        return histogramField(field, propertyPath, Set.of(
                StatsMetric.COUNT,
                StatsMetric.DISTINCT_COUNT,
                StatsMetric.SUM,
                StatsMetric.AVG,
                StatsMetric.MIN,
                StatsMetric.MAX
        ));
    }

    public static StatsFieldDescriptor distinctCountField(
            String field,
            String propertyPath
    ) {
        return metricField(field, propertyPath, Set.of(StatsMetric.DISTINCT_COUNT));
    }

    /**
     * Verifica se o campo suporta a metrica agregada informada.
     *
     * @param metric metrica desejada
     * @return {@code true} quando a metrica estiver habilitada no descritor
     */
    public boolean supports(StatsMetric metric) {
        return metric != null && metrics.contains(metric);
    }
}
