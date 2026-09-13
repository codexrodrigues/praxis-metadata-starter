package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JavaType;

import java.util.Objects;

/**
 * Server-side association between a strictly resolved operation and its actual MVC request DTO.
 * Custom operation resolvers own the same handler/type guarantee as the default implementation.
 * This value is not a discovery payload and does not certify schema, converters or authorization.
 */
public record CanonicalRequestBodyBinding(CanonicalOperationRef operation, JavaType bodyType) {
    public CanonicalRequestBodyBinding {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(bodyType, "bodyType");
    }
}
