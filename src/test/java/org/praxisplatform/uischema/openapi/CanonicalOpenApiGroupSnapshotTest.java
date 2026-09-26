package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalOpenApiGroupSnapshotTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CanonicalOperationRef OPERATION = new CanonicalOperationRef(
            "inventory", "bulk-evaluate", "/api/items/bulk/evaluation", "POST");

    @Test
    void oneDefensiveExactGroupSnapshotCanFeedRequestAndResponseWithoutReReadingTheCache() {
        ObjectNode source = document();
        AtomicInteger strictReads = new AtomicInteger();
        AtomicInteger fallbackReads = new AtomicInteger();
        OpenApiDocumentService documents = new OpenApiDocumentService() {
            @Override public String resolveGroupFromPath(String path) { return "inventory"; }
            @Override public JsonNode getDocumentForGroup(String groupName) {
                fallbackReads.incrementAndGet();
                return document();
            }
            @Override public JsonNode getDocumentForGroupStrict(String groupName) {
                strictReads.incrementAndGet();
                return source;
            }
            @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
                return "not-used";
            }
            @Override public void clearCaches() { }
        };

        CanonicalOpenApiGroupSnapshot snapshot = CanonicalOpenApiGroupSnapshot.capture(documents, "inventory");
        source.with("paths").with(OPERATION.path()).with("post").with("responses")
                .with("200").with("content").with("application/json").with("schema")
                .with("properties").with("result").put("type", "integer");
        JsonNode exposed = snapshot.document();
        ((ObjectNode) exposed.path("paths").path(OPERATION.path()).path("post")
                .path("requestBody").path("content").path("application/json").path("schema")).put("leak", true);

        CanonicalRequestSchema request = snapshot.requireRequestSchema(OPERATION);
        CanonicalResponseSchema response = snapshot.requireResponseSchema(OPERATION);

        assertEquals(1, strictReads.get());
        assertEquals(0, fallbackReads.get());
        assertEquals("string", request.schema().path("properties").path("value").path("type").asText());
        assertEquals("string", response.schema().path("properties").path("result").path("type").asText());
        assertEquals("application/json", request.mediaType());
        assertEquals(200, response.variants().getFirst().status());
        assertEquals("application/json", response.variants().getFirst().mediaType());
        assertFalse(request.schema().has("leak"));
        assertThrows(IllegalStateException.class, () ->
                snapshot.requireRequestSchema(
                        new CanonicalOperationRef("other", OPERATION.operationId(), OPERATION.path(), "POST")));
    }

    private static ObjectNode document() {
        ObjectNode root = JSON.createObjectNode().put("openapi", "3.0.3");
        ObjectNode operation = root.putObject("paths").putObject(OPERATION.path()).putObject("post")
                .put("operationId", OPERATION.operationId());
        operation.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("type", "object").putObject("properties")
                .putObject("value").put("type", "string");
        operation.putObject("responses").putObject("200").put("description", "ok")
                .putObject("content").putObject("application/json")
                .putObject("schema").put("type", "object").putObject("properties")
                .putObject("result").put("type", "string");
        return root;
    }
}
