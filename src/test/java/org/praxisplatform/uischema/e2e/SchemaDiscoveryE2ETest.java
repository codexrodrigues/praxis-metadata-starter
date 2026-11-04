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
    void filterMatchesInclusiveAndOpenValidity() throws Exception {
        var response = postJson("/employees/filter?size=10", """
                {"effectiveDate":"2023-02-15"}
                """);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        var names = body(response).findValuesAsText("nome");
        assertTrue(names.containsAll(java.util.List.of("Alice", "Bob", "Diana")), names.toString());
        assertFalse(names.contains("Carol"), names.toString());
        assertFalse(names.contains("Eve"), names.toString());
    }

    @Test
    void filterResolvesBothJoinedBounds() throws Exception {
        var response = postJson("/employees/filter?size=10", """
                {"departmentEffectiveDate":"2023-02-15"}
                """);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        var names = body(response).findValuesAsText("nome");
        assertTrue(names.containsAll(java.util.List.of("Alice", "Carol", "Eve")), names.toString());
        assertFalse(names.contains("Bob"), names.toString());
        assertFalse(names.contains("Diana"), names.toString());
    }

    @Test
    void hierarchyIsPublishedOnTheResponseSchemaOnly() throws Exception {
        var response = get("/schemas/filtered?path=/employees/all&operation=get&schemaType=response");
        assertEquals(200, response.getStatusCode().value());
        var hierarchy = body(response).path("x-ui").path("hierarchy");
        assertEquals("departmentId", hierarchy.path("parentField").asText());
        assertEquals("admissionDate", hierarchy.path("effectiveFromField").asText());
        assertFalse(hierarchy.has("effectiveToField"));

        var tableSchema = get("/schemas/filtered?path=/employees/filter&operation=post&schemaType=response");
        assertEquals(200, tableSchema.getStatusCode().value(), tableSchema.getBody());
        assertEquals(hierarchy, body(tableSchema).path("x-ui").path("hierarchy"));
        assertFalse(body(get("/schemas/filtered?path=/employees/filter&operation=post&schemaType=request"))
                .path("x-ui").has("hierarchy"));
        assertFalse(body(get("/schemas/filtered?path=/departments/all&operation=get&schemaType=response"))
                .path("x-ui").has("hierarchy"));
    }

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
        JsonNode netAmount = readOnlyBody.path("properties").path("netAmount");
        assertEquals("number", netAmount.path("type").asText());
        assertEquals("decimal", netAmount.path("format").asText());
        assertEquals(
                "Valor liquido agregado da folha usado para validar campos decimais em schemas somente leitura.",
                netAmount.path("description").asText()
        );

        ResponseEntity<String> createSchemaLinkFromController = get("/employees/schemas");
        assertEquals(200, createSchemaLinkFromController.getStatusCode().value());
        JsonNode redirectedSchemaBody = body(createSchemaLinkFromController);
        assertEquals("id", redirectedSchemaBody.path("x-ui").path("resource").path("idField").asText());
        JsonNode salario = redirectedSchemaBody.path("properties").path("salario");
        assertEquals("number", salario.path("type").asText());
        assertEquals("decimal", salario.path("format").asText());
        assertEquals(
                "Remuneracao mensal usada para exemplos de campos monetarios preservados no contrato OpenAPI.",
                salario.path("description").asText()
        );
    }
}
