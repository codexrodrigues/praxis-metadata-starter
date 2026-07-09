package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenApiDocumentServicePathResolutionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenApiDocumentService service = new TestOpenApiDocumentService();

    @Test
    void resolveDocumentPathPrefersExactMatchBeforeStructuralEquivalence() throws Exception {
        JsonNode paths = objectMapper.readTree("""
                {
                  "/api/foo/{id}/children/{childId}": { "put": {} },
                  "/api/foo/{resourceId}/children/{id}": { "put": {} }
                }
                """);

        String resolved = service.resolveDocumentPath(
                paths,
                "/api/foo/{resourceId}/children/{id}",
                "put"
        );

        assertEquals("/api/foo/{resourceId}/children/{id}", resolved);
    }

    @Test
    void resolveDocumentPathUsesUniqueStructuralTemplateMatchForOperation() throws Exception {
        JsonNode paths = objectMapper.readTree("""
                {
                  "/api/foo/{id}/children/{childId}": { "put": {} },
                  "/api/foo/{resourceId}/children/{id}": { "get": {} }
                }
                """);

        String resolved = service.resolveDocumentPath(
                paths,
                "/api/foo/{resourceId}/children/{id}",
                "put"
        );

        assertEquals("/api/foo/{id}/children/{childId}", resolved);
    }

    @Test
    void resolveDocumentPathRejectsAmbiguousStructuralTemplateMatches() throws Exception {
        JsonNode paths = objectMapper.readTree("""
                {
                  "/api/foo/{id}/children/{childId}": { "put": {} },
                  "/api/foo/{resourceId}/children/{id}": { "put": {} }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveDocumentPath(paths, "/api/foo/{parentId}/children/{documentId}", "put")
        );

        assertEquals(
                "Ambiguous OpenAPI template path resolution for '/api/foo/{parentId}/children/{documentId}'.",
                exception.getMessage()
        );
    }

    private static final class TestOpenApiDocumentService implements OpenApiDocumentService {
        @Override
        public String resolveGroupFromPath(String path) {
            return "test";
        }

        @Override
        public JsonNode getDocumentForGroup(String groupName) {
            return null;
        }

        @Override
        public String getOrComputeSchemaHash(String schemaId, java.util.function.Supplier<JsonNode> payloadSupplier) {
            return "hash";
        }

        @Override
        public void clearCaches() {
        }
    }
}
