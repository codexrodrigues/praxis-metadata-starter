package org.praxisplatform.uischema.capability;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Resolve as operacoes canonicas publicadas para um recurso a partir do OpenAPI.
 *
 * <p>
 * Esta fronteira reduz o documento OpenAPI a um mapa enxuto de capacidades baseline do recurso,
 * como create, update, filter, cursor e stats. Ela nao trata availability contextual nem
 * surfaces/actions, apenas operacoes canonicas detectadas no contrato HTTP.
 * </p>
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
