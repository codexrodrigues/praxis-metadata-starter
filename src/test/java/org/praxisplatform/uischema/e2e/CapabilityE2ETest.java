package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityE2ETest extends AbstractE2eH2Test {

    @Test
    void collectionCapabilitiesAggregateCanonicalOperationsSurfacesAndCollectionActions() throws Exception {
        ResponseEntity<String> response = get("/employees/capabilities");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("e2e", response.getHeaders().getFirst("X-Data-Version"));

        JsonNode snapshot = body(response);
        assertEquals("human-resources.employees", snapshot.path("resourceKey").asText());
        assertEquals("/employees", snapshot.path("resourcePath").asText());
        assertEquals("employees", snapshot.path("group").asText());
        assertTrue(snapshot.path("resourceId").isNull());
        assertTrue(snapshot.path("canonicalOperations").path("create").asBoolean());
        assertTrue(snapshot.path("canonicalOperations").path("update").asBoolean());
        assertTrue(snapshot.path("canonicalOperations").path("delete").asBoolean());
        assertTrue(snapshot.path("canonicalOperations").path("filter").asBoolean());

        JsonNode create = findById(snapshot.path("surfaces"), "create");
        assertNotNull(create);
        assertEquals("COLLECTION", create.path("scope").asText());
        assertEquals(null, findById(snapshot.path("surfaces"), "detail"));
        assertEquals(null, findById(snapshot.path("surfaces"), "profile"));

        JsonNode bulkApprove = findById(snapshot.path("actions"), "bulk-approve");
        assertNotNull(bulkApprove);
        assertEquals("COLLECTION", bulkApprove.path("scope").asText());
        assertEquals("missing-authority", bulkApprove.path("availability").path("reason").asText());
        assertEquals(null, findById(snapshot.path("actions"), "approve"));
    }

    @Test
    void itemCapabilitiesAggregateItemScopedSurfacesAndActionsWithContextualAvailability() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "qa-user");
        headers.add("X-Test-Authorities", "employee:approve,employee:profile:update");

        ResponseEntity<String> response = exchange("/employees/" + carolId + "/capabilities", HttpMethod.GET, headers);
        assertEquals(200, response.getStatusCode().value());

        JsonNode snapshot = body(response);
        assertEquals("human-resources.employees", snapshot.path("resourceKey").asText());
        assertEquals("/employees", snapshot.path("resourcePath").asText());
        assertEquals("employees", snapshot.path("group").asText());
        assertEquals(carolId.longValue(), snapshot.path("resourceId").asLong());
        assertTrue(snapshot.path("canonicalOperations").path("update").asBoolean());

        JsonNode detail = findById(snapshot.path("surfaces"), "detail");
        assertNotNull(detail);
        assertTrue(detail.path("availability").path("allowed").asBoolean());

        JsonNode profile = findById(snapshot.path("surfaces"), "profile");
        assertNotNull(profile);
        assertEquals("ITEM", profile.path("scope").asText());
        assertEquals("resource-state-blocked", profile.path("availability").path("reason").asText());
        assertEquals("INACTIVE", profile.path("availability").path("metadata").path("resourceState").asText());

        JsonNode approve = findById(snapshot.path("actions"), "approve");
        assertNotNull(approve);
        assertEquals("ITEM", approve.path("scope").asText());
        assertTrue(approve.path("availability").path("allowed").asBoolean());
        assertEquals("INACTIVE", approve.path("availability").path("metadata").path("resourceState").asText());
        assertEquals(null, findById(snapshot.path("actions"), "bulk-approve"));
    }

    @Test
    void readOnlyCollectionCapabilitiesStayCanonicalAndDoNotInventWorkflowActions() throws Exception {
        ResponseEntity<String> response = get("/payroll-view/capabilities");
        assertEquals(200, response.getStatusCode().value());

        JsonNode snapshot = body(response);
        assertEquals("human-resources.payroll-view", snapshot.path("resourceKey").asText());
        assertEquals("/payroll-view", snapshot.path("resourcePath").asText());
        assertFalse(snapshot.path("canonicalOperations").path("create").asBoolean());
        assertFalse(snapshot.path("canonicalOperations").path("update").asBoolean());
        assertFalse(snapshot.path("canonicalOperations").path("delete").asBoolean());
        assertTrue(snapshot.path("canonicalOperations").path("byId").asBoolean());
        assertTrue(snapshot.path("canonicalOperations").path("all").asBoolean());
        assertTrue(snapshot.path("canonicalOperations").path("filter").asBoolean());
        assertNotNull(findById(snapshot.path("surfaces"), "list"));
        assertEquals(0, snapshot.path("actions").size());
    }

    @Test
    void itemCapabilitiesReturnNotFoundForUnknownResourceId() {
        ResponseEntity<String> response = get("/employees/999999/capabilities");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void repeatedCapabilityRequestsRemainStableForTheSameContext() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "qa-user");
        headers.add("X-Test-Authorities", "employee:approve,employee:profile:update");

        JsonNode firstCollection = body(get("/employees/capabilities"));
        JsonNode secondCollection = body(get("/employees/capabilities"));
        assertEquals(firstCollection, secondCollection);

        JsonNode firstItem = body(exchange("/employees/" + carolId + "/capabilities", HttpMethod.GET, headers));
        JsonNode secondItem = body(exchange("/employees/" + carolId + "/capabilities", HttpMethod.GET, headers));
        assertEquals(firstItem, secondItem);
    }

    private JsonNode findById(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private void assertFalse(boolean value) {
        org.junit.jupiter.api.Assertions.assertFalse(value);
    }
}
