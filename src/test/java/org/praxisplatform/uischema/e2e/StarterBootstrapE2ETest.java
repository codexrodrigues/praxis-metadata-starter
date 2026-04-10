package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.e2e.fixture.E2eBootstrapFixtureApplication;
import org.praxisplatform.uischema.e2e.fixture.E2eFixtureDataSupport;
import org.praxisplatform.uischema.options.OptionSourceEligibility;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.service.OptionSourceQueryExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = E2eBootstrapFixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e-h2")
class StarterBootstrapE2ETest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private E2eFixtureDataSupport fixtureData;

    private E2eFixtureDataSupport.SeedState state;

    @BeforeEach
    void resetFixture() {
        state = fixtureData.resetDefaultData();
    }

    @Test
    void starterBootstrapsCanonicalDocsAndNewControllersWithoutManualCoreWiring() throws Exception {
        assertTrue(applicationContext.getBeansOfType(E2eFixtureDataSupport.class).containsKey("e2eFixtureDataSupport"));
        assertEquals(1, applicationContext.getBeansOfType(OptionSourceQueryExecutor.class).size());
        assertEquals(1, applicationContext.getBeansOfType(OptionSourceEligibility.class).size());
        assertEquals(1, applicationContext.getBeansOfType(OptionSourceRegistry.class).size());

        ResponseEntity<String> employeesAllResponse = get("/employees/all");
        assertEquals(200, employeesAllResponse.getStatusCode().value());
        assertEquals("e2e", employeesAllResponse.getHeaders().getFirst("X-Data-Version"));
        assertEquals(6, fixtureData.employeeCount());
        JsonNode employeesAllBody = body(employeesAllResponse);
        assertEquals(6, employeesAllBody.path("data").size());
        assertEquals("Alice", employeesAllBody.path("data").get(0).path("nome").asText());

        ResponseEntity<String> filteredSchemaResponse = get(
                "/schemas/filtered?path=/employees/all&operation=get&schemaType=response"
        );
        assertEquals(200, filteredSchemaResponse.getStatusCode().value());
        assertNotNull(filteredSchemaResponse.getHeaders().getETag());
        JsonNode filteredSchemaBody = body(filteredSchemaResponse);
        assertEquals("id", filteredSchemaBody.path("x-ui").path("resource").path("idField").asText());
        assertFalse(filteredSchemaBody.path("x-ui").path("resource").path("readOnly").asBoolean());

        ResponseEntity<String> optionSourceResponse = postJson(
                "/employees/option-sources/payrollProfile/options/filter?page=0&size=10",
                """
                {
                  "departmentId": %d
                }
                """.formatted(state.humanResourcesDepartmentId())
        );
        assertEquals(200, optionSourceResponse.getStatusCode().value());
        JsonNode optionSourceBody = body(optionSourceResponse);
        assertEquals(2, optionSourceBody.path("content").size());
        assertEquals("EXEC", optionSourceBody.path("content").get(0).path("id").asText());

        ResponseEntity<String> patchProfileResponse = patchJson(
                "/employees/" + state.employeeIdsByName().get("Alice") + "/profile",
                """
                {
                  "nome": "Alice Bootstrap"
                }
                """
        );
        assertEquals(200, patchProfileResponse.getStatusCode().value());
        JsonNode patchProfileBody = body(patchProfileResponse);
        assertEquals("Alice Bootstrap", patchProfileBody.path("data").path("nome").asText());

        ResponseEntity<String> patchSchemaResponse = get(
                "/schemas/filtered?path=/employees/%7Bid%7D/profile&operation=patch&schemaType=request"
        );
        assertEquals(200, patchSchemaResponse.getStatusCode().value());
        JsonNode patchSchemaBody = body(patchSchemaResponse);
        assertEquals("string", patchSchemaBody.path("properties").path("nome").path("type").asText());

        ResponseEntity<String> catalogResponse = get("/schemas/catalog?path=/employees");
        assertEquals(200, catalogResponse.getStatusCode().value());
        JsonNode catalogBody = body(catalogResponse);
        assertEquals("employees", catalogBody.path("group").asText());
        assertTrue(catalogBody.path("endpoints").isArray());
        assertFalse(catalogBody.path("endpoints").isEmpty());
        ResponseEntity<String> openApiGroupResponse = get("/v3/api-docs/employees");
        assertEquals(200, openApiGroupResponse.getStatusCode().value());
        JsonNode openApiGroupBody = body(openApiGroupResponse);
        assertTrue(openApiGroupBody.path("paths").has("/employees"));
        assertTrue(openApiGroupBody.path("paths").has("/employees/all"));
        assertTrue(openApiGroupBody.path("paths").has("/employees/{id}/profile"));
    }

    private ResponseEntity<String> get(String path) {
        return rest.getRestTemplate().getForEntity(URI.create("http://localhost:" + port + path), String.class);
    }

    private ResponseEntity<String> patchJson(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .method(HttpMethod.PATCH.name(), HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            HttpHeaders headers = new HttpHeaders();
            response.headers().map().forEach((key, values) -> headers.put(key, new ArrayList<>(values)));
            return ResponseEntity.status(response.statusCode()).headers(headers).body(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("PATCH helper failed in StarterBootstrapE2ETest", ex);
        }
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(
                URI.create("http://localhost:" + port + path),
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody(), "Expected response body for " + response.getStatusCode());
        return objectMapper.readTree(response.getBody());
    }
}
