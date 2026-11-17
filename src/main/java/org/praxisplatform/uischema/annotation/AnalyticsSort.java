package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Default de ordenacao publicado por uma projection analitica.
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticsSort {

    String field();

    AnalyticsSortDirection direction() default AnalyticsSortDirection.ASC;
}
