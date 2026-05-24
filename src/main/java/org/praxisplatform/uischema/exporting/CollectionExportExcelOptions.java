package org.praxisplatform.uischema.exporting;

/**
 * Opcoes declarativas para futura materializacao XLSX real.
 */
public record CollectionExportExcelOptions(
        String sheetName,
        Boolean freezeHeaders,
        Boolean autoFitColumns,
        Boolean typedCells,
        Boolean includeFormulas
) {
}
