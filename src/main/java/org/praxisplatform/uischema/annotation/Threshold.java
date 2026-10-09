package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Governanca declarativa de faixa ou limiar semantico para micro-visualizacoes ({@link MicroVisualization}).
 *
 * <p>
 * Permite definir regras semanticas de coloracao ({@code tone}) ou classificacao de risco sem a necessidade
 * de embutir formulas procedurais JavaScript / ternarios no frontend ou no schema OpenAPI.
 * </p>
 *
 * <p>
 * O processador OpenAPI {@code CustomOpenApiResolver} traduz esta anotacao para o contrato canonico
 * {@code x-ui.presentation.visualization.thresholds = [ { min, max, value, equals, tone, label }, ... ]}.
 * </p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target({}) // Usada exclusivamente como membro aninhado dentro de @MicroVisualization
public @interface Threshold {

    /**
     * Limite inferior inclusivo da faixa numerica continua.
     */
    double min() default Double.NaN;

    /**
     * Limite superior inclusivo da faixa numerica continua.
     */
    double max() default Double.NaN;

    /**
     * Valor pontual de corte / limiar progressivo (usado comumente em visualizacoes do tipo BULLET).
     */
    double value() default Double.NaN;

    /**
     * Valor exato para correspondencia discreta (string, numero ou booleano literal).
     * Exemplos: "CRITICAL", "ALERTA", "100", "true".
     */
    String equalsValue() default "";

    /**
     * Tom semantico associado: "neutral", "info", "success", "warning", "danger", "critical".
     */
    String tone() default "info";

    /**
     * Rotulo descritivo legivel opcional da faixa (ex.: "Zona de Risco", "Meta Batida").
     */
    String label() default "";
}
