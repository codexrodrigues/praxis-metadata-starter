package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.LinkedHashSet;
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
        if (pathsNode == null || pathsNode.isMissingNode()) {
            return requestedPath;
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(requestedPath);

        String normalized = normalizeOpenApiPath(requestedPath);
        candidates.add(normalized);
        if ("/".equals(normalized)) {
            candidates.add("/");
        } else {
            candidates.add(normalized + "/");
        }

        for (String candidate : candidates) {
            if (hasText(candidate) && !pathsNode.path(candidate).isMissingNode()) {
                return candidate;
            }
        }

        String structurallyEquivalentPath = findStructurallyEquivalentPath(pathsNode, normalized);
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

    private String findStructurallyEquivalentPath(JsonNode pathsNode, String requestedPath) {
        String requestedSignature = templatedPathSignature(requestedPath);
        if (!hasText(requestedSignature)) {
            return null;
        }

        Iterator<String> pathIterator = pathsNode.fieldNames();
        while (pathIterator.hasNext()) {
            String candidatePath = pathIterator.next();
            if (requestedSignature.equals(templatedPathSignature(candidatePath))) {
                return candidatePath;
            }
        }
        return null;
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
}
