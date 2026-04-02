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
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UiAnalytics {

    AnalyticsProjection[] projections();
}
