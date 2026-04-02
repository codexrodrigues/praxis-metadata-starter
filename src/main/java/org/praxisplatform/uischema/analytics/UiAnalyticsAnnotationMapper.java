package org.praxisplatform.uischema.analytics;

import org.praxisplatform.uischema.annotation.AnalyticsDimensionBinding;
import org.praxisplatform.uischema.annotation.AnalyticsGranularity;
import org.praxisplatform.uischema.annotation.AnalyticsMetricBinding;
import org.praxisplatform.uischema.annotation.AnalyticsProjection;
import org.praxisplatform.uischema.annotation.AnalyticsPresentationFamily;
import org.praxisplatform.uischema.annotation.AnalyticsSort;
import org.praxisplatform.uischema.annotation.UiAnalytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traduz {@link UiAnalytics} para o payload canonico publicado em {@code x-ui.analytics}.
 */
public class UiAnalyticsAnnotationMapper {

    public Map<String, Object> toXUiAnalytics(UiAnalytics annotation, String operationPath) {
        List<Map<String, Object>> projections = new ArrayList<>();
        for (AnalyticsProjection projection : annotation.projections()) {
            projections.add(toProjection(projection, operationPath));
        }

        Map<String, Object> analytics = new LinkedHashMap<>();
        analytics.put("projections", projections);
        return analytics;
    }

    private Map<String, Object> toProjection(AnalyticsProjection projection, String operationPath) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", projection.id());
        result.put("intent", projection.intent().wireValue());
        result.put("source", buildSource(projection, operationPath));
        result.put("bindings", buildBindings(projection));

        Map<String, Object> defaults = buildDefaults(
                projection.defaultSort(),
                projection.defaultLimit(),
                projection.defaultGranularity()
        );
        if (!defaults.isEmpty()) {
            result.put("defaults", defaults);
        }

        Map<String, Object> presentationHints = buildPresentationHints(projection.preferredFamilies());
        if (!presentationHints.isEmpty()) {
            result.put("presentationHints", presentationHints);
        }

        Map<String, Object> interactions = buildInteractions(projection);
        if (!interactions.isEmpty()) {
            result.put("interactions", interactions);
        }

        return result;
    }

    private Map<String, Object> buildSource(AnalyticsProjection projection, String operationPath) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "praxis.stats");
        source.put("resource", deriveSourceResource(projection.sourceResource(), operationPath));
        source.put("operation", projection.sourceOperation().wireValue());
        return source;
    }

    private String deriveSourceResource(String configuredSourceResource, String operationPath) {
        if (configuredSourceResource != null && !configuredSourceResource.isBlank()) {
            return configuredSourceResource.trim();
        }

        String normalizedPath = operationPath == null ? "" : operationPath.trim();
        for (String suffix : List.of("/stats/group-by", "/stats/timeseries", "/stats/distribution")) {
            if (normalizedPath.endsWith(suffix)) {
                return normalizedPath.substring(0, normalizedPath.length() - suffix.length());
            }
        }
        return normalizedPath;
    }

    private Map<String, Object> buildBindings(AnalyticsProjection projection) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        Map<String, Object> primaryDimension = buildDimension(projection.primaryDimension());
        if (!primaryDimension.isEmpty()) {
            bindings.put("primaryDimension", primaryDimension);
        }
        bindings.put("primaryMetrics", buildMetrics(projection.primaryMetrics()));

        List<Map<String, Object>> secondaryMetrics = buildMetrics(projection.secondaryMetrics());
        if (!secondaryMetrics.isEmpty()) {
            bindings.put("secondaryMetrics", secondaryMetrics);
        }
        return bindings;
    }

    private Map<String, Object> buildDimension(AnalyticsDimensionBinding binding) {
        Map<String, Object> dimension = new LinkedHashMap<>();
        if (binding == null || binding.field().isBlank()) {
            return dimension;
        }
        dimension.put("field", binding.field());
        if (!binding.role().isBlank()) {
            dimension.put("role", binding.role());
        }
        if (!binding.label().isBlank()) {
            dimension.put("label", binding.label());
        }
        return dimension;
    }

    private List<Map<String, Object>> buildMetrics(AnalyticsMetricBinding[] metrics) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (metrics == null) {
            return result;
        }

        for (AnalyticsMetricBinding metric : metrics) {
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("field", metric.field());
            if (!metric.aggregation().isBlank()) {
                next.put("aggregation", metric.aggregation());
            }
            if (!metric.label().isBlank()) {
                next.put("label", metric.label());
            }
            result.add(next);
        }
        return result;
    }

    private Map<String, Object> buildDefaults(
            AnalyticsSort[] defaultSort,
            int defaultLimit,
            AnalyticsGranularity defaultGranularity
    ) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (defaultSort != null && defaultSort.length > 0) {
            List<Map<String, Object>> sort = new ArrayList<>();
            for (AnalyticsSort item : defaultSort) {
                Map<String, Object> next = new LinkedHashMap<>();
                next.put("field", item.field());
                next.put("direction", item.direction().wireValue());
                sort.add(next);
            }
            defaults.put("sort", sort);
        }
        if (defaultLimit > 0) {
            defaults.put("limit", defaultLimit);
        }
        if (defaultGranularity != null && !defaultGranularity.wireValue().isBlank()) {
            defaults.put("granularity", defaultGranularity.wireValue());
        }
        return defaults;
    }

    private Map<String, Object> buildPresentationHints(AnalyticsPresentationFamily[] preferredFamilies) {
        Map<String, Object> presentationHints = new LinkedHashMap<>();
        if (preferredFamilies == null || preferredFamilies.length == 0) {
            return presentationHints;
        }

        List<String> families = new ArrayList<>();
        for (AnalyticsPresentationFamily family : preferredFamilies) {
            families.add(family.wireValue());
        }
        presentationHints.put("preferredFamilies", families);
        return presentationHints;
    }

    private Map<String, Object> buildInteractions(AnalyticsProjection projection) {
        Map<String, Object> interactions = new LinkedHashMap<>();
        if (projection.drillDown()) {
            interactions.put("drillDown", true);
        }
        if (projection.pointSelection()) {
            interactions.put("pointSelection", true);
        }
        if (projection.crossFilter()) {
            interactions.put("crossFilter", true);
        }
        return interactions;
    }
}
