package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyResourceE2ETest extends AbstractE2eH2Test {

    @Test
    void readOnlyResourcePublishesOnlyQuerySurface() throws Exception {
        Long payrollId = state.payrollIdsByEmployee().get("Alice");

        ResponseEntity<String> allResponse = get("/payroll-view/all");
        assertEquals(200, allResponse.getStatusCode().value());
        assertEquals("e2e", allResponse.getHeaders().getFirst("X-Data-Version"));
        JsonNode allBody = body(allResponse);
        assertEquals(4, allBody.path("data").size());

        ResponseEntity<String> byIdResponse = get("/payroll-view/" + payrollId);
        assertEquals(200, byIdResponse.getStatusCode().value());
        JsonNode byIdBody = body(byIdResponse);
        assertEquals(payrollId.longValue(), byIdBody.path("data").path("id").asLong());
        assertTrue(byIdResponse.getBody().contains("/payroll-view/" + payrollId));
        assertFalse(byIdResponse.getBody().contains("/payroll-view/batch"));
        assertFalse(byIdResponse.getBody().contains("\"rel\":\"create\""));
        assertFalse(byIdResponse.getBody().contains("\"rel\":\"update\""));
        assertFalse(byIdResponse.getBody().contains("\"rel\":\"delete\""));

        ResponseEntity<String> postResponse = postJson("/payroll-view", """
                {
                  "employeeNome": "Injected"
                }
                """);
        assertTrue(postResponse.getStatusCode().value() == 404 || postResponse.getStatusCode().value() == 405);

        ResponseEntity<String> putResponse = putJson("/payroll-view/" + payrollId, """
                {
                  "employeeNome": "Injected"
                }
                """);
        assertEquals(405, putResponse.getStatusCode().value());

        ResponseEntity<String> deleteResponse = delete("/payroll-view/" + payrollId);
        assertEquals(405, deleteResponse.getStatusCode().value());

        ResponseEntity<String> schemaResponse = get("/schemas/filtered?path=/payroll-view/all&operation=get&schemaType=response");
        assertEquals(200, schemaResponse.getStatusCode().value());
        JsonNode schemaBody = body(schemaResponse);
        assertTrue(schemaBody.path("x-ui").path("resource").path("readOnly").asBoolean());
    }
}
