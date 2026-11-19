package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara projecoes analiticas semanticas associadas a um endpoint ou controller de recurso.
 *
 * <p>A anotacao nao define renderer nem detalhes visuais de chart. Ela publica projecoes em
 * {@code x-ui.analytics}, preservando a neutralidade de {@code praxis.stats} e deixando a escolha
 * da apresentacao visual para o runtime consumidor.</p>
 *
 * <p>Quando aplicada em nivel de classe ({@link ElementType#TYPE}) em um controller
 * resource-oriented, publica automaticamente {@code x-ui.analytics} na operacao
 * {@code /stats/group-by} do recurso (ou em {@code /filter} quando {@code /stats/group-by} nao
 * existir), respeitando sobrescritas em nivel de metodo ({@link ElementType#METHOD}).</p>
 *
 * <pre>{@code
 * @UiAnalytics(
 *     projections = {
 *         @AnalyticsProjection(
 *             id = "orders-by-department",
 *             intent = AnalyticsIntent.RANKING,
 *             sourceOperation = AnalyticsOperation.GROUP_BY,
 *             primaryDimension = @AnalyticsDimensionBinding(
 *                 field = "department",
 *                 keyFilterField = "departmentId"
 *             ),
 *             primaryMetrics = {
 *                 @AnalyticsMetricBinding(field = "totalAmount", aggregation = "sum", label = "Total")
 *             },
 *             crossFilter = true
 *         )
 *     }
 * )
 * }</pre>
 *
 * @see AnalyticsProjection
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface UiAnalytics {

    /** Projecoes semanticas publicadas em {@code x-ui.analytics.projections}. */
    AnalyticsProjection[] projections();
}
