package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.CanonicalOpenApiGroupSnapshot;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalRequestSchema;
import org.praxisplatform.uischema.openapi.CanonicalResponseSchema;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalOpenApiSnapshotPublicConsumerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anotherMetadataPackageCanReadBothContractsFromOneStrictSnapshot() {
        String path = "/items/bulk/evaluation";
        CanonicalOperationRef operation = new CanonicalOperationRef(
                "inventory", "items-bulk-evaluation", path, "POST");
        ObjectNode document = JSON.createObjectNode().put("openapi", "3.0.3");
        ObjectNode operationNode = document.putObject("paths").putObject(path).putObject("post")
                .put("operationId", operation.operationId());
        operationNode.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("type", "string");
        operationNode.putObject("responses").putObject("202").put("description", "accepted")
                .putObject("content").putObject("application/json").putObject("schema").put("type", "string");

        OpenApiDocumentService documents = new OpenApiDocumentService() {
            @Override public String resolveGroupFromPath(String candidate) { return "inventory"; }
            @Override public JsonNode getDocumentForGroup(String groupName) { return document; }
            @Override public JsonNode getDocumentForGroupStrict(String groupName) {
                assertThat(groupName).isEqualTo("inventory");
                return document;
            }
            @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
                return "unused";
            }
            @Override public void clearCaches() { }
        };

        CanonicalOpenApiGroupSnapshot snapshot = CanonicalOpenApiGroupSnapshot.capture(documents, "inventory");
        CanonicalRequestSchema request = snapshot.requireRequestSchema(operation);
        CanonicalResponseSchema response = snapshot.requireResponseSchema(operation);

        assertThat(request.operation()).isEqualTo(operation);
        assertThat(request.mediaType()).isEqualTo("application/json");
        assertThat(request.schema().path("type").asText()).isEqualTo("string");
        assertThat(response.operation()).isEqualTo(operation);
        assertThat(response.variants()).containsExactly(new CanonicalResponseSchema.Variant(202, "application/json"));
        assertThat(response.schema().path("type").asText()).isEqualTo("string");
    }
}
