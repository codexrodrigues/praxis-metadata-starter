package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.ResourceStateSnapshot;

import java.security.Principal;
import java.util.Locale;
import java.util.Set;

/**
 * Contexto canonico de avaliacao de disponibilidade de actions.
 *
 * <p>
 * O contexto consolida identidade do recurso, instancia atual quando houver, tenancy, locale,
 * principal, authorities e snapshot opcional de estado para que varias actions possam ser
 * avaliadas de forma consistente no mesmo request.
 * </p>
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
