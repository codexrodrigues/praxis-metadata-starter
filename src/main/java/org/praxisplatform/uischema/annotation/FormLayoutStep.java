package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara uma etapa explícita dentro de {@link FormLayout#steps()} no modo
 * {@link FormLayoutMode#STEPPER}, permitindo agrupar uma ou mais seções ({@link #sectionIds()})
 * sob uma mesma etapa e marcar etapas opcionais ({@link #optional()}).
 *
 * @since 8.0.0
 */
@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface FormLayoutStep {

    /** Identificador único e estável da etapa. */
    String id();

    /** Rótulo legível exibido no cabeçalho da etapa. */
    String label() default "";

    /** Subtítulo ou descrição curta exibida abaixo do rótulo da etapa. */
    String description() default "";

    /** Nome do ícone Material Symbols associado à etapa. */
    String icon() default "";

    /**
     * Identificadores das seções ({@link FormLayoutSection#id()}) agrupadas nesta etapa.
     * Deve conter ao menos um identificador de seção válido.
     */
    String[] sectionIds();

    /** Marca a etapa como opcional (exibe pill Opcional e permite salto direto enquanto válida). */
    UiBoolean optional() default UiBoolean.AUTO;
}
