package org.praxisplatform.uischema.schema;

/**
 * Referencia canonica para um schema resolvido por {@code /schemas/filtered}.
 *
 * <p>
 * O record carrega a identidade estrutural do schema em {@code schemaId}, o tipo logico
 * ({@code request} ou {@code response}) e a URL canonica que materializa a mesma variante
 * estrutural. Os tres campos devem permanecer semanticamente coerentes entre si.
 * </p>
 */
public record CanonicalSchemaRef(
        String schemaId,
        String schemaType,
        String url
) {
}
