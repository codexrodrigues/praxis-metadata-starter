package org.praxisplatform.uischema.surface;

import java.util.List;

/**
 * Payload canonico do catalogo de surfaces de um recurso ou instancia.
 *
 * <p>
 * A resposta identifica o recurso, o grupo documental resolvido e, quando aplicavel, o
 * {@code resourceId} contextual da consulta item-level. A lista {@code surfaces} representa
 * somente discovery semantico; o contrato estrutural continua vindo de {@code /schemas/filtered}.
 * </p>
 */
public record SurfaceCatalogResponse(
        String resourceKey,
        String resourcePath,
        String group,
        Object resourceId,
        List<SurfaceCatalogItem> surfaces
) {
}
