package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Implementacao padrao de {@link OpenApiDocumentService} com cache em memoria.
 *
 * <p>
 * Mantem cache separado para documentos OpenAPI por grupo e para hashes estruturais por
 * {@code schemaId}. O fetch do documento continua delegando a {@link OpenApiDocsSupport}; esta
 * classe apenas concentra a politica de memoizacao e a traducao de falhas em excecoes estruturais
 * adequadas para os controllers canonicamente expostos.
 * </p>
 *
 * <p>
 * Em termos de plataforma, esta classe e o ponto central de cache para documentos e hashes
 * estruturais. Ela evita que controllers e resolvedores repitam fetch remoto e recalculo de hash
 * com criterios divergentes.
 * </p>
 */
public class CachedOpenApiDocumentService implements OpenApiDocumentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachedOpenApiDocumentService.class);

    @Value("${springdoc.api-docs.path:/v3/api-docs}")
    private String openApiBasePath;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final OpenApiDocsSupport openApiDocsSupport;
    private final SchemaCanonicalizer schemaCanonicalizer = new SchemaCanonicalizer();
    private final Map<String, JsonNode> documentCache = new ConcurrentHashMap<>();
    private final Map<String, String> schemaHashCache = new ConcurrentHashMap<>();

    public CachedOpenApiDocumentService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            OpenApiDocsSupport openApiDocsSupport
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.openApiDocsSupport = openApiDocsSupport;
    }

    @Override
    public String resolveGroupFromPath(String path) {
        return openApiDocsSupport.resolveGroupFromPath(path);
    }

    @Override
    public JsonNode getDocumentForGroup(String groupName) {
        return documentCache.computeIfAbsent(groupName, group -> {
            try {
                JsonNode groupDoc = openApiDocsSupport.fetchOpenApiDocument(restTemplate, openApiBasePath, group, LOGGER);
                if (groupDoc != null) {
                    long sizeKB = estimateJsonSize(groupDoc) / 1024;
                    LOGGER.info("Documento OpenAPI especifico cacheado para grupo '{}' (~{}KB)", group, sizeKB);
                    return groupDoc;
                }
                throw new IllegalStateException("OpenAPI document helper returned null for group: " + group);
            } catch (Exception e) {
                LOGGER.error("Falha critica ao buscar documento OpenAPI para grupo '{}': {}", group, e.getMessage());
                throw new IllegalStateException("Failed to retrieve the OpenAPI document for group: " + group, e);
            }
        });
    }

    @Override
    public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
        return schemaHashCache.computeIfAbsent(schemaId, key -> {
            JsonNode payloadNode = payloadSupplier.get();
            JsonNode canonical = schemaCanonicalizer.canonicalize(payloadNode);
            return SchemaHashUtil.sha256Hex(canonical);
        });
    }

    @Override
    public void clearCaches() {
        int cacheSize = documentCache.size();
        int schemaCacheSize = schemaHashCache.size();
        documentCache.clear();
        schemaHashCache.clear();
        LOGGER.info(
                "Cache de documentos OpenAPI limpo. {} entradas removidas. Cache de schemaHash limpo. {} entradas removidas.",
                cacheSize,
                schemaCacheSize
        );
    }

    private long estimateJsonSize(JsonNode jsonNode) {
        try {
            return objectMapper.writeValueAsString(jsonNode).length();
        } catch (Exception e) {
            return 0;
        }
    }
}
