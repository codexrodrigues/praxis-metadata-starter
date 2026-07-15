package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Referencia estavel a uma surface de recurso.
 *
 * <p>Path, schema, presentation e availability continuam pertencendo ao catalogo
 * canonico de {@code /schemas/surfaces}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticsSurfaceTarget {

    String resourceKey();

    String surfaceId();
}
