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
     * Campo publico do request de filtro que recebe {@code bucket.key} em interacoes
     * de cross-filter. Nao deve conter property path interno de entidade/JPA.
     */
    String keyFilterField() default "";
}
