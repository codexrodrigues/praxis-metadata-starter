package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Exercises the filtered-schema boundary with serialized OpenAPI 3.0 and 3.1 type values. */
class ApiDocsControllerUnionTypeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ApiDocsController controller;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        OpenApiGroupResolver groups = mock(OpenApiGroupResolver.class);
        when(groups.resolveGroup(anyString())).thenReturn(null);
        OpenApiDocsSupport support = new OpenApiDocsSupport();
        ReflectionTestUtils.setField(support, "openApiGroupResolver", groups);
        ReflectionTestUtils.setField(support, "openApiInternalBaseUrl", "http://localhost");
        CachedOpenApiDocumentService documents = new CachedOpenApiDocumentService(restTemplate, mapper, support);
        ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");
        controller = new ApiDocsController();
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "openApiDocsSupport", support);
        ReflectionTestUtils.setField(controller, "openApiDocumentService", documents);
        ReflectionTestUtils.setField(controller, "canonicalOperationResolver", new OpenApiCanonicalOperationResolver(documents, null));
        ReflectionTestUtils.setField(controller, "schemaReferenceResolver", new FilteredSchemaReferenceResolver());
        ReflectionTestUtils.setField(controller, "canonicalCapabilityResolver", new OpenApiCanonicalCapabilityResolver(documents));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(80);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"array\"|true", "[\"array\"]|true", "[\"null\",\"array\"]|true",
            "[\"array\",\"object\"]|false", "[\"null\"]|false", "[]|false", "[\"array\",3]|false"
    })
    void filteredSchemaPreservesTypesAndOnlyPropagatesUnambiguousArrayEnums(String typeJson, boolean array) throws Exception {
        ObjectNode document = document();
        JsonNode type = mapper.readTree(typeJson);
        ((ObjectNode) document.at("/components/schemas/MissionFilterDTO/properties/status")).set("type", type);
        Map<String, Object> result = filtered(document);
        JsonNode body = mapper.valueToTree(result);
        JsonNode status = body.at("/properties/status");
        assertEquals(type, status.path("type"));
        assertEquals(type, document.at("/components/schemas/MissionFilterDTO/properties/status/type"));
        if (array) {
            assertEquals("chipInput", status.at("/x-ui/controlType").asText());
            assertFalse(status.path("x-ui").has("array"));
            assertEquals(3, status.at("/x-ui/options").size());
        } else {
            assertEquals("array", status.at("/x-ui/controlType").asText());
            assertTrue(status.path("x-ui").has("array"));
            assertFalse(status.path("x-ui").has("options"));
        }
        // The real range representation includes nullable scalar unions below oneOf.
        assertEquals(List.of("integer", "null"),
                mapper.convertValue(body.at("/properties/countRange/oneOf/0/items/type"), List.class));
        assertEquals(List.of("integer", "null"),
                mapper.convertValue(body.at("/properties/countRange/oneOf/1/properties/min/type"), List.class));
        assertEquals(2, body.at("/properties/countRange/oneOf").size());
        assertFalse(body.at("/properties/countRange").has("type"));
        JsonNode inline = body.at("/properties/members/x-ui/array/itemSchema");
        assertEquals(List.of("object", "null"), mapper.convertValue(inline.path("type"), List.class));
        assertEquals("name", inline.path("fields").get(0).path("name").asText());
        assertTrue(inline.path("fields").get(0).at("/x-ui/required").asBoolean());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"object\"|true", "[\"object\",\"null\"]|true",
            "[\"object\",\"array\"]|false", "[\"null\"]|false"
    })
    void filteredEmptyObjectNormalizationDoesNotChooseAnAmbiguousType(String typeJson, boolean object) throws Exception {
        ObjectNode document = document();
        ObjectNode request = (ObjectNode) document.at("/components/schemas/MissionFilterDTO");
        JsonNode type = mapper.readTree(typeJson);
        request.set("type", type);
        request.remove("properties");
        JsonNode body = mapper.valueToTree(filtered(document));
        assertEquals(type, body.path("type"));
        assertEquals(object, body.has("properties"));
        if (object) assertEquals(mapper.createObjectNode(), body.path("properties"));
        assertFalse(body.has("required"));
        assertFalse(body.has("additionalProperties"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"integer\"|reference", "[\"null\",\"integer\"]|reference",
            "[\"integer\",\"string\"]|id", "[\"null\"]|id"
    })
    void filteredCanonicalIdentifierOnlyUsesAnUnambiguousScalarType(String typeJson, String expectedId) throws Exception {
        ObjectNode document = document();
        JsonNode type = mapper.readTree(typeJson);
        ((ObjectNode) document.at("/components/schemas/MissionDTO/properties/reference")).set("type", type);
        JsonNode body = mapper.valueToTree(filtered(document));
        assertEquals(expectedId, body.at("/x-ui/resource/idField").asText());
        assertEquals(type, document.at("/components/schemas/MissionDTO/properties/reference/type"));
    }

    private Map<String, Object> filtered(ObjectNode document) throws Exception {
        server.expect(requestTo("http://localhost/v3/api-docs/missions"))
                .andRespond(withSuccess(mapper.writeValueAsString(document), MediaType.APPLICATION_JSON));
        var response = controller.getFilteredSchema("/missions/filter", "post", false,
                "request", null, null, Locale.ENGLISH);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getETag());
        assertNotNull(response.getBody());
        server.verify();
        return response.getBody();
    }

    private ObjectNode document() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {
                  "openapi":"3.1.0",
                  "paths":{
                    "/missions/filter":{"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/MissionFilterDTO"}}}}}},
                    "/missions/{id}":{"get":{"responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/MissionDTO"}}}}}}}
                  },
                  "components":{"schemas":{
                    "MissionFilterDTO":{"type":"object","properties":{
                      "status":{"type":"array","items":{"$ref":"#/components/schemas/MissionStatus"},"x-ui":{"controlType":"array","array":{"itemSchemaRef":"#/components/schemas/MissionStatus"}}},
                      "members":{"type":["array","null"],"items":{"$ref":"#/components/schemas/Member"},"x-ui":{"controlType":"array","array":{"itemSchemaRef":"#/components/schemas/Member"}}},
                      "countRange":{"oneOf":[{"type":"array","items":{"type":["integer","null"]}},{"type":"object","properties":{"min":{"type":["integer","null"]},"max":{"type":["integer","null"]}}}]}
                    }},
                    "MissionStatus":{"type":"string","enum":["PLANEJADA","EM_ANDAMENTO","CONCLUIDA"]},
                    "Member":{"type":["object","null"],"required":["name"],"properties":{"name":{"type":"string"}}},
                    "MissionDTO":{"type":"object","required":["reference"],"properties":{"reference":{"type":"integer"}}}
                  }}
                }
                """);
    }
}
