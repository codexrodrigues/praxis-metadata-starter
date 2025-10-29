package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

/**
 * Define capacidades habilitadas para um recurso.
 * Pode ser usada para informar documentação e UI sobre operações disponíveis.
 */
@Target(TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceCapabilities {
    boolean create() default true;
    boolean update() default true;
    boolean delete() default true;
    boolean options() default true;
    boolean byId() default true;
    boolean all() default true;
    boolean filter() default true;
    boolean cursor() default true;
}

