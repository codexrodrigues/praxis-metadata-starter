package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Trilha individual auditavel de uma {@link CalculationMemory} publicada em {@code x-ui.calculationMemory}
 * ({@code PLAT-EVO-20}).
 *
 * <p>Todas as expressoes declaradas ({@code visibleWhen}, {@code baseExpr}, {@code additionExpr},
 * {@code deductionExpr}, {@code factorExpr}, {@code capExpr}, {@code resultExpr} e variantes
 * {@code *Expr}) sao validadas estaticamente contra as propriedades reais do DTO pelo
 * {@code PraxisExpressionStaticValidator}.</p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface CalculationTrail {

    /**
     * Identificador estavel da trilha de calculo dentro da secao (ex.: {@code "abate-teto-constitucional-cf88"}).
     */
    String id();

    /**
     * Titulo humano da trilha de calculo.
     */
    String title() default "";

    /**
     * Expressao opcional para titulo dinamico da trilha.
     */
    String titleExpr() default "";

    /**
     * Subtitulo contextual opcional da trilha.
     */
    String subtitle() default "";

    /**
     * Expressao opcional para subtitulo dinamico da trilha.
     */
    String subtitleExpr() default "";

    /**
     * Icone Material Symbols da trilha.
     */
    String icon() default "calculate";

    /**
     * Codigo deterministico da regra de negocio ou formula (ex.: {@code "ERGON-TETO-CF88-37-XI"}).
     */
    String ruleCode() default "";

    /**
     * Expressao opcional para codigo dinamico da regra aplicada.
     */
    String ruleCodeExpr() default "";

    /**
     * Formula algebrica legivel por humanos e auditores.
     */
    String formula() default "";

    /**
     * Expressao opcional para formula dinamica.
     */
    String formulaExpr() default "";

    /**
     * Condicao declarativa opcional para exibir a trilha apenas quando aplicavel ao registro.
     */
    String visibleWhen() default "";

    String baseLabel() default "";
    String baseExpr() default "";
    String baseFormat() default "";
    String baseHint() default "";
    String baseHintExpr() default "";

    String additionLabel() default "";
    String additionExpr() default "";
    String additionFormat() default "";
    String additionHint() default "";
    String additionHintExpr() default "";

    String deductionLabel() default "";
    String deductionExpr() default "";
    String deductionFormat() default "";
    String deductionHint() default "";
    String deductionHintExpr() default "";

    String factorLabel() default "";
    String factorExpr() default "";
    String factorFormat() default "";
    String factorHint() default "";
    String factorHintExpr() default "";

    String capLabel() default "";
    String capExpr() default "";
    String capFormat() default "";
    String capHint() default "";
    String capHintExpr() default "";

    String resultLabel() default "";
    String resultExpr() default "";
    String resultFormat() default "";
    String resultTone() default "";
    String resultHint() default "";
    String resultHintExpr() default "";

    /**
     * Fundamentacao constitucional, legal ou normativa da trilha.
     */
    String legalBasis() default "";

    /**
     * Expressao opcional para fundamentacao legal dinamica a partir dos atributos do DTO.
     */
    String legalBasisExpr() default "";

    /**
     * Finalidade juridico-administrativa e explicabilidade da trilha para controle interno/externo.
     */
    String legalPurpose() default "";

    /**
     * Expressao opcional para finalidade juridica dinamica.
     */
    String legalPurposeExpr() default "";
}
