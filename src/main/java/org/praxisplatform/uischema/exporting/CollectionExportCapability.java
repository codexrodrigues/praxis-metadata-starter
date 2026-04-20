package org.praxisplatform.uischema.exporting;

import java.util.List;
import java.util.Map;

/**
 * Detalhes publicados por um recurso que suporta exportacao de colecao.
 *
 * <p>
 * O endpoint {@code /capabilities} usa este contrato para expor formatos, escopos e limites
 * reais do recurso sem transformar capabilities em fonte estrutural de schema.
 * </p>
 */
public record CollectionExportCapability(
        List<CollectionExportFormat> formats,
        List<CollectionExportScope> scopes,
        Map<String, Integer> maxRows,
        boolean async
) {

    public CollectionExportCapability {
        formats = formats == null ? List.of() : List.copyOf(formats);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        maxRows = maxRows == null ? Map.of() : Map.copyOf(maxRows);
    }
}
