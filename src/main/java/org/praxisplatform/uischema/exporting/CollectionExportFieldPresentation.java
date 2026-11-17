package org.praxisplatform.uischema.exporting;

import java.util.Map;

/**
 * Apresentacao serializavel de um campo de exportacao.
 *
 * <p>O cliente pode materializar esta intencao a partir da UI, mas o recurso deve
 * reconcilia-la com sua allowlist canonica antes de exportar dados.</p>
 */
public record CollectionExportFieldPresentation(
        String semanticType,
        String format,
        String currency,
        String locale,
        String timeZone,
        String trueLabel,
        String falseLabel,
        String nullDisplay,
        Map<String, String> valueLabels
) {

    public CollectionExportFieldPresentation {
        valueLabels = valueLabels == null || valueLabels.isEmpty() ? Map.of() : Map.copyOf(valueLabels);
    }

    public CollectionExportFieldPresentation(
            String semanticType,
            String format,
            String currency,
            String locale,
            String timeZone,
            String trueLabel,
            String falseLabel,
            String nullDisplay
    ) {
        this(semanticType, format, currency, locale, timeZone, trueLabel, falseLabel, nullDisplay, Map.of());
    }
}
