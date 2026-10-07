package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara um filtro rapido canonico associado ao recurso.
 *
 * <p>
 * Filtros rapidos sao expostos como chips/botoes na barra de ferramentas da tabela.
 * O campo {@link #filter()} suporta expressoes do tipo {@code "chave=valor"}
 * (ex.: {@code "ativo=true"}, {@code "status=EM_MISSAO"}) ou objetos JSON
 * (ex.: {@code "{\"ativo\":true}"}).
 * </p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface QuickFilter {

    /**
     * Identificador unico do filtro rapido.
     *
     * @return identificador do filtro rapido
     */
    String id();

    /**
     * Rotulo legivel exibido no chip de filtro rapido.
     *
     * @return rotulo do filtro
     */
    String label();

    /**
     * Expressao ou JSON do filtro aplicado ao clicar no chip.
     * Exemplo: {@code "ativo=true"}, {@code "status=EM_MISSAO"} ou {@code "{\"ativo\":true}"}.
     *
     * @return expressao de filtro
     */
    String filter();

    /**
     * Icone semantico opcional (token de material/praxis) exibido no chip.
     *
     * @return token do icone
     */
    String icon() default "";
}
