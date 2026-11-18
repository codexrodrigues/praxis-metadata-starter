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
 * @param endPropertyPath caminho opcional da extremidade final para eixos temporais em intervalo ativo {@code [keyPropertyPath, endPropertyPath]}
 * @param minBucketCount limiar declarativo minimo de contagem para supressao LGPD em buckets/celulas (nulo herda o default global; {@code 0} desabilita no campo)
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
        boolean metricFieldEligible,
        String endPropertyPath,
        Integer minBucketCount
) {
    /**
     * Normaliza a colecao de metricas e invariantes de intervalo/supressao.
     */
    public StatsFieldDescriptor {
        metrics = metrics == null ? Set.of() : Set.copyOf(metrics);
        labelPropertyPath = labelPropertyPath == null || labelPropertyPath.isBlank()
                ? keyPropertyPath
                : labelPropertyPath;
        endPropertyPath = endPropertyPath == null || endPropertyPath.isBlank()
                ? null
                : endPropertyPath.trim();
        if (minBucketCount != null && minBucketCount < 0) {
            throw new IllegalArgumentException("minBucketCount must not be negative");
        }
    }

    public StatsFieldDescriptor(
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
        this(
                field,
                keyPropertyPath,
                labelPropertyPath,
                metrics,
                groupByEligible,
                timeSeriesEligible,
                distributionTermsEligible,
                distributionHistogramEligible,
                metricFieldEligible,
                null,
                null
        );
    }

    public StatsFieldDescriptor(
            String field,
            String propertyPath,
            Set<StatsMetric> metrics
    ) {
        this(field, propertyPath, propertyPath, metrics, true, true, true, true, true, null, null);
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

    public static StatsFieldDescriptor categoricalGroupByBucket(
            String field,
            String propertyPath,
            Integer minBucketCount
    ) {
        return groupByBucket(field, propertyPath, Set.of(StatsMetric.COUNT)).withMinBucketCount(minBucketCount);
    }

    public static StatsFieldDescriptor categoricalTermsBucket(
            String field,
            String propertyPath
    ) {
        return distributionTermsBucket(field, propertyPath, Set.of(StatsMetric.COUNT));
    }

    public static StatsFieldDescriptor categoricalTermsBucket(
            String field,
            String propertyPath,
            Integer minBucketCount
    ) {
        return distributionTermsBucket(field, propertyPath, Set.of(StatsMetric.COUNT)).withMinBucketCount(minBucketCount);
    }

    public static StatsFieldDescriptor temporalTimeSeriesField(
            String field,
            String propertyPath
    ) {
        return timeSeriesField(field, propertyPath);
    }

    public static StatsFieldDescriptor temporalIntervalTimeSeriesField(
            String field,
            String startPropertyPath,
            String endPropertyPath
    ) {
        return temporalIntervalTimeSeriesField(field, startPropertyPath, endPropertyPath, Set.of(StatsMetric.COUNT));
    }

    public static StatsFieldDescriptor temporalIntervalTimeSeriesField(
            String field,
            String startPropertyPath,
            String endPropertyPath,
            Set<StatsMetric> metrics
    ) {
        if (startPropertyPath == null || startPropertyPath.isBlank()) {
            throw new IllegalArgumentException("startPropertyPath must not be blank");
        }
        if (endPropertyPath == null || endPropertyPath.isBlank()) {
            throw new IllegalArgumentException("endPropertyPath must not be blank");
        }
        return new StatsFieldDescriptor(
                field,
                startPropertyPath.trim(),
                startPropertyPath.trim(),
                metrics == null || metrics.isEmpty() ? Set.of(StatsMetric.COUNT) : metrics,
                false,
                true,
                false,
                false,
                false,
                endPropertyPath.trim(),
                null
        );
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
                StatsMetric.MAX,
                StatsMetric.PERCENTILE_25,
                StatsMetric.MEDIAN,
                StatsMetric.PERCENTILE_75,
                StatsMetric.PERCENTILE_90,
                StatsMetric.PERCENTILE_95
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
                StatsMetric.MAX,
                StatsMetric.PERCENTILE_25,
                StatsMetric.MEDIAN,
                StatsMetric.PERCENTILE_75,
                StatsMetric.PERCENTILE_90,
                StatsMetric.PERCENTILE_95
        ));
    }

    public static StatsFieldDescriptor distinctCountField(
            String field,
            String propertyPath
    ) {
        return metricField(field, propertyPath, Set.of(StatsMetric.DISTINCT_COUNT));
    }

    public boolean isTemporalInterval() {
        return endPropertyPath != null && !endPropertyPath.isBlank();
    }

    public String startPropertyPath() {
        return keyPropertyPath;
    }

    public StatsFieldDescriptor withMinBucketCount(Integer minBucketCount) {
        return new StatsFieldDescriptor(
                field,
                keyPropertyPath,
                labelPropertyPath,
                metrics,
                groupByEligible,
                timeSeriesEligible,
                distributionTermsEligible,
                distributionHistogramEligible,
                metricFieldEligible,
                endPropertyPath,
                minBucketCount
        );
    }

    public int resolveMinBucketCount(int defaultMinBucketCount) {
        if (minBucketCount != null) {
            return Math.max(0, minBucketCount);
        }
        return Math.max(0, defaultMinBucketCount);
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
