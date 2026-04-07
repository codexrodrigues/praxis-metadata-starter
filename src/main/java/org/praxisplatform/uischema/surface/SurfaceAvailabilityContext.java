package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.ResourceStateSnapshot;

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
 *
 * <p>
 * O objetivo e concentrar esses sinais uma unica vez por recurso e request, para que multiplas
 * surfaces possam ser avaliadas sem repetir acesso a contexto HTTP, seguranca ou estado.
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
