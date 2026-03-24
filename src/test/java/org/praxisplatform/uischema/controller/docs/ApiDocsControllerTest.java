package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ApiDocsControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ApiDocsController controller;
    private String openApiDoc;
    
    @Mock
    private OpenApiGroupResolver openApiGroupResolver;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        controller = new ApiDocsController();
        OpenApiDocsSupport openApiDocsSupport = new OpenApiDocsSupport();
        ReflectionTestUtils.setField(openApiDocsSupport, "openApiGroupResolver", openApiGroupResolver);
        ReflectionTestUtils.setField(controller, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "openApiDocsSupport", openApiDocsSupport);
        ReflectionTestUtils.setField(controller, "OPEN_API_BASE_PATH", "/v3/api-docs");
        openApiDoc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/users\": {\n" +
                "      \"post\": {\n" +
                "        \"x-ui\": {\"responseSchema\": \"UserResponse\"},\n" +
                "        \"requestBody\": {\n" +
                "          \"content\": {\n" +
                "            \"application/json\": {\n" +
                "              \"schema\": {\"$ref\": \"#/components/schemas/UserRequest\"}\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  \"components\": {\n" +
                "    \"schemas\": {\n" +
                "      \"UserRequest\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\"name\": {\"type\": \"string\"}}\n" +
                "      },\n" +
                "      \"UserResponse\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\"email\": {\"type\": \"string\"}}\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
    }

    @Test
    void findRequestSchemaExtractsName() throws Exception {
        String json = "{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/TestDTO\"}}}}}";
        JsonNode node = mapper.readTree(json);
        assertEquals("TestDTO", controller.findRequestSchema(node));
    }

    @Test
    void getFilteredSchemaSelectsSchemas() throws Exception {
        // Mock para o OpenApiGroupResolver não retornar nada, forçando derivação do path
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        
        // Mock para resolver grupo baseado no path "/users" → derivação = "users"  
        server.expect(requestTo("http://localhost/v3/api-docs/users"))
                .andRespond(withSuccess(openApiDoc, MediaType.APPLICATION_JSON));
        var req1 = new MockHttpServletRequest();
        req1.setScheme("http");
        req1.setServerName("localhost");
        req1.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req1));

        var rReq = controller.getFilteredSchema("/users", "post", false, "request", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> requestSchema = rReq.getBody();
        assertNotNull(requestSchema);
        assertTrue(((Map<?,?>) requestSchema.get("properties")).containsKey("name"));

        var rRes = controller.getFilteredSchema("/users", "post", false, "response", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> responseSchema = rRes.getBody();
        assertNotNull(responseSchema);
        assertTrue(((Map<?,?>) responseSchema.get("properties")).containsKey("email"));
    }

    @Test 
    void getFilteredSchemaHandlesDirectDtoResponse() throws Exception {
        // Mock para o OpenApiGroupResolver não retornar nada, forçando derivação do path
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/api/ui-test/wrappers\": {\n" +
                "      \"get\": {\n" +
                "        \"responses\": {\n" +
                "          \"200\": {\n" +
                "            \"content\": {\n" +
                "              \"*/*\": {\n" +
                "                \"schema\": {\n" +
                "                  \"$ref\": \"#/components/schemas/UiSchemaTestDTO\"\n" +
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
                "      \"UiSchemaTestDTO\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"textField\": {\n" +
                "            \"type\": \"string\"\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        // Mock para resolver grupo baseado no path "/api/ui-test/wrappers" → "api-ui-test-wrappers"
        server.expect(requestTo("http://localhost/v3/api-docs/api-ui-test-wrappers"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));
        var req2 = new MockHttpServletRequest();
        req2.setScheme("http");
        req2.setServerName("localhost");
        req2.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req2));

        var r = controller.getFilteredSchema(
                "/api/ui-test/wrappers",
                "get",
                false,
                "response",
                null,
                null,
                java.util.Locale.ENGLISH);

        Map<String, Object> responseSchema = r.getBody();
        assertNotNull(responseSchema);
        assertTrue(((Map<?, ?>) responseSchema.get("properties")).containsKey("textField"));
        server.verify();
    }

    @Test
    void invalidSchemaTypeThrowsException() {
        // Mock para o OpenApiGroupResolver não retornar nada, forçando derivação do path
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        
        server.expect(requestTo("http://localhost/v3/api-docs/users"))
                .andRespond(withSuccess(openApiDoc, MediaType.APPLICATION_JSON));
        var req3 = new MockHttpServletRequest();
        req3.setScheme("http");
        req3.setServerName("localhost");
        req3.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req3));
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> controller.getFilteredSchema("/users", "post", false, "unknown", null, null, java.util.Locale.ENGLISH)
        );

        assertEquals("Parameter 'schemaType' must be 'response' or 'request'.", exception.getMessage());
    }

    @Test
    void getFilteredSchemaIncludesOperationExamplesForRequestedSchemaType() throws Exception {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/stats\": {\n" +
                "      \"post\": {\n" +
                "        \"requestBody\": {\n" +
                "          \"content\": {\n" +
                "            \"application/json\": {\n" +
                "              \"schema\": {\"$ref\": \"#/components/schemas/StatsRequest\"},\n" +
                "              \"examples\": {\n" +
                "                \"timeseries\": {\n" +
                "                  \"summary\": \"Time-series\",\n" +
                "                  \"value\": {\"field\": \"createdOn\", \"granularity\": \"DAY\"}\n" +
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
                "                  \"timeseriesResult\": {\n" +
                "                    \"summary\": \"Result\",\n" +
                "                    \"value\": {\"data\": {\"points\": []}}\n" +
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

        server.expect(requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));
        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var response = controller.getFilteredSchema("/stats", "post", false, "response", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> schema = response.getBody();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> xUi = (Map<String, Object>) schema.get("x-ui");
        assertNotNull(xUi);

        @SuppressWarnings("unchecked")
        Map<String, Object> operationExamples = (Map<String, Object>) xUi.get("operationExamples");
        assertNotNull(operationExamples);
        assertTrue(operationExamples.containsKey("response"));
        assertFalse(operationExamples.containsKey("request"));

        @SuppressWarnings("unchecked")
        Map<String, Object> responseExamples = (Map<String, Object>) operationExamples.get("response");
        assertTrue(responseExamples.containsKey("timeseriesResult"));
    }

    @Test
    void getFilteredSchemaResolvesStatsResponseFromRestApiResponseWrapper() throws Exception {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/stats/group-by\": {\n" +
                "      \"post\": {\n" +
                "        \"responses\": {\n" +
                "          \"200\": {\n" +
                "            \"content\": {\n" +
                "              \"application/json\": {\n" +
                "                \"schema\": {\"$ref\": \"#/components/schemas/RestApiResponseGroupByStatsResponse\"},\n" +
                "                \"examples\": {\n" +
                "                  \"groupByCountResponse\": {\n" +
                "                    \"summary\": \"Buckets\",\n" +
                "                    \"value\": {\"status\": \"success\", \"data\": {\"field\": \"status\", \"buckets\": []}}\n" +
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
                "      \"RestApiResponseGroupByStatsResponse\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"status\": {\"type\": \"string\"},\n" +
                "          \"data\": {\"$ref\": \"#/components/schemas/GroupByStatsResponse\"}\n" +
                "        }\n" +
                "      },\n" +
                "      \"GroupByStatsResponse\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"field\": {\"type\": \"string\"},\n" +
                "          \"buckets\": {\n" +
                "            \"type\": \"array\",\n" +
                "            \"items\": {\"$ref\": \"#/components/schemas/GroupByStatsBucket\"}\n" +
                "          }\n" +
                "        }\n" +
                "      },\n" +
                "      \"GroupByStatsBucket\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"label\": {\"type\": \"string\"},\n" +
                "          \"value\": {\"type\": \"number\"}\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        server.expect(requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));
        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var response = controller.getFilteredSchema("/stats/group-by", "post", false, "response", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> schema = response.getBody();
        assertNotNull(schema);
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("field"));
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("buckets"));

        @SuppressWarnings("unchecked")
        Map<String, Object> xUi = (Map<String, Object>) schema.get("x-ui");
        assertNotNull(xUi);

        @SuppressWarnings("unchecked")
        Map<String, Object> operationExamples = (Map<String, Object>) xUi.get("operationExamples");
        assertNotNull(operationExamples);
        assertTrue(operationExamples.containsKey("response"));
    }

    @Test
    void getFilteredSchemaResolvesDistributionResponseFromRestApiResponseWrapper() throws Exception {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn("stats");
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/stats/distribution\": {\n" +
                "      \"post\": {\n" +
                "        \"responses\": {\n" +
                "          \"200\": {\n" +
                "            \"content\": {\n" +
                "              \"application/json\": {\n" +
                "                \"schema\": {\"$ref\": \"#/components/schemas/RestApiResponseDistributionStatsResponse\"},\n" +
                "                \"examples\": {\n" +
                "                  \"distributionResponse\": {\n" +
                "                    \"summary\": \"Distribution\",\n" +
                "                    \"value\": {\"status\": \"success\", \"data\": {\"field\": \"salario\", \"buckets\": []}}\n" +
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
                "      \"RestApiResponseDistributionStatsResponse\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"status\": {\"type\": \"string\"},\n" +
                "          \"data\": {\"$ref\": \"#/components/schemas/DistributionStatsResponse\"}\n" +
                "        }\n" +
                "      },\n" +
                "      \"DistributionStatsResponse\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"field\": {\"type\": \"string\"},\n" +
                "          \"buckets\": {\"type\": \"array\", \"items\": {\"type\": \"object\"}}\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        server.expect(requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));
        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var response = controller.getFilteredSchema("/stats/distribution", "post", false, "response", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> schema = response.getBody();
        assertNotNull(schema);
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("field"));
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("buckets"));
    }

    @Test
    void getFilteredSchemaIncludesRequestExamplesWithFallbackMediaType() throws Exception {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/stats\": {\n" +
                "      \"post\": {\n" +
                "        \"requestBody\": {\n" +
                "          \"content\": {\n" +
                "            \"*/*\": {\n" +
                "              \"schema\": {\"$ref\": \"#/components/schemas/StatsRequest\"},\n" +
                "              \"examples\": {\n" +
                "                \"requestExample\": {\n" +
                "                  \"summary\": \"Fallback request\",\n" +
                "                  \"value\": {\"field\": \"createdOn\"}\n" +
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
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        server.expect(requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));
        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var response = controller.getFilteredSchema("/stats", "post", false, "request", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> schema = response.getBody();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> xUi = (Map<String, Object>) schema.get("x-ui");
        assertNotNull(xUi);

        @SuppressWarnings("unchecked")
        Map<String, Object> operationExamples = (Map<String, Object>) xUi.get("operationExamples");
        assertNotNull(operationExamples);
        assertTrue(operationExamples.containsKey("request"));
        assertFalse(operationExamples.containsKey("response"));

        @SuppressWarnings("unchecked")
        Map<String, Object> requestExamples = (Map<String, Object>) operationExamples.get("request");
        @SuppressWarnings("unchecked")
        Map<String, Object> requestExample = (Map<String, Object>) requestExamples.get("requestExample");
        assertEquals("Fallback request", requestExample.get("summary"));
    }

    @Test
    void getFilteredSchemaPrefersExplicitOperationExamplesFromXUi() throws Exception {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/stats\": {\n" +
                "      \"post\": {\n" +
                "        \"x-ui\": {\n" +
                "          \"responseSchema\": \"StatsResponse\",\n" +
                "          \"operationExamples\": {\n" +
                "            \"response\": {\n" +
                "              \"canonical\": {\n" +
                "                \"summary\": \"Resource specific\",\n" +
                "                \"value\": {\"data\": {\"buckets\": [{\"label\": \"gold\"}]}}\n" +
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
                "                  \"derived\": {\n" +
                "                    \"summary\": \"Derived\",\n" +
                "                    \"value\": {\"data\": {\"buckets\": []}}\n" +
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
                "      \"StatsResponse\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\"data\": {\"type\": \"object\"}}\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        server.expect(requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));
        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var response = controller.getFilteredSchema("/stats", "post", false, "response", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> schema = response.getBody();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> xUi = (Map<String, Object>) schema.get("x-ui");
        @SuppressWarnings("unchecked")
        Map<String, Object> operationExamples = (Map<String, Object>) xUi.get("operationExamples");
        @SuppressWarnings("unchecked")
        Map<String, Object> responseExamples = (Map<String, Object>) operationExamples.get("response");

        assertTrue(responseExamples.containsKey("canonical"));
        assertTrue(responseExamples.containsKey("derived"));

        @SuppressWarnings("unchecked")
        Map<String, Object> canonical = (Map<String, Object>) responseExamples.get("canonical");
        assertEquals("Resource specific", canonical.get("summary"));
    }

    @Test
    void getFilteredSchemaPreservesExternalValueExamples() throws Exception {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/stats\": {\n" +
                "      \"post\": {\n" +
                "        \"requestBody\": {\n" +
                "          \"content\": {\n" +
                "            \"application/json\": {\n" +
                "              \"schema\": {\"$ref\": \"#/components/schemas/StatsRequest\"},\n" +
                "              \"examples\": {\n" +
                "                \"linked\": {\n" +
                "                  \"summary\": \"Arquivo externo\",\n" +
                "                  \"externalValue\": \"https://example.org/examples/stats-request.json\"\n" +
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
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        server.expect(requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));
        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var response = controller.getFilteredSchema("/stats", "post", false, "request", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> schema = response.getBody();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> xUi = (Map<String, Object>) schema.get("x-ui");
        @SuppressWarnings("unchecked")
        Map<String, Object> operationExamples = (Map<String, Object>) xUi.get("operationExamples");
        @SuppressWarnings("unchecked")
        Map<String, Object> requestExamples = (Map<String, Object>) operationExamples.get("request");
        @SuppressWarnings("unchecked")
        Map<String, Object> linkedExample = (Map<String, Object>) requestExamples.get("linked");

        assertEquals("Arquivo externo", linkedExample.get("summary"));
        assertEquals("https://example.org/examples/stats-request.json", linkedExample.get("externalValue"));
        assertFalse(linkedExample.containsKey("value"));
    }

    @Test
    void getFilteredSchemaResolvesIdFieldFromCanonicalResourceResponseForRequestSchema() throws Exception {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);
        String doc = "{\n" +
                "  \"paths\": {\n" +
                "    \"/api/catalog/produtos/filter\": {\n" +
                "      \"post\": {\n" +
                "        \"requestBody\": {\n" +
                "          \"content\": {\n" +
                "            \"application/json\": {\n" +
                "              \"schema\": {\"$ref\": \"#/components/schemas/ProdutoFilterDTO\"}\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/api/catalog/produtos/{id}\": {\n" +
                "      \"get\": {\n" +
                "        \"responses\": {\n" +
                "          \"200\": {\n" +
                "            \"content\": {\n" +
                "              \"application/json\": {\n" +
                "                \"schema\": {\"$ref\": \"#/components/schemas/ProdutoDTO\"}\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  \"components\": {\n" +
                "    \"schemas\": {\n" +
                "      \"ProdutoFilterDTO\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"categoriaId\": {\"type\": \"integer\"},\n" +
                "          \"nome\": {\"type\": \"string\"}\n" +
                "        }\n" +
                "      },\n" +
                "      \"ProdutoDTO\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"id\": {\"type\": \"integer\"},\n" +
                "          \"categoriaId\": {\"type\": \"integer\"},\n" +
                "          \"nome\": {\"type\": \"string\"}\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        server.expect(requestTo("http://localhost/v3/api-docs/api-catalog-produtos"))
                .andRespond(withSuccess(doc, MediaType.APPLICATION_JSON));
        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var response = controller.getFilteredSchema(
                "/api/catalog/produtos/filter",
                "post",
                false,
                "request",
                null,
                null,
                java.util.Locale.ENGLISH);

        Map<String, Object> schema = response.getBody();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> xUi = (Map<String, Object>) schema.get("x-ui");
        assertNotNull(xUi);

        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) xUi.get("resource");
        assertNotNull(resource);
        assertEquals("id", resource.get("idField"));
        assertEquals(Boolean.FALSE, resource.get("idFieldValid"));
        assertEquals("idField not found in schema properties", resource.get("idFieldMessage"));
    }
}
