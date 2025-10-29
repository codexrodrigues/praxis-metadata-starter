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
        ReflectionTestUtils.setField(controller, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "openApiGroupResolver", openApiGroupResolver);
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
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        var rReq = controller.getFilteredSchema("/users", "post", false, "request", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> requestSchema = rReq.getBody();
        assertNotNull(requestSchema);
        assertTrue(((Map<?,?>) requestSchema.get("properties")).containsKey("name"));

        server.reset();
        server.expect(requestTo("http://localhost/v3/api-docs/users"))
                .andRespond(withSuccess(openApiDoc, MediaType.APPLICATION_JSON));

        var rRes = controller.getFilteredSchema("/users", "post", false, "response", null, null, java.util.Locale.ENGLISH);
        Map<String, Object> responseSchema = rRes.getBody();
        assertNotNull(responseSchema);
        assertTrue(((Map<?,?>) responseSchema.get("properties")).containsKey("email"));

        server.verify();
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
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

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
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThrows(IllegalArgumentException.class,
                () -> controller.getFilteredSchema("/users", "post", false, "unknown", null, null, java.util.Locale.ENGLISH));
    }
}
