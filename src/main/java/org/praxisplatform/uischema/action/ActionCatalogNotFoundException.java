package org.praxisplatform.uischema.action;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Falha explicita de lookup no catalogo de actions.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ActionCatalogNotFoundException extends RuntimeException {

    public ActionCatalogNotFoundException(String message) {
        super(message);
    }

    public static ActionCatalogNotFoundException unknownResourceKey(String resourceKey) {
        return new ActionCatalogNotFoundException("Unknown action resourceKey: " + resourceKey);
    }

    public static ActionCatalogNotFoundException unknownGroup(String group) {
        return new ActionCatalogNotFoundException("Unknown action group: " + group);
    }

    public static ActionCatalogNotFoundException missingItemActions(String resourceKey) {
        return new ActionCatalogNotFoundException("No item-scoped actions were found for resourceKey: " + resourceKey);
    }

    public static ActionCatalogNotFoundException missingCollectionActions(String resourceKey) {
        return new ActionCatalogNotFoundException("No collection-scoped actions were found for resourceKey: " + resourceKey);
    }
}
