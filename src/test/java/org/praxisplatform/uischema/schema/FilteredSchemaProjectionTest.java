package org.praxisplatform.uischema.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class FilteredSchemaProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FilteredSchemaProjection projection = new FilteredSchemaProjection(mapper);
    private final FilteredSchemaReferenceResolver references = new FilteredSchemaReferenceResolver();

    @Test
    void derivesVariantPerRoleRatherThanCopyingCrudIdentityOrActionOverrides() throws Exception {
        JsonNode document = document();
        var response = resolve(document, "/items/{id}/preview", "get", "response", null, null);
        assertEquals(references.resolve("/items/{id}/preview", "get", "response", false, null, null, "proposalId", false), response.reference());
        var request = resolve(document, "/items/{id}/preview", "get", "request", null, null);
        assertTrue(request.reference().schemaId().contains("idField:itemCode|readOnly:false"));
        var readonly = resolve(document, "/archive/{id}", "get", "response", null, null);
        assertTrue(readonly.reference().schemaId().contains("idField:archiveId|readOnly:true"));
        var explicit = resolve(document, "/archive/{id}", "get", "response", "external+id", false);
        assertEquals(references.resolve("/archive/{id}", "get", "response", false, null, null, "external+id", false), explicit.reference());
        assertTrue(explicit.reference().url().contains("idField=external%2Bid&readOnly=false"));
        assertFalse(readonly.reference().equals(explicit.reference()));
        assertEquals("Preview", response.evidence().path("schemaName").asText());
    }

    @Test
    void preservesRequestFilterAndWrappedResponseSelectionFromTheEndpoint() throws Exception {
        JsonNode document = document();
        assertEquals("Preview", projection.select(document, "/items/{id}/preview", "get", "response").schemaName());
        assertEquals(document.at("/components/schemas/FilterDTO"),
                projection.select(document, "/items/filter", "post", "request").schema());
        assertEquals("Item", projection.select(document, "/items/{id}", "get", "response").schemaName());
        assertEquals("Item", projection.select(document, "/created", "post", "response").schemaName());
    }

    @Test
    void absentComponentOrUnsupportedResponseSelectionFailsClosed() throws Exception {
        ObjectNode document = (ObjectNode) document();
        assertThrows(ResponseStatusException.class, () -> projection.select(document, "/missing", "get", "response"));
        ((ObjectNode) document.at("/paths/~1items~1{id}~1preview/get/x-ui")).put("responseSchema", "Absent");
        assertThrows(ResponseStatusException.class, () -> projection.select(document, "/items/{id}/preview", "get", "response"));
        assertThrows(ResponseStatusException.class, () -> projection.select(document, "/accepted", "post", "response"));
        assertThrows(ResponseStatusException.class, () -> projection.select(document, "/inline", "post", "response"));
    }

    @Test
    void capturedEvidenceIsDefensiveAndOnlyExplicitDimensionsChangeIdentity() throws Exception {
        JsonNode document = document();
        var first = resolve(document, "/items/{id}/preview", "get", "response", null, null);
        ((ObjectNode) first.evidence().path("schema")).remove("properties");
        assertTrue(first.evidence().path("schema").has("properties"));
        ((ObjectNode) document.at("/components/schemas/Preview")).put("description", "Changed UI shape");
        var changed = resolve(document, "/items/{id}/preview", "get", "response", null, null);
        assertEquals(first.reference(), changed.reference());
        assertNotEquals(first.evidence(), changed.evidence(), "UI selection evidence, not just its URL, must be fingerprinted");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"string\"|itemCode", "[\"null\",\"string\"]|itemCode",
            "[\"integer\",\"string\"]|id", "[\"null\"]|id",
            "[]|id", "[\"string\",3]|id"
    })
    void canonicalSnapshotIdentifierUsesOnlyOneNonNullScalarType(String typeJson, String expectedId) throws Exception {
        JsonNode document = document();
        JsonNode type = mapper.readTree(typeJson);
        ((ObjectNode) document.at("/components/schemas/Item/properties/itemCode")).set("type", type);

        var resolved = resolve(document, "/items/{id}", "get", "response", null, null);

        assertEquals(references.resolve("/items/{id}", "get", "response", false,
                null, null, expectedId, false), resolved.reference());
        assertEquals(type, resolved.evidence().at("/schema/properties/itemCode/type"));
        assertEquals(type, document.at("/components/schemas/Item/properties/itemCode/type"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"\"array\"", "[\"array\"]", "[\"null\",\"array\"]"})
    void wrappedCollectionSelectionPreservesTheOriginalDialectAndDomainComponent(String typeJson) throws Exception {
        JsonNode document = document();
        JsonNode type = mapper.readTree(typeJson);
        ObjectNode data = (ObjectNode) document.at("/components/schemas/RestApiResponseItem/properties/data");
        data.remove("$ref");
        data.set("type", type);
        data.putObject("items").put("$ref", "#/components/schemas/Item");

        var selected = projection.select(document, "/items/{id}", "get", "response");
        var resolved = resolve(document, "/items/{id}", "get", "response", null, null);

        assertEquals("Item", selected.schemaName());
        assertEquals(document.at("/components/schemas/Item"), selected.schema());
        assertEquals(selected.schema(), resolved.evidence().path("schema"));
        assertEquals(type, data.path("type"));
        assertEquals("#/components/schemas/Item", data.at("/items/$ref").asText());
    }

    private FilteredSchemaProjection.Resolved resolve(JsonNode document, String path, String method, String type,
            String idField, Boolean readOnly) {
        return projection.resolve(document, new CanonicalOperationRef("test", "operation", path, method.toUpperCase()),
                type, references, new OpenApiCanonicalCapabilityResolver(null), idField, readOnly);
    }

    private JsonNode document() throws Exception {
        return mapper.readTree("""
                {"paths": {
                  "/items/{id}": {"get":{"responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/RestApiResponseItem"}}}}}},"patch":{}},
                  "/items/{id}/preview": {"get":{"x-ui":{"responseSchema":"Preview"},"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"reason":{"type":"string"}}}}}}}},
                  "/items/filter": {"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"filterDTO":{"$ref":"#/components/schemas/FilterDTO"}}}}}}}},
                  "/archive/{id}": {"get":{"responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Archive"}}}}}}},
                  "/created": {"post":{"responses":{"201":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Item"}}}}}}},
                  "/accepted": {"post":{"responses":{"202":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Item"}}}}}}},
                  "/inline": {"post":{"responses":{"200":{"content":{"application/json":{"schema":{"type":"object"}}}}}}}
                },"components":{"schemas":{
                  "Item":{"type":"object","properties":{"itemCode":{"type":"string"}},"required":["itemCode"]},
                  "RestApiResponseItem":{"type":"object","properties":{"data":{"$ref":"#/components/schemas/Item"}}},
                  "Preview":{"type":"object","properties":{"proposalId":{"type":"string"}}},
                  "Archive":{"type":"object","properties":{"archiveId":{"type":"string"}}},
                  "FilterDTO":{"type":"object","properties":{"name":{"type":"string"}}}
                }}}
                """);
    }
}
