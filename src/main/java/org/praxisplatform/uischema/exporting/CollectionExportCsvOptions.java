package org.praxisplatform.uischema.exporting;

/**
 * Opcoes canonicas para materializacao CSV.
 */
public record CollectionExportCsvOptions(
        String delimiter,
        String encoding,
        Boolean includeBom,
        String lineEnding,
        Boolean excelCompatibility,
        Boolean includeSepDirective
) {
}
