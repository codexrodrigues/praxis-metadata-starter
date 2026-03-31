package org.praxisplatform.uischema.surface;

import java.security.Principal;
import java.util.Locale;
import java.util.Set;

/**
 * Contexto canonico de avaliacao de disponibilidade de surfaces.
 *
 * <p>
 * O contexto representa sinais compartilhados do request/catalogo atual: identidade do recurso,
 * `resourceId` concreto quando houver, tenancy, locale, principal, authorities e snapshot
 * opcional do estado do recurso.
 * </p>
 */
public record SurfaceAvailabilityContext(
        String resourceKey,
        String resourcePath,
        Object resourceId,
        String tenant,
        Locale locale,
        Principal principal,
        Set<String> authorities,
        ResourceStateSnapshot resourceState
) {
}
