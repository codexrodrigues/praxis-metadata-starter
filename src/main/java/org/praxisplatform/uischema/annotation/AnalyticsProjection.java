package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Projecao semantica analitica publicada em {@code x-ui.analytics.projections[]} sobre uma
 * operacao {@code praxis.stats} real.
 *
 * <p>O {@code UiAnalyticsAnnotationMapper} valida em tempo de geracao OpenAPI regras fail-fast
 * para garantir contratos completos:</p>
 * <ul>
 *   <li>{@link #sourceResource()}: quando omitido, e derivado automaticamente removendo os sufixos
 *       {@code /stats/group-by}, {@code /stats/timeseries}, {@code /stats/distribution} ou
 *       {@code /stats/comparison} do path da operacao;</li>
 *   <li>{@link #comparisonPeriod()}: obrigatorio (com {@code field} e {@code timezone} nao vazios)
 *       quando {@link #sourceOperation()} for {@link AnalyticsOperation#COMPARISON};</li>
 *   <li>{@link #crossFilter()}: quando {@code true}, exige {@code field} e o campo publico do
 *       {@code FilterDTO} em {@code keyFilterField} na dimensao primaria (e na secundaria, se
 *       declarada, ou em cada item de {@link #dimensions()} quando nao vazio);</li>
 *   <li>{@link #recordOpen()}: regra de triade — declarar qualquer um entre
 *       {@code sourceIdentityField}, {@code target.resourceKey} ou {@code target.surfaceId} exige
 *       todos os tres preenchidos;</li>
 *   <li>{@link #defaultLimit()}: sentinela {@code -1} (valores {@code <= 0} omitem {@code defaults.limit}).</li>
 * </ul>
 *
 * @see UiAnalytics
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticsProjection {

    /** Identificador estavel da projecao dentro de {@code x-ui.analytics.projections}. */
    String id();

    /** Intencao analitica semantica (ranking, tendencia, distribuicao, comparacao, etc.). */
    AnalyticsIntent intent();

    /** Operacao canonica {@code praxis.stats} executada pela projecao. */
    AnalyticsOperation sourceOperation();

    /**
     * Caminho base do recurso dono dos endpoints {@code /stats/*}. Quando vazio, o mapper deriva
     * removendo {@code /stats/group-by}, {@code /stats/timeseries}, {@code /stats/distribution}
     * ou {@code /stats/comparison} do path da operacao OpenAPI anotada.
     */
    String sourceResource() default "";

    /** Binding da dimensao principal (ex.: categoria de agrupamento ou eixo temporal). */
    AnalyticsDimensionBinding primaryDimension() default @AnalyticsDimensionBinding(field = "");

    /** Binding opcional da dimensao secundaria para series empilhadas ou matrizes 2D. */
    AnalyticsDimensionBinding secondaryDimension() default @AnalyticsDimensionBinding(field = "");

    /** Lista de dimensoes para projecoes multidimensionais; quando preenchida e {@link #crossFilter()} estiver ativo, todas exigem {@code field} e {@code keyFilterField}. */
    AnalyticsDimensionBinding[] dimensions() default {};

    /**
     * Configuracao de periodo comparativo. Obrigatoria com {@code field} e {@code timezone} nao
     * vazios quando {@link #sourceOperation()} for {@link AnalyticsOperation#COMPARISON}.
     */
    AnalyticsComparisonPeriodBinding comparisonPeriod() default @AnalyticsComparisonPeriodBinding(field = "", timezone = "");

    /** Metricas principais agregadas pela projecao (ex.: count, sum, avg, median). */
    AnalyticsMetricBinding[] primaryMetrics();

    /** Metricas secundarias opcionais exibidas como referencia ou serie auxiliar. */
    AnalyticsMetricBinding[] secondaryMetrics() default {};

    /** Referencias versionadas de politicas de dominio aplicadas; cada entrada exige {@code policyId}, {@code policyVersion}, {@code role} e {@code resultField}, alem de atestacao em par ({@code policyIdField} + {@code policyVersionField}). */
    AnalyticsPolicyReference[] policyRefs() default {};

    /** Ordenacao inicial sugerida para os buckets ou series retornadas. */
    AnalyticsSort[] defaultSort() default {};

    /** Limite inicial maximo de buckets retornados; o sentinela {@code -1} (ou {@code <= 0}) omite {@code defaults.limit}. */
    int defaultLimit() default -1;

    /** Granularidade temporal padrao publicada em {@code defaults.granularity} quando diferente de {@link AnalyticsGranularity#UNSPECIFIED}. */
    AnalyticsGranularity defaultGranularity() default AnalyticsGranularity.UNSPECIFIED;

    /** Fuso horario IANA padrao publicado em {@code defaults.timezone} quando nao vazio. */
    String defaultTimezone() default "";

    /** Identificador de preset de periodo inicial publicado em {@code defaults.periodPreset} quando nao vazio. */
    String defaultPeriodPreset() default "";

    /** Indica se lacunas temporais sem ocorrencias devem ser preenchidas por padrao em series temporais. */
    boolean defaultFillGaps() default false;

    /** Familias de apresentacao recomendadas ao runtime consumidor (ex.: chart, analytic-table, kpi). */
    AnalyticsPresentationFamily[] preferredFamilies() default {};

    /** Habilita navegacao de drill-down hierarquico a partir dos buckets desta projecao. */
    boolean drillDown() default false;

    /** Habilita selecao pontual de elementos/buckets no runtime consumidor. */
    boolean pointSelection() default false;

    /**
     * Habilita filtragem cruzada no dashboard; exige {@code field} e o nome de campo publico do
     * {@code FilterDTO} em {@code keyFilterField} nas dimensoes declaradas.
     */
    boolean crossFilter() default false;

    /**
     * Configuracao de abertura direta de item a partir de linhas nominais; segue regra de triade
     * exigindo {@code sourceIdentityField}, {@code target.resourceKey} e {@code target.surfaceId}
     * juntos sempre que declarada.
     */
    AnalyticsRecordOpen recordOpen() default @AnalyticsRecordOpen(
            sourceIdentityField = "",
            target = @AnalyticsSurfaceTarget(resourceKey = "", surfaceId = "")
    );
}
