package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

import java.util.List;

/**
 * Action pronta para consumo por clientes documentais e runtimes UI.
 */
public record ActionCatalogItem(
        String id,
        String resourceKey,
        ActionScope scope,
        String title,
        String description,
        String operationId,
        String path,
        String method,
        String requestSchemaId,
        String requestSchemaUrl,
        String responseSchemaId,
        String responseSchemaUrl,
        AvailabilityDecision availability,
        int order,
        String successMessage,
        List<String> tags
) {
}
