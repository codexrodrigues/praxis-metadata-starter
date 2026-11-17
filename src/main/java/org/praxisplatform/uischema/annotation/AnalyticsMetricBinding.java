package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binding canonico de metrica em uma projection analitica.
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticsMetricBinding {

    String field();

    String aggregation() default "count";

    String label() default "";
}
