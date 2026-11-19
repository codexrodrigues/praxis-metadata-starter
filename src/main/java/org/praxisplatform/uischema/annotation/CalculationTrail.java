package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Trilha individual auditável de uma {@link CalculationMemory} publicada em {@code x-ui.calculationMemory}
 * ({@code PLAT-EVO-20}).
 *
 * <p>Permite modelar tanto trilhas estruturadas com parcelas fixas ({@code baseExpr},
 * {@code additionExpr}, {@code deductionExpr}, {@code factorExpr}, {@code capExpr}, {@code resultExpr})
 * quanto trilhas extensíveis de N etapas via <code>steps = { @CalculationStep(...) }</code>.
 * Todas as expressões declaradas são validadas estaticamente contra as propriedades reais do DTO pelo
 * {@link org.praxisplatform.uischema.validation.PraxisExpressionStaticValidator}.</p>
 *
 * <p><b>Regras anti-duplicação ({@code PLAT-EVO-20}):</b></p>
 * <ul>
 *   <li>Não repita a mesma expressão de {@link #baseExpr()} em {@link #additionExpr()} ou
 *       {@link #deductionExpr()} (o validador estático rejeita parcelas fantasma duplicadas);</li>
 *   <li>Escolha <b>ou</b> os campos fixos de parcela ({@code base*}, {@code addition*},
 *       {@code deduction*}, {@code factor*}, {@code cap*}, {@code target*}) <b>ou</b> a lista
 *       extensível {@link #steps()}, evitando duplicar os mesmos passos em ambos.</li>
 * </ul>
 *
 * <pre>{@code
 * @CalculationMemory(
 *     title = "Composição salarial",
 *     trails = {
 *         @CalculationTrail(
 *             id = "net-compensation",
 *             title = "Composição líquida",
 *             resultLabel = "Líquido",
 *             resultExpr = "row.netAmount",
 *             resultFormat = "currency:BRL",
 *             steps = {
 *                 @CalculationStep(label = "Base", expr = "row.grossAmount", kind = StepKind.BASE, format = "currency:BRL"),
 *                 @CalculationStep(label = "Descontos", expr = "row.totalDeductions", kind = StepKind.DEDUCTION, format = "currency:BRL")
 *             }
 *         )
 *     }
 * )
 * }</pre>
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
     * Codigo deterministico da regra de negocio ou formula (ex.: {@code "FIN-TETO-CF88-37-XI"}).
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

    /** Rotulo humano da parcela base inicial da trilha. */
    String baseLabel() default "";
    /** Expressao deterministica do valor base inicial sobre {@code row.*}; nao deve ser repetida em {@link #additionExpr()} ou {@link #deductionExpr()}. */
    String baseExpr() default "";
    /** Formato de exibicao da parcela base (ex.: {@code "currency:BRL"}, {@code "integer"}, {@code "percent"}). */
    String baseFormat() default "";
    /** Nota explicativa fixa exibida sob a parcela base. */
    String baseHint() default "";
    /** Expressao opcional sobre {@code row.*} para nota explicativa dinamica da parcela base. */
    String baseHintExpr() default "";

    /** Rotulo humano da parcela de adicao/acrescimo da trilha. */
    String additionLabel() default "";
    /** Expressao deterministica da parcela de adicao sobre {@code row.*}; deve ser distinta de {@link #baseExpr()}. */
    String additionExpr() default "";
    /** Formato de exibicao da parcela de adicao (ex.: {@code "currency:BRL"}, {@code "integer"}, {@code "percent"}). */
    String additionFormat() default "";
    /** Nota explicativa fixa exibida sob a parcela de adicao. */
    String additionHint() default "";
    /** Expressao opcional sobre {@code row.*} para nota explicativa dinamica da parcela de adicao. */
    String additionHintExpr() default "";

    /** Rotulo humano da parcela de deducao/desconto da trilha. */
    String deductionLabel() default "";
    /** Expressao deterministica da parcela de deducao sobre {@code row.*}; deve ser distinta de {@link #baseExpr()}. */
    String deductionExpr() default "";
    /** Formato de exibicao da parcela de deducao (ex.: {@code "currency:BRL"}, {@code "integer"}, {@code "percent"}). */
    String deductionFormat() default "";
    /** Nota explicativa fixa exibida sob a parcela de deducao. */
    String deductionHint() default "";
    /** Expressao opcional sobre {@code row.*} para nota explicativa dinamica da parcela de deducao. */
    String deductionHintExpr() default "";

    /** Rotulo humano do fator multiplicador ou percentual aplicado na trilha. */
    String factorLabel() default "";
    /** Expressao deterministica do fator aplicado sobre {@code row.*}. */
    String factorExpr() default "";
    /** Formato de exibicao do fator (ex.: {@code "percent"}, {@code "decimal"}, {@code "number"}). */
    String factorFormat() default "";
    /** Nota explicativa fixa exibida sob o fator. */
    String factorHint() default "";
    /** Expressao opcional sobre {@code row.*} para nota explicativa dinamica do fator. */
    String factorHintExpr() default "";

    /** Rotulo humano da etapa de trava/teto ou limite maximo aplicado na trilha. */
    String capLabel() default "";
    /**
     * Operador semantico exibido para a etapa de trava/teto (ex.: "≤", "≥", "=").
     * Quando omitido, o runtime assume "≤" por retrocompatibilidade.
     */
    String capOperator() default "";
    /** Expressao deterministica do valor de trava/teto sobre {@code row.*}. */
    String capExpr() default "";
    /** Formato de exibicao da etapa de trava/teto (ex.: {@code "currency:BRL"}, {@code "integer"}). */
    String capFormat() default "";
    /** Nota explicativa fixa exibida sob a etapa de trava/teto. */
    String capHint() default "";
    /** Expressao opcional sobre {@code row.*} para nota explicativa dinamica da etapa de trava/teto. */
    String capHintExpr() default "";

    /**
     * Rotulo opcional para etapa de meta/piso minimo exigido (operador padrao "≥"),
     * adequado para requisitos como carencia, pontuacao minima ou tempo/idade minima.
     */
    String targetLabel() default "";
    /**
     * Expressao opcional para o valor de meta/piso minimo exigido.
     */
    String targetExpr() default "";
    /**
     * Formato de exibicao da etapa de meta/piso (ex.: "integer", "number", "currency:BRL").
     */
    String targetFormat() default "";
    /**
     * Nota explicativa opcional exibida sob a etapa de meta/piso.
     */
    String targetHint() default "";
    /**
     * Expressao opcional para nota explicativa dinamica da etapa de meta/piso.
     */
    String targetHintExpr() default "";

    /** Rotulo humano do resultado final consolidado da trilha. */
    String resultLabel() default "";
    /** Expressao deterministica do resultado final sobre {@code row.*}. */
    String resultExpr() default "";
    /** Formato de exibicao do resultado final (ex.: {@code "currency:BRL"}, {@code "integer"}, {@code "percent"}). */
    String resultFormat() default "";
    /** Tom semantico opcional do resultado final ({@link QuickFilterTone#allowedWireValues()}). */
    String resultTone() default "";
    /** Nota explicativa fixa exibida sob o resultado final. */
    String resultHint() default "";
    /** Expressao opcional sobre {@code row.*} para nota explicativa dinamica do resultado final. */
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

    /**
     * Lista ordenada de etapas ou parcelas explicaveis arbitrarias desta trilha.
     */
    CalculationStep[] steps() default {};
}
