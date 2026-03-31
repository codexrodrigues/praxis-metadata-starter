package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableResourceLifecycleE2ETest extends AbstractE2eH2Test {

    @Test
    void mutableResourceLifecycleRemainsCanonical() throws Exception {
        ResponseEntity<String> getAllResponse = get("/employees/all");
        assertEquals(200, getAllResponse.getStatusCode().value());
        assertEquals("e2e", getAllResponse.getHeaders().getFirst("X-Data-Version"));
        JsonNode getAllBody = body(getAllResponse);
        assertEquals(6, getAllBody.path("data").size());
        assertEquals("Alice", getAllBody.path("data").get(0).path("nome").asText());

        Long aliceId = state.employeeIdsByName().get("Alice");
        ResponseEntity<String> getByIdResponse = get("/employees/" + aliceId);
        assertEquals(200, getByIdResponse.getStatusCode().value());
        JsonNode getByIdBody = body(getByIdResponse);
        assertEquals(aliceId.longValue(), getByIdBody.path("data").path("id").asLong());
        assertEquals("Alice", getByIdBody.path("data").path("nome").asText());
        assertTrue(getByIdResponse.getBody().contains("/employees/" + aliceId), "Self link should point to the item resource");

        ResponseEntity<String> createResponse = postJson("/employees", """
                {
                  "nome": "Grace",
                  "matricula": "HR-777",
                  "status": "ACTIVE",
                  "salario": 8123.45,
                  "admissionDate": "2025-02-01",
                  "departmentId": %d
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(201, createResponse.getStatusCode().value());
        assertEquals("e2e", createResponse.getHeaders().getFirst("X-Data-Version"));
        JsonNode createBody = body(createResponse);
        long createdId = createBody.path("data").path("id").asLong();
        assertTrue(createdId > 0);
        assertEquals(url("/employees/" + createdId), createResponse.getHeaders().getLocation().toString());
        assertEquals("Grace", createBody.path("data").path("nome").asText());
        assertEquals("Human Resources", createBody.path("data").path("departmentNome").asText());

        ResponseEntity<String> updateResponse = putJson("/employees/" + createdId, """
                {
                  "nome": "Grace Updated",
                  "matricula": "OPS-909",
                  "status": "LEAVE",
                  "salario": 9000.00,
                  "admissionDate": "2025-02-10",
                  "departmentId": %d
                }
                """.formatted(state.operationsDepartmentId()));
        assertEquals(200, updateResponse.getStatusCode().value());
        JsonNode updateBody = body(updateResponse);
        assertEquals("Grace Updated", updateBody.path("data").path("nome").asText());
        assertEquals("Operations", updateBody.path("data").path("departmentNome").asText());

        ResponseEntity<String> deleteResponse = delete("/employees/" + createdId);
        assertEquals(204, deleteResponse.getStatusCode().value());
        ResponseEntity<String> deletedLookup = get("/employees/" + createdId);
        assertEquals(404, deletedLookup.getStatusCode().value());

        Long bobId = state.employeeIdsByName().get("Bob");
        Long carolId = state.employeeIdsByName().get("Carol");
        ResponseEntity<String> batchDeleteResponse = deleteJson("/employees/batch", "[" + bobId + "," + carolId + "]");
        assertEquals(204, batchDeleteResponse.getStatusCode().value());
        assertEquals(4, fixtureData.employeeCount());
        assertEquals(404, get("/employees/" + bobId).getStatusCode().value());
        assertEquals(404, get("/employees/" + carolId).getStatusCode().value());

        ResponseEntity<String> postDeleteAll = get("/employees/all");
        JsonNode postDeleteAllBody = body(postDeleteAll);
        assertFalse(postDeleteAllBody.toString().contains("\"nome\":\"Bob\""));
        assertFalse(postDeleteAllBody.toString().contains("\"nome\":\"Carol\""));
    }

    @Test
    void mutableResourceCreateRejectsInvalidPayloadWithCanonicalValidationResponse() throws Exception {
        long beforeCount = fixtureData.employeeCount();

        ResponseEntity<String> response = postJson("/employees", """
                {
                  "matricula": "HR-999",
                  "status": "ACTIVE",
                  "salario": 8123.45,
                  "admissionDate": "2025-02-01",
                  "departmentId": %d
                }
                """.formatted(state.humanResourcesDepartmentId()));

        assertEquals(400, response.getStatusCode().value());
        JsonNode error = body(response);
        assertEquals("failure", error.path("status").asText());
        assertEquals("Validation error.", error.path("message").asText());
        assertEquals("nome", error.path("errors").get(0).path("title").asText());
        assertEquals("INVALID_PARAMETER", error.path("errors").get(0).path("properties").path("code").asText());
        assertEquals(beforeCount, fixtureData.employeeCount());
    }
}
