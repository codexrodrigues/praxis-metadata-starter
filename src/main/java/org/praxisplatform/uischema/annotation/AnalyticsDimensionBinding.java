package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Binding canonico da dimensao principal de uma projection analitica.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticsDimensionBinding {

    String field();

    String role() default "category";

    String label() default "";
}
