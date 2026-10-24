package org.praxisplatform.uischema.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
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

    @Test
    void recordOpenExampleIsSchemaValidAndPartialTargetIsRejected() throws Exception {
        JsonNode schemaDocument = objectMapper.readTree(
                Path.of("docs/spec/x-ui-analytics.schema.json").toFile());
        ObjectNode example = (ObjectNode) objectMapper.readTree(
                Path.of("docs/spec/examples/x-ui-analytics.valid.json").toFile());
        JsonSchema schema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaDocument);

        assertTrue(schema.validate(example).isEmpty());

        ObjectNode invalid = example.deepCopy();
        ((ObjectNode) invalid.path("projections").path(0)
                .path("interactions").path("recordOpen").path("target"))
                .remove("surfaceId");
        assertFalse(schema.validate(invalid).isEmpty());
    }

    @Test
    void recordOpenSchemaCannotEmbedRuntimeOrAuthorizationDetails() throws Exception {
        JsonNode schema = objectMapper.readTree(
                Path.of("docs/spec/x-ui-analytics.schema.json").toFile());
        JsonNode recordOpen = schema.path("$defs").path("recordOpen");
        JsonNode target = schema.path("$defs").path("surfaceTarget");

        assertFalse(recordOpen.path("additionalProperties").asBoolean(true));
        assertFalse(target.path("additionalProperties").asBoolean(true));
        assertEquals(Set.of("sourceIdentityField", "target"), requiredFields(recordOpen));
        assertEquals(Set.of("resourceKey", "surfaceId"), requiredFields(target));
        assertFalse(target.path("properties").has("path"));
        assertFalse(target.path("properties").has("schemaUrl"));
        assertFalse(target.path("properties").has("availability"));
        assertFalse(recordOpen.path("properties").has("widget"));
        assertFalse(recordOpen.path("properties").has("presentation"));
    }

    @Test
    void multiDimensionalProjectionWithExtractionsAndCycleOccurrenceMetricIsSchemaValid() throws Exception {
        JsonNode schemaDocument = objectMapper.readTree(
                Path.of("docs/spec/x-ui-analytics.schema.json").toFile());
        JsonSchema schema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaDocument);

        JsonNode matrixExample = objectMapper.readTree("""
                {
                  "projections": [
                    {
                      "id": "operational-rhythm-matrix",
                      "intent": "distribution",
                      "source": {
                        "kind": "praxis.stats",
                        "resource": "/api/human-resources/employees",
                        "operation": "group-by"
                      },
                      "bindings": {
                        "primaryDimension": {
                          "field": "lastCheckInAt",
                          "extraction": "DAY_OF_WEEK",
                          "alias": "weekday",
                          "role": "time-cycle",
                          "label": "Dia da semana",
                          "keyFilterField": "checkInWeekday"
                        },
                        "secondaryDimension": {
                          "field": "lastCheckInAt",
                          "extraction": "HOUR_OF_DAY",
                          "alias": "hour",
                          "role": "time-cycle",
                          "label": "Hora do dia",
                          "keyFilterField": "checkInHour"
                        },
                        "primaryMetrics": [
                          {
                            "field": "id",
                            "aggregation": "count",
                            "label": "Volume"
                          },
                          {
                            "field": "id",
                            "aggregation": "avg-per-cycle-occurrence",
                            "label": "Media por ocorrencia do ciclo"
                          }
                        ]
                      },
                      "defaults": {
                        "timezone": "America/Sao_Paulo",
                        "periodPreset": "LAST_30_DAYS",
                        "fillGaps": true,
                        "limit": 168
                      },
                      "presentationHints": {
                        "preferredFamilies": ["chart", "analytic-table"]
                      },
                      "interactions": {
                        "crossFilter": true
                      }
                    }
                  ]
                }
                """);

        assertTrue(schema.validate(matrixExample).isEmpty());
    }

    private Set<String> requiredFields(JsonNode definition) {
        return objectMapper.convertValue(
                definition.path("required"),
                objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class));
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        Iterator<String> fields = node.fieldNames();
        fields.forEachRemaining(names::add);
        return names;
    }
}
