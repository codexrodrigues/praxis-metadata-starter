package org.praxisplatform.uischema.capability;

/**
 * Agrega operacoes canonicas, surfaces e actions para uma colecao ou instancia.
 */
public interface CapabilityService {

    CapabilitySnapshot collectionCapabilities(String resourceKey, String resourcePath);

    CapabilitySnapshot itemCapabilities(String resourceKey, String resourcePath, Object resourceId);
}
