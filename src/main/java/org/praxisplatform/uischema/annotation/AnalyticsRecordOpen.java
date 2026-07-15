package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Declara como uma linha nominal de uma projection abre uma surface de item.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticsRecordOpen {

    /** Campo publico da linha cujo valor identifica o recurso alvo. */
    String sourceIdentityField();

    /** Referencia semantica resolvida pelo catalogo de surfaces. */
    AnalyticsSurfaceTarget target();
}
