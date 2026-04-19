package org.praxisplatform.uischema.exporting;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.util.List;
import java.util.Map;

/**
 * Request canonico para exportacao de colecoes resource-oriented.
 *
 * <p>
 * O request espelha o estado de colecao que runtimes como Table e List precisam preservar:
 * escopo, selecao, filtros, ordenacao, campos e limites. A execucao efetiva pertence ao recurso,
 * porque somente ele conhece seguranca, limites de volume, joins e regras de negocio.
 * </p>
 */
public record CollectionExportRequest<FilterDTO extends GenericFilterDTO>(
        String componentType,
        String componentId,
        String resourcePath,
        CollectionExportFormat format,
        CollectionExportScope scope,
        CollectionExportSelection selection,
        List<CollectionExportField> fields,
        FilterDTO filters,
        Object sort,
        CollectionExportPage pagination,
        Map<String, Object> query,
        Boolean includeHeaders,
        Boolean applyFormatting,
        Integer maxRows,
        String fileName,
        Map<String, Object> metadata
) {
    public CollectionExportRequest {
        format = format == null ? CollectionExportFormat.CSV : format;
        scope = scope == null ? CollectionExportScope.AUTO : scope;
        fields = fields == null ? List.of() : List.copyOf(fields);
        query = query == null ? Map.of() : Map.copyOf(query);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
