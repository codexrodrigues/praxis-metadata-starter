package org.praxisplatform.uischema.surface;

import java.util.Optional;

/**
 * Provider baseline que nao publica estado contextual do recurso.
 */
public class NoOpResourceStateSnapshotProvider implements ResourceStateSnapshotProvider {

    @Override
    public Optional<ResourceStateSnapshot> resolve(String resourceKey, Object resourceId) {
        return Optional.empty();
    }
}
