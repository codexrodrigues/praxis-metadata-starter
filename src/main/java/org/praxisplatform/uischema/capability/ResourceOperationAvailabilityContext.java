package org.praxisplatform.uischema.capability;

import java.util.Map;

/**
 * Contexto neutro para avaliar disponibilidade de operacoes canonicas de recurso.
 *
 * <p>
 * O contexto carrega apenas semantica publica e host-neutral: recurso, path, operacao,
 * escopo, item opcional e snapshot de estado opcional. Detalhes privados de seguranca,
 * tenant, sessao ou legado devem permanecer encapsulados no provider do host.
 * </p>
 */
public record ResourceOperationAvailabilityContext(
        String resourceKey,
        String resourcePath,
        String operationId,
        String scope,
        Object resourceId,
        ResourceStateSnapshot resourceState,
        Map<String, Object> metadata
) {

    public ResourceOperationAvailabilityContext {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ResourceOperationAvailabilityContext collection(
            String resourceKey,
            String resourcePath,
            String operationId
    ) {
        return new ResourceOperationAvailabilityContext(
                resourceKey,
                resourcePath,
                operationId,
                "COLLECTION",
                null,
                null,
                Map.of()
        );
    }

    public static ResourceOperationAvailabilityContext item(
            String resourceKey,
            String resourcePath,
            String operationId,
            Object resourceId
    ) {
        return item(resourceKey, resourcePath, operationId, resourceId, null);
    }

    public static ResourceOperationAvailabilityContext item(
            String resourceKey,
            String resourcePath,
            String operationId,
            Object resourceId,
            ResourceStateSnapshot resourceState
    ) {
        return new ResourceOperationAvailabilityContext(
                resourceKey,
                resourcePath,
                operationId,
                "ITEM",
                resourceId,
                resourceState,
                Map.of()
        );
    }
}
