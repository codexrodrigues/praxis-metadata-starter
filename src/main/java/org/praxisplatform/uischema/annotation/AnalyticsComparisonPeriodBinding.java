package org.praxisplatform.uischema.annotation;

import org.praxisplatform.uischema.stats.ComparisonPeriodMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Canonical period binding and execution defaults for a comparison projection.
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticsComparisonPeriodBinding {

    String field();

    String timezone();

    ComparisonPeriodPreset preset() default ComparisonPeriodPreset.LAST_30_DAYS;

    ComparisonPeriodMode mode() default ComparisonPeriodMode.PREVIOUS_ALIGNED;
}
