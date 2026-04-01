package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypermediaDiscoveryE2ETest extends AbstractE2eH2Test {

    @Test
    void collectionLinksLetAnAngularLikeClientDiscoverCatalogsAndSchemas() throws Exception {
        JsonNode collectionEnvelope = body(get("/employees/all"));

        String surfacesHref = findLinkHref(collectionEnvelope, "surfaces");
        String actionsHref = findLinkHref(collectionEnvelope, "actions");
        String capabilitiesHref = findLinkHref(collectionEnvelope, "capabilities");

        assertNotNull(surfacesHref);
        assertNotNull(actionsHref);
        assertNotNull(capabilitiesHref);

        JsonNode surfacesCatalog = body(getHref(surfacesHref));
        assertEquals("human-resources.employees", surfacesCatalog.path("resourceKey").asText());
        JsonNode createSurface = findById(surfacesCatalog.path("surfaces"), "create");
        assertNotNull(createSurface);
        assertEquals("COLLECTION", createSurface.path("scope").asText());

        JsonNode createSchema = body(getHref(createSurface.path("schemaUrl").asText()));
        assertTrue(createSchema.path("properties").has("nome"));
        assertTrue(createSchema.path("properties").has("departmentId"));
        assertFalse(createSchema.path("readOnly").asBoolean());

        JsonNode actionsCatalog = body(getHref(actionsHref));
        assertEquals("human-resources.employees", actionsCatalog.path("resourceKey").asText());
        JsonNode bulkApprove = findById(actionsCatalog.path("actions"), "bulk-approve");
        assertNotNull(bulkApprove);
        assertEquals("COLLECTION", bulkApprove.path("scope").asText());

        JsonNode bulkApproveRequestSchema = body(getHref(bulkApprove.path("requestSchemaUrl").asText()));
        assertTrue(bulkApproveRequestSchema.path("properties").has("employeeIds"));
        assertTrue(bulkApproveRequestSchema.path("properties").has("comentario"));
        assertFalse(bulkApproveRequestSchema.path("readOnly").asBoolean());

        JsonNode capabilities = body(getHref(capabilitiesHref));
        assertEquals("human-resources.employees", capabilities.path("resourceKey").asText());
        assertEquals("COLLECTION", findById(capabilities.path("surfaces"), "create").path("scope").asText());
        assertEquals("COLLECTION", findById(capabilities.path("actions"), "bulk-approve").path("scope").asText());
    }

    @Test
    void itemLinksLetAnAngularLikeClientFollowContextualDiscoveryAndSchemas() throws Exception {
        Long aliceId = state.employeeIdsByName().get("Alice");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "qa-user");
        headers.add("X-Test-Authorities", "employee:approve,employee:profile:update");

        JsonNode itemEnvelope = body(exchange("/employees/" + aliceId, HttpMethod.GET, headers));
        String surfacesHref = findLinkHref(itemEnvelope, "surfaces");
        String actionsHref = findLinkHref(itemEnvelope, "actions");
        String capabilitiesHref = findLinkHref(itemEnvelope, "capabilities");

        assertNotNull(surfacesHref);
        assertNotNull(actionsHref);
        assertNotNull(capabilitiesHref);

        JsonNode surfacesCatalog = body(exchangeHref(surfacesHref, HttpMethod.GET, headers));
        JsonNode actionsCatalog = body(exchangeHref(actionsHref, HttpMethod.GET, headers));
        JsonNode capabilities = body(exchangeHref(capabilitiesHref, HttpMethod.GET, headers));

        JsonNode profileSurface = findById(surfacesCatalog.path("surfaces"), "profile");
        assertNotNull(profileSurface);
        assertTrue(profileSurface.path("availability").path("allowed").asBoolean());
        JsonNode profileSchema = body(getHref(profileSurface.path("schemaUrl").asText()));
        assertTrue(profileSchema.path("properties").has("nome"));
        assertFalse(profileSchema.path("readOnly").asBoolean());

        JsonNode approveAction = findById(actionsCatalog.path("actions"), "approve");
        assertNotNull(approveAction);
        assertFalse(approveAction.path("availability").path("allowed").asBoolean());
        assertEquals("resource-state-blocked", approveAction.path("availability").path("reason").asText());
        JsonNode approveRequestSchema = body(getHref(approveAction.path("requestSchemaUrl").asText()));
        assertTrue(approveRequestSchema.path("properties").has("comentario"));

        assertEquals(indexById(surfacesCatalog.path("surfaces")), indexById(capabilities.path("surfaces")));
        assertEquals(indexById(actionsCatalog.path("actions")), indexById(capabilities.path("actions")));
    }

    @Test
    void readOnlyLinksRemainDiscoverableWithoutWorkflowAffordances() throws Exception {
        Long payrollId = state.payrollIdsByEmployee().get("Alice");

        JsonNode collectionEnvelope = body(get("/payroll-view/all"));
        assertNotNull(findLinkHref(collectionEnvelope, "surfaces"));
        assertNotNull(findLinkHref(collectionEnvelope, "capabilities"));
        assertNull(findLinkHref(collectionEnvelope, "actions"));
        assertNull(findLinkHref(collectionEnvelope, "create"));

        JsonNode collectionSurfaces = body(getHref(findLinkHref(collectionEnvelope, "surfaces")));
        assertEquals("human-resources.payroll-view", collectionSurfaces.path("resourceKey").asText());
        assertNotNull(findById(collectionSurfaces.path("surfaces"), "list"));

        JsonNode itemEnvelope = body(get("/payroll-view/" + payrollId));
        assertNotNull(findLinkHref(itemEnvelope, "surfaces"));
        assertNotNull(findLinkHref(itemEnvelope, "capabilities"));
        assertNull(findLinkHref(itemEnvelope, "actions"));
        assertNull(findLinkHref(itemEnvelope, "update"));
        assertNull(findLinkHref(itemEnvelope, "delete"));

        JsonNode itemSurfaces = body(getHref(findLinkHref(itemEnvelope, "surfaces")));
        JsonNode detailSurface = findById(itemSurfaces.path("surfaces"), "detail");
        assertNotNull(detailSurface);
        JsonNode detailSchema = body(getHref(detailSurface.path("schemaUrl").asText()));
        assertTrue(detailSchema.path("properties").has("employeeNome"));
        assertTrue(detailSchema.path("properties").has("departmentNome"));
    }

    private JsonNode findById(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private Set<String> indexById(JsonNode items) {
        Set<String> indexed = new LinkedHashSet<>();
        for (JsonNode item : items) {
            indexed.add(item.path("id").asText());
        }
        return indexed;
    }
}
