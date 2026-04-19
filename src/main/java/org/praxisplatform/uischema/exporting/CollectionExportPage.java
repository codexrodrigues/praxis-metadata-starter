package org.praxisplatform.uischema.exporting;

/**
 * Estado de paginacao informado pelo runtime consumidor.
 */
public record CollectionExportPage(
        Integer pageIndex,
        Integer pageNumber,
        Integer pageSize,
        Long totalItems
) {
}
