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

    /**
     * Extracao canonica opcional da dimensao (ex.: {@code DAY_OF_WEEK}, {@code HOUR_OF_DAY},
     * {@code MONTH_OF_YEAR}, {@code DAY}, {@code MONTH}).
     */
    String extraction() default "";

    /**
     * Alias canonico opcional da dimensao em projecoes multidimensionais (ex.: {@code weekday}, {@code hour}).
     */
    String alias() default "";

    /**
     * Campo publico do request de filtro que recebe {@code bucket.key} em interacoes
     * de cross-filter. Nao deve conter property path interno de entidade/JPA.
     */
    String keyFilterField() default "";
}
