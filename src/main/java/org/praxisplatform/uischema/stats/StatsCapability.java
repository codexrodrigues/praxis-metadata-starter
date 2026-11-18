package org.praxisplatform.uischema.stats;

import java.util.List;
import java.util.Set;

/**
 * Discovery publico das dimensoes estatisticas declaradas por um recurso.
 *
 * <p>
 * Este contrato e uma projecao do {@link StatsFieldRegistry}; ele nao redefine a regra de
 * elegibilidade. Consumidores podem usar este inventario para escolher charts antes de executar
 * consultas em {@code /stats/group-by}, {@code /stats/timeseries} ou {@code /stats/distribution}.
 * </p>
 *
 * @param fields campos estatisticos elegiveis no recurso
 * @param maxDimensions numero maximo de dimensoes simultaneas suportadas em group-by
 * @param maxCells numero maximo de celulas (buckets) suportadas em consultas multidimensionais/densificadas
 * @param maxTimeSeriesWindowDays janela maxima em dias permitida para series temporais diarias ({@code DAY})
 * @param defaultMinBucketCount limiar minimo padrao de k-anonymity (LGPD) aplicado aos campos sem override proprio ({@code 0} = desabilitado por padrao)
 * @param maxInMemorySampleRows limite maximo de linhas carregadas em memoria para agregacoes que exigem ordenacao/amostragem em Java
 */
