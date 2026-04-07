package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

/**
 * Declara quais capacidades HTTP e metadata-driven um recurso publica no baseline canonico.
 *
 * <p>
 * Esta anotacao funciona como um resumo declarativo do envelope
 * {@code resource + surfaces + actions + capabilities}. Ela nao cria endpoints por si so, mas
 * informa ao starter quais grupos de operacoes devem ser considerados habilitados para discovery,
 * documentacao e composicao do snapshot de capabilities.
 * </p>
 *
 * <p>
 * O uso recomendado e manter os valores alinhados com a hierarquia realmente herdada pelo
 * controller e pelo service do recurso. Esta anotacao nao deve mascarar um contrato diferente do
 * que o recurso efetivamente publica.
 * </p>
 */
@Target(TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceCapabilities {
    /**
     * Indica se o recurso publica create.
     */
    boolean create() default true;

    /**
     * Indica se o recurso publica update.
     */
    boolean update() default true;

    /**
     * Indica se o recurso publica delete.
     */
    boolean delete() default true;

    /**
     * Indica se o recurso publica lookup de opcoes.
     */
    boolean options() default true;

    /**
     * Indica se o recurso publica leitura por id.
     */
    boolean byId() default true;

    /**
     * Indica se o recurso publica listagem simples.
     */
    boolean all() default true;

    /**
     * Indica se o recurso publica filtragem paginada classica.
     */
    boolean filter() default true;

    /**
     * Indica se o recurso publica filtragem com cursor.
     */
    boolean cursor() default true;

    /**
     * Indica se o recurso publica group-by stats.
     */
    boolean statsGroupBy() default true;

    /**
     * Indica se o recurso publica time-series stats.
     */
    boolean statsTimeSeries() default true;

    /**
     * Indica se o recurso publica distribution stats.
     */
    boolean statsDistribution() default true;
}
