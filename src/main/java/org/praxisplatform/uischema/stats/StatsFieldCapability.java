package org.praxisplatform.uischema.stats;

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
        boolean metricFieldEligible
) {

    public StatsFieldCapability {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        modes = modes == null ? List.of() : List.copyOf(modes);
    }

    public static StatsFieldCapability from(StatsFieldDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        return new StatsFieldCapability(
                descriptor.field(),
                humanize(descriptor.field()),
                !java.util.Objects.equals(descriptor.keyPropertyPath(), descriptor.labelPropertyPath()),
                descriptor.metrics().stream()
                        .sorted(Comparator.comparing(Enum::ordinal))
                        .map(Enum::name)
                        .toList(),
                modes(descriptor),
                descriptor.groupByEligible(),
                descriptor.timeSeriesEligible(),
                descriptor.distributionTermsEligible(),
                descriptor.distributionHistogramEligible(),
                descriptor.metricFieldEligible()
        );
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
