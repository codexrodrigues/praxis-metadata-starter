package org.praxisplatform.uischema.action;

import java.util.List;

/**
 * Payload canonico do catalogo de actions de um recurso ou instancia.
 *
 * <p>
 * A resposta agrega somente discovery semantico de comandos de negocio. Ela identifica o recurso,
 * o grupo documental resolvido e o contexto item-level quando houver {@code resourceId}.
 * Os schemas de request e response continuam sendo referenciados pelos itens individuais.
 * </p>
 */
public record ActionCatalogResponse(
        String resourceKey,
        String resourcePath,
        String group,
        Object resourceId,
        List<ActionCatalogItem> actions
) {
}
