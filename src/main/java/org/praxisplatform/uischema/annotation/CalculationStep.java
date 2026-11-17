package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara uma parcela ou etapa explicavel arbitraria dentro de {@link CalculationTrail#steps()},
 * permitindo memorias de calculo com N etapas alem das parcelas fixas base/adicao/deducao/teto.
 */
@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface CalculationStep {

    /** Rotulo humano da parcela ou etapa. */
    String label();

    /** Expressao deterministica da etapa sobre {@code row.*}, validada em {@link org.praxisplatform.uischema.validation.PraxisExpressionStaticValidator}. */
    String expr();

    /** Formato opcional de apresentacao (ex.: {@code currency:BRL}, {@code percent}, {@code integer}). */
    String format() default "";

    /** Natureza da etapa no waterfall explicavel. */
    StepKind kind() default StepKind.BASE;

    /** Expressao opcional de explicacao contextual exibida junto a etapa. */
    String hintExpr() default "";

    /** Base normativa ou regra documental associada a esta etapa. */
    String legalBasis() default "";
}
