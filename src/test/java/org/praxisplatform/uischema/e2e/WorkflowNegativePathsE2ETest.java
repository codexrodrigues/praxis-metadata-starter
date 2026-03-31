package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowNegativePathsE2ETest extends AbstractE2eH2Test {

    @Test
    void typedApproveRejectsInvalidPayloadAndKeepsEmployeeStateUnchanged() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");

        ResponseEntity<String> response = rest.getRestTemplate().exchange(
                URI.create(url("/employees/" + carolId + "/actions/approve")),
                HttpMethod.POST,
                jsonEntity("{}"),
                String.class
        );

        assertEquals(400, response.getStatusCode().value());
        JsonNode error = body(response);
        assertEquals("failure", error.path("status").asText());
        assertEquals("Validation error.", error.path("message").asText());
        assertEquals("comentario", error.path("errors").get(0).path("title").asText());
        assertEquals("INVALID_PARAMETER", error.path("errors").get(0).path("properties").path("code").asText());
        assertEquals("INACTIVE", body(get("/employees/" + carolId)).path("data").path("status").asText());
    }

    @Test
    void typedBulkApproveRejectsInvalidPayloadAndKeepsEmployeeStatesUnchanged() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");
        Long frankId = state.employeeIdsByName().get("Frank");

        ResponseEntity<String> response = rest.getRestTemplate().exchange(
                URI.create(url("/employees/actions/bulk-approve")),
                HttpMethod.POST,
                jsonEntity("{\"employeeIds\":[],\"comentario\":\"\"}"),
                String.class
        );

        assertEquals(400, response.getStatusCode().value());
        JsonNode error = body(response);
        assertEquals("failure", error.path("status").asText());
        assertEquals("Validation error.", error.path("message").asText());
        assertEquals("INVALID_PARAMETER", error.path("errors").get(0).path("properties").path("code").asText());
        assertEquals("INACTIVE", body(get("/employees/" + carolId)).path("data").path("status").asText());
        assertEquals("LEAVE", body(get("/employees/" + frankId)).path("data").path("status").asText());
    }

    @Test
    void typedApproveReturnsNotFoundForUnknownEmployee() throws Exception {
        ResponseEntity<String> response = rest.getRestTemplate().exchange(
                URI.create(url("/employees/999999/actions/approve")),
                HttpMethod.POST,
                jsonEntity("{\"comentario\":\"Tudo certo\"}"),
                String.class
        );

        assertEquals(404, response.getStatusCode().value());
        JsonNode error = body(response);
        assertEquals("failure", error.path("status").asText());
        assertEquals("Resource not found.", error.path("message").asText());
        assertEquals("RESOURCE_NOT_FOUND", error.path("errors").get(0).path("properties").path("code").asText());
    }

    @Test
    void typedBulkApproveReturnsNotFoundWhenRequestContainsUnknownEmployeeAndDoesNotPartiallyApply() throws Exception {
        Long carolId = state.employeeIdsByName().get("Carol");
        Long frankId = state.employeeIdsByName().get("Frank");

        ResponseEntity<String> response = rest.getRestTemplate().exchange(
                URI.create(url("/employees/actions/bulk-approve")),
                HttpMethod.POST,
                jsonEntity("{\"employeeIds\":[" + carolId + "," + frankId + ",999999],\"comentario\":\"Lote parcial\"}"),
                String.class
        );

        assertEquals(404, response.getStatusCode().value());
        JsonNode error = body(response);
        assertEquals("failure", error.path("status").asText());
        assertEquals("Resource not found.", error.path("message").asText());
        assertEquals("RESOURCE_NOT_FOUND", error.path("errors").get(0).path("properties").path("code").asText());
        assertEquals("INACTIVE", body(get("/employees/" + carolId)).path("data").path("status").asText());
        assertEquals("LEAVE", body(get("/employees/" + frankId)).path("data").path("status").asText());
    }
}
