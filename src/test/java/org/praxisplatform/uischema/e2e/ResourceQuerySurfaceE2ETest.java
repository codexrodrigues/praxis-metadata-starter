package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceQuerySurfaceE2ETest extends AbstractE2eH2Test {

    private static final String TEST_PRINCIPAL_HEADER = "X-Test-Principal";

    @Test
    void filterRespectsDefaultSortAndIncludeIdsOnlyOnFirstPage() throws Exception {
        ResponseEntity<String> firstPageResponse = postJson("/employees/filter?page=0&size=2", "{}");
        assertEquals(200, firstPageResponse.getStatusCode().value());
        assertEquals("e2e", firstPageResponse.getHeaders().getFirst("X-Data-Version"));
        JsonNode firstPageBody = body(firstPageResponse);
        assertEquals(List.of("Alice", "Bob"), names(firstPageBody.path("data").path("content")));
        assertEquals(6, firstPageBody.path("data").path("totalElements").asInt());

        Long frankId = state.employeeIdsByName().get("Frank");
        Long carolId = state.employeeIdsByName().get("Carol");

        ResponseEntity<String> includeFirstPageResponse = postJson(
                "/employees/filter?page=0&size=2&includeIds=%d&includeIds=%d".formatted(frankId, carolId),
                "{}"
        );
        assertEquals(200, includeFirstPageResponse.getStatusCode().value());
        JsonNode includeFirstPageBody = body(includeFirstPageResponse);
        List<String> includeFirstPageNames = names(includeFirstPageBody.path("data").path("content"));
        assertEquals(List.of("Frank", "Carol"), includeFirstPageNames.subList(0, 2));
        assertTrue(includeFirstPageNames.contains("Alice"));
        assertTrue(includeFirstPageNames.contains("Bob"));

        ResponseEntity<String> includeSecondPageResponse = postJson(
                "/employees/filter?page=1&size=2&includeIds=%d&includeIds=%d".formatted(frankId, carolId),
                "{}"
        );
        assertEquals(200, includeSecondPageResponse.getStatusCode().value());
        JsonNode includeSecondPageBody = body(includeSecondPageResponse);
        List<String> secondPageNames = names(includeSecondPageBody.path("data").path("content"));
        assertEquals(List.of("Diana"), secondPageNames);
        assertFalse(secondPageNames.contains("Frank"));
        assertFalse(secondPageNames.contains("Carol"));
    }

    @Test
    void cursorAndLocateSupportStableNavigationForThePilotResource() throws Exception {
        ResponseEntity<String> firstCursorResponse = postJson("/employees/filter/cursor?size=2", "{}");
        assertEquals(200, firstCursorResponse.getStatusCode().value());
        JsonNode firstCursorBody = body(firstCursorResponse);
        assertEquals(List.of("Alice", "Bob"), names(firstCursorBody.path("data").path("content")));
        String next = firstCursorBody.path("data").path("next").asText();
        assertFalse(next.isBlank());
        assertTrue(firstCursorBody.path("data").path("prev").isMissingNode()
                || firstCursorBody.path("data").path("prev").isNull()
                || firstCursorBody.path("data").path("prev").asText("").isBlank());

        ResponseEntity<String> secondCursorResponse = postJson(
                "/employees/filter/cursor?size=2&after=" + URLEncoder.encode(next, StandardCharsets.UTF_8),
                "{}"
        );
        assertEquals(200, secondCursorResponse.getStatusCode().value());
        JsonNode secondCursorBody = body(secondCursorResponse);
        assertEquals(List.of("Carol", "Diana"), names(secondCursorBody.path("data").path("content")));
        String prev = secondCursorBody.path("data").path("prev").asText();
        assertFalse(prev.isBlank());

        ResponseEntity<String> previousCursorResponse = postJson(
                "/employees/filter/cursor?size=2&before=" + URLEncoder.encode(prev, StandardCharsets.UTF_8),
                "{}"
        );
        assertEquals(200, previousCursorResponse.getStatusCode().value());
        JsonNode previousCursorBody = body(previousCursorResponse);
        assertEquals(List.of("Alice", "Bob"), names(previousCursorBody.path("data").path("content")));

        Long dianaId = state.employeeIdsByName().get("Diana");
        ResponseEntity<String> locateResponse = postJson("/employees/locate?id=" + dianaId + "&size=2", "{}");
        assertEquals(200, locateResponse.getStatusCode().value());
        JsonNode locateBody = body(locateResponse);
        assertEquals(3L, locateBody.path("position").asLong());
        assertEquals(1L, locateBody.path("page").asLong());
    }

    @Test
    void byIdsPreservesTheRequestedClientOrder() throws Exception {
        Long frankId = state.employeeIdsByName().get("Frank");
        Long bobId = state.employeeIdsByName().get("Bob");
        Long aliceId = state.employeeIdsByName().get("Alice");

        ResponseEntity<String> byIdsResponse = get(
                "/employees/by-ids?ids=%d&ids=%d&ids=%d".formatted(frankId, bobId, aliceId)
        );
        assertEquals(200, byIdsResponse.getStatusCode().value());
        assertEquals("e2e", byIdsResponse.getHeaders().getFirst("X-Data-Version"));
        JsonNode byIdsBody = body(byIdsResponse);
        assertEquals(List.of("Frank", "Bob", "Alice"), names(byIdsBody));
    }

    @Test
    void resourceFilterComposesAccessScopeWithFunctionalFilterAndIncludeIds() throws Exception {
        Long authorizedOutsideFilter = state.employeeIdsByName().get("Carol");
        Long unauthorizedOutsideScope = state.employeeIdsByName().get("Bob");

        ResponseEntity<String> scopedResponse = postJsonAs(
                "/employees/filter?page=0&size=10&includeIds=%d&includeIds=%d"
                        .formatted(authorizedOutsideFilter, unauthorizedOutsideScope),
                "{\"status\":\"ACTIVE\"}",
                "hr-resource-user"
        );
        assertEquals(200, scopedResponse.getStatusCode().value());
        JsonNode scopedBody = body(scopedResponse).path("data");
        assertEquals(List.of("Carol", "Alice", "Eve"), names(scopedBody.path("content")));
        assertEquals(3, scopedBody.path("totalElements").asInt());

        ResponseEntity<String> noIncludeIdsResponse = postJsonAs(
                "/employees/filter?page=0&size=10",
                "{\"status\":\"ACTIVE\"}",
                "hr-resource-user"
        );
        JsonNode noIncludeIdsBody = body(noIncludeIdsResponse).path("data");
        assertEquals(List.of("Alice", "Eve"), names(noIncludeIdsBody.path("content")));
        assertEquals(2, noIncludeIdsBody.path("totalElements").asInt());

        ResponseEntity<String> deniedResponse = postJsonAs(
                "/employees/filter?page=0&size=10",
                "{}",
                "blocked-resource-user"
        );
        JsonNode deniedBody = body(deniedResponse).path("data");
        assertTrue(deniedBody.path("content").isEmpty());
        assertEquals(0, deniedBody.path("totalElements").asInt());

        ResponseEntity<String> unrestrictedResponse = postJson("/employees/filter?page=0&size=10", "{}");
        assertEquals(6, body(unrestrictedResponse).path("data").path("totalElements").asInt());
    }

    @Test
    void readOnlyResourceFilterAppliesTheSameAccessBoundaryToIncludeIds() throws Exception {
        Long authorizedOutsideFilter = state.payrollIdsByEmployee().get("Carol");
        Long unauthorizedOutsideScope = state.payrollIdsByEmployee().get("Bob");

        ResponseEntity<String> response = postJsonAs(
                "/payroll-view/filter?page=0&size=10&includeIds=%d&includeIds=%d"
                        .formatted(authorizedOutsideFilter, unauthorizedOutsideScope),
                "{\"employeeNome\":\"Alice\"}",
                "hr-resource-user"
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode data = body(response).path("data");
        assertEquals(List.of("Carol", "Alice"), employeeNames(data.path("content")));
        assertEquals(2, data.path("totalElements").asInt());
    }

    private List<String> names(JsonNode nodes) {
        assertNotNull(nodes);
        return java.util.stream.StreamSupport.stream(nodes.spliterator(), false)
                .map(node -> node.path("nome").asText())
                .toList();
    }

    private List<String> employeeNames(JsonNode nodes) {
        assertNotNull(nodes);
        return java.util.stream.StreamSupport.stream(nodes.spliterator(), false)
                .map(node -> node.path("employeeNome").asText())
                .toList();
    }

    private ResponseEntity<String> postJsonAs(String path, String requestBody, String principalName) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TEST_PRINCIPAL_HEADER, principalName);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.getRestTemplate().exchange(
                URI.create(url(path)),
                HttpMethod.POST,
                new HttpEntity<>(requestBody, headers),
                String.class
        );
    }
}
