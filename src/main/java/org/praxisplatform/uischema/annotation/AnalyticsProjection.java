package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Projection semantica analitica opcional anexada a uma operacao real.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticsProjection {

    String id();

    AnalyticsIntent intent();

    AnalyticsOperation sourceOperation();

    String sourceResource() default "";

    AnalyticsDimensionBinding primaryDimension() default @AnalyticsDimensionBinding(field = "");

    AnalyticsMetricBinding[] primaryMetrics();

    AnalyticsMetricBinding[] secondaryMetrics() default {};

    AnalyticsSort[] defaultSort() default {};

    int defaultLimit() default -1;

    AnalyticsGranularity defaultGranularity() default AnalyticsGranularity.UNSPECIFIED;

    AnalyticsPresentationFamily[] preferredFamilies() default {};

    boolean drillDown() default false;

    boolean pointSelection() default false;

    boolean crossFilter() default false;
}
