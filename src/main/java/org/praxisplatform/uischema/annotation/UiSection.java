package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara uma secao semantica dentro de {@link UiFormLayout#sections()}, agrupando blocos por
 * {@code @UISchema(group = ...)}.
 */
@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface UiSection {

    /** Identificador estavel da secao no layout do formulario. */
    String id();

    /** Titulo humano exibido na aba, painel expansivel ou etapa. */
    String title();

    /** Subtitulo descritivo opcional da secao. */
    String subtitle() default "";

    /** Ligadura opcional Material Icons em {@code snake_case} minusculo. */
    String icon() default "";

    /** Ordem explicita da secao no container de formulario. */
    int order() default 0;

    /** Nomes dos grupos {@code @UISchema(group = ...)} vinculados a esta secao. */
    String[] groups() default {};
}
