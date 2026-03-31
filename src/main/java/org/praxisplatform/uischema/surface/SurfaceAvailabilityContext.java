package org.praxisplatform.uischema.surface;

import java.security.Principal;
import java.util.Locale;

/**
 * Contexto minimo de avaliacao de disponibilidade de surfaces.
 */
public record SurfaceAvailabilityContext(
        String resourceKey,
        String resourcePath,
        Object resourceId,
        String tenant,
        Locale locale,
        Principal principal
) {
}
