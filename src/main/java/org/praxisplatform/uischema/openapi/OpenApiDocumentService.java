package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Fronteira canonica para documentos OpenAPI usados pelo runtime metadata-driven.
 *
 * <p>
 * Este servico centraliza quatro responsabilidades compartilhadas: resolucao do grupo a partir do
 * path, leitura do documento OpenAPI do grupo, cache estrutural desses documentos e calculo de
 * hash canonico para payloads de schema.
 * </p>
 *
 * <p>
 * No desenho atual do starter, essa interface sustenta tanto endpoints documentais quanto a
 * resolucao estrutural usada por {@code /schemas/filtered}. Ela existe para evitar que cada
 * superfície implemente fetch, cache e hashing de forma divergente.
 * </p>
 */
public interface OpenApiDocumentService {

    /**
     * Resolve o grupo OpenAPI associado ao path informado.
     *
     * <p>
     * Implementacoes podem normalizar o path conforme necessario para reaproveitar a mesma logica
     * de roteamento usada pelos grupos dinamicos do SpringDoc.
     * </p>
     */
    String resolveGroupFromPath(String path);

    /**
     * Retorna o documento OpenAPI do grupo informado.
     *
     * <p>
     * Implementacoes podem buscar o documento remotamente e reutilizar cache interno. Falhas de
     * resolucao ou fetch devem emergir como excecao estrutural, pois essa chamada alimenta
     * superficies canonicas como {@code /schemas/filtered} e {@code /schemas/catalog}.
     * </p>
     */
    JsonNode getDocumentForGroup(String groupName);

    /**
     * Retorna o hash estrutural canonico para o {@code schemaId} informado.
     *
     * <p>
     * O supplier deve produzir o payload estrutural canonico do schema. O hash nao deve refletir
     * campos puramente documentais ou ruido nao estrutural.
     * </p>
     */
    String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier);

    /**
     * Limpa os caches estruturais mantidos pela implementacao.
     *
     * <p>
     * A limpeza deve abranger tanto documentos OpenAPI quanto hashes estruturais ja calculados.
     * </p>
     */
    void clearCaches();

    /**
     * Resolve o path real dentro de {@code paths}, aceitando equivalencia estrutural entre
     * templates OpenAPI que usam nomes diferentes para parametros posicionais.
     */
    default String resolveDocumentPath(JsonNode pathsNode, String requestedPath) {
        return resolveDocumentPath(pathsNode, requestedPath, null);
    }

    /**
     * Resolve o path real dentro de {@code paths}, aceitando equivalencia estrutural entre
     * templates OpenAPI que usam nomes diferentes para parametros posicionais quando a operacao
     * HTTP tambem e compativel.
     *
     * <p>
     * Matches exatos continuam tendo precedencia. A equivalencia estrutural so e usada quando
     * existe um unico candidato compativel; multiplos candidatos indicam ambiguidade canonica e
     * devem ser corrigidos na publicacao OpenAPI em vez de inferidos por heuristica local.
     * </p>
     */
    default String resolveDocumentPath(JsonNode pathsNode, String requestedPath, String operation) {
        if (pathsNode == null || pathsNode.isMissingNode()) {
            return requestedPath;
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(requestedPath);

        String normalized = normalizeOpenApiPath(requestedPath);
        String normalizedOperation = normalizeOperation(operation);
        candidates.add(normalized);
        if ("/".equals(normalized)) {
            candidates.add("/");
        } else {
            candidates.add(normalized + "/");
        }

        for (String candidate : candidates) {
            if (hasText(candidate) && hasOperation(pathsNode, candidate, normalizedOperation)) {
                return candidate;
            }
        }

        String structurallyEquivalentPath = findStructurallyEquivalentPath(pathsNode, normalized, operation);
        if (hasText(structurallyEquivalentPath)) {
            return structurallyEquivalentPath;
        }

        return normalized;
    }

    default String normalizeOpenApiPath(String path) {
        if (!hasText(path)) {
            return "/";
        }

        String normalized = path.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String findStructurallyEquivalentPath(JsonNode pathsNode, String requestedPath, String operation) {
        String requestedSignature = templatedPathSignature(requestedPath);
        if (!hasText(requestedSignature)) {
            return null;
        }

        String normalizedOperation = normalizeOperation(operation);
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        pathsNode.fieldNames().forEachRemaining(candidatePath -> {
            if (!requestedSignature.equals(templatedPathSignature(candidatePath))) {
                return;
            }
            if (!hasOperation(pathsNode, candidatePath, normalizedOperation)) {
                return;
            }
            matches.add(candidatePath);
        });
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous OpenAPI template path resolution for '" + requestedPath + "'."
            );
        }
        return matches.stream().findFirst().orElse(null);
    }

    private String templatedPathSignature(String path) {
        String normalized = normalizeOpenApiPath(path);
        if (!hasText(normalized)) {
            return null;
        }
        return normalized.replaceAll("\\{[^}/]+}", "{}");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeOperation(String operation) {
        return hasText(operation) ? operation.trim().toLowerCase(Locale.ROOT) : null;
    }

    private static boolean hasOperation(JsonNode pathsNode, String candidate, String normalizedOperation) {
        JsonNode pathNode = pathsNode.path(candidate);
        if (pathNode.isMissingNode()) {
            return false;
        }
        return !hasText(normalizedOperation) || !pathNode.path(normalizedOperation).isMissingNode();
    }
}
