package org.praxisplatform.uischema.surface;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Sinaliza que o catalogo de surfaces nao encontrou a chave semantica solicitada.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SurfaceCatalogNotFoundException extends RuntimeException {

    private SurfaceCatalogNotFoundException(String message) {
        super(message);
    }

    public static SurfaceCatalogNotFoundException unknownResourceKey(String resourceKey) {
        return new SurfaceCatalogNotFoundException("Unknown surface resource key: " + resourceKey);
    }

    public static SurfaceCatalogNotFoundException unknownGroup(String group) {
        return new SurfaceCatalogNotFoundException("Unknown surface group: " + group);
    }

    public static SurfaceCatalogNotFoundException missingItemSurfaces(String resourceKey) {
        return new SurfaceCatalogNotFoundException("No item-level surfaces published for resource: " + resourceKey);
    }
}
