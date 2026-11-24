package org.praxisplatform.uischema.annotation;

import org.praxisplatform.uischema.stats.ComparisonPeriodMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a governed, server-verified analytical recipe on a Praxis resource controller.
 *
 * <p>
 * A {@code @VerifiedAnalyticalRecipe} anchors statistical semantics (operation, grouping field or
 * dimensions, metric, locked filter criteria, and bounded adjustable parameters) in backend code
 * rather than leaving AI agents or clients to assemble ad hoc aggregations. Published automatically
 * via {@code x-ui.analytics.verifiedRecipes}, {@code /schemas/domain}, {@code GET /stats/recipes},
 * and executed fail-closed via {@code POST /stats/recipes/{recipeId}}.
 * </p>
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(VerifiedAnalyticalRecipes.class)
public @interface VerifiedAnalyticalRecipe {

    /** Stable canonical identifier for this recipe within the resource (kebab-case recommended). */
    String id();

    /** Short human-readable title displayed in dashboards, catalogs, and AI citations. */
    String title();

    /** Detailed business semantics explaining what indicator is measured and any domain rules. */
    String description() default "";

    /** Normalized business question or analytical intent addressed by this recipe. */
    String intent() default "";

    /** Statistical endpoint family used when executing this recipe. */
    AnalyticsOperation operation() default AnalyticsOperation.GROUP_BY;

    /** Primary canonical field registered in the resource {@code StatsFieldRegistry}. */
    String field() default "";

    /**
     * Optional multi-dimensional specs ({@code field} or {@code field:EXTRACTION}) for 2D/ND
     * {@link AnalyticsOperation#GROUP_BY} cross-tab recipes.
     */
    String[] dimensions() default {};

    /** Primary aggregate metric operation. */
    StatsMetric metric() default StatsMetric.COUNT;

    /** Optional target numeric field when {@link #metric()} requires a field (SUM, AVG, MIN, MAX, etc.). */
    String metricField() default "";

    /** Optional stable alias for the metric output key. */
    String metricAlias() default "";

    /** Ordering applied to buckets in GROUP_BY / DISTRIBUTION / COMPARISON operations. */
    StatsBucketOrder orderBy() default StatsBucketOrder.VALUE_DESC;

    /** Maximum number of buckets returned when non-zero; defaults to 10 when unset or &lt;= 0. */
    int limit() default 10;

    /** Temporal bucket granularity for {@link AnalyticsOperation#TIMESERIES} recipes. */
    TimeSeriesGranularity granularity() default TimeSeriesGranularity.MONTH;

    /** Whether temporal gaps should be filled with zero-count points where supported. */
    boolean fillGaps() default false;

    /** Distribution mode when {@link #operation()} is {@link AnalyticsOperation#DISTRIBUTION}. */
    DistributionMode distributionMode() default DistributionMode.TERMS;

    /** Optional histogram bucket count when {@link #distributionMode()} is {@link DistributionMode#HISTOGRAM}. */
    int bucketCount() default 0;

    /** Optional histogram bucket size literal (e.g. {@code "1000"}) for numeric ranges. */
    String bucketSize() default "";

    /** Default IANA timezone applied to temporal operations (for example, {@code "America/Sao_Paulo"}). */
    String timezone() default "";

    /** Optional date/time field used as period reference in GROUP_BY or COMPARISON recipes. */
    String periodField() default "";

    /** Default comparison period preset for {@link AnalyticsOperation#COMPARISON} or windowed GROUP_BY. */
    ComparisonPeriodPreset periodPreset() default ComparisonPeriodPreset.LAST_30_DAYS;

    /** Comparison derivation mode when {@link #operation()} is {@link AnalyticsOperation#COMPARISON}. */
    ComparisonPeriodMode periodMode() default ComparisonPeriodMode.PREVIOUS_ALIGNED;

    /**
     * Server-enforced filter criteria merged unconditionally last during execution.
     * Callers and AI agents cannot clear, widen, or override these criteria.
     */
    FilterCriterion[] lockedCriteria() default {};

    /** Bounded parameters callers may optionally adjust at execution time. */
    AnalyticalRecipeParameter[] adjustableParams() default {};

    /** Governance tier indicating certification maturity of this analytical definition. */
    AnalyticalRecipeGovernanceTier governanceTier() default AnalyticalRecipeGovernanceTier.CERTIFIED;

    /** Semantic tags used by discovery catalogs and AI retrieval. */
    String[] domainTags() default {};

    /** Semantic version string included in deterministic provenance digests. */
    String version() default "1.0.0";

    /** Display priority order among recipes of the same resource. */
    int order() default 0;
}
