package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DomainCatalogControllerTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private DomainCatalogController controller;

    @Mock
    private OpenApiGroupResolver openApiGroupResolver;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        controller = new DomainCatalogController();
        ReflectionTestUtils.setField(controller, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(controller, "openApiGroupResolver", openApiGroupResolver);
        ReflectionTestUtils.setField(controller, "openApiBasePath", "/v3/api-docs");
        ReflectionTestUtils.setField(controller, "excludedPathsRaw", "");
        controller.initExcludedPaths();
    }

    @Test
    void getCatalogIncludesOperationExamplesAndSchemaLinks() {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/stats\": {\n" +
                "      \"post\": {\n" +
                "        \"summary\": \"Stats endpoint\",\n" +
                "        \"requestBody\": {\n" +
                "          \"content\": {\n" +
                "            \"application/json\": {\n" +
                "              \"schema\": {\"$ref\": \"#/components/schemas/StatsRequest\"},\n" +
                "              \"examples\": {\n" +
                "                \"requestExample\": {\n" +
                "                  \"summary\": \"Request example\",\n" +
                "                  \"value\": {\"field\": \"status\"}\n" +
                "                }\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        },\n" +
                "        \"responses\": {\n" +
                "          \"200\": {\n" +
                "            \"content\": {\n" +
                "              \"application/json\": {\n" +
                "                \"schema\": {\"$ref\": \"#/components/schemas/StatsResponse\"},\n" +
                "                \"examples\": {\n" +
                "                  \"responseExample\": {\n" +
                "                    \"summary\": \"Response example\",\n" +
                "                    \"externalValue\": \"https://example.org/examples/stats-response.json\"\n" +
                "                  }\n" +
                "                }\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  \"components\": {\n" +
                "    \"schemas\": {\n" +
                "      \"StatsRequest\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\"field\": {\"type\": \"string\"}}\n" +
                "      },\n" +
                "      \"StatsResponse\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\"data\": {\"type\": \"object\"}}\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        server.expect(requestTo("http://localhost/v3/api-docs"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));

        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var response = controller.getCatalog(null, null, null);
        assertEquals(200, response.getStatusCodeValue());
        DomainCatalogController.CatalogResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.getEndpoints().size());

        DomainCatalogController.EndpointSummary endpoint = body.getEndpoints().get(0);
        assertEquals("/stats", endpoint.getPath());
        assertEquals("POST", endpoint.getMethod());

        Map<String, Map<String, Object>> examples = endpoint.getOperationExamples();
        assertTrue(examples.containsKey("request"));
        assertTrue(examples.containsKey("response"));
        assertEquals("Request example", ((Map<?, ?>) examples.get("request").get("requestExample")).get("summary"));
        assertEquals(
                "https://example.org/examples/stats-response.json",
                ((Map<?, ?>) examples.get("response").get("responseExample")).get("externalValue")
        );

        assertEquals("/schemas/filtered?path=%2Fstats&operation=post&schemaType=request", endpoint.getSchemaLinks().getRequest());
        assertEquals("/schemas/filtered?path=%2Fstats&operation=post&schemaType=response", endpoint.getSchemaLinks().getResponse());
    }

    @Test
    void getCatalogFiltersByPathAndOperation() {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/stats\": {\n" +
                "      \"post\": {\n" +
                "        \"summary\": \"Stats endpoint\",\n" +
                "        \"responses\": {\"200\": {\"content\": {\"application/json\": {\"schema\": {\"type\": \"object\"}}}}}\n" +
                "      }\n" +
                "    },\n" +
                "    \"/users\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"Users endpoint\",\n" +
                "        \"responses\": {\"200\": {\"content\": {\"application/json\": {\"schema\": {\"type\": \"object\"}}}}}\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        server.expect(requestTo("http://localhost/v3/api-docs"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));

        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var response = controller.getCatalog(null, "/stats", "post");
        DomainCatalogController.CatalogResponse body = response.getBody();
        assertNotNull(body);

        List<DomainCatalogController.EndpointSummary> endpoints = body.getEndpoints();
        assertEquals(1, endpoints.size());
        assertEquals("/stats", endpoints.get(0).getPath());
        assertEquals("POST", endpoints.get(0).getMethod());
    }
}
