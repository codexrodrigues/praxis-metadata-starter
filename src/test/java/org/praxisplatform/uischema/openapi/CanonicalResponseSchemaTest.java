package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for the strict response reader used by bulk descriptor composition. */
class CanonicalResponseSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CanonicalOperationRef OPERATION = new CanonicalOperationRef(
            "inventory", "bulk-proposal-read", "/api/items/bulk/proposals/{proposalId}", "GET"
    );

    @Test
    void acceptsEveryExplicitSuccessfulStatusAndReturnsTheOrderedStatusMediaTuple() {
        ObjectNode document = responseDocument();
        ObjectNode responses = (ObjectNode) operation(document).path("responses");
        responses.putObject("101").put("description", "informational");
        responses.putObject("1XX").put("description", "informational range");
        responses.putObject("500").put("description", "failure");
        responses.putObject("302").put("description", "redirect");
        responses.putObject("3XX").put("description", "redirect range");
        responses.putObject("400").put("description", "client failure");
        responses.putObject("4XX").put("description", "client failure range");
        responses.putObject("5XX").put("description", "server failure range");
        JsonNode twoHundred = responses.remove("200");
        response(responses, "201", "application/json", schemaWith("title", "Item"));
        response(responses, "202", "Application/Vnd.Praxis+JSON", schemaWith("title", "Item"));
        responses.set("200", twoHundred);

        CanonicalResponseSchema result = readResponse(document);

        assertEquals(200, result.variants().get(0).status());
        assertEquals("application/json", result.variants().get(0).mediaType());
        assertEquals(201, result.variants().get(1).status());
        assertEquals(202, result.variants().get(2).status());
        assertEquals("Application/Vnd.Praxis+JSON", result.variants().get(2).mediaType());
        assertEquals(3, result.variants().size());
        assertEquals("Item", result.schema().path("properties").path("title").path("description").asText());
        assertEquals("example", result.schema().path("examples").get(0).asText());
        assertEquals("bulk", result.schema().path("x-ui").path("kind").asText());
    }

    @Test
    void canonicalizesEquivalentSuccessfulSchemasButRejectsDivergentOnes() {
        ObjectNode equivalent = responseDocument();
        response(operation(equivalent).path("responses"), "201", "application/json", schemaWith("title", "Item"));
        CanonicalResponseSchema equivalentResult = readResponse(equivalent);
        assertEquals("Item", equivalentResult.schema().path("properties").path("title").path("description").asText());

        ObjectNode divergent = responseDocument();
        response(operation(divergent).path("responses"), "201", "application/json", schemaWith("different", "Item"));
        assertThrows(IllegalStateException.class, () -> readResponse(divergent));

        ObjectNode documentationDifferent = responseDocument();
        ObjectNode responseSchema = schemaWith("title", "Item");
        responseSchema.put("description", "separately documented");
        response(operation(documentationDifferent).path("responses"), "201", "application/json", responseSchema);
        assertThrows(IllegalStateException.class, () -> readResponse(documentationDifferent),
                "Canonical equality conservatively preserves descriptions and other schema data");
    }

    @Test
    void rejectsDefaultAndWildcardSuccessRanges() {
        for (String status : List.of("default", "2XX")) {
            ObjectNode document = responseDocument();
            ObjectNode responses = ((ObjectNode) operation(document).path("responses")).deepCopy();
            operation(document).set("responses", responses);
            responses.putObject(status).put("description", "not explicit");
            assertThrows(IllegalStateException.class, () -> readResponse(document), status);
        }
    }

    @Test
    void rejectsMissingSchemaContentAndUnsupportedOrAmbiguousMediaTypes() {
        ObjectNode missingContent = responseDocument();
        ((ObjectNode) operation(missingContent).path("responses").path("200")).remove("content");

        ObjectNode missingSchema = responseDocument();
        ((ObjectNode) operation(missingSchema).path("responses").path("200")
                .path("content").path("application/json")).remove("schema");

        ObjectNode wildcard = responseDocument();
        ObjectNode wildcardContent = (ObjectNode) operation(wildcard).path("responses").path("200").path("content");
        wildcardContent.removeAll().putObject("*/*").set("schema", schemaWith("title", "Item"));

        ObjectNode xml = responseDocument();
        ObjectNode xmlContent = (ObjectNode) operation(xml).path("responses").path("200").path("content");
        xmlContent.removeAll().putObject("application/xml").set("schema", schemaWith("title", "Item"));

        ObjectNode multipleJson = responseDocument();
        ObjectNode multiple = (ObjectNode) operation(multipleJson).path("responses").path("200").path("content");
        multiple.putObject("application/vnd.praxis+json").set("schema", schemaWith("title", "Item"));

        List<ObjectNode> malformedMediaTypes = new ArrayList<>();
        for (String mediaType : List.of("application/a/b+json", "application/a\tb+json",
                "application/café+json", "application/jſon", "application/vnd.praxis+json; charset=utf-8")) {
            ObjectNode document = responseDocument();
            ObjectNode content = (ObjectNode) operation(document).path("responses").path("200").path("content");
            content.removeAll().putObject(mediaType).set("schema", schemaWith("title", "Item"));
            malformedMediaTypes.add(document);
        }

        ObjectNode empty204 = responseDocument();
        ((ObjectNode) operation(empty204).path("responses")).set(
                "204", JSON.createObjectNode().put("description", "empty"));

        List<ObjectNode> invalid = new ArrayList<>(List.of(
                missingContent, missingSchema, wildcard, xml, multipleJson, empty204));
        invalid.addAll(malformedMediaTypes);
        for (ObjectNode document : invalid) {
            assertThrows(IllegalStateException.class, () -> readResponse(document));
        }
    }

    @Test
    void resolvesLocalResponseObjectAndSchemaReferences() {
        ObjectNode document = responseDocument();
        ObjectNode response200 = (ObjectNode) operation(document).path("responses").path("200");
        response200.removeAll().put("$ref", "#/components/responses/ItemResponse");
        child(child(document, "components"), "responses").putObject("ItemResponse")
                .put("$ref", "#/components/responses/ItemResponseBody");
        child(child(document, "components"), "responses").putObject("ItemResponseBody")
                .putObject("content").putObject("application/json").putObject("schema")
                .put("$ref", "#/components/schemas/Item");
        child(child(document, "components"), "schemas").putObject("Item")
                .put("type", "object").putObject("properties").putObject("title")
                .put("type", "string").put("description", "Item");

        CanonicalResponseSchema result = readResponse(document);
        assertEquals("Item", result.schema().path("properties").path("title").path("description").asText());
    }

    @Test
    void rejectsExternalMissingCyclicAndSiblingReferences() {
        ObjectNode external = responseDocument();
        response200(external).removeAll().put("$ref", "https://example.test/response");

        ObjectNode missing = responseDocument();
        response200(missing).removeAll().put("$ref", "#/components/responses/Missing");

        ObjectNode cyclic = responseDocument();
        response200(cyclic).removeAll().put("$ref", "#/components/responses/A");
        child(child(cyclic, "components"), "responses").putObject("A").put("$ref", "#/components/responses/B");
        child(child(cyclic, "components"), "responses").putObject("B").put("$ref", "#/components/responses/A");

        ObjectNode sibling = responseDocument();
        response200(sibling).removeAll().put("$ref", "#/components/responses/Item").put("description", "sibling");
        child(child(sibling, "components"), "responses").putObject("Item").putObject("content")
                .putObject("application/json").set("schema", schemaWith("title", "Item"));

        for (ObjectNode document : List.of(external, missing, cyclic, sibling)) {
            assertThrows(IllegalStateException.class, () -> readResponse(document));
        }
    }

    private static CanonicalResponseSchema readResponse(JsonNode document) {
        OpenApiDocumentService service = source(document);
        return OpenApiResponseSchemaReader.read(service, OPERATION);
    }

    private static OpenApiDocumentService source(JsonNode document) {
        return new OpenApiDocumentService() {
            @Override public String resolveGroupFromPath(String path) { return "inventory"; }
            @Override public JsonNode getDocumentForGroup(String groupName) { return document; }
            @Override public JsonNode getDocumentForGroupStrict(String groupName) { return document; }
            @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> supplier) { return "hash"; }
            @Override public void clearCaches() { }
        };
    }

    private static ObjectNode responseDocument() {
        ObjectNode root = JSON.createObjectNode().put("openapi", "3.0.3");
        ObjectNode operation = operation(root);
        operation.putObject("responses");
        response(operation.path("responses"), "200", "application/json", schemaWith("title", "Item"));
        return root;
    }

    private static ObjectNode operation(ObjectNode document) {
        return child(child(child(document, "paths"), OPERATION.path()), "get")
                .put("operationId", OPERATION.operationId());
    }

    private static ObjectNode response200(ObjectNode document) {
        return (ObjectNode) operation(document).path("responses").path("200");
    }

    private static void response(JsonNode responses, String status, String mediaType, JsonNode schema) {
        ((ObjectNode) responses).putObject(status).put("description", "success")
                .putObject("content").putObject(mediaType).set("schema", schema);
    }

    private static ObjectNode schemaWith(String property, String description) {
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject(property).put("type", "string")
                .put("description", description);
        schema.putArray("examples").add("example");
        schema.putObject("x-ui").put("kind", "bulk");
        return schema;
    }

    private static ObjectNode child(ObjectNode parent, String name) {
        JsonNode existing = parent.path(name);
        return existing.isObject() ? (ObjectNode) existing : parent.putObject(name);
    }
}
