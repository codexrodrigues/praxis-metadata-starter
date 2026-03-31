package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "praxis.query.by-ids.max=3",
        "praxis.pagination.max-size=2"
})
class LimitsAndErrorsE2ETest extends AbstractE2eH2Test {

    @Test
    void defensiveContractsReturnStableHttpSemantics() throws Exception {
        ResponseEntity<String> byIdsLimitResponse = get("/employees/by-ids?ids=1&ids=2&ids=3&ids=4");
        assertErrorStatus(byIdsLimitResponse.getStatusCode(), 422);
        JsonNode byIdsError = body(byIdsLimitResponse);
        assertContainsReason(byIdsError, "Maximum number of IDs exceeded");
        assertNoStackTrace(byIdsError);

        ResponseEntity<String> paginationLimitResponse = postJson("/employees/filter?size=3", """
                {
                  "nome": "a"
                }
                """);
        assertErrorStatus(paginationLimitResponse.getStatusCode(), 422);
        JsonNode paginationError = body(paginationLimitResponse);
        assertContainsReason(paginationError, "Maximum page size exceeded");
        assertNoStackTrace(paginationError);

        ResponseEntity<String> invalidSchemaTypeResponse = get("/schemas/filtered?path=/employees&operation=post&schemaType=invalid");
        assertEquals(400, invalidSchemaTypeResponse.getStatusCode().value());
        JsonNode invalidSchemaTypeBody = body(invalidSchemaTypeResponse);
        assertContainsReason(invalidSchemaTypeBody, "schemaType");
        assertNoStackTrace(invalidSchemaTypeBody);

        ResponseEntity<String> missingPathResponse = get("/schemas/filtered?path=/does-not-exist&operation=get&schemaType=response");
        assertEquals(404, missingPathResponse.getStatusCode().value());
        JsonNode missingPathBody = body(missingPathResponse);
        assertNoStackTrace(missingPathBody);

        ResponseEntity<String> unsupportedCursorResponse = postJson("/payroll-view/filter/cursor?size=2", """
                {
                  "employeeNome": "Alice"
                }
                """);
        assertEquals(501, unsupportedCursorResponse.getStatusCode().value());
        JsonNode unsupportedCursorBody = body(unsupportedCursorResponse);
        assertContainsReason(unsupportedCursorBody, "Not implemented");
        assertNoStackTrace(unsupportedCursorBody);

        ResponseEntity<String> unsupportedLocateResponse = postJson(
                "/payroll-view/locate?id=" + state.payrollIdsByEmployee().get("Alice") + "&size=2",
                """
                        {
                          "employeeNome": "Alice"
                        }
                        """
        );
        assertEquals(501, unsupportedLocateResponse.getStatusCode().value());
        JsonNode unsupportedLocateBody = body(unsupportedLocateResponse);
        assertContainsReason(unsupportedLocateBody, "Not implemented");
        assertNoStackTrace(unsupportedLocateBody);

        ResponseEntity<String> missingLocateResponse = postJson("/employees/locate?id=999999&size=2", "{}");
        assertEquals(404, missingLocateResponse.getStatusCode().value());
        JsonNode missingLocateBody = body(missingLocateResponse);
        assertContainsReason(missingLocateBody, "Registro nao encontrado");
        assertNoStackTrace(missingLocateBody);
    }

    private void assertErrorStatus(HttpStatusCode statusCode, int expected) {
        assertEquals(expected, statusCode.value());
    }

    private void assertContainsReason(JsonNode errorBody, String expectedFragment) {
        String message = errorBody.path("message").asText("");
        String detail = errorBody.path("errors").isArray() && !errorBody.path("errors").isEmpty()
                ? errorBody.path("errors").get(0).path("message").asText("")
                : "";
        assertTrue(message.contains(expectedFragment) || detail.contains(expectedFragment));
    }

    private void assertNoStackTrace(JsonNode errorBody) {
        String payload = errorBody.toString().toLowerCase();
        assertFalse(payload.contains("stacktrace"));
        assertFalse(payload.contains("exception"));
    }
}
