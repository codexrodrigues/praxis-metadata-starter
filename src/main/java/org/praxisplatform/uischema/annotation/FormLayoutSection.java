package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara uma seção ou etapa explícita dentro de {@link FormLayout#sections()}, vinculando um
 * identificador estável, metadados visuais e os valores de {@code @UISchema(group = ...)} que
 * compõem a etapa/seção.
 *
 * @since 8.0.0
 */
@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface FormLayoutSection {

    /** Identificador único e estável da seção/etapa. */
    String id();

    /** Título legível exibido no cabeçalho da seção, aba, acordeão ou etapa. */
    String title();

    /** Subtítulo ou descrição curta exibida abaixo do título da seção/etapa. */
    String subtitle() default "";

    /** Nome do ícone Material Symbols associado à seção/etapa. */
    String icon() default "";

    /** Ordem relativa da seção/etapa na materialização do formulário. */
    int order() default 0;

    /**
     * Valores de {@code @UISchema(group = ...)} dos campos que pertencem a esta seção/etapa.
     * Quando omitido, o materializador associa automaticamente pelo {@code title} ou {@code id}.
     */
    String[] groups() default {};

    /**
     * Marca a seção/etapa derivada como opcional no modo {@link FormLayoutMode#STEPPER}
     * (exibe pill Opcional e permite salto direto enquanto válida).
     */
    UiBoolean optional() default UiBoolean.AUTO;
}
