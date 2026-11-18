package org.praxisplatform.uischema.stats;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Campo estatistico materializado para discovery em capabilities.
 *
 * @param field campo canonico aceito nos payloads de stats
 * @param label rotulo sugerido para UX quando o schema filtrado nao tiver titulo melhor
 * @param keyAndLabelDistinct indica se a dimensao publica identidade e display separados
 * @param metrics metricas agregadas aceitas para o campo
 * @param modes modos estatisticos em que o campo pode participar
 * @param groupByEligible indica se o campo pode ser bucket de group-by
 * @param timeSeriesEligible indica se o campo pode ser eixo temporal
 * @param distributionTermsEligible indica se o campo pode ser bucket de distribuicao por termos
 * @param distributionHistogramEligible indica se o campo pode ser histograma
 * @param metricFieldEligible indica se o campo pode ser metric.field
 * @param supportedExtractions extracoes de dimensao aceitas para o campo em group-by
 * @param temporalInterval indica se o eixo temporal representa intervalo ativo {@code [start, end]}
 * @param minBucketCount limiar minimo declarativo de contagem (k-anonymity / LGPD) configurado especificamente no campo, quando definido
 */
public record StatsFieldCapability(
        String field,
        String label,
        boolean keyAndLabelDistinct,
        List<String> metrics,
        List<String> modes,
        boolean groupByEligible,
        boolean timeSeriesEligible,
        boolean distributionTermsEligible,
        boolean distributionHistogramEligible,
        boolean metricFieldEligible,
        List<String> supportedExtractions,
        boolean temporalInterval,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer minBucketCount
) {

    public StatsFieldCapability {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        modes = modes == null ? List.of() : List.copyOf(modes);
        supportedExtractions = supportedExtractions == null
                ? defaultExtractions(groupByEligible, timeSeriesEligible, temporalInterval)
                : List.copyOf(supportedExtractions);
    }

    public StatsFieldCapability(
            String field,
            String label,
            boolean keyAndLabelDistinct,
            List<String> metrics,
            List<String> modes,
            boolean groupByEligible,
            boolean timeSeriesEligible,
            boolean distributionTermsEligible,
            boolean distributionHistogramEligible,
            boolean metricFieldEligible
    ) {
        this(
                field,
                label,
                keyAndLabelDistinct,
                metrics,
                modes,
                groupByEligible,
                timeSeriesEligible,
                distributionTermsEligible,
                distributionHistogramEligible,
                metricFieldEligible,
                defaultExtractions(groupByEligible, timeSeriesEligible, false),
                false,
                null
        );
    }

    public StatsFieldCapability(
            String field,
            String label,
            boolean keyAndLabelDistinct,
            List<String> metrics,
            List<String> modes,
            boolean groupByEligible,
            boolean timeSeriesEligible,
            boolean distributionTermsEligible,
            boolean distributionHistogramEligible,
            boolean metricFieldEligible,
            List<String> supportedExtractions
    ) {
        this(
                field,
                label,
                keyAndLabelDistinct,
                metrics,
                modes,
                groupByEligible,
                timeSeriesEligible,
                distributionTermsEligible,
                distributionHistogramEligible,
                metricFieldEligible,
                supportedExtractions,
                false,
                null
        );
    }

    public static StatsFieldCapability from(StatsFieldDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        boolean isInterval = descriptor.isTemporalInterval();
        return new StatsFieldCapability(
                descriptor.field(),
                humanize(descriptor.field()),
                !isInterval && !java.util.Objects.equals(descriptor.keyPropertyPath(), descriptor.labelPropertyPath()),
                descriptor.metrics().stream()
                        .sorted(Comparator.comparing(Enum::ordinal))
                        .map(Enum::name)
                        .toList(),
                modes(descriptor),
                descriptor.groupByEligible(),
                descriptor.timeSeriesEligible(),
                descriptor.distributionTermsEligible(),
                descriptor.distributionHistogramEligible(),
                descriptor.metricFieldEligible(),
                defaultExtractions(descriptor.groupByEligible(), descriptor.timeSeriesEligible(), isInterval),
                isInterval,
                descriptor.minBucketCount()
        );
    }

    private static List<String> defaultExtractions(
            boolean groupByEligible,
            boolean timeSeriesEligible,
            boolean temporalInterval
    ) {
        if (temporalInterval) {
            return List.of();
        }
        List<String> extractions = new ArrayList<>();
        if (groupByEligible) {
            extractions.add(StatsDimensionExtraction.NONE.name());
        }
        if (timeSeriesEligible) {
            extractions.addAll(StatsDimensionExtraction.allTemporalExtractionNames());
        }
        return List.copyOf(extractions);
    }

    private static List<String> modes(StatsFieldDescriptor descriptor) {
        List<String> modes = new ArrayList<>();
        if (descriptor.groupByEligible()) {
            modes.add("GROUP_BY");
        }
        if (descriptor.timeSeriesEligible()) {
            modes.add("TIME_SERIES");
        }
        if (descriptor.distributionTermsEligible()) {
            modes.add("DISTRIBUTION_TERMS");
        }
        if (descriptor.distributionHistogramEligible()) {
            modes.add("DISTRIBUTION_HISTOGRAM");
        }
        if (descriptor.metricFieldEligible()) {
            modes.add("METRIC_FIELD");
        }
        return List.copyOf(modes);
    }

    private static String humanize(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        String spaced = field
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
                .trim();
        if (spaced.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
