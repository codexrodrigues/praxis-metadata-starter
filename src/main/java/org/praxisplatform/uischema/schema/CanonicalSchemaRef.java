package org.praxisplatform.uischema.schema;

/**
 * Canonical reference to a schema resolved through /schemas/filtered.
 */
public record CanonicalSchemaRef(
        String schemaId,
        String schemaType,
        String url
) {
}
