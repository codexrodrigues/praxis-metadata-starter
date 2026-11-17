package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara metadados neutros de linha do tempo operacional ou vigencia para um DTO de recurso,
 * publicados em {@code x-ui.timeline} no schema de resposta.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceTimeline {

    /** Titulo humano da visualizacao em linha do tempo. */
    String title() default "";

    /** Campo de data/instante inicial do marco ou intervalo. */
    String startDateField() default "";

    /** Campo de data/instante final do marco ou intervalo. */
    String endDateField() default "";

    /** Campo de data de referencia utilizada como ancora na linha do tempo. */
    String referenceDateField() default "";

    /** Campo de situacao ou status do evento/periodo. */
    String statusField() default "";

    /** Campo de categoria ou tipo do evento/periodo. */
    String categoryField() default "";

    /** Campo com o rotulo principal do marco/intervalo. */
    String labelField() default "";

    /** Campo com a duracao formatada ou numerica do evento/periodo. */
    String durationField() default "";

    /** Campo com o agente ou responsavel pelo evento. */
    String actorField() default "";
}
