package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara metadados neutros de grade de horarios, turnos ou agenda para um DTO de recurso,
 * publicados em {@code x-ui.schedule} no schema de resposta.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceSchedule {

    /** Titulo humano da visualizacao de agenda ou escala. */
    String title() default "";

    /** Campo com horario ou instante inicial. */
    String startTimeField() default "";

    /** Campo com horario ou instante final. */
    String endTimeField() default "";

    /** Campo com fuso horario associado a agenda. */
    String timezoneField() default "";

    /** Campo com expressao ou regra de recorrencia (ex.: CRON ou RRULE). */
    String recurrenceField() default "";

    /** Campo com o dia da semana ou padrao de dias aplicaveis. */
    String dayOfWeekField() default "";

    /** Campo com o turno ou janela operacional. */
    String shiftField() default "";

    /** Campo com a capacidade total do slot ou janela. */
    String capacityField() default "";

    /** Campo com a quantidade ou carga alocada no slot. */
    String allocatedField() default "";

    /** Campo com o status operacional da agenda. */
    String statusField() default "";
}
