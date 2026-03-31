package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.surface.ResourceStateSnapshot;

import java.security.Principal;
import java.util.Locale;
import java.util.Set;

/**
 * Contexto canonico de avaliacao de disponibilidade de actions.
 */
public record ActionAvailabilityContext(
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
