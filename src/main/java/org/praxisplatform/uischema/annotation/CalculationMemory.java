package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publica o contrato canonico de Memoria de Calculo Explicavel ({@code x-ui.calculationMemory})
 * diretamente no {@code ResponseDTO} Java ({@code PLAT-EVO-20}), permitindo que o
 * {@code PraxisAnalyticalDrawerComponent} renderize trilhas auditaveis {@code Base -> Adicao ->
 * Deducao -> Fator -> Trava/Teto -> Resultado} sem duplicar catalogos extensos no frontend e com
 * validacao estatica de todas as expressoes contra as colunas reais do DTO.
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CalculationMemory {

    /**
     * Titulo principal da secao de memoria de calculo.
     */
    String title() default "";

    /**
     * Expressao opcional para titulo dinamico da secao.
     */
    String titleExpr() default "";

    /**
     * Subtitulo descritivo da secao.
     */
    String subtitle() default "";

    /**
     * Expressao opcional para subtitulo dinamico da secao.
     */
    String subtitleExpr() default "";

    /**
     * Icone Material Symbols da secao.
     */
    String icon() default "calculate";

    /**
     * Fundamentacao legal padrao herdada pelas trilhas quando nao sobrescrita individualmente.
     */
    String legalBasis() default "";

    /**
     * Expressao opcional para fundamentacao legal padrao dinamica.
     */
    String legalBasisExpr() default "";

    /**
     * Texto informativo quando nenhuma trilha for aplicavel ao registro selecionado.
     */
    String emptyText() default "";

    /**
     * Trilhas ordenadas de decomposicao matematica e normativa do calculo.
     */
    CalculationTrail[] trails();
}
