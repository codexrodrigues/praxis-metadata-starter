package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.surface.SurfaceCatalogItem;

import java.util.List;
import java.util.Map;

/**
 * Snapshot unificado das capacidades canonicamente disponiveis para uma colecao ou instancia.
 */
public record CapabilitySnapshot(
        String resourceKey,
        String resourcePath,
        String group,
        Object resourceId,
        Map<String, Boolean> canonicalOperations,
        List<SurfaceCatalogItem> surfaces,
        List<ActionCatalogItem> actions
) {

    public CapabilitySnapshot {
        canonicalOperations = canonicalOperations == null ? Map.of() : Map.copyOf(canonicalOperations);
        surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
