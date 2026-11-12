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
        ((ObjectNode) source.path("paths").path(OPERATION.path()).path("post").path("responses")
                .path("200").path("content").path("application/json").path("schema")
                .path("properties").path("result")).put("type", "integer");
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
        assertEquals("integer", CanonicalOpenApiGroupSnapshot.capture(documents, "inventory")
                .requireResponseSchema(OPERATION).schema().path("properties").path("result").path("type").asText());
        assertEquals(2, strictReads.get());
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

    @Test void pathResolverAndReturnedContractsCannotMutateTheOwnedSnapshot() {
        var pathReads = new AtomicInteger();
        OpenApiDocumentService documents = org.mockito.Mockito.mock(OpenApiDocumentService.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doReturn(document()).when(documents).getDocumentForGroupStrict("inventory");
        org.mockito.Mockito.doAnswer(call -> {
            pathReads.incrementAndGet();
            ((ObjectNode) call.getArgument(0)).removeAll();
            return OPERATION.path();
        }).when(documents).resolveDocumentPath(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        var snapshot = CanonicalOpenApiGroupSnapshot.capture(documents, "inventory");
        var request = snapshot.requireRequestSchema(OPERATION);
        ((ObjectNode) request.schema()).removeAll();
        var response = snapshot.requireResponseSchema(OPERATION);
        ((ObjectNode) response.schema()).removeAll();
        assertEquals("string", snapshot.requireRequestSchema(OPERATION).schema()
                .path("properties").path("value").path("type").asText());
        assertEquals("string", snapshot.requireResponseSchema(OPERATION).schema()
                .path("properties").path("result").path("type").asText());
        assertEquals(1, pathReads.get());
        CanonicalOpenApiGroupSnapshot.capture(documents, "inventory").requireRequestSchema(OPERATION);
        assertEquals(2, pathReads.get());
    }

    @Test void projectionCapturesBaselineCapabilitiesOnceAndIsolatesCallbackMutations() {
        var source = document();
        source.putObject("components").putObject("schemas").putObject("Result")
                .put("type", "object").putObject("properties").putObject("result").put("type", "string");
        ((ObjectNode) source.path("paths").path(OPERATION.path()).path("post").path("responses")
                .path("200").path("content").path("application/json")).putObject("schema")
                .put("$ref", "#/components/schemas/Result");
        var documents = org.mockito.Mockito.mock(OpenApiDocumentService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doReturn(source).when(documents).getDocumentForGroupStrict("inventory");
        var caps = org.mockito.Mockito.mock(org.praxisplatform.uischema.capability.CanonicalCapabilityResolver.class);
        var values = new java.util.HashMap<>(java.util.Map.of("create", true));
        var reads = new AtomicInteger();
        org.mockito.Mockito.when(caps.resolve(org.mockito.ArgumentMatchers.any(JsonNode.class),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            reads.incrementAndGet();
            ((ObjectNode) call.getArgument(0)).removeAll();
            return values;
        });
        var references = org.mockito.Mockito.mock(org.praxisplatform.uischema.schema.SchemaReferenceResolver.class);
        var snapshot = CanonicalOpenApiGroupSnapshot.capture(documents, "inventory");
        var request = snapshot.resolveFilteredProjection(OPERATION, "request", references, caps, "id", null);
        ((ObjectNode) request.evidence()).removeAll();
        values.put("create", false);
        var response = snapshot.resolveFilteredProjection(OPERATION, "response", references, caps, "id", null);
        assertEquals(true, response.evidence().path("capabilities").path("create").asBoolean());
        assertEquals("string", response.evidence().path("resolvedSchema").path("properties")
                .path("result").path("type").asText());
        assertEquals(1, reads.get());
        assertEquals("string", snapshot.requireRequestSchema(OPERATION).schema()
                .path("properties").path("value").path("type").asText());
        var next = CanonicalOpenApiGroupSnapshot.capture(documents, "inventory")
                .resolveFilteredProjection(OPERATION, "request", references, caps, "id", null);
        assertEquals(false, next.evidence().path("capabilities").path("create").asBoolean());
        assertEquals(2, reads.get());
        var other = org.mockito.Mockito.mock(org.praxisplatform.uischema.capability.CanonicalCapabilityResolver.class);
        org.mockito.Mockito.when(other.resolve(org.mockito.ArgumentMatchers.any(JsonNode.class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.Map.of("create", false));
        assertEquals(false, snapshot.resolveFilteredProjection(OPERATION, "request", references, other, "id", null)
                .evidence().path("capabilities").path("create").asBoolean());
    }

    @Test void reusedProjectionPreservesIdReadOnlyAndResolverIdentityAndNeverExposesItsEvidence() {
        var documents = org.mockito.Mockito.mock(OpenApiDocumentService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doReturn(document()).when(documents).getDocumentForGroupStrict("inventory");
        var caps = org.mockito.Mockito.mock(org.praxisplatform.uischema.capability.CanonicalCapabilityResolver.class);
        org.mockito.Mockito.when(caps.resolve(org.mockito.ArgumentMatchers.any(JsonNode.class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.Map.of("create", true));
        var references = org.mockito.Mockito.mock(org.praxisplatform.uischema.schema.SchemaReferenceResolver.class);
        org.mockito.Mockito.when(references.resolve(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(call ->
                new org.praxisplatform.uischema.schema.CanonicalSchemaRef(
                        call.getArgument(6) + ":" + call.getArgument(7), "request", "/schemas/filtered"));
        var snapshot = CanonicalOpenApiGroupSnapshot.capture(documents, "inventory");
        var first = snapshot.resolveFilteredProjection(OPERATION, "request", references, caps, "id", true);
        ((ObjectNode) first.evidence()).removeAll();
        var again = snapshot.resolveFilteredProjection(OPERATION, "request", references, caps, "id", true);
        assertEquals("id:true", again.reference().schemaId());
        assertEquals("string", again.evidence().path("resolvedSchema").path("properties").path("value").path("type").asText());
        assertEquals("code:true", snapshot.resolveFilteredProjection(OPERATION, "request", references, caps, "code", true)
                .reference().schemaId());
        assertEquals("id:false", snapshot.resolveFilteredProjection(OPERATION, "request", references, caps, "id", false)
                .reference().schemaId());
        var other = org.mockito.Mockito.mock(org.praxisplatform.uischema.schema.SchemaReferenceResolver.class);
        org.mockito.Mockito.when(other.resolve(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(
                new org.praxisplatform.uischema.schema.CanonicalSchemaRef("other", "request", "/schemas/filtered"));
        assertEquals("other", snapshot.resolveFilteredProjection(OPERATION, "request", other, caps, "id", true)
                .reference().schemaId());
    }
}
