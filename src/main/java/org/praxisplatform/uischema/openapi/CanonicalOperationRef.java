package org.praxisplatform.uischema.openapi;

/**
 * Canonical reference to a concrete OpenAPI operation.
 */
public record CanonicalOperationRef(
        String group,
        String operationId,
        String path,
        String method
) {
}
