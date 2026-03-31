package org.praxisplatform.uischema.action;

import java.util.List;

/**
 * Payload do catalogo de actions.
 */
public record ActionCatalogResponse(
        String resourceKey,
        String resourcePath,
        String group,
        Object resourceId,
        List<ActionCatalogItem> actions
) {
}
