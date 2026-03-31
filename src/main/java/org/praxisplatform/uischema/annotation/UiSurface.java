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
     * Escopo da surface.
     */
    SurfaceScope scope();

    /**
     * Titulo principal consumido pela UX e por clientes documentais.
     */
    String title();

    /**
     * Descricao opcional da surface.
     */
    String description() default "";

    /**
     * Intencao semantica adicional da surface. Quando omitida, o runtime usa {@link #id()}.
     */
    String intent() default "";

    /**
     * Ordem estavel para renderizacao/listagem.
     */
    int order() default 0;

    /**
     * Tags opcionais de organizacao semantica.
     */
    String[] tags() default {};
}