public record StatsCapability(
        List<StatsFieldCapability> fields,
        int maxDimensions,
        int maxCells,
        int maxTimeSeriesWindowDays,
        int defaultMinBucketCount,
        int maxInMemorySampleRows
) {

    public static final int DEFAULT_MAX_DIMENSIONS = 2;
    public static final int DEFAULT_MAX_CELLS = 500;
    public static final int DEFAULT_MAX_TIME_SERIES_WINDOW_DAYS = 366;
    public static final int DEFAULT_MIN_BUCKET_COUNT = 0;
    public static final int DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS = 100_000;

    public StatsCapability {
        fields = fields == null ? List.of() : List.copyOf(fields);
        maxDimensions = maxDimensions <= 0 ? DEFAULT_MAX_DIMENSIONS : maxDimensions;
        maxCells = maxCells <= 0 ? DEFAULT_MAX_CELLS : maxCells;
        maxTimeSeriesWindowDays = maxTimeSeriesWindowDays <= 0
                ? DEFAULT_MAX_TIME_SERIES_WINDOW_DAYS
                : maxTimeSeriesWindowDays;
        defaultMinBucketCount = Math.max(0, defaultMinBucketCount);
        maxInMemorySampleRows = maxInMemorySampleRows <= 0
                ? DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
                : maxInMemorySampleRows;
    }

    public StatsCapability(List<StatsFieldCapability> fields) {
        this(
                fields,
                DEFAULT_MAX_DIMENSIONS,
                DEFAULT_MAX_CELLS,
                DEFAULT_MAX_TIME_SERIES_WINDOW_DAYS,
                DEFAULT_MIN_BUCKET_COUNT,
                DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
        );
    }

    public StatsCapability(List<StatsFieldCapability> fields, int maxDimensions, int maxCells) {
        this(
                fields,
                maxDimensions,
                maxCells,
                DEFAULT_MAX_TIME_SERIES_WINDOW_DAYS,
                DEFAULT_MIN_BUCKET_COUNT,
                DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
        );
    }

    public StatsCapability(
            List<StatsFieldCapability> fields,
            int maxDimensions,
            int maxCells,
            int maxTimeSeriesWindowDays,
            int defaultMinBucketCount
    ) {
        this(
                fields,
                maxDimensions,
                maxCells,
                maxTimeSeriesWindowDays,
                defaultMinBucketCount,
                DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
        );
    }

    public static StatsCapability empty() {
        return new StatsCapability(
                List.of(),
                DEFAULT_MAX_DIMENSIONS,
                DEFAULT_MAX_CELLS,
                DEFAULT_MAX_TIME_SERIES_WINDOW_DAYS,
                DEFAULT_MIN_BUCKET_COUNT,
                DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
        );
    }

    public static StatsCapability from(StatsFieldRegistry registry) {
        if (registry == null || registry.isEmpty()) {
            return empty();
        }
        return new StatsCapability(
                registry.descriptors().stream()
                        .map(StatsFieldCapability::from)
                        .toList(),
                DEFAULT_MAX_DIMENSIONS,
                DEFAULT_MAX_CELLS,
                DEFAULT_MAX_TIME_SERIES_WINDOW_DAYS,
                DEFAULT_MIN_BUCKET_COUNT,
                DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
        );
    }

    public static StatsCapability from(
            StatsFieldRegistry registry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode
    ) {
        return from(
                registry,
                groupByStatsSupportMode,
                timeSeriesStatsSupportMode,
                distributionStatsSupportMode,
                DEFAULT_MAX_DIMENSIONS,
                DEFAULT_MAX_CELLS,
                DEFAULT_MAX_TIME_SERIES_WINDOW_DAYS,
                DEFAULT_MIN_BUCKET_COUNT
        );
    }

    public static StatsCapability from(
            StatsFieldRegistry registry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode,
            int maxDimensions,
            int maxCells
    ) {
        return from(
                registry,
                groupByStatsSupportMode,
                timeSeriesStatsSupportMode,
                distributionStatsSupportMode,
                maxDimensions,
                maxCells,
                DEFAULT_MAX_TIME_SERIES_WINDOW_DAYS,
                DEFAULT_MIN_BUCKET_COUNT
        );
    }

    public static StatsCapability from(
            StatsFieldRegistry registry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode,
            int maxDimensions,
            int maxCells,
            int maxTimeSeriesWindowDays,
            int defaultMinBucketCount
    ) {
        return from(
                registry,
                groupByStatsSupportMode,
                timeSeriesStatsSupportMode,
                distributionStatsSupportMode,
                maxDimensions,
                maxCells,
                maxTimeSeriesWindowDays,
                defaultMinBucketCount,
                DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
        );
    }

    public static StatsCapability from(
            StatsFieldRegistry registry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode,
            int maxDimensions,
            int maxCells,
            int maxTimeSeriesWindowDays,
            int defaultMinBucketCount,
            int maxInMemorySampleRows
    ) {
        if (registry == null || registry.isEmpty()) {
            return new StatsCapability(
                    List.of(),
                    maxDimensions,
                    maxCells,
                    maxTimeSeriesWindowDays,
                    defaultMinBucketCount,
                    maxInMemorySampleRows
            );
        }
        boolean groupByEnabled = groupByStatsSupportMode != StatsSupportMode.DISABLED;
        boolean timeSeriesEnabled = timeSeriesStatsSupportMode != StatsSupportMode.DISABLED;
        boolean distributionEnabled = distributionStatsSupportMode != StatsSupportMode.DISABLED;
        if (!groupByEnabled && !timeSeriesEnabled && !distributionEnabled) {
            return new StatsCapability(
                    List.of(),
                    maxDimensions,
                    maxCells,
                    maxTimeSeriesWindowDays,
                    defaultMinBucketCount,
                    maxInMemorySampleRows
            );
        }
        return new StatsCapability(
                registry.descriptors().stream()
                        .map(descriptor -> restrictDescriptor(
                                descriptor,
                                groupByEnabled,
                                timeSeriesEnabled,
                                distributionEnabled
                        ))
                        .filter(descriptor -> descriptor.groupByEligible()
                                || descriptor.timeSeriesEligible()
                                || descriptor.distributionTermsEligible()
                                || descriptor.distributionHistogramEligible()
                                || descriptor.metricFieldEligible())
                        .map(StatsFieldCapability::from)
                        .toList(),
                maxDimensions,
                maxCells,
                maxTimeSeriesWindowDays,
                defaultMinBucketCount,
                maxInMemorySampleRows
        );
    }

    private static StatsFieldDescriptor restrictDescriptor(
            StatsFieldDescriptor descriptor,
            boolean groupByEnabled,
            boolean timeSeriesEnabled,
            boolean distributionEnabled
    ) {
        boolean hasBucketMode = groupByEnabled && descriptor.groupByEligible();
        boolean hasTimeSeriesMode = timeSeriesEnabled && descriptor.timeSeriesEligible();
        boolean hasTermsMode = distributionEnabled && descriptor.distributionTermsEligible();
        boolean hasHistogramMode = distributionEnabled && descriptor.distributionHistogramEligible();
        boolean hasAnyStatsMode = hasBucketMode || hasTimeSeriesMode || hasTermsMode || hasHistogramMode;
        boolean hasMetricMode = descriptor.metricFieldEligible()
                && (groupByEnabled || timeSeriesEnabled || distributionEnabled);
        return new StatsFieldDescriptor(
                descriptor.field(),
                descriptor.keyPropertyPath(),
                descriptor.labelPropertyPath(),
                hasAnyStatsMode || hasMetricMode ? descriptor.metrics() : Set.of(),
                hasBucketMode,
                hasTimeSeriesMode,
                hasTermsMode,
                hasHistogramMode,
                hasMetricMode,
                descriptor.endPropertyPath(),
                descriptor.minBucketCount()
        );
    }
}
