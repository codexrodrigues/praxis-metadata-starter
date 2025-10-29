package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ApiDocsControllerSchemaHashTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ApiDocsController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ApiDocsController();

        // Inject ObjectMapper
        Field om = ApiDocsController.class.getDeclaredField("objectMapper");
        om.setAccessible(true);
        om.set(controller, mapper);

        // Preload a minimal OpenAPI doc into the private documentCache
        Field dc = ApiDocsController.class.getDeclaredField("documentCache");
        dc.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, JsonNode> docCache = (Map<String, JsonNode>) dc.get(controller);

        String group = "api-human-resources-funcionarios"; // derived from path below
        JsonNode doc = buildMinimalOpenApiDocument();
        docCache.put(group, doc);
    }

    private JsonNode buildMinimalOpenApiDocument() {
        // paths -> /api/human-resources/funcionarios/all -> get
        ObjectNode root = mapper.createObjectNode();
        ObjectNode paths = root.putObject("paths");
        ObjectNode op = paths
                .putObject("/api/human-resources/funcionarios/all")
                .putObject("get");

        // operation-level x-ui with responseSchema
        ObjectNode xui = op.putObject("x-ui");
        xui.put("responseSchema", "PersonDTO");
        xui.put("someFlag", true);

        // minimal responses (not used because x-ui has responseSchema)
        op.putObject("responses")
          .putObject("200")
          .putObject("content")
          .putObject("application/json")
          .putObject("schema");

        // components.schemas
        ObjectNode components = root.putObject("components");
        ObjectNode schemas = components.putObject("schemas");

        // Referenced AddressDTO (to test $ref expansion copies full schema)
        ObjectNode address = schemas.putObject("AddressDTO");
        address.put("type", "object");
        ObjectNode addressProps = address.putObject("properties");
        addressProps.putObject("street").put("type", "string");

        // Target PersonDTO referencing AddressDTO
        ObjectNode person = schemas.putObject("PersonDTO");
        person.put("type", "object");
        ObjectNode personProps = person.putObject("properties");
        personProps.putObject("name").put("type", "string");
        personProps.putObject("address").put("$ref", "#/components/schemas/AddressDTO");

        return root;
    }

    @Test
    void returns200WithETagThen304OnMatch_andCopiesFullRefSchema() {
        String path = "/api/human-resources/funcionarios/all";
        // First call: expect 200, body present, ETag provided
        ResponseEntity<Map<String,Object>> r1 = controller.getFilteredSchema(
                path,
                "get",
                true, // includeInternalSchemas triggers $ref expansion
                "response",
                null,
                null,
                Locale.ENGLISH
        );

        assertEquals(200, r1.getStatusCodeValue());
        assertNotNull(r1.getHeaders().getETag());
        Map<String, Object> body = r1.getBody();
        assertNotNull(body);

        // Assert x-ui from operation was copied
        @SuppressWarnings("unchecked")
        Map<String,Object> xui = (Map<String, Object>) body.get("x-ui");
        assertNotNull(xui);
        assertEquals("PersonDTO", xui.get("responseSchema"));

        // Assert $ref was expanded with full schema (type + properties)
        @SuppressWarnings("unchecked")
        Map<String,Object> props = (Map<String, Object>) body.get("properties");
        assertNotNull(props);
        @SuppressWarnings("unchecked")
        Map<String,Object> address = (Map<String, Object>) props.get("address");
        assertNotNull(address);
        assertEquals("object", address.get("type"));
        assertTrue(address.containsKey("properties"));

        // Second call: pass If-None-Match with returned ETag -> expect 304
        String etag = r1.getHeaders().getETag();
        ResponseEntity<Map<String,Object>> r2 = controller.getFilteredSchema(
                path,
                "get",
                true,
                "response",
                etag,
                null,
                Locale.ENGLISH
        );
        assertEquals(304, r2.getStatusCodeValue());
        assertNull(r2.getBody());
    }
}

