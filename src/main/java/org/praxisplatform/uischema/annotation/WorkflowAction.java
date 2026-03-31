package org.praxisplatform.uischema.annotation;

import org.praxisplatform.uischema.action.ActionScope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma operacao HTTP real como action de workflow ou comando de negocio.
 *
 * <p>
 * A anotacao nao define payload inline nem cria dispatcher generico. Ela apenas sinaliza que a
 * operacao deve aparecer no catalogo semantico de actions, sempre por referencia a endpoint,
 * request schema e response schema canonicos.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkflowAction {

    /**
     * Identificador local da action dentro do recurso.
     */
    String id();

    /**
     * Titulo principal consumido pela UX e por clientes documentais.
     */
    String title();

    /**
     * Descricao opcional da action.
     */
    String description() default "";

    /**
     * Escopo semantico da action.
     */
    ActionScope scope() default ActionScope.ITEM;

    /**
     * Ordem estavel para listagem.
     */
    int order() default 0;

    /**
     * Mensagem opcional de sucesso para UIs e clientes documentais.
     */
    String successMessage() default "";

    /**
     * Tags opcionais de organizacao semantica.
     */
    String[] tags() default {};

    /**
     * Authorities/roles canonicamente exigidas para a action.
     */
    String[] requiredAuthorities() default {};

    /**
     * Estados canonicos do recurso em que a action pode ficar disponivel.
     */
    String[] allowedStates() default {};
}
