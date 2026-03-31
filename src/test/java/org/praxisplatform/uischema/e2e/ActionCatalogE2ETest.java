package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionCatalogE2ETest extends AbstractE2eH2Test {

    @Test
    void actionsCatalogExposesExplicitWorkflowActionsByResourceKey() throws Exception {
        ResponseEntity<String> response = get("/schemas/actions?resource=human-resources.employees");
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        assertEquals("human-resources.employees", catalog.path("resourceKey").asText());
        assertEquals("/employees", catalog.path("resourcePath").asText());
        assertEquals("human-resources", catalog.path("group").asText());
        assertEquals(2, catalog.path("actions").size());

        JsonNode approve = findAction(catalog.path("actions"), "approve");
        assertNotNull(approve);
        assertEquals("ITEM", approve.path("scope").asText());
        assertEquals("POST", approve.path("method").asText());
        assertEquals("/employees/{id}/actions/approve", approve.path("path").asText());
        assertEquals("approve", approve.path("id").asText());
        assertEquals("workflow", approve.path("tags").get(0).asText());
        assertEquals("approval", approve.path("tags").get(1).asText());
        assertTrue(approve.path("requestSchemaUrl").asText().contains("schemaType=request"));
        assertTrue(approve.path("responseSchemaUrl").asText().contains("schemaType=response"));
        assertFalse(approve.path("availability").path("allowed").asBoolean());
        assertEquals("resource-context-required", approve.path("availability").path("reason").asText());
        assertFalse(approve.has("fields"));
        assertFalse(approve.has("schema"));

        JsonNode bulkApprove = findAction(catalog.path("actions"), "bulk-approve");
        assertNotNull(bulkApprove);
        assertEquals("COLLECTION", bulkApprove.path("scope").asText());
        assertEquals("/employees/actions/bulk-approve", bulkApprove.path("path").asText());
        assertFalse(bulkApprove.path("availability").path("allowed").asBoolean());
        assertEquals("missing-authority", bulkApprove.path("availability").path("reason").asText());
        assertEquals("employee:bulk-approve", bulkApprove.path("availability").path("metadata").path("requiredAuthorities").get(0).asText());
    }

    @Test
    void actionsCatalogAggregatesOnlyAnnotatedWorkflowActionsByGroup() throws Exception {
        ResponseEntity<String> response = get("/schemas/actions?group=human-resources");
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        assertEquals("human-resources", catalog.path("group").asText());
        assertTrue(catalog.path("resourceKey").isNull());

        JsonNode approve = findAction(catalog.path("actions"), "human-resources.employees", "approve");
        assertNotNull(approve);
        assertEquals("/employees/{id}/actions/approve", approve.path("path").asText());
        JsonNode bulkApprove = findAction(catalog.path("actions"), "human-resources.employees", "bulk-approve");
        assertNotNull(bulkApprove);
        assertEquals("/employees/actions/bulk-approve", bulkApprove.path("path").asText());
        assertNullAction(catalog.path("actions"), "human-resources.departments", "approve");
        assertNullAction(catalog.path("actions"), "human-resources.payroll-view", "approve");
    }

    @Test
    void collectionActionCatalogReturnsOnlyCollectionScopedActionsForResource() throws Exception {
        ResponseEntity<String> response = get("/employees/actions");
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        assertEquals("human-resources.employees", catalog.path("resourceKey").asText());
        assertTrue(catalog.path("resourceId").isNull());
        assertEquals(1, catalog.path("actions").size());

        JsonNode bulkApprove = findAction(catalog.path("actions"), "bulk-approve");
        assertNotNull(bulkApprove);
        assertEquals("COLLECTION", bulkApprove.path("scope").asText());
        assertFalse(bulkApprove.path("availability").path("allowed").asBoolean());
        assertEquals("missing-authority", bulkApprove.path("availability").path("reason").asText());
        assertEquals("employee:bulk-approve", bulkApprove.path("availability").path("metadata").path("missingAuthorities").get(0).asText());
        assertEquals(null, findAction(catalog.path("actions"), "approve"));
    }

    @Test
    void collectionActionCatalogAllowsBulkApproveWhenAuthorityMatches() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "qa-user");
        headers.add("X-Test-Authorities", "employee:bulk-approve");

        ResponseEntity<String> response = exchange("/employees/actions", HttpMethod.GET, headers);
        assertEquals(200, response.getStatusCode().value());

        JsonNode bulkApprove = findAction(body(response).path("actions"), "bulk-approve");
        assertNotNull(bulkApprove);
        assertTrue(bulkApprove.path("availability").path("allowed").asBoolean());
        assertTrue(bulkApprove.path("availability").path("reason").isNull());
        assertTrue(bulkApprove.path("availability").path("metadata").path("principalPresent").asBoolean());
        assertFalse(bulkApprove.path("availability").path("metadata").has("resourceState"));
    }

    @Test
    void itemActionCatalogReturnsOnlyItemScopedActionsForConcreteResource() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");

        ResponseEntity<String> response = get("/employees/" + carolId + "/actions");
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        assertEquals("human-resources.employees", catalog.path("resourceKey").asText());
        assertEquals("/employees", catalog.path("resourcePath").asText());
        assertEquals(carolId.longValue(), catalog.path("resourceId").asLong());

        JsonNode approve = findAction(catalog.path("actions"), "approve");
        assertNotNull(approve);
        assertEquals("ITEM", approve.path("scope").asText());
        assertFalse(approve.path("availability").path("allowed").asBoolean());
        assertEquals("missing-authority", approve.path("availability").path("reason").asText());
        assertEquals("employee:approve", approve.path("availability").path("metadata").path("requiredAuthorities").get(0).asText());
        assertEquals("employee:approve", approve.path("availability").path("metadata").path("missingAuthorities").get(0).asText());
        assertFalse(approve.path("availability").path("metadata").has("allowedStates"));
        assertFalse(approve.path("availability").path("metadata").has("resourceState"));
    }

    @Test
    void itemActionCatalogAllowsApproveWhenAuthorityAndStateMatch() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "qa-user");
        headers.add("X-Test-Authorities", "employee:approve");

        ResponseEntity<String> response = exchange("/employees/" + carolId + "/actions", HttpMethod.GET, headers);
        assertEquals(200, response.getStatusCode().value());

        JsonNode approve = findAction(body(response).path("actions"), "approve");
        assertNotNull(approve);
        assertTrue(approve.path("availability").path("allowed").asBoolean());
        assertTrue(approve.path("availability").path("reason").isNull());
        assertEquals("INACTIVE", approve.path("availability").path("metadata").path("resourceState").asText());
        assertTrue(approve.path("availability").path("metadata").path("principalPresent").asBoolean());
    }

    @Test
    void itemActionCatalogBlocksApproveWhenResourceStateDoesNotMatch() throws Exception {
        Long aliceId = state.employeeIdsByName().get("Alice");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "qa-user");
        headers.add("X-Test-Authorities", "employee:approve");

        ResponseEntity<String> response = exchange("/employees/" + aliceId + "/actions", HttpMethod.GET, headers);
        assertEquals(200, response.getStatusCode().value());

        JsonNode approve = findAction(body(response).path("actions"), "approve");
        assertNotNull(approve);
        assertFalse(approve.path("availability").path("allowed").asBoolean());
        assertEquals("resource-state-blocked", approve.path("availability").path("reason").asText());
        assertEquals("ACTIVE", approve.path("availability").path("metadata").path("resourceState").asText());
    }

    @Test
    void itemActionCatalogReturnsNotFoundForUnknownIdOrResourceWithoutActions() {
        ResponseEntity<String> missingEmployee = get("/employees/999999/actions");
        assertEquals(404, missingEmployee.getStatusCode().value());

        Long payrollId = state.payrollIdsByEmployee().get("Alice");
        ResponseEntity<String> payrollView = get("/payroll-view/" + payrollId + "/actions");
        assertEquals(404, payrollView.getStatusCode().value());

        ResponseEntity<String> departments = get("/departments/actions");
        assertEquals(404, departments.getStatusCode().value());
    }

    @Test
    void actionsCatalogRejectsMissingOrAmbiguousQueryParametersAndUnknownTargets() {
        assertEquals(400, get("/schemas/actions").getStatusCode().value());
        assertEquals(400, get("/schemas/actions?resource=human-resources.employees&group=human-resources").getStatusCode().value());
        assertEquals(404, get("/schemas/actions?resource=unknown.resource").getStatusCode().value());
        assertEquals(404, get("/schemas/actions?group=unknown-group").getStatusCode().value());
    }

    @Test
    void surfacesCatalogDoesNotExposeWorkflowActions() throws Exception {
        ResponseEntity<String> response = get("/schemas/surfaces?resource=human-resources.employees");
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        assertNullAction(catalog.path("surfaces"), "approve");
    }

    @Test
    void typedApproveEndpointExecutesWorkflowCommandWithoutGenericDispatcher() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.getRestTemplate().exchange(
                URI.create(url("/employees/" + carolId + "/actions/approve")),
                HttpMethod.POST,
                new HttpEntity<>("{\"comentario\":\"Tudo certo\"}", headers),
                String.class
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode body = body(response);
        assertEquals(carolId.longValue(), body.path("data").path("id").asLong());
        assertEquals("ACTIVE", body.path("data").path("status").asText());
        assertEquals("e2e", response.getHeaders().getFirst("X-Data-Version"));
    }

    @Test
    void typedBulkApproveEndpointExecutesCollectionWorkflowCommandWithoutGenericDispatcher() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");
        Long dianaId = state.employeeIdsByName().get("Diana");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.getRestTemplate().exchange(
                URI.create(url("/employees/actions/bulk-approve")),
                HttpMethod.POST,
                new HttpEntity<>("{\"employeeIds\":[" + carolId + "," + dianaId + "],\"comentario\":\"Lote ok\"}", headers),
                String.class
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode body = body(response);
        assertEquals(2, body.path("data").path("approvedCount").asInt());
        assertEquals(carolId.longValue(), body.path("data").path("approvedEmployeeIds").get(0).asLong());
        assertEquals(dianaId.longValue(), body.path("data").path("approvedEmployeeIds").get(1).asLong());
        assertEquals("e2e", response.getHeaders().getFirst("X-Data-Version"));

        assertEquals("ACTIVE", body(get("/employees/" + carolId)).path("data").path("status").asText());
        assertEquals("ACTIVE", body(get("/employees/" + dianaId)).path("data").path("status").asText());
    }

    private JsonNode findAction(JsonNode actions, String id) {
        for (JsonNode action : actions) {
            if (id.equals(action.path("id").asText())) {
                return action;
            }
        }
        return null;
    }

    private JsonNode findAction(JsonNode actions, String resourceKey, String id) {
        for (JsonNode action : actions) {
            if (resourceKey.equals(action.path("resourceKey").asText())
                    && id.equals(action.path("id").asText())) {
                return action;
            }
        }
        return null;
    }

    private void assertNullAction(JsonNode actions, String id) {
        assertEquals(null, findAction(actions, id));
    }

    private void assertNullAction(JsonNode actions, String resourceKey, String id) {
        assertEquals(null, findAction(actions, resourceKey, id));
    }
}
