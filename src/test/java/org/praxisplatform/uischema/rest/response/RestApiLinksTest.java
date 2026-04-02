package org.praxisplatform.uischema.rest.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.hateoas.Link;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestApiLinksTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesSingleRelAsObjectAndRepeatedRelAsArray() throws Exception {
        RestApiLinks links = RestApiLinks.from(List.of(
                Link.of("/employees/1").withSelfRel(),
                Link.of("/employees/1/actions/approve").withRel("action"),
                Link.of("/employees/1/actions/reject").withRel("action")
        ));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(links));

        assertTrue(json.path("self").isObject());
        assertEquals("/employees/1", json.path("self").path("href").asText());
        assertTrue(json.path("action").isArray());
        assertEquals(2, json.path("action").size());
        assertEquals("/employees/1/actions/approve", json.path("action").get(0).path("href").asText());
        assertEquals("/employees/1/actions/reject", json.path("action").get(1).path("href").asText());
        assertFalse(json.has("empty"));
        assertFalse(json.has("asMap"));
    }
}
