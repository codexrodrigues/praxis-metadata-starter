package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceCatalogE2ETest extends AbstractE2eH2Test {

    @Test
    void surfacesCatalogExposesAutomaticAndExplicitSurfacesByResourceKey() throws Exception {
        ResponseEntity<String> response = get("/schemas/surfaces?resource=human-resources.employees");
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        assertEquals("human-resources.employees", catalog.path("resourceKey").asText());
        assertEquals("/employees", catalog.path("resourcePath").asText());
        assertEquals("human-resources", catalog.path("group").asText());

        JsonNode create = findSurface(catalog.path("surfaces"), "create");
        assertNotNull(create);
        assertEquals("FORM", create.path("kind").asText());
        assertEquals("COLLECTION", create.path("scope").asText());
        assertEquals("POST", create.path("method").asText());
        assertEquals("/employees", create.path("path").asText());
        assertTrue(create.path("schemaUrl").asText().contains("path=%2Femployees"));
        assertTrue(create.path("schemaUrl").asText().contains("schemaType=request"));
        assertTrue(create.path("availability").path("allowed").asBoolean());
        assertFalse(create.has("fields"));
        assertFalse(create.has("schema"));

        JsonNode detail = findSurface(catalog.path("surfaces"), "detail");
        assertNotNull(detail);
        assertEquals("VIEW", detail.path("kind").asText());
        assertEquals("ITEM", detail.path("scope").asText());
        assertEquals("GET", detail.path("method").asText());
        assertTrue(detail.path("schemaUrl").asText().contains("schemaType=response"));
        assertFalse(detail.path("availability").path("allowed").asBoolean());
        assertEquals("resource-context-required", detail.path("availability").path("reason").asText());
        assertFalse(detail.path("availability").path("metadata").path("contextual").asBoolean());

        JsonNode profile = findSurface(catalog.path("surfaces"), "profile");
        assertNotNull(profile);
        assertEquals("PARTIAL_FORM", profile.path("kind").asText());
        assertEquals("ITEM", profile.path("scope").asText());
        assertEquals("PATCH", profile.path("method").asText());
        assertEquals("/employees/{id}/profile", profile.path("path").asText());
        assertEquals("profile", profile.path("intent").asText());
        assertEquals("updateProfile", profile.path("operationId").asText());
        assertTrue(profile.path("schemaUrl").asText().contains("schemaType=request"));
        assertFalse(profile.path("availability").path("allowed").asBoolean());
        assertEquals("resource-context-required", profile.path("availability").path("reason").asText());
        assertFalse(profile.path("availability").path("metadata").has("requiredAuthorities"));
        assertFalse(profile.path("availability").path("metadata").has("allowedStates"));
    }

    @Test
    void surfacesCatalogAggregatesOnlyCanonicalResourceSurfacesByGroup() throws Exception {
        ResponseEntity<String> response = get("/schemas/surfaces?group=human-resources");
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        assertEquals("human-resources", catalog.path("group").asText());
        assertTrue(catalog.path("resourceKey").isNull());
        assertTrue(catalog.path("surfaces").isArray());

        assertNotNull(findSurface(catalog.path("surfaces"), "human-resources.employees", "create"));
        assertNotNull(findSurface(catalog.path("surfaces"), "human-resources.departments", "create"));
        assertNotNull(findSurface(catalog.path("surfaces"), "human-resources.payroll-view", "list"));
    }

    @Test
    void itemSurfaceCatalogReturnsOnlyItemScopedSurfacesForMutableResource() throws Exception {
        Long aliceId = state.employeeIdsByName().get("Alice");

        ResponseEntity<String> response = get("/employees/" + aliceId + "/surfaces");
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        assertEquals("human-resources.employees", catalog.path("resourceKey").asText());
        assertEquals("/employees", catalog.path("resourcePath").asText());
        assertEquals("human-resources", catalog.path("group").asText());
        assertEquals(aliceId.longValue(), catalog.path("resourceId").asLong());

        JsonNode detail = findSurface(catalog.path("surfaces"), "detail");
        assertNotNull(detail);
        assertEquals("ITEM", detail.path("scope").asText());
        assertEquals("GET", detail.path("method").asText());
        assertTrue(detail.path("availability").path("allowed").asBoolean());
        assertTrue(detail.path("availability").path("metadata").path("contextual").asBoolean());
        assertFalse(detail.path("availability").path("metadata").path("tenantPresent").asBoolean());

        JsonNode edit = findSurface(catalog.path("surfaces"), "edit");
        assertNotNull(edit);
        assertEquals("ITEM", edit.path("scope").asText());
        assertEquals("PUT", edit.path("method").asText());

        JsonNode profile = findSurface(catalog.path("surfaces"), "profile");
        assertNotNull(profile);
        assertEquals("ITEM", profile.path("scope").asText());
        assertEquals("PATCH", profile.path("method").asText());
        assertFalse(profile.path("availability").path("allowed").asBoolean());
        assertEquals("missing-authority", profile.path("availability").path("reason").asText());
        assertEquals("employee:profile:update", profile.path("availability").path("metadata").path("requiredAuthorities").get(0).asText());
        assertFalse(profile.path("availability").path("metadata").has("resourceState"));

        assertNullSurface(catalog.path("surfaces"), "create");
        assertNullSurface(catalog.path("surfaces"), "list");
    }

    @Test
    void itemSurfaceCatalogAllowsProfileWhenAuthorityAndStateMatch() throws Exception {
        Long aliceId = state.employeeIdsByName().get("Alice");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "qa-user");
        headers.add("X-Test-Authorities", "employee:profile:update");

        ResponseEntity<String> response = exchange("/employees/" + aliceId + "/surfaces", HttpMethod.GET, headers);
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        JsonNode profile = findSurface(catalog.path("surfaces"), "profile");
        assertNotNull(profile);
        assertTrue(profile.path("availability").path("allowed").asBoolean());
        assertTrue(profile.path("availability").path("reason").isNull());
        assertTrue(profile.path("availability").path("metadata").path("principalPresent").asBoolean());
        assertEquals("ACTIVE", profile.path("availability").path("metadata").path("resourceState").asText());
        assertEquals("employee:profile:update", profile.path("availability").path("metadata").path("requiredAuthorities").get(0).asText());
        assertEquals("ACTIVE", profile.path("availability").path("metadata").path("allowedStates").get(0).asText());
    }

    @Test
    void itemSurfaceCatalogBlocksProfileWhenResourceStateDoesNotMatch() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test-Principal", "qa-user");
        headers.add("X-Test-Authorities", "employee:profile:update");

        ResponseEntity<String> response = exchange("/employees/" + carolId + "/surfaces", HttpMethod.GET, headers);
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        JsonNode profile = findSurface(catalog.path("surfaces"), "profile");
        assertNotNull(profile);
        assertFalse(profile.path("availability").path("allowed").asBoolean());
        assertEquals("resource-state-blocked", profile.path("availability").path("reason").asText());
        assertEquals("INACTIVE", profile.path("availability").path("metadata").path("resourceState").asText());
        assertEquals("ACTIVE", profile.path("availability").path("metadata").path("allowedStates").get(0).asText());
    }

    @Test
    void itemSurfaceCatalogReturnsOnlyDetailForReadOnlyResource() throws Exception {
        Long payrollId = state.payrollIdsByEmployee().get("Alice");

        ResponseEntity<String> response = get("/payroll-view/" + payrollId + "/surfaces");
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        assertEquals("human-resources.payroll-view", catalog.path("resourceKey").asText());
        assertEquals("/payroll-view", catalog.path("resourcePath").asText());
        assertEquals("human-resources", catalog.path("group").asText());
        assertEquals(payrollId.longValue(), catalog.path("resourceId").asLong());

        JsonNode detail = findSurface(catalog.path("surfaces"), "detail");
        assertNotNull(detail);
        assertEquals("VIEW", detail.path("kind").asText());
        assertEquals("ITEM", detail.path("scope").asText());
        assertEquals("GET", detail.path("method").asText());
        assertTrue(detail.path("availability").path("allowed").asBoolean());
        assertTrue(detail.path("availability").path("metadata").path("contextual").asBoolean());

        assertNullSurface(catalog.path("surfaces"), "edit");
        assertNullSurface(catalog.path("surfaces"), "create");
        assertNullSurface(catalog.path("surfaces"), "list");
    }

    @Test
    void itemSurfaceCatalogCapturesTenantPresenceInAvailabilityMetadata() throws Exception {
        Long aliceId = state.employeeIdsByName().get("Alice");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Tenant", "tenant-a");

        ResponseEntity<String> response = exchange("/employees/" + aliceId + "/surfaces", HttpMethod.GET, headers);
        assertEquals(200, response.getStatusCode().value());

        JsonNode catalog = body(response);
        JsonNode detail = findSurface(catalog.path("surfaces"), "detail");
        assertNotNull(detail);
        assertTrue(detail.path("availability").path("metadata").path("tenantPresent").asBoolean());
        assertTrue(detail.path("availability").path("metadata").path("contextual").asBoolean());
    }

    @Test
    void itemSurfaceCatalogReturnsNotFoundForUnknownResourceId() {
        ResponseEntity<String> response = get("/employees/999999/surfaces");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void surfacesCatalogRejectsMissingOrAmbiguousQueryParameters() {
        ResponseEntity<String> missingParams = get("/schemas/surfaces");
        assertEquals(400, missingParams.getStatusCode().value());

        ResponseEntity<String> ambiguousParams = get("/schemas/surfaces?resource=human-resources.employees&group=human-resources");
        assertEquals(400, ambiguousParams.getStatusCode().value());
    }

    @Test
    void surfacesCatalogReturnsNotFoundForUnknownResourceOrGroup() {
        ResponseEntity<String> unknownResource = get("/schemas/surfaces?resource=unknown.resource");
        assertEquals(404, unknownResource.getStatusCode().value());

        ResponseEntity<String> unknownGroup = get("/schemas/surfaces?group=unknown-group");
        assertEquals(404, unknownGroup.getStatusCode().value());
    }

    private JsonNode findSurface(JsonNode surfaces, String id) {
        for (JsonNode surface : surfaces) {
            if (id.equals(surface.path("id").asText())) {
                return surface;
            }
        }
        return null;
    }

    private JsonNode findSurface(JsonNode surfaces, String resourceKey, String id) {
        for (JsonNode surface : surfaces) {
            if (resourceKey.equals(surface.path("resourceKey").asText())
                    && id.equals(surface.path("id").asText())) {
                return surface;
            }
        }
        return null;
    }

    private void assertNullSurface(JsonNode surfaces, String resourceKey, String id) {
        assertEquals(null, findSurface(surfaces, resourceKey, id));
    }

    private void assertNullSurface(JsonNode surfaces, String id) {
        assertEquals(null, findSurface(surfaces, id));
    }
}
