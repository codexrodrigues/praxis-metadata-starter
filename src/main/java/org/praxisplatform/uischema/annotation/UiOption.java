package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara uma opcao estatica tipada para campos com dominio fechado em
 * {@link org.praxisplatform.uischema.extension.annotation.UISchema#optionItems()}.
 *
 * <p>
 * Cada {@code @UiOption} e materializado no contrato canonico {@code x-ui.options} como um objeto
 * JSON contendo {@code value}, {@code label} e metadados visuais opcionais ({@code icon},
 * {@code tone}, {@code description}, {@code disabled}), eliminando strings JSON escapadas e erros
 * silenciosos de chave divergente como {@code "id"} sem {@code "value"}.
 * </p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface UiOption {

    /**
     * Valor tecnico canonico persistido ou enviado como criterio de filtro (chave {@code "value"}
     * em {@code x-ui.options}).
     *
     * @return valor da opcao
     */
    String value();

    /**
     * Rotulo humano exibido na UI para a opcao (chave {@code "label"} em {@code x-ui.options}).
     *
     * @return rotulo da opcao
     */
    String label();

    /**
     * Ligadura opcional de icone Material Icons em {@code snake_case} minusculo (ex.: {@code "check_circle"}).
     *
     * @return nome do icone em snake_case
     */
    String icon() default "";

    /**
     * Tom semantico opcional da opcao para badges, chips e menus de selecao.
     *
     * @return tom visual da opcao
     */
    QuickFilterTone tone() default QuickFilterTone.UNSPECIFIED;

    /**
     * Descricao auxiliar opcional exibida em tooltips, ajudas contextuais ou catalogos de IA.
     *
     * @return descricao da opcao
     */
    String description() default "";

    /**
     * Indica se a opcao deve aparecer desabilitada para nova selecao na interface.
     *
     * @return {@code true} se a opcao estiver desabilitada
     */
    boolean disabled() default false;
}
