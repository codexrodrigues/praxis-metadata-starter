package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma operacao HTTP real com projections semanticas analiticas.
 *
 * <p>
 * A anotacao nao define renderer nem detalhes visuais de chart. Ela apenas publica projections
 * opcionais em {@code x-ui.analytics}, preservando a neutralidade de {@code praxis.stats} e a
 * escolha de apresentacao no runtime consumidor.
 * </p>
 */
/**
 * Declara projecoes analiticas semanticas associadas a um endpoint ou controller de recurso.
 *
 * <p>Quando aplicada em nivel de classe ({@link ElementType#TYPE}) em um controller resource-oriented,
 * publica automaticamente {@code x-ui.analytics} na operacao {@code /stats/group-by} do recurso
 * (ou em {@code /filter} quando {@code /stats/group-by} nao existir), respeitando sobrescritas
 * em nivel de metodo ({@link ElementType#METHOD}).</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface UiAnalytics {

    AnalyticsProjection[] projections();
}
