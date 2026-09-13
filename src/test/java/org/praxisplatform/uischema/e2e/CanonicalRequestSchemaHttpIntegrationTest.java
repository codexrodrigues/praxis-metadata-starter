package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.SpecVersion;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.bulk.BulkEditableFields;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Import(CanonicalRequestSchemaHttpIntegrationTest.UpdateController.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CanonicalRequestSchemaHttpIntegrationTest extends AbstractE2eH2Test {
    @Autowired CanonicalOperationResolver operations;
    @Autowired OpenApiDocumentService documents;
    @Autowired OpenApiDocsSupport docsSupport;

    @Test
    void compilesFieldsFromTheRealUpdateHandlerAndItsServedSpringDocSchema() throws Exception {
        // Default document retrieval uses self HTTP: run after server startup, with the test server's actual port.
        ReflectionTestUtils.setField(docsSupport, "openApiInternalBaseUrl", url(""));
        var operation = operations.requireResourceOperation("schema-binding.items", "schema-binding.items.update", "PUT");
        var request = documents.requireRequestSchema(operation);
        var handler = UpdateController.class.getDeclaredMethod("update", String.class, UpdateBody.class);
        // This fixture proves a known handler/type association; the production registry must establish it explicitly.
        var type = objectMapper.getTypeFactory().constructType(handler.getGenericParameterTypes()[1]);
        var fields = BulkEditableFields.compile(objectMapper, type, request.schema(), request.specVersion(), Set.of("id", "version"));

        assertEquals(SpecVersion.V30, request.specVersion());
        assertEquals("application/json", request.mediaType());
        assertEquals(Set.of("display-name"), fields.writableFields(BulkMode.UNIFORM_UPDATE));
        assertEquals(Set.of("display-name"), fields.clearableFields(BulkMode.UNIFORM_UPDATE));
        JsonNode document = body(get("/v3/api-docs/" + operation.group()));
        assertEquals(operation.operationId(), document.at("/paths/~1schema-binding-items~1{id}/put/operationId").asText());
        assertFalse(request.schema().has("$ref"));

        var response = putJson("/schema-binding-items/1", "{\"display-name\":\"Updated\",\"version\":1}");
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals("Updated", body(response).path("display-name").asText());
    }

    @Test
    void keepsFilteredUiProjectionAvailableAndProtectsTheCachedDocument() throws Exception {
        ReflectionTestUtils.setField(docsSupport, "openApiInternalBaseUrl", url(""));
        var operation = operations.requireResourceOperation("schema-binding.items", "schema-binding.items.update", "PUT");
        JsonNode before = documents.getDocumentForGroup(operation.group()).deepCopy();
        var request = documents.requireRequestSchema(operation);
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.schema()).removeAll();
        assertEquals(before, documents.getDocumentForGroup(operation.group()));
        assertTrue(documents.requireRequestSchema(operation).schema().path("properties").has("display-name"));

        var filtered = get("/schemas/filtered?path=%2Fschema-binding-items%2F%7Bid%7D&operation=put&schemaType=request");
        assertTrue(filtered.getStatusCode().is2xxSuccessful());
        assertTrue(body(filtered).path("properties").has("display-name"));
        assertNotNull(filtered.getHeaders().getETag());
        assertNotNull(filtered.getHeaders().getFirst("X-Schema-Hash"));
    }

    @ApiResource(value = "/schema-binding-items", resourceKey = "schema-binding.items")
    @ApiGroup("schema-binding")
    public static class UpdateController {
        @PutMapping(value = "/{id}", consumes = "application/json", produces = "application/json")
        @Operation(operationId = "schema-binding.items.update")
        public UpdateBody update(@PathVariable String id, @RequestBody UpdateBody body) { return body; }
    }

    public record UpdateBody(
            @JsonProperty("display-name") @BulkEditable(allowClear = true) @Schema(nullable = true) String name,
            @Schema(description = "Protected version supplied by the explicit binding") Integer version) { }
}
