package org.praxisplatform.uischema.action;

/**
 * Resolve o contexto compartilhado de availability de actions para o catalogo atual.
 */
public interface ActionAvailabilityContextResolver {

    ActionAvailabilityContext resolve(String resourceKey, String resourcePath, Object resourceId);
}
