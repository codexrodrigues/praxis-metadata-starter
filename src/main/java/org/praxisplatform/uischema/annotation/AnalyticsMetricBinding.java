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

    /**
     * Agregacao canonica (ex.: {@code count}, {@code distinct-count}, {@code sum}, {@code avg},
     * {@code min}, {@code max}, {@code percentile-25}, {@code median}, {@code percentile-75},
     * {@code percentile-90}, {@code percentile-95}, {@code avg-per-cycle-occurrence}).
     */
    String aggregation() default "count";

    String label() default "";

    /**
     * Alias opcional da metrica quando multiplas metricas operam sobre o mesmo campo.
     */
    String alias() default "";
}
