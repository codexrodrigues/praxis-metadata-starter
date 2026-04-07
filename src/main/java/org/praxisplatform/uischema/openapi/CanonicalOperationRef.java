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
 *
 * <p>
 * O record representa o minimo contrato compartilhado entre resolvedores OpenAPI, catalogos
 * semanticos e resolvedores de schema. Ele identifica uma operacao documentada sem carregar o
 * payload ou o schema da operacao.
 * </p>
 */
public record CanonicalOperationRef(
        String group,
        String operationId,
        String path,
        String method
) {
}
