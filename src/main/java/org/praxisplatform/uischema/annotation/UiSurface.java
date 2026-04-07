package org.praxisplatform.uischema.annotation;

import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma operacao HTTP real como surface semantica de UI.
 *
 * <p>
 * A anotacao nao define schema inline nem substitui o contrato OpenAPI da operacao. Ela apenas
 * atribui metadados semanticos para discovery, sempre apontando para uma operacao canonicamente
 * documentada e para o schema resolvivel via {@code /schemas/filtered}.
 * </p>
 *
 * <p>
 * Use {@code @UiSurface} para expor experiencias de leitura ou interacao visual, como
 * {@code list}, {@code details}, {@code create-form}, {@code edit-form} ou dashboards
 * especializados. Nao use esta anotacao para modelar comando de negocio; nesses casos, a
 * superficie correta e {@link WorkflowAction}.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UiSurface {

    /**
     * Identificador local da surface dentro do recurso.
     */
    String id();

    /**
     * Tipo semantico da surface.
     */
    SurfaceKind kind();

    /**
     * Escopo em que a surface faz sentido, como colecao ou item especifico.
     */
    SurfaceScope scope();

    /**
     * Titulo principal consumido pela UX e por clientes documentais.
     */
    String title();

    /**
     * Descricao opcional exibida em catalogos semanticos e consumidores documentais.
     */
    String description() default "";

    /**
     * Intencao semantica adicional da surface. Quando omitida, o runtime usa {@link #id()}.
     */
    String intent() default "";

    /**
     * Ordem estavel para renderizacao, navegacao ou listagem.
     */
    int order() default 0;

    /**
     * Authorities ou roles canonicamente exigidas para a surface ficar disponivel.
     *
     * <p>
     * O starter nao impoe framework de seguranca especifico. Quando um principal com authorities
     * estiver disponivel no runtime, a avaliacao de availability pode bloquear a surface se
     * alguma authority declarada estiver ausente.
     * </p>
     */
    String[] requiredAuthorities() default {};

    /**
     * Estados canonicos do recurso em que a surface pode ficar disponivel.
     *
     * <p>
     * A avaliacao usa um {@code ResourceStateSnapshotProvider} plugavel para resolver o estado do
     * recurso uma vez por request item-level, sem custo N+1 por surface.
     * </p>
     */
    String[] allowedStates() default {};

    /**
     * Tags opcionais de organizacao semantica, navegacao e filtros documentais.
     */
    String[] tags() default {};
}
