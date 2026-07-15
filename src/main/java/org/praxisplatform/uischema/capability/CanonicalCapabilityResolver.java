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

    /**
     * Resolve a semantica operacional completa publicada para o recurso informado.
     *
     * <p>
     * O default preserva implementacoes customizadas anteriores que conhecem apenas o recorte
     * CRUD. Resolvers baseados no contrato HTTP devem sobrescrever este metodo para publicar
     * tambem query, options, stats e demais operacoes canonicas executaveis.
     * </p>
     */
    default Map<String, CapabilityOperation> resolveOperations(String resourcePath) {
        return resolveCrudOperations(resourcePath);
    }

    /**
     * Resolve a semantica operacional completa usando um documento OpenAPI ja carregado.
     */
    default Map<String, CapabilityOperation> resolveOperations(JsonNode openApiDocument, String resourcePath) {
        return resolveCrudOperations(openApiDocument, resourcePath);
    }

    /**
     * Resolve a semantica operacional minima de CRUD para o recurso informado.
     */
    Map<String, CapabilityOperation> resolveCrudOperations(String resourcePath);

    /**
     * Resolve a semantica operacional minima de CRUD usando um documento OpenAPI ja carregado.
     */
    Map<String, CapabilityOperation> resolveCrudOperations(JsonNode openApiDocument, String resourcePath);
}
