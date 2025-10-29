package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ApiDocsControllerAllOfTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ApiDocsController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ApiDocsController();
        Field om = ApiDocsController.class.getDeclaredField("objectMapper");
        om.setAccessible(true);
        om.set(controller, mapper);

        Field dc = ApiDocsController.class.getDeclaredField("documentCache");
        dc.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, JsonNode> docCache = (Map<String, JsonNode>) dc.get(controller);

        String group = "api-ui-test-wrappers";
        JsonNode doc = buildDocWithAllOf();
        docCache.put(group, doc);
    }

    private JsonNode buildDocWithAllOf() {
        // Root
        var root = mapper.createObjectNode();
        var paths = root.putObject("paths");
        var op = paths.putObject("/api/ui-test/wrappers").putObject("get");
        op.putObject("x-ui");
        var resp = op.putObject("responses").putObject("200").putObject("content").putObject("application/json").putObject("schema");
        resp.put("$ref", "#/components/schemas/TopWrapper");

        var components = root.putObject("components");
        var schemas = components.putObject("schemas");

        // Wrapper with data.schema -> $ref to PersonAllOf
        var wrapper = schemas.putObject("TopWrapper");
        wrapper.put("type", "object");
        var wrapperProps = wrapper.putObject("properties");
        var data = wrapperProps.putObject("data");
        var dataSchema = data.putObject("schema");
        dataSchema.put("$ref", "#/components/schemas/PersonAllOf");

        var base = schemas.putObject("BaseDTO");
        base.put("type", "object");
        base.putObject("properties").putObject("id").put("type", "string");

        var extra = schemas.putObject("ExtraDTO");
        extra.put("type", "object");
        extra.putObject("properties").putObject("name").put("type", "string");

        var composed = schemas.putObject("PersonAllOf");
        composed.put("type", "object");
        var allOf = composed.putArray("allOf");
        allOf.addObject().put("$ref", "#/components/schemas/BaseDTO");
        allOf.addObject().put("$ref", "#/components/schemas/ExtraDTO");

        return root;
    }

    @Test
    void expandsRefsInsideAllOfAndTopLevelRefs() {
        var res = controller.getFilteredSchema(
                "/api/ui-test/wrappers",
                "get",
                true,
                "response",
                null,
                null,
                Locale.ENGLISH
        );
        assertEquals(200, res.getStatusCodeValue());
        Map<String, Object> body = res.getBody();
        assertNotNull(body);

        // Traverse into data.schema (expanded full schema)
        @SuppressWarnings("unchecked")
        Map<String,Object> props = (Map<String, Object>) body.get("properties");
        assertNotNull(props);
        @SuppressWarnings("unchecked")
        Map<String,Object> data = (Map<String, Object>) props.get("data");
        assertNotNull(data);
        @SuppressWarnings("unchecked")
        Map<String,Object> schema = (Map<String, Object>) data.get("schema");
        assertNotNull(schema);

        // PersonAllOf should contain allOf array with expanded objects (no $ref inside elements)
        @SuppressWarnings("unchecked")
        var allOf = (java.util.List<Object>) schema.get("allOf");
        assertNotNull(allOf);
        assertFalse(allOf.isEmpty());
        @SuppressWarnings("unchecked")
        Map<String,Object> first = (Map<String, Object>) allOf.get(0);
        assertTrue(first.containsKey("properties"));
        assertFalse(first.containsKey("$ref"));
    }
}

