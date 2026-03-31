package org.praxisplatform.uischema.capability;

import java.util.Optional;

/**
 * Resolve snapshots de estado de recurso para availability contextual.
 *
 * <p>
 * Implementacoes devem ser batch-aware quando necessario e nunca disparar lookup por item do
 * catalogo. O starter chama essa fronteira no maximo uma vez por recurso/catalogo contextual.
 * </p>
 */
public interface ResourceStateSnapshotProvider {

    Optional<ResourceStateSnapshot> resolve(String resourceKey, Object resourceId);
}
