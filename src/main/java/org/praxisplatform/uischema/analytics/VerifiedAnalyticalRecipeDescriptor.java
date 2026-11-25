package org.praxisplatform.uischema.analytics;

import org.praxisplatform.uischema.annotation.AnalyticalRecipeGovernanceTier;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.ComparisonPeriodMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsDimensionExtraction;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Descritor programatico e type-safe de receita analitica verificada associada ao {@code FilterDTO}
 * de um recurso.
 *
 * <p>
 * Complementa {@link org.praxisplatform.uischema.annotation.VerifiedAnalyticalRecipe} para
 * declaracoes refatoraveis em Java via {@code AbstractResourceQueryController#defineAnalyticalRecipes()}
 * ou {@link VerifiedAnalyticalRecipeProvider}.
 * </p>
 *
 * @param <F> tipo concreto do {@link GenericFilterDTO} do recurso
 */
public final class VerifiedAnalyticalRecipeDescriptor<F extends GenericFilterDTO> {

    private final String id;
    private final String title;
    private final String description;
    private final String intent;
    private final AnalyticsOperation operation;
    private final String field;
    private final List<DimensionSpec> dimensions;
    private final List<MetricSpec> metrics;
    private final StatsBucketOrder orderBy;
    private final int limit;
    private final TimeSeriesGranularity granularity;
    private final boolean fillGaps;
    private final DistributionMode distributionMode;
    private final Integer bucketCount;
    private final BigDecimal bucketSize;
    private final String timezone;
    private final String periodField;
    private final ComparisonPeriodPreset periodPreset;
    private final ComparisonPeriodMode periodMode;
    private final Consumer<F> lockedFilterConfigurer;
    private final Map<String, Object> explicitLockedCriteria;
    private final List<ParameterSpec> adjustableParams;
    private final AnalyticalRecipeGovernanceTier governanceTier;
    private final List<String> domainTags;
    private final String version;
    private final int order;
    private final KpiHint kpiHint;

    private VerifiedAnalyticalRecipeDescriptor(Builder<F> builder) {
        this.id = Objects.requireNonNull(builder.id, "id must not be null").trim();
        this.title = Objects.requireNonNull(builder.title, "title must not be null").trim();
        this.description = builder.description == null ? "" : builder.description.trim();
        this.intent = builder.intent == null ? "" : builder.intent.trim();
        this.operation = Objects.requireNonNull(builder.operation, "operation must not be null");
        this.field = builder.field == null ? "" : builder.field.trim();
        this.dimensions = List.copyOf(builder.dimensions);
        this.metrics = List.copyOf(builder.metrics);
        this.orderBy = builder.orderBy == null ? StatsBucketOrder.VALUE_DESC : builder.orderBy;
        this.limit = builder.limit <= 0 ? 10 : builder.limit;
        this.granularity = builder.granularity == null ? TimeSeriesGranularity.MONTH : builder.granularity;
        this.fillGaps = builder.fillGaps;
        this.distributionMode = builder.distributionMode == null ? DistributionMode.TERMS : builder.distributionMode;
        this.bucketCount = builder.bucketCount;
        this.bucketSize = builder.bucketSize;
        this.timezone = builder.timezone == null ? "" : builder.timezone.trim();
        this.periodField = builder.periodField == null ? "" : builder.periodField.trim();
        this.periodPreset = builder.periodPreset == null ? ComparisonPeriodPreset.LAST_30_DAYS : builder.periodPreset;
        this.periodMode = builder.periodMode == null ? ComparisonPeriodMode.PREVIOUS_ALIGNED : builder.periodMode;
        this.lockedFilterConfigurer = builder.lockedFilterConfigurer;
        this.explicitLockedCriteria = Collections.unmodifiableMap(new LinkedHashMap<>(builder.explicitLockedCriteria));
        this.adjustableParams = List.copyOf(builder.adjustableParams);
        this.governanceTier = builder.governanceTier == null
                ? AnalyticalRecipeGovernanceTier.CERTIFIED
                : builder.governanceTier;
        this.domainTags = List.copyOf(builder.domainTags);
        this.version = builder.version == null || builder.version.isBlank() ? "1.0.0" : builder.version.trim();
        this.order = builder.order;
        this.kpiHint = builder.kpiHint;
    }

    public static <F extends GenericFilterDTO> Builder<F> builder(String id, String title) {
        return new Builder<>(id, title);
    }

    public String id() { return id; }
    public String title() { return title; }
    public String description() { return description; }
    public String intent() { return intent; }
    public AnalyticsOperation operation() { return operation; }
    public String field() { return field; }
    public List<DimensionSpec> dimensions() { return dimensions; }
    public List<MetricSpec> metrics() { return metrics; }
    public StatsBucketOrder orderBy() { return orderBy; }
    public int limit() { return limit; }
    public TimeSeriesGranularity granularity() { return granularity; }
    public boolean fillGaps() { return fillGaps; }
    public DistributionMode distributionMode() { return distributionMode; }
    public Integer bucketCount() { return bucketCount; }
    public BigDecimal bucketSize() { return bucketSize; }
    public String timezone() { return timezone; }
    public String periodField() { return periodField; }
    public ComparisonPeriodPreset periodPreset() { return periodPreset; }
    public ComparisonPeriodMode periodMode() { return periodMode; }
    public Consumer<F> lockedFilterConfigurer() { return lockedFilterConfigurer; }
    public Map<String, Object> explicitLockedCriteria() { return explicitLockedCriteria; }
    public List<ParameterSpec> adjustableParams() { return adjustableParams; }
    public AnalyticalRecipeGovernanceTier governanceTier() { return governanceTier; }
    public List<String> domainTags() { return domainTags; }
    public String version() { return version; }
    public int order() { return order; }
    public KpiHint kpiHint() { return kpiHint; }

    public record DimensionSpec(String field, StatsDimensionExtraction extraction, String alias) {
        public DimensionSpec {
            field = field == null ? "" : field.trim();
            extraction = extraction == null ? StatsDimensionExtraction.NONE : extraction;
            alias = alias == null || alias.isBlank() ? null : alias.trim();
        }
    }

    public record MetricSpec(StatsMetric operation, String field, String alias) {
        public MetricSpec {
            operation = operation == null ? StatsMetric.COUNT : operation;
            field = field == null || field.isBlank() ? null : field.trim();
            alias = alias == null || alias.isBlank() ? null : alias.trim();
        }
    }

    public record ParameterSpec(
            String name,
            String filterField,
            String description,
            String dataType,
            boolean required,
            String defaultValue,
            List<String> allowedValues,
            String minValue,
            String maxValue
    ) {
        public ParameterSpec {
            name = name == null ? "" : name.trim();
            filterField = filterField == null ? "" : filterField.trim();
            description = description == null ? "" : description.trim();
            dataType = dataType == null || dataType.isBlank() ? "string" : dataType.trim();
            defaultValue = defaultValue == null || defaultValue.isBlank() ? null : defaultValue.trim();
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
            minValue = minValue == null || minValue.isBlank() ? null : minValue.trim();
            maxValue = maxValue == null || maxValue.isBlank() ? null : maxValue.trim();
        }
    }

    public record KpiHint(
            String polarity,
            String unitSingular,
            String unitPlural,
            String suggestedDerivationKind,
            String suggestedVisualizationKind,
            BigDecimal referenceTarget,
            List<Map<String, Object>> suggestedThresholds
    ) {
        public KpiHint {
            polarity = polarity == null || polarity.isBlank() ? null : polarity.trim();
            unitSingular = unitSingular == null || unitSingular.isBlank() ? null : unitSingular.trim();
            unitPlural = unitPlural == null || unitPlural.isBlank() ? null : unitPlural.trim();
            suggestedDerivationKind = suggestedDerivationKind == null || suggestedDerivationKind.isBlank()
                    ? null
                    : suggestedDerivationKind.trim();
            suggestedVisualizationKind = suggestedVisualizationKind == null || suggestedVisualizationKind.isBlank()
                    ? null
                    : suggestedVisualizationKind.trim();
            if (suggestedThresholds == null || suggestedThresholds.isEmpty()) {
                suggestedThresholds = List.of();
            } else {
                List<Map<String, Object>> copied = new ArrayList<>(suggestedThresholds.size());
                for (Map<String, Object> item : suggestedThresholds) {
                    if (item != null && !item.isEmpty()) {
                        copied.add(Collections.unmodifiableMap(new LinkedHashMap<>(item)));
                    }
                }
                suggestedThresholds = List.copyOf(copied);
            }
        }
    }

    public static final class Builder<F extends GenericFilterDTO> {
        private final String id;
        private final String title;
        private String description = "";
        private String intent = "";
        private AnalyticsOperation operation = AnalyticsOperation.GROUP_BY;
        private String field = "";
        private final List<DimensionSpec> dimensions = new ArrayList<>();
        private final List<MetricSpec> metrics = new ArrayList<>();
        private StatsBucketOrder orderBy = StatsBucketOrder.VALUE_DESC;
        private int limit = 10;
        private TimeSeriesGranularity granularity = TimeSeriesGranularity.MONTH;
        private boolean fillGaps;
        private DistributionMode distributionMode = DistributionMode.TERMS;
        private Integer bucketCount;
        private BigDecimal bucketSize;
        private String timezone = "";
        private String periodField = "";
        private ComparisonPeriodPreset periodPreset = ComparisonPeriodPreset.LAST_30_DAYS;
        private ComparisonPeriodMode periodMode = ComparisonPeriodMode.PREVIOUS_ALIGNED;
        private Consumer<F> lockedFilterConfigurer;
        private final Map<String, Object> explicitLockedCriteria = new LinkedHashMap<>();
        private final List<ParameterSpec> adjustableParams = new ArrayList<>();
        private AnalyticalRecipeGovernanceTier governanceTier = AnalyticalRecipeGovernanceTier.CERTIFIED;
        private final List<String> domainTags = new ArrayList<>();
        private String version = "1.0.0";
        private int order;
        private KpiHint kpiHint;

        private Builder(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public Builder<F> description(String description) { this.description = description; return this; }
        public Builder<F> intent(String intent) { this.intent = intent; return this; }
        public Builder<F> operation(AnalyticsOperation operation) { this.operation = operation; return this; }
        public Builder<F> field(String field) { this.field = field; return this; }
        public Builder<F> dimension(String field, StatsDimensionExtraction extraction, String alias) {
            this.dimensions.add(new DimensionSpec(field, extraction, alias));
            return this;
        }
        public Builder<F> metric(StatsMetric operation, String field, String alias) {
            this.metrics.add(new MetricSpec(operation, field, alias));
            return this;
        }
        public Builder<F> metric(StatsMetric operation, String field) {
            return metric(operation, field, null);
        }
        public Builder<F> orderBy(StatsBucketOrder orderBy) { this.orderBy = orderBy; return this; }
        public Builder<F> limit(int limit) { this.limit = limit; return this; }
        public Builder<F> granularity(TimeSeriesGranularity granularity) { this.granularity = granularity; return this; }
        public Builder<F> fillGaps(boolean fillGaps) { this.fillGaps = fillGaps; return this; }
        public Builder<F> distributionMode(DistributionMode distributionMode) { this.distributionMode = distributionMode; return this; }
        public Builder<F> bucketCount(Integer bucketCount) { this.bucketCount = bucketCount; return this; }
        public Builder<F> bucketSize(BigDecimal bucketSize) { this.bucketSize = bucketSize; return this; }
        public Builder<F> timezone(String timezone) { this.timezone = timezone; return this; }
        public Builder<F> periodField(String periodField) { this.periodField = periodField; return this; }
        public Builder<F> periodPreset(ComparisonPeriodPreset periodPreset) { this.periodPreset = periodPreset; return this; }
        public Builder<F> periodMode(ComparisonPeriodMode periodMode) { this.periodMode = periodMode; return this; }
        public Builder<F> whereLocked(Consumer<F> filterConfigurer) { this.lockedFilterConfigurer = filterConfigurer; return this; }
        public Builder<F> lockedCriterion(String field, Object value) {
            if (field != null && !field.isBlank() && value != null) {
                this.explicitLockedCriteria.put(field.trim(), value);
            }
            return this;
        }
        public Builder<F> adjustableParam(ParameterSpec param) {
            if (param != null) {
                this.adjustableParams.add(param);
            }
            return this;
        }
        public Builder<F> governanceTier(AnalyticalRecipeGovernanceTier governanceTier) {
            this.governanceTier = governanceTier;
            return this;
        }
        public Builder<F> domainTags(String... tags) {
            if (tags != null) {
                for (String tag : tags) {
                    if (tag != null && !tag.isBlank()) {
                        this.domainTags.add(tag.trim());
                    }
                }
            }
            return this;
        }
        public Builder<F> version(String version) { this.version = version; return this; }
        public Builder<F> order(int order) { this.order = order; return this; }
        public Builder<F> kpiHint(KpiHint kpiHint) { this.kpiHint = kpiHint; return this; }

        public VerifiedAnalyticalRecipeDescriptor<F> build() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("VerifiedAnalyticalRecipeDescriptor requires a non-blank id.");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("VerifiedAnalyticalRecipeDescriptor requires a non-blank title.");
            }
            if ((field == null || field.isBlank()) && dimensions.isEmpty()) {
                throw new IllegalArgumentException(
                        "VerifiedAnalyticalRecipeDescriptor '" + id + "' requires field or dimensions."
                );
            }
            if (metrics.isEmpty()) {
                metrics.add(new MetricSpec(StatsMetric.COUNT, null, null));
            }
            return new VerifiedAnalyticalRecipeDescriptor<>(this);
        }
    }
}
