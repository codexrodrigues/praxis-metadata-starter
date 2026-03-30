package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class TestOpenApiDocumentService implements OpenApiDocumentService {

    private final OpenApiDocsSupport openApiDocsSupport;
    private final SchemaCanonicalizer schemaCanonicalizer = new SchemaCanonicalizer();
    private final Map<String, JsonNode> documentCache = new ConcurrentHashMap<>();
    private final Map<String, String> schemaHashCache = new ConcurrentHashMap<>();

    TestOpenApiDocumentService(OpenApiDocsSupport openApiDocsSupport) {
        this.openApiDocsSupport = openApiDocsSupport;
    }

    @Override
    public String resolveGroupFromPath(String path) {
        return openApiDocsSupport.resolveGroupFromPath(path);
    }

    @Override
    public JsonNode getDocumentForGroup(String groupName) {
        JsonNode document = documentCache.get(groupName);
        if (document == null) {
            throw new IllegalStateException("No OpenAPI document registered for group: " + groupName);
        }
        return document;
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
        documentCache.clear();
        schemaHashCache.clear();
    }

    void putDocument(String groupName, JsonNode document) {
        documentCache.put(groupName, document);
    }

    JsonNode getCachedDocument(String groupName) {
        return documentCache.get(groupName);
    }

    void clearSchemaHashes() {
        schemaHashCache.clear();
    }
}
