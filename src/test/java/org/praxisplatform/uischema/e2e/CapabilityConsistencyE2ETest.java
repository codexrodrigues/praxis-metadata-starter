package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityConsistencyE2ETest extends AbstractE2eH2Test {

    @Test
    void collectionCapabilitiesStayConsistentWithDedicatedCollectionDiscoveryEndpoints() throws Exception {
        JsonNode capabilitySnapshot = body(get("/employees/capabilities"));
        JsonNode surfacesCatalog = body(get("/schemas/surfaces?resource=human-resources.employees"));
        JsonNode actionsCatalog = body(get("/employees/actions"));

        Map<String, JsonNode> capabilitySurfaces = indexById(capabilitySnapshot.path("surfaces"));
        Map<String, JsonNode> expectedCollectionSurfaces = indexById(filterByScope(surfacesCatalog.path("surfaces"), "COLLECTION"));
        assertEquals(expectedCollectionSurfaces.keySet(), capabilitySurfaces.keySet());
        expectedCollectionSurfaces.forEach((id, expected) -> assertSurfaceMatches(expected, capabilitySurfaces.get(id)));

        Map<String, JsonNode> capabilityActions = indexById(capabilitySnapshot.path("actions"));
        Map<String, JsonNode> expectedCollectionActions = indexById(actionsCatalog.path("actions"));
        assertEquals(expectedCollectionActions.keySet(), capabilityActions.keySet());
        expectedCollectionActions.forEach((id, expected) -> assertActionMatches(expected, capabilityActions.get(id)));

        assertEquals("COLLECTION", capabilitySnapshot.path("operations").path("create").path("scope").asText());
        assertEquals("POST", capabilitySnapshot.path("operations").path("create").path("preferredMethod").asText());
        assertTrue(capabilitySnapshot.path("operations").path("view").path("supported").asBoolean());
        assertTrue(capabilitySnapshot.path("operations").path("edit").path("supported").asBoolean());
        assertTrue(capabilitySnapshot.path("operations").path("delete").path("supported").asBoolean());
    }

    @Test
    void itemCapabilitiesStayConsistentWithDedicatedItemDiscoveryEndpoints() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "qa-user");
        headers.add("X-Test-Authorities", "employee:approve,employee:profile:update");

        JsonNode capabilitySnapshot = body(exchange("/employees/" + carolId + "/capabilities", HttpMethod.GET, headers));
        JsonNode surfacesCatalog = body(exchange("/employees/" + carolId + "/surfaces", HttpMethod.GET, headers));
        JsonNode actionsCatalog = body(exchange("/employees/" + carolId + "/actions", HttpMethod.GET, headers));

        Map<String, JsonNode> capabilitySurfaces = indexById(capabilitySnapshot.path("surfaces"));
        Map<String, JsonNode> expectedItemSurfaces = indexById(surfacesCatalog.path("surfaces"));
        assertEquals(expectedItemSurfaces.keySet(), capabilitySurfaces.keySet());
        expectedItemSurfaces.forEach((id, expected) -> assertSurfaceMatches(expected, capabilitySurfaces.get(id)));

        Map<String, JsonNode> capabilityActions = indexById(capabilitySnapshot.path("actions"));
        Map<String, JsonNode> expectedItemActions = indexById(actionsCatalog.path("actions"));
        assertEquals(expectedItemActions.keySet(), capabilityActions.keySet());
        expectedItemActions.forEach((id, expected) -> assertActionMatches(expected, capabilityActions.get(id)));

        assertEquals("ITEM", capabilitySnapshot.path("operations").path("view").path("scope").asText());
        assertEquals("GET", capabilitySnapshot.path("operations").path("view").path("preferredMethod").asText());
        assertEquals("ITEM", capabilitySnapshot.path("operations").path("edit").path("scope").asText());
        assertTrue(capabilitySnapshot.path("operations").path("edit").path("supported").asBoolean());
    }

    private JsonNode filterByScope(JsonNode items, String scope) {
        com.fasterxml.jackson.databind.node.ArrayNode filtered = objectMapper.createArrayNode();
        for (JsonNode item : items) {
            if (scope.equals(item.path("scope").asText())) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private Map<String, JsonNode> indexById(JsonNode items) {
        Map<String, JsonNode> indexed = new LinkedHashMap<>();
        for (JsonNode item : items) {
            indexed.put(item.path("id").asText(), item);
        }
        return indexed;
    }

    private void assertSurfaceMatches(JsonNode expected, JsonNode actual) {
        assertEquals(expected.path("resourceKey").asText(), actual.path("resourceKey").asText());
        assertEquals(expected.path("kind").asText(), actual.path("kind").asText());
        assertEquals(expected.path("scope").asText(), actual.path("scope").asText());
        assertEquals(expected.path("operationId").asText(), actual.path("operationId").asText());
        assertEquals(expected.path("path").asText(), actual.path("path").asText());
        assertEquals(expected.path("method").asText(), actual.path("method").asText());
        assertEquals(expected.path("schemaId").asText(), actual.path("schemaId").asText());
        assertEquals(expected.path("schemaUrl").asText(), actual.path("schemaUrl").asText());
        assertEquals(expected.path("availability").path("allowed").asBoolean(), actual.path("availability").path("allowed").asBoolean());
        assertEquals(expected.path("availability").path("reason").asText(null), actual.path("availability").path("reason").asText(null));
        assertEquals(expected.path("availability").path("metadata"), actual.path("availability").path("metadata"));
    }

    private void assertActionMatches(JsonNode expected, JsonNode actual) {
        assertEquals(expected.path("resourceKey").asText(), actual.path("resourceKey").asText());
        assertEquals(expected.path("scope").asText(), actual.path("scope").asText());
        assertEquals(expected.path("operationId").asText(), actual.path("operationId").asText());
        assertEquals(expected.path("path").asText(), actual.path("path").asText());
        assertEquals(expected.path("method").asText(), actual.path("method").asText());
        assertEquals(expected.path("requestSchemaId").asText(), actual.path("requestSchemaId").asText());
        assertEquals(expected.path("requestSchemaUrl").asText(), actual.path("requestSchemaUrl").asText());
        assertEquals(expected.path("responseSchemaId").asText(), actual.path("responseSchemaId").asText());
        assertEquals(expected.path("responseSchemaUrl").asText(), actual.path("responseSchemaUrl").asText());
        assertEquals(expected.path("availability").path("allowed").asBoolean(), actual.path("availability").path("allowed").asBoolean());
        assertEquals(expected.path("availability").path("reason").asText(null), actual.path("availability").path("reason").asText(null));
        assertEquals(expected.path("availability").path("metadata"), actual.path("availability").path("metadata"));
    }
}
