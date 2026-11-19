package org.praxisplatform.uischema.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configura opcionalmente o layout estrutural raiz ({@code x-ui.formLayout}) de formulários
 * gerados por schema para um DTO de comando/detalhe ou método de controller Spring MVC.
 *
 * <p>Permanece opt-in: DTOs e endpoints sem {@code @FormLayout} não emitem {@code x-ui.formLayout}
 * nem alteram o {@code X-Schema-Hash}. Nunca é publicado para payloads de filtro ou listagens de
 * coleção.</p>
 *
 * @since 8.0.0
 */
@Inherited
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface FormLayout {

    /** Alias conveniente para {@link #mode()}. */
    @AliasFor("mode")
    FormLayoutMode value() default FormLayoutMode.FLAT;

    /** Modo estrutural de layout das seções do formulário. */
    @AliasFor("value")
    FormLayoutMode mode() default FormLayoutMode.FLAT;

    /** Identificador da aba selecionada inicialmente quando {@link #mode()} for {@link FormLayoutMode#TABS}. */
    String defaultTabId() default "";

    /** Aparência visual dos botões de abas quando {@link #mode()} for {@link FormLayoutMode#TABS}. */
    FormLayoutTabsAppearance tabsAppearance() default FormLayoutTabsAppearance.AUTO;

    /** Permite manter múltiplos painéis expandidos simultaneamente em {@link FormLayoutMode#ACCORDION}. */
    UiBoolean accordionMulti() default UiBoolean.AUTO;

    /** Controla a supressão do cabeçalho duplicado de seção em {@link FormLayoutMode#TABS}. */
    UiBoolean hideSectionHeaderInTabs() default UiBoolean.AUTO;

    /** Controla a supressão do cabeçalho duplicado de seção em {@link FormLayoutMode#STEPPER}. */
    UiBoolean hideSectionHeaderInStepper() default UiBoolean.AUTO;

    /**
     * Controla o comportamento de {@link FormLayoutMode#STEPPER} em modos de leitura
     * ({@code presentation}, {@code all} ou {@code none}).
     */
    FormLayoutStepperReadMode stepperInReadMode() default FormLayoutStepperReadMode.AUTO;

    /**
     * Quando {@link UiBoolean#TRUE}, adiciona automaticamente uma etapa final sintética de revisão
     * ({@code x-ui.formLayout.stepperReviewStep}) ao término do fluxo {@link FormLayoutMode#STEPPER}.
     */
    UiBoolean stepperReviewStep() default UiBoolean.AUTO;

    /**
     * Quando {@link UiBoolean#TRUE}, habilita a persistência de rascunho de sessão
     * ({@code x-ui.formLayout.draftPersistence}) em fluxos de criação ({@code mode='create'}).
     */
    UiBoolean draftPersistence() default UiBoolean.AUTO;

    /** Lista ordenada opcional de seções/etapas e seus respectivos agrupamentos {@code @UISchema(group)}. */
    FormLayoutSection[] sections() default {};

    /**
     * Lista ordenada opcional de etapas explícitas ({@code x-ui.formLayout.steps}) agrupando uma
     * ou mais seções por {@code sectionIds} no modo {@link FormLayoutMode#STEPPER}.
     */
    FormLayoutStep[] steps() default {};
}
