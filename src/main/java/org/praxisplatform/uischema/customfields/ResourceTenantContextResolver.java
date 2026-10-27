package org.praxisplatform.uischema.customfields;

import java.util.Optional;

/** Resolves the host's current tenant context. Hosts may bind this to authenticated identity. */
@FunctionalInterface
public interface ResourceTenantContextResolver {
    Optional<String> resolveTenant();
}
