package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;

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
}
