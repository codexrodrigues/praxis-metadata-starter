package org.praxisplatform.uischema.exporting;

/**
 * Contexto canonico de localizacao usado para materializar valores exportados.
 */
public record CollectionExportLocalization(
        String locale,
        String timeZone
) {
}
