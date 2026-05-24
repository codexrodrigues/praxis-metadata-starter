package org.praxisplatform.uischema.exporting;

/**
 * Campo solicitado para exportacao de uma colecao metadata-driven.
 */
public record CollectionExportField(
        String key,
        String label,
        Boolean visible,
        Boolean exportable,
        String type,
        String valuePath,
        String format,
        CollectionExportFieldPresentation presentation
) {
    public CollectionExportField(
            String key,
            String label,
            Boolean visible,
            Boolean exportable,
            String type,
            String valuePath
    ) {
        this(key, label, visible, exportable, type, valuePath, null, null);
    }
}
