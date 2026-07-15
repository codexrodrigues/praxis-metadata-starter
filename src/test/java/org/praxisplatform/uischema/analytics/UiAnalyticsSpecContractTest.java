package org.praxisplatform.uischema.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiAnalyticsSpecContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void policyReferenceSchemaIsClosedAndContainsNoExecutablePolicyFields() throws Exception {
        JsonNode schema = objectMapper.readTree(
                Path.of("docs/spec/x-ui-analytics.schema.json").toFile());
        JsonNode policyRef = schema.path("$defs").path("policyRef");

        assertFalse(policyRef.path("additionalProperties").asBoolean(true));
        assertEquals(
                Set.of("policyId", "policyVersion", "role", "resultField"),
                objectMapper.convertValue(policyRef.path("required"),
                        objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class)));
        assertFalse(policyRef.path("properties").has("thresholds"));
        assertFalse(policyRef.path("properties").has("expression"));
        assertFalse(policyRef.path("properties").has("script"));
    }

    @Test
    void publishedExampleUsesOnlyDeclaredPolicyReferenceFields() throws Exception {
        JsonNode schema = objectMapper.readTree(
                Path.of("docs/spec/x-ui-analytics.schema.json").toFile());
        JsonNode example = objectMapper.readTree(
                Path.of("docs/spec/examples/x-ui-analytics.valid.json").toFile());
        Set<String> allowed = fieldNames(
                schema.path("$defs").path("policyRef").path("properties"));
        JsonNode policyRef = example.path("projections").path(0)
                .path("governance").path("policyRefs").path(0);

        assertEquals(allowed, fieldNames(policyRef));
    }

    @Test
    void dimensionSchemaAndExamplePublishOnlyThePublicBucketKeyFilterBinding() throws Exception {
        JsonNode schema = objectMapper.readTree(
                Path.of("docs/spec/x-ui-analytics.schema.json").toFile());
        JsonNode example = objectMapper.readTree(
                Path.of("docs/spec/examples/x-ui-analytics.valid.json").toFile());
        JsonNode dimensionProperties = schema.path("$defs").path("dimension").path("properties");
        JsonNode projection = example.path("projections").path(0);
        JsonNode dimension = projection.path("bindings").path("primaryDimension");

        assertTrue(dimensionProperties.has("keyFilterField"));
        assertFalse(dimensionProperties.has("keyPropertyPath"));
        assertFalse(dimensionProperties.has("labelPropertyPath"));
        assertEquals("departamentoId", dimension.path("keyFilterField").asText());
        assertTrue(projection.path("interactions").path("crossFilter").asBoolean());
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        Iterator<String> fields = node.fieldNames();
        fields.forEachRemaining(names::add);
        return names;
    }
}
