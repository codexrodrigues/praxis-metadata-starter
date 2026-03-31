package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogDiscoveryE2ETest extends AbstractE2eH2Test {

    @Test
    void catalogExposesCanonicalEndpointsAndResolvableSchemaLinks() throws Exception {
        ResponseEntity<String> employeeCatalogResponse = get("/schemas/catalog?path=/employees");
        assertEquals(200, employeeCatalogResponse.getStatusCode().value());
        JsonNode employeeCatalog = body(employeeCatalogResponse);
        assertEquals("employees", employeeCatalog.path("group").asText());
        assertTrue(employeeCatalog.path("endpoints").isArray());
        assertFalse(employeeCatalog.path("endpoints").isEmpty());

        JsonNode createEmployeeEndpoint = findEndpoint(employeeCatalog.path("endpoints"), "/employees", "POST");
        assertNotNull(createEmployeeEndpoint);
        assertEquals("POST", createEmployeeEndpoint.path("method").asText());
        assertTrue(createEmployeeEndpoint.path("schemaLinks").hasNonNull("request"));
        assertTrue(createEmployeeEndpoint.path("schemaLinks").hasNonNull("response"));
        assertResolvable(createEmployeeEndpoint.path("schemaLinks").path("request").asText());
        assertResolvable(createEmployeeEndpoint.path("schemaLinks").path("response").asText());

        ResponseEntity<String> humanResourcesCatalogResponse = get("/schemas/catalog?group=human-resources");
        assertEquals(200, humanResourcesCatalogResponse.getStatusCode().value());
        JsonNode humanResourcesCatalog = body(humanResourcesCatalogResponse);
        assertEquals("human-resources", humanResourcesCatalog.path("group").asText());
        assertNotNull(findEndpoint(humanResourcesCatalog.path("endpoints"), "/employees", "POST"));
        assertNotNull(findEndpoint(humanResourcesCatalog.path("endpoints"), "/employees/all", "GET"));
        assertNotNull(findEndpoint(humanResourcesCatalog.path("endpoints"), "/departments", "POST"));
        assertNotNull(findEndpoint(humanResourcesCatalog.path("endpoints"), "/payroll-view/all", "GET"));

        ResponseEntity<String> payrollCatalogResponse = get("/schemas/catalog?path=/payroll-view/%7Bid%7D");
        assertEquals(200, payrollCatalogResponse.getStatusCode().value());
        JsonNode payrollCatalog = body(payrollCatalogResponse);
        assertEquals("payroll-view", payrollCatalog.path("group").asText());
        JsonNode payrollByIdEndpoint = findEndpoint(payrollCatalog.path("endpoints"), "/payroll-view/{id}", "GET");
        assertNotNull(payrollByIdEndpoint);
        assertTrue(payrollByIdEndpoint.path("schemaLinks").hasNonNull("response"));
        assertResolvable(payrollByIdEndpoint.path("schemaLinks").path("response").asText());

        ResponseEntity<String> postCatalogResponse = get("/schemas/catalog?operation=post");
        assertEquals(200, postCatalogResponse.getStatusCode().value());
        JsonNode postCatalog = body(postCatalogResponse);
        assertEquals("application", postCatalog.path("group").asText());
        assertNotNull(findEndpoint(postCatalog.path("endpoints"), "/employees", "POST"));
    }

    private void assertResolvable(String relativePath) {
        ResponseEntity<String> response = get(relativePath);
        assertEquals(200, response.getStatusCode().value());
    }

    private JsonNode findEndpoint(JsonNode endpoints, String path, String method) {
        for (JsonNode endpoint : endpoints) {
            if (path.equals(endpoint.path("path").asText()) && method.equalsIgnoreCase(endpoint.path("method").asText())) {
                return endpoint;
            }
        }
        return null;
    }
}
