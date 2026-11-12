package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binding declarativo entre um campo do rascunho de formulario e um atributo do payload de
 * requisicao da operacao de determinacao reativa.
 *
 * <p>Aceita nomes simples de propriedade ({@code "rubricaId"}) ou JSON Pointers explicitos
 * ({@code "/rubricaId"}). Quando {@link #value()} e informado, o mesmo caminho e usado tanto no
 * formulario quanto no request DTO da determinacao.</p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface DeterminationInput {

    /**
     * Atalho quando o nome da propriedade no formulario coincide exatamente com o nome no request da determinacao.
     */
    String value() default "";

    /**
     * Caminho do campo de origem no schema do formulario (ex.: {@code "valorBaseCalculo"} ou {@code "/valorBaseCalculo"}).
     */
    String field() default "";

    /**
     * Caminho correspondente no payload de requisicao da determinacao (ex.: {@code "vencimentoBasico"} ou {@code "/vencimentoBasico"}).
     */
    String request() default "";

    /**
     * Indica se alteracoes neste campo disparam automaticamente a determinacao quando
     * {@link ReactiveDetermination#sourceFields()} nao for especificado manualmente.
     */
    boolean triggerSource() default true;
}
