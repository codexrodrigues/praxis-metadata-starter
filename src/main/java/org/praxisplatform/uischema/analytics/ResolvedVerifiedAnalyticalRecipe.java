package org.praxisplatform.uischema.analytics;

import org.praxisplatform.uischema.annotation.AnalyticalRecipeGovernanceTier;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.stats.ComparisonPeriodMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsDimensionExtraction;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Representacao imutavel, validada e pronta para publicacao/execucao de uma
 * receita analitica verificada de um recurso.
 */
public record ResolvedVerifiedAnalyticalRecipe(
        String id,
        String title,
        String description,
        String intent,
        String resourceKey,
        String resourcePath,
        String endpointPath,
        AnalyticsOperation operation,
        String field,
        List<VerifiedAnalyticalRecipeDescriptor.DimensionSpec> dimensions,
        List<VerifiedAnalyticalRecipeDescriptor.MetricSpec> metrics,
        StatsBucketOrder orderBy,
        int limit,
        TimeSeriesGranularity granularity,
        boolean fillGaps,
        DistributionMode distributionMode,
        Integer bucketCount,
        BigDecimal bucketSize,
        String timezone,
        String periodField,
        ComparisonPeriodPreset periodPreset,
        ComparisonPeriodMode periodMode,
        Map<String, Object> lockedFilters,
        List<VerifiedAnalyticalRecipeDescriptor.ParameterSpec> adjustableParams,
        AnalyticalRecipeGovernanceTier governanceTier,
        List<String> domainTags,
        String version,
        int order,
        String provenanceDigest
) {

    public ResolvedVerifiedAnalyticalRecipe {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        lockedFilters = lockedFilters == null ? Map.of() : Map.copyOf(lockedFilters);
        adjustableParams = adjustableParams == null ? List.of() : List.copyOf(adjustableParams);
        domainTags = domainTags == null ? List.of() : List.copyOf(domainTags);
    }

    /**
     * Projeta a receita no formato canonico publicado em {@code x-ui.analytics.verifiedRecipes},
     * {@code GET /stats/recipes} e {@code /schemas/domain}.
     */
    public Map<String, Object> toMetadataMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("title", title);
        if (hasText(description)) {
            payload.put("description", description);
        }
        if (hasText(intent)) {
            payload.put("intent", intent);
        }
        if (hasText(resourceKey)) {
            payload.put("resourceKey", resourceKey);
        }
        if (hasText(resourcePath)) {
            payload.put("resourcePath", resourcePath);
        }
        payload.put("endpointPath", endpointPath);
        payload.put("operation", operation.wireValue());
        if (hasText(field)) {
            payload.put("field", field);
        }
        if (!dimensions.isEmpty()) {
            List<Map<String, Object>> dims = new ArrayList<>(dimensions.size());
            for (VerifiedAnalyticalRecipeDescriptor.DimensionSpec dim : dimensions) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("field", dim.field());
                d.put("extraction", dim.extraction().name());
                if (hasText(dim.alias())) {
                    d.put("alias", dim.alias());
                }
                dims.add(Map.copyOf(d));
            }
            payload.put("dimensions", List.copyOf(dims));
        }
        if (!metrics.isEmpty()) {
            List<Map<String, Object>> ms = new ArrayList<>(metrics.size());
            for (VerifiedAnalyticalRecipeDescriptor.MetricSpec metric : metrics) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("operation", metric.operation().wireValue());
                if (hasText(metric.field())) {
                    m.put("field", metric.field());
                }
                if (hasText(metric.alias())) {
                    m.put("alias", metric.alias());
                }
                ms.add(Map.copyOf(m));
            }
            payload.put("metrics", List.copyOf(ms));
        }
        if (operation == AnalyticsOperation.GROUP_BY
                || operation == AnalyticsOperation.DISTRIBUTION
                || operation == AnalyticsOperation.COMPARISON) {
            payload.put("orderBy", orderBy.name());
            payload.put("limit", limit);
        }
        if (operation == AnalyticsOperation.TIMESERIES) {
            payload.put("granularity", granularity.name());
            payload.put("fillGaps", fillGaps);
        }
        if (operation == AnalyticsOperation.DISTRIBUTION) {
            payload.put("distributionMode", distributionMode.name());
            if (bucketCount != null && bucketCount > 0) {
                payload.put("bucketCount", bucketCount);
            }
            if (bucketSize != null) {
                payload.put("bucketSize", bucketSize);
            }
        }
        if (operation == AnalyticsOperation.COMPARISON) {
            payload.put("periodPreset", periodPreset.name());
            payload.put("periodMode", periodMode.name());
        }
        if (hasText(timezone)) {
            payload.put("timezone", timezone);
        }
        if (hasText(periodField)) {
            payload.put("periodField", periodField);
        }
        if (!lockedFilters.isEmpty()) {
            payload.put("lockedFilters", lockedFilters);
        }
        if (!adjustableParams.isEmpty()) {
            List<Map<String, Object>> params = new ArrayList<>(adjustableParams.size());
            for (VerifiedAnalyticalRecipeDescriptor.ParameterSpec param : adjustableParams) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", param.name());
                if (hasText(param.filterField())) {
                    p.put("filterField", param.filterField());
                }
                if (hasText(param.description())) {
                    p.put("description", param.description());
                }
                p.put("dataType", param.dataType());
                p.put("required", param.required());
                if (hasText(param.defaultValue())) {
                    p.put("defaultValue", param.defaultValue());
                }
                if (!param.allowedValues().isEmpty()) {
                    p.put("allowedValues", param.allowedValues());
                }
                if (hasText(param.minValue())) {
                    p.put("minValue", param.minValue());
                }
                if (hasText(param.maxValue())) {
                    p.put("maxValue", param.maxValue());
                }
                params.add(Map.copyOf(p));
            }
            payload.put("adjustableParams", List.copyOf(params));
        }
        payload.put("governanceTier", governanceTier.wireValue());
        if (!domainTags.isEmpty()) {
            payload.put("domainTags", domainTags);
        }
        payload.put("version", version);
        payload.put("provenanceDigest", provenanceDigest);
        return Map.copyOf(payload);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
