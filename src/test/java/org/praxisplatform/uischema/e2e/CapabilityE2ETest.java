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
        assertFalse(snapshot.path("canonicalOperations").path("filterExpression").asBoolean());
        assertTrue(snapshot.path("operations").path("create").path("supported").asBoolean());
        assertEquals("COLLECTION", snapshot.path("operations").path("create").path("scope").asText());
        assertEquals("POST", snapshot.path("operations").path("create").path("preferredMethod").asText());
        assertTrue(snapshot.path("operations").path("view").path("supported").asBoolean());
        assertEquals("ITEM", snapshot.path("operations").path("view").path("scope").asText());
        assertEquals("GET", snapshot.path("operations").path("view").path("preferredMethod").asText());
        assertTrue(snapshot.path("operations").path("edit").path("supported").asBoolean());
        assertEquals("ITEM", snapshot.path("operations").path("edit").path("scope").asText());
        assertEquals("PUT", snapshot.path("operations").path("edit").path("preferredMethod").asText());
        assertTrue(snapshot.path("operations").path("delete").path("supported").asBoolean());
        assertEquals("DELETE", snapshot.path("operations").path("delete").path("preferredMethod").asText());
        assertTrue(snapshot.path("operations").path("byId").path("supported").asBoolean());
        assertEquals("ITEM", snapshot.path("operations").path("byId").path("scope").asText());
        assertTrue(snapshot.path("operations").path("update").path("supported").asBoolean());
        assertEquals("ITEM", snapshot.path("operations").path("update").path("scope").asText());
        assertTrue(snapshot.path("operations").path("all").path("supported").asBoolean());
        assertEquals("GET", snapshot.path("operations").path("all").path("preferredMethod").asText());
        assertTrue(snapshot.path("operations").path("filter").path("supported").asBoolean());
        assertEquals("POST", snapshot.path("operations").path("filter").path("preferredMethod").asText());
        assertTrue(snapshot.path("operations").path("cursor").path("supported").asBoolean());
        assertEquals("filter-cursor", snapshot.path("operations").path("cursor").path("preferredRel").asText());
        assertTrue(snapshot.path("operations").path("options").path("supported").asBoolean());
        assertTrue(snapshot.path("operations").path("optionSources").path("supported").asBoolean());
        assertTrue(snapshot.path("operations").path("statsGroupBy").path("supported").asBoolean());
        assertTrue(snapshot.path("operations").path("statsTimeSeries").path("supported").asBoolean());
        assertTrue(snapshot.path("operations").path("statsDistribution").path("supported").asBoolean());
        assertTrue(snapshot.path("operations").path("statsComparison").path("supported").asBoolean());
        assertFalse(snapshot.path("operations").path("export").path("supported").asBoolean());

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
        assertTrue(snapshot.path("operations").path("view").path("supported").asBoolean());
        assertEquals("GET", snapshot.path("operations").path("view").path("preferredMethod").asText());
        assertTrue(snapshot.path("operations").path("edit").path("supported").asBoolean());
        assertEquals("PUT", snapshot.path("operations").path("edit").path("preferredMethod").asText());
        assertTrue(snapshot.path("operations").path("edit").path("availability").path("allowed").asBoolean());
        assertTrue(snapshot.path("operations").path("delete").path("supported").asBoolean());

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
        assertFalse(snapshot.path("canonicalOperations").path("filterExpression").asBoolean());
        assertFalse(snapshot.path("operations").path("create").path("supported").asBoolean());
        assertTrue(snapshot.path("operations").path("view").path("supported").asBoolean());
        assertFalse(snapshot.path("operations").path("edit").path("supported").asBoolean());
        assertFalse(snapshot.path("operations").path("delete").path("supported").asBoolean());
        assertNotNull(findById(snapshot.path("surfaces"), "list"));
        assertEquals(0, snapshot.path("actions").size());
    }

    @Test
    void aggregateOnlyPrincipalCanCompareStatsWithoutNominalQueryAvailability() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "analytics-user");
        headers.add("X-Test-Authorities", "employee:analytics:aggregate");

        JsonNode snapshot = body(exchange("/employees/capabilities", HttpMethod.GET, headers));

        JsonNode comparison = snapshot.path("operations").path("statsComparison");
        assertTrue(comparison.path("supported").asBoolean());
        assertTrue(comparison.path("availability").path("allowed").asBoolean());
        assertEquals("aggregate", comparison.path("availability").path("metadata").path("accessClass").asText());

        assertMissingNominalAuthority(snapshot.path("operations").path("filter"), "filter");
        assertMissingNominalAuthority(snapshot.path("operations").path("cursor"), "cursor");
        assertFalse(snapshot.toString().contains("analytics-user"));
    }

    @Test
    void nominalPrincipalCanUseFilterAndCursorWithoutChangingStructuralSupport() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "nominal-user");
        headers.add("X-Test-Authorities", "employee:analytics:nominal");

        JsonNode snapshot = body(exchange("/employees/capabilities", HttpMethod.GET, headers));

        JsonNode filter = snapshot.path("operations").path("filter");
        JsonNode cursor = snapshot.path("operations").path("cursor");
        assertTrue(filter.path("supported").asBoolean());
        assertTrue(filter.path("availability").path("allowed").asBoolean());
        assertTrue(cursor.path("supported").asBoolean());
        assertTrue(cursor.path("availability").path("allowed").asBoolean());
        assertEquals("nominal", filter.path("availability").path("metadata").path("accessClass").asText());
        assertFalse(snapshot.toString().contains("nominal-user"));
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

    private void assertMissingNominalAuthority(JsonNode operation, String operationId) {
        assertTrue(operation.path("supported").asBoolean());
        assertFalse(operation.path("availability").path("allowed").asBoolean());
        assertEquals("missing-authority", operation.path("availability").path("reason").asText());
        assertEquals(
                operationId,
                operation.path("availability").path("metadata").path("blockedOperation").asText()
        );
        assertEquals(
                "employee:analytics:nominal",
                operation.path("availability").path("metadata").path("requiredAuthorities").get(0).asText()
        );
    }

    private void assertFalse(boolean value) {
        org.junit.jupiter.api.Assertions.assertFalse(value);
    }
}
