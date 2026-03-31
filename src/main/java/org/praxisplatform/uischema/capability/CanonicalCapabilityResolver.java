package org.praxisplatform.uischema.capability;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Resolve as operacoes canonicas publicadas para um recurso a partir do OpenAPI.
 */
public interface CanonicalCapabilityResolver {

    /**
     * Resolve as operacoes canonicas publicadas para o path base informado.
     */
    Map<String, Boolean> resolve(String resourcePath);

    /**
     * Resolve as operacoes canonicas usando um documento OpenAPI ja carregado.
     */
    Map<String, Boolean> resolve(JsonNode openApiDocument, String resourcePath);
}
