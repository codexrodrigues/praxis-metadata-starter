package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Governanca declarativa de micro visualizacoes ultraleves para campos e metodos de DTOs.
 *
 * <p>
 * O processador OpenAPI do {@code praxis-metadata-starter} traduz esta anotacao para o contrato
 * canonico publicado em {@code x-ui.presentation = { presenter: "microVisualization", visualization: { ... } }}.
 * O {@code @praxisui/table} consome esse contrato para instanciar automaticamente o renderer de celula
 * correspondente (bullet, radial, comparison, etc.) sem exigir codigo TypeScript no cliente.
 * A anotacao fornece a base apos presets; {@code @UISchema.extraProperties} tem precedencia final.
 * O contrato efetivo com presenter {@code microVisualization} exige fallback textual nao vazio,
 * declarado aqui ou em {@code presentation.visualization.fallbackText} via extraProperties.
 * </p>
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MicroVisualization {

    /**
     * Tipo da micro visualizacao.
     */
    MicroVisualizationKind kind() default MicroVisualizationKind.BULLET;

    /**
     * Superficie de renderizacao visada. Padrao: "table-cell".
     */
    String surface() default "table-cell";

    /**
     * Tamanho/densidade visual: "xs", "sm", "md", "lg" ou "responsive".
     */
    String size() default "sm";

    /**
     * Valor numerico estatico (quando aplicavel).
     */
    double value() default Double.NaN;

    /**
     * Expressao dinamica JsonLogic ou property path para obter o valor da linha.
     */
    String valueExpr() default "";

    /**
     * Meta ou target numerico estatico.
     */
    double target() default Double.NaN;

    /**
     * Expressao dinamica para obter o target da linha.
     */
    String targetExpr() default "";

    /**
     * Total ou teto numerico estatico.
     */
    double total() default Double.NaN;

    /**
     * Expressao dinamica para obter o total da linha.
     */
    String totalExpr() default "";

    /**
     * Linha de base estatica.
     */
    double baseline() default Double.NaN;

    /**
     * Expressao dinamica para obter a linha de base.
     */
    String baselineExpr() default "";

    /**
     * Tom semantico: "neutral", "info", "success", "warning", "danger", "critical".
     */
    String tone() default "";

    /**
     * Texto alternativo legivel / fallback caso o microchart nao renderize.
     * Obrigatorio e nao vazio no contrato efetivo de microVisualization. O valor pode ser
     * fornecido por extraProperties; o starter nao inventa texto de dominio.
     */
    String fallbackText() default "";

    /**
     * Se deve exibir valor compacto formatado junto da visualizacao (ex: em bullets).
     */
    boolean compactValue() default false;

    /**
     * Sufixo do valor (ex: "%", " pts", " h").
     */
    String valueSuffix() default "";

    /**
     * Expressao dinamica computada para obtencao do tom semantico (JsonLogic ou formula com prefixo "=").
     */
    String toneExpr() default "";

    /**
     * Conjunto ordenado de limiares / faixas semanticas declarativas para resolucao automatica de tom e bullets.
     */
    Threshold[] thresholds() default {};
}
