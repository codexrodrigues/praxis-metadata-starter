package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara metadados neutros de progressao ou evolucao por niveis/degraus para um DTO de recurso,
 * publicados em {@code x-ui.progression} no schema de resposta.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceProgression {

    /** Titulo humano da visualizacao de progressao. */
    String title() default "";

    /** Campo com o nivel atual alcancado. */
    String currentLevelField() default "";

    /** Campo com a etapa ou referencia atual dentro do nivel. */
    String currentStepField() default "";

    /** Campo com o nivel maximo da trilha ou escala. */
    String maxLevelField() default "";

    /** Campo com a proxima etapa elegivel. */
    String nextStepField() default "";

    /** Campo com a proxima data prevista de elegibilidade. */
    String nextEligibleDateField() default "";

    /** Campo com a contagem de dias restantes ate a proxima etapa. */
    String remainingDaysField() default "";

    /** Campo com a pontuacao ou percentual de progresso acumulado. */
    String scoreField() default "";

    /** Rotulo curto da unidade exibida (ex.: {@code "pts"}, {@code "niveis"}). */
    String unitLabel() default "";

    /** Campo que indica a situacao atual da progressao. */
    String statusField() default "";
}
