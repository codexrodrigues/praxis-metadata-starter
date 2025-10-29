package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiDocsControllerReadOnlyMetaTest {

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

        String group = "api-ro-demo-all"; // derived from path below
        JsonNode doc = buildReadOnlyOpenApiDocument();
        docCache.put(group, doc);
    }

    private JsonNode buildReadOnlyOpenApiDocument() {
        // paths
        ObjectNode root = mapper.createObjectNode();
        ObjectNode paths = root.putObject("paths");
        // GET byId and all, POST filter, POST filter/cursor -> Read-only shape
        paths.putObject("/api/ro-demo/{id}").putObject("get").putObject("x-ui");
        ObjectNode allGet = paths.putObject("/api/ro-demo/all").putObject("get");
        ObjectNode xui = allGet.putObject("x-ui");
        xui.put("responseSchema", "DemoDTO");
        paths.putObject("/api/ro-demo/filter").putObject("post").putObject("x-ui");
        paths.putObject("/api/ro-demo/filter/cursor").putObject("post").putObject("x-ui");

        // components.schemas with DemoDTO that uses demoId as id field
        ObjectNode components = root.putObject("components");
        ObjectNode schemas = components.putObject("schemas");
        ObjectNode demo = schemas.putObject("DemoDTO");
        demo.put("type", "object");
        ObjectNode props = demo.putObject("properties");
        props.putObject("demoId").put("type", "integer");
        props.putObject("name").put("type", "string");

        return root;
    }

    @Test
    void xUiResource_includesReadOnlyCapabilitiesAndIdFieldHeuristics() {
        String path = "/api/ro-demo/all";
        ResponseEntity<Map<String, Object>> r = controller.getFilteredSchema(
                path,
                "get",
                false,
                "response",
                null, // idField
                true, // readOnly flag
                null,
                null,
                Locale.ENGLISH
        );
        assertEquals(200, r.getStatusCodeValue());
        Map<String, Object> body = r.getBody();
        assertNotNull(body);

        @SuppressWarnings("unchecked")
        Map<String, Object> xui = (Map<String, Object>) body.get("x-ui");
        assertNotNull(xui);
        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) xui.get("resource");
        assertNotNull(resource);
        assertEquals(Boolean.TRUE, resource.get("readOnly"));
        assertEquals("demoId", resource.get("idField"));

        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) resource.get("capabilities");
        assertNotNull(caps);
        assertEquals(Boolean.FALSE, caps.get("create"));
        assertEquals(Boolean.FALSE, caps.get("update"));
        assertEquals(Boolean.FALSE, caps.get("delete"));
        assertEquals(Boolean.TRUE, caps.get("byId"));
        assertEquals(Boolean.TRUE, caps.get("all"));
        assertEquals(Boolean.TRUE, caps.get("filter"));
        assertEquals(Boolean.TRUE, caps.get("cursor"));
    }
}
