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
 */
public record StatsCapability(
        List<StatsFieldCapability> fields
) {

    public StatsCapability {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static StatsCapability empty() {
        return new StatsCapability(List.of());
    }

    public static StatsCapability from(StatsFieldRegistry registry) {
        if (registry == null || registry.isEmpty()) {
            return empty();
        }
        return new StatsCapability(
                registry.descriptors().stream()
                        .map(StatsFieldCapability::from)
                        .toList()
        );
    }

    public static StatsCapability from(
            StatsFieldRegistry registry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode
    ) {
        if (registry == null || registry.isEmpty()) {
            return empty();
        }
        boolean groupByEnabled = groupByStatsSupportMode != StatsSupportMode.DISABLED;
        boolean timeSeriesEnabled = timeSeriesStatsSupportMode != StatsSupportMode.DISABLED;
        boolean distributionEnabled = distributionStatsSupportMode != StatsSupportMode.DISABLED;
        if (!groupByEnabled && !timeSeriesEnabled && !distributionEnabled) {
            return empty();
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
                        .toList()
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
                hasMetricMode
        );
    }
}
