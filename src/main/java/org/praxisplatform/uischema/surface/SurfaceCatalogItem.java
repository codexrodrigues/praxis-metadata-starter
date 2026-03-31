package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

import java.util.List;

/**
 * Surface pronta para consumo por clientes documentais e runtimes UI.
 */
public record SurfaceCatalogItem(
        String id,
        String resourceKey,
        SurfaceKind kind,
        SurfaceScope scope,
        String title,
        String description,
        String intent,
        String operationId,
        String path,
        String method,
        String schemaId,
        String schemaUrl,
        AvailabilityDecision availability,
        int order,
        List<String> tags
) {
}
