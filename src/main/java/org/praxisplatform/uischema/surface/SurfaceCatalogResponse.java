package org.praxisplatform.uischema.surface;

import java.util.List;

/**
 * Payload do catalogo de surfaces.
 */
public record SurfaceCatalogResponse(
        String resourceKey,
        String resourcePath,
        String group,
        Object resourceId,
        List<SurfaceCatalogItem> surfaces
) {
}
