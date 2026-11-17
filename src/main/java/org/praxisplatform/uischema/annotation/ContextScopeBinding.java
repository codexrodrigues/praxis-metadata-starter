package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Vincula uma propriedade de DTO ou filtro a uma dimensao de contexto operacional ativo da sessao,
 * publicando {@code x-ui.contextBinding} no schema OpenAPI.
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ContextScopeBinding {

    /** Dimensao de contexto que alimenta ou restringe o campo/recurso. */
    ScopeDimension dimension();

    /** Indica se o controle de UI deve ficar bloqueado quando a dimensao ja esta fixada no contexto ativo. */
    boolean lockWhenActive() default true;

    /** Nome explicito da propriedade alvo quando diferente do campo anotado (ou ao anotar no tipo). */
    String targetField() default "";
}
