package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.exporting.CollectionExportCapability;

/**
 * Agrega operacoes canonicas, surfaces e actions para uma colecao ou instancia.
 *
 * <p>
 * Esta interface expõe o snapshot consolidado de capabilities usado por consumidores runtime e
 * documentais. Ela agrega discovery semantico e availability contextual, mas nao redefine schemas
 * ou payloads das operacoes publicadas pelo recurso.
 * </p>
 */
public interface CapabilityService {

    /**
     * Resolve o snapshot de capabilities no escopo da colecao do recurso.
     */
    CapabilitySnapshot collectionCapabilities(String resourceKey, String resourcePath);

    default CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported
    ) {
        return collectionCapabilities(resourceKey, resourcePath);
    }

    default CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            CollectionExportCapability collectionExportCapability
    ) {
        return collectionCapabilities(resourceKey, resourcePath, false, collectionExportCapability);
    }

    default CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported,
            CollectionExportCapability collectionExportCapability
    ) {
        return collectionCapabilities(resourceKey, resourcePath, collectionExportSupported);
    }

    /**
     * Resolve o snapshot de capabilities no escopo de um item especifico do recurso.
     */
    CapabilitySnapshot itemCapabilities(String resourceKey, String resourcePath, Object resourceId);
}
