package org.praxisplatform.uischema.openapi;

/**
 * Referencia canonica para uma operacao OpenAPI concreta.
 *
 * <p>
 * Quando esta referencia nasce apenas de {@code path + method}, o campo
 * {@code operationId} pode ser {@code null}. Chamadores que exigem {@code operationId} nao nulo
 * devem resolver a operacao a partir de um {@code HandlerMethod} concreto ou por
 * {@code resolveByOperationId}.
 * </p>
 */
public record CanonicalOperationRef(
        String group,
        String operationId,
        String path,
        String method
) {
}
