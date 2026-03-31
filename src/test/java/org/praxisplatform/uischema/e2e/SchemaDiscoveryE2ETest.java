package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiscoveryE2ETest extends AbstractE2eH2Test {

    @Test
    void filteredSchemasRemainCanonicalForNewControllers() throws Exception {
        ResponseEntity<String> createRequestResponse = get("/schemas/filtered?path=/employees&operation=post&schemaType=request");
        assertEquals(200, createRequestResponse.getStatusCode().value());
        assertNotNull(createRequestResponse.getHeaders().getETag());
        assertNotNull(createRequestResponse.getHeaders().getFirst("X-Schema-Hash"));
        JsonNode createRequestBody = body(createRequestResponse);
        assertEquals("string", createRequestBody.path("properties").path("nome").path("type").asText());
        assertEquals("string", createRequestBody.path("properties").path("matricula").path("type").asText());
        assertFalse(createRequestBody.path("x-ui").path("resource").path("readOnly").asBoolean());

        HttpHeaders ifNoneMatch = new HttpHeaders();
        ifNoneMatch.set("If-None-Match", createRequestResponse.getHeaders().getETag());
        ResponseEntity<String> notModifiedResponse = exchange(
                "/schemas/filtered?path=/employees&operation=post&schemaType=request",
                HttpMethod.GET,
                ifNoneMatch
        );
        assertEquals(304, notModifiedResponse.getStatusCode().value());

        ResponseEntity<String> updateRequestResponse = get("/schemas/filtered?path=/employees/%7Bid%7D&operation=put&schemaType=request");
        JsonNode updateRequestBody = body(updateRequestResponse);
        assertEquals("string", updateRequestBody.path("properties").path("nome").path("type").asText());
        assertEquals("string", updateRequestBody.path("properties").path("matricula").path("type").asText());

        ResponseEntity<String> filterRequestResponse = get("/schemas/filtered?path=/employees/filter&operation=post&schemaType=request");
        JsonNode filterRequestBody = body(filterRequestResponse);
        assertTrue(filterRequestBody.path("properties").has("nome"));

        ResponseEntity<String> employeesResponseSchema = get("/schemas/filtered?path=/employees/all&operation=get&schemaType=response");
        JsonNode employeesResponseBody = body(employeesResponseSchema);
        assertEquals("id", employeesResponseBody.path("x-ui").path("resource").path("idField").asText());
        assertFalse(employeesResponseBody.path("x-ui").path("resource").path("readOnly").asBoolean());
        assertTrue(employeesResponseBody.path("properties").path("department").has("$ref"));

        ResponseEntity<String> employeesExpandedSchema = get("/schemas/filtered?path=/employees/all&operation=get&schemaType=response&includeInternalSchemas=true");
        JsonNode employeesExpandedBody = body(employeesExpandedSchema);
        assertTrue(employeesExpandedBody.path("properties").path("department").has("properties"));
        assertFalse(employeesExpandedBody.path("properties").path("department").has("$ref"));
        assertNotEquals(employeesResponseSchema.getHeaders().getETag(), employeesExpandedSchema.getHeaders().getETag());

        ResponseEntity<String> readOnlySchema = get("/schemas/filtered?path=/payroll-view/all&operation=get&schemaType=response");
        JsonNode readOnlyBody = body(readOnlySchema);
        assertTrue(readOnlyBody.path("x-ui").path("resource").path("readOnly").asBoolean());

        ResponseEntity<String> createSchemaLinkFromController = get("/employees/schemas");
        assertEquals(200, createSchemaLinkFromController.getStatusCode().value());
        JsonNode redirectedSchemaBody = body(createSchemaLinkFromController);
        assertEquals("id", redirectedSchemaBody.path("x-ui").path("resource").path("idField").asText());
    }
}
