package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

import java.util.List;

/**
 * Representacao serializavel de uma action semantica pronta para clientes documentais e runtimes UI.
 *
 * <p>
 * Cada item referencia uma operacao HTTP real que representa um comando explicito de negocio.
 * Quando a operacao tiver payload de entrada ou saida, os campos de schema apontam para os
 * schemas canonicos da operacao, sem redefini-los inline no catalogo.
 * </p>
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
