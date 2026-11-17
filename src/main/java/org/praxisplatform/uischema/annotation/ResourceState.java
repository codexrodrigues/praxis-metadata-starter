package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca explicitamente o campo que representa o estado operacional ou de ciclo de vida de um registro,
 * declarando seus estados iniciais e estados em que edicao completa ({@code PUT}) permanece habilitada.
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceState {

    /** Estados validos na criacao inicial de um registro. */
    String[] initialStates() default {};

    /** Estados em que operacoes de atualizacao estrutural completa permanecem permitidas. */
    String[] putMutableStates() default {};
}
