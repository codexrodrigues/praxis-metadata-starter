package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Supplier;

public interface OpenApiDocumentService {

    String resolveGroupFromPath(String path);

    JsonNode getDocumentForGroup(String groupName);

    String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier);

    void clearCaches();
}
