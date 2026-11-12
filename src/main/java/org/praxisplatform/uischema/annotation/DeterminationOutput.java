package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binding declarativo entre uma propriedade do response da determinacao reativa e um campo do
 * formulario de destino.
 *
 * <p>Aceita nomes simples de propriedade ({@code "valorLiquidoPrevisto"}) ou JSON Pointers
 * explicitos ({@code "/valorLiquidoPrevisto"}). Quando {@link #value()} e informado, o mesmo
 * caminho e usado tanto no response da determinacao quanto no formulario.</p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface DeterminationOutput {

    /**
     * Atalho quando o nome da propriedade no response da determinacao coincide com o nome do campo no formulario.
     */
    String value() default "";

    /**
     * Caminho da propriedade produzida no response DTO da determinacao (ex.: {@code "valorBrutoPrevisto"} ou {@code "/valorBrutoPrevisto"}).
     */
    String response() default "";

    /**
     * Caminho do campo de destino no schema do formulario (ex.: {@code "valorBrutoPrevisto"} ou {@code "/observacoes"}).
     */
    String field() default "";
}
