package org.praxisplatform.uischema.exporting;

/**
 * Opcoes por formato transportadas no contrato canonico de exportacao.
 */
public record CollectionExportFormatOptions(
        CollectionExportCsvOptions csv,
        CollectionExportExcelOptions excel
) {
}
