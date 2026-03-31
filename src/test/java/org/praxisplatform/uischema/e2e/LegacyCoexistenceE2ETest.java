package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyCoexistenceE2ETest extends AbstractE2eH2Test {

    @Test
    void legacyAndNewControllersCoexistInSameApplicationContext() throws Exception {
        ResponseEntity<String> newResourceResponse = get("/employees/all");
        assertEquals(200, newResourceResponse.getStatusCode().value());
        JsonNode newResourceBody = body(newResourceResponse);
        assertEquals(6, newResourceBody.path("data").size());

        ResponseEntity<String> legacyResourceResponse = get("/legacy-employees/all");
        assertEquals(200, legacyResourceResponse.getStatusCode().value());
        JsonNode legacyResourceBody = body(legacyResourceResponse);
        assertEquals(6, legacyResourceBody.path("data").size());
        assertEquals("Alice", legacyResourceBody.path("data").get(0).path("nome").asText());

        ResponseEntity<String> newSchemaResponse = get("/schemas/filtered?path=/employees/all&operation=get&schemaType=response");
        ResponseEntity<String> legacySchemaResponse = get("/schemas/filtered?path=/legacy-employees/all&operation=get&schemaType=response");
        assertEquals(200, newSchemaResponse.getStatusCode().value());
        assertEquals(200, legacySchemaResponse.getStatusCode().value());

        ResponseEntity<String> newCatalogResponse = get("/schemas/catalog?path=/employees");
        ResponseEntity<String> legacyCatalogResponse = get("/schemas/catalog?path=/legacy-employees");
        assertEquals(200, newCatalogResponse.getStatusCode().value());
        assertEquals(200, legacyCatalogResponse.getStatusCode().value());

        JsonNode newCatalogBody = body(newCatalogResponse);
        JsonNode legacyCatalogBody = body(legacyCatalogResponse);
        assertTrue(newCatalogBody.toString().contains("/employees"));
        assertTrue(legacyCatalogBody.toString().contains("/legacy-employees"));
        assertTrue(legacyCatalogBody.toString().contains("/schemas/filtered"));

        ResponseEntity<String> employeeGroup = get("/v3/api-docs/employees");
        ResponseEntity<String> legacyGroup = get("/v3/api-docs/legacy-employees");
        assertEquals(200, employeeGroup.getStatusCode().value());
        assertEquals(200, legacyGroup.getStatusCode().value());
    }
}
