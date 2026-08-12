package org.praxisplatform.uischema.formeffect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormEffectSpecContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void contractIsClosedAndDoesNotExposeExecutableClientCode() throws Exception {
        JsonNode schema = objectMapper.readTree(
                Path.of("docs/spec/x-ui-form-effects.schema.json").toFile());
        JsonNode effect = schema.path("$defs").path("formEffect");
        JsonNode operation = schema.path("$defs").path("operation");

        assertFalse(effect.path("additionalProperties").asBoolean(true));
        assertFalse(operation.path("additionalProperties").asBoolean(true));
        assertFalse(effect.path("properties").has("script"));
        assertFalse(effect.path("properties").has("onChange"));
        assertFalse(operation.path("properties").has("headers"));
        assertEquals("POST", operation.path("properties").path("method").path("const").asText());
    }

    @Test
    void officialExampleUsesTheCanonicalValueChangeTrigger() throws Exception {
        JsonNode example = objectMapper.readTree(
                Path.of("docs/spec/examples/x-ui-form-effects.valid.json").toFile());

        assertEquals("value-change", example.path(0).path("trigger").path("event").asText());
        assertTrue(example.path(0).path("outputs").size() > 1);
    }
}
