package org.praxisplatform.uischema.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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

    @Test
    void boxplotProjectionWithPercentilesAndAliasIsSchemaValid() throws Exception {
        JsonNode schemaDocument = objectMapper.readTree(
                Path.of("docs/spec/x-ui-analytics.schema.json").toFile());
        JsonSchema schema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaDocument);

        JsonNode boxplotExample = objectMapper.readTree("""
                {
                  "projections": [
                    {
                      "id": "salary-boxplot-by-department",
                      "intent": "distribution",
                      "source": {
                        "kind": "praxis.stats",
                        "resource": "/api/human-resources/employees",
                        "operation": "group-by"
                      },
                      "bindings": {
                        "primaryDimension": {
                          "field": "department",
                          "role": "category"
                        },
                        "primaryMetrics": [
                          { "field": "salario", "aggregation": "min", "alias": "min" },
                          { "field": "salario", "aggregation": "percentile-25", "alias": "p25" },
                          { "field": "salario", "aggregation": "median", "alias": "median" },
                          { "field": "salario", "aggregation": "percentile-75", "alias": "p75" },
                          { "field": "salario", "aggregation": "max", "alias": "max" },
                          { "field": "salario", "aggregation": "count", "alias": "count" }
                        ]
                      }
                    }
                  ]
                }
                """);

        assertTrue(schema.validate(boxplotExample).isEmpty());
    }

    @Test
    void distributionHistogramProjectionWithDefaultsIsSchemaValid() throws Exception {
        JsonNode schemaDocument = objectMapper.readTree(
                Path.of("docs/spec/x-ui-analytics.schema.json").toFile());
        JsonSchema schema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaDocument);

        JsonNode histogramExample = objectMapper.readTree("""
                {
                  "projections": [
                    {
                      "id": "salary-histogram",
                      "intent": "distribution",
                      "source": {
                        "kind": "praxis.stats",
                        "resource": "/api/human-resources/employees",
                        "operation": "distribution"
                      },
                      "bindings": {
                        "primaryDimension": {
                          "field": "salario",
                          "role": "bucket"
                        },
                        "primaryMetrics": [
                          { "field": "salario", "aggregation": "histogram", "label": "Faixa salarial" }
                        ]
                      },
                      "defaults": {
                        "distributionMode": "histogram",
                        "bucketSize": 1000.0,
                        "bucketCount": 8
                      }
                    }
                  ]
                }
                """);

        assertTrue(schema.validate(histogramExample).isEmpty());
    }

    @Test
    void inferenceAndAnomalyOperationsMapAndValidateAgainstAnalyticsSchema() throws Exception {
        JsonNode schemaRoot = objectMapper.readTree(
                Files.readString(Path.of("docs", "spec", "x-ui-analytics.schema.json")));
        JsonNode operationEnum = schemaRoot
                .path("$defs").path("source")
                .path("properties").path("operation")
                .path("enum");
        List<String> operations = new java.util.ArrayList<>();
        operationEnum.forEach(node -> operations.add(node.asText()));
        assertTrue(operations.contains("inference"));
        assertTrue(operations.contains("anomalies"));

        UiAnalyticsAnnotationMapper mapper = new UiAnalyticsAnnotationMapper();
        @org.praxisplatform.uischema.annotation.UiAnalytics(projections = {
                @org.praxisplatform.uischema.annotation.AnalyticsProjection(
                        id = "inference-test",
                        intent = org.praxisplatform.uischema.annotation.AnalyticsIntent.COMPARISON,
                        sourceOperation = org.praxisplatform.uischema.annotation.AnalyticsOperation.INFERENCE,
                        primaryDimension = @org.praxisplatform.uischema.annotation.AnalyticsDimensionBinding(field = "status"),
                        primaryMetrics = {@org.praxisplatform.uischema.annotation.AnalyticsMetricBinding(field = "salario", aggregation = "median")}
                ),
                @org.praxisplatform.uischema.annotation.AnalyticsProjection(
                        id = "anomalies-test",
                        intent = org.praxisplatform.uischema.annotation.AnalyticsIntent.DISTRIBUTION,
                        sourceOperation = org.praxisplatform.uischema.annotation.AnalyticsOperation.ANOMALIES,
                        primaryDimension = @org.praxisplatform.uischema.annotation.AnalyticsDimensionBinding(field = "department"),
                        primaryMetrics = {@org.praxisplatform.uischema.annotation.AnalyticsMetricBinding(field = "salario", aggregation = "sum")}
                )
        })
        final class HostSample {}

        org.praxisplatform.uischema.annotation.UiAnalytics annotation =
                HostSample.class.getAnnotation(org.praxisplatform.uischema.annotation.UiAnalytics.class);
        java.util.Map<String, Object> mappedInference =
                mapper.toXUiAnalytics(annotation, "/intelligent-employees/stats/inference");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> projections =
                (List<java.util.Map<String, Object>>) mappedInference.get("projections");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> firstSource =
                (java.util.Map<String, Object>) projections.getFirst().get("source");
        assertEquals("/intelligent-employees", firstSource.get("resource"));
        assertEquals("inference", firstSource.get("operation"));

        java.util.Map<String, Object> mappedAnomalies =
                mapper.toXUiAnalytics(annotation, "/intelligent-employees/stats/anomalies");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> anomalyProjections =
                (List<java.util.Map<String, Object>>) mappedAnomalies.get("projections");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> secondSource =
                (java.util.Map<String, Object>) anomalyProjections.get(1).get("source");
        assertEquals("/intelligent-employees", secondSource.get("resource"));
        assertEquals("anomalies", secondSource.get("operation"));

        assertEquals("inference", org.praxisplatform.uischema.annotation.AnalyticsOperation.INFERENCE.wireValue());
        assertEquals("anomalies", org.praxisplatform.uischema.annotation.AnalyticsOperation.ANOMALIES.wireValue());

        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(objectMapper.readTree(Path.of("docs", "spec", "x-ui-analytics.schema.json").toFile()));
        JsonNode intelligenceAnalytics = objectMapper.readTree("""
                {
                  "projections": [
                    {
                      "id": "compensation-inference",
                      "intent": "comparison",
                      "source": {
                        "kind": "praxis.stats",
                        "resource": "/intelligent-employees",
                        "operation": "inference"
                      },
                      "bindings": {
                        "primaryDimension": {
                          "field": "status",
                          "role": "category"
                        },
                        "primaryMetrics": [
                          {
                            "field": "salario",
                            "aggregation": "median"
                          }
                        ]
                      }
                    },
                    {
                      "id": "compensation-anomalies",
                      "intent": "distribution",
                      "source": {
                        "kind": "praxis.stats",
                        "resource": "/intelligent-employees",
                        "operation": "anomalies"
                      },
                      "bindings": {
                        "primaryDimension": {
                          "field": "department",
                          "role": "category"
                        },
                        "primaryMetrics": [
                          {
                            "field": "salario",
                            "aggregation": "sum"
                          }
                        ]
                      }
                    }
                  ]
                }
                """);
        assertTrue(schema.validate(intelligenceAnalytics).isEmpty());
    }

    @Test
    void extendedWaveAnalyticsIntentsValidateAgainstAnalyticsSchema() throws Exception {
        JsonNode schemaRoot = objectMapper.readTree(
                Files.readString(Path.of("docs", "spec", "x-ui-analytics.schema.json")));
        JsonNode intentEnum = schemaRoot
                .path("$defs").path("projection")
                .path("properties").path("intent")
                .path("enum");
        List<String> intents = new java.util.ArrayList<>();
        intentEnum.forEach(node -> intents.add(node.asText()));
        assertTrue(intents.containsAll(List.of("matrix", "flow", "range", "multivariate")));

        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaRoot);
        JsonNode extendedProjections = objectMapper.readTree("""
                {
                  "projections": [
                    {
                      "id": "pay-grade-matrix",
                      "intent": "matrix",
                      "source": {
                        "kind": "praxis.stats",
                        "resource": "/workforce/grades",
                        "operation": "group-by"
                      },
                      "bindings": {
                        "primaryDimension": { "field": "gradeClass", "role": "category" },
                        "secondaryDimension": { "field": "gradeStep", "role": "category" },
                        "primaryMetrics": [{ "field": "baseSalary", "aggregation": "avg" }]
                      }
                    },
                    {
                      "id": "mobility-flow",
                      "intent": "flow",
                      "source": {
                        "kind": "praxis.stats",
                        "resource": "/workforce/transfers",
                        "operation": "group-by"
                      },
                      "bindings": {
                        "primaryDimension": { "field": "originUnit", "role": "category" },
                        "secondaryDimension": { "field": "targetUnit", "role": "category" },
                        "primaryMetrics": [{ "field": "id", "aggregation": "count" }]
                      }
                    },
                    {
                      "id": "compensation-range",
                      "intent": "range",
                      "source": {
                        "kind": "praxis.stats",
                        "resource": "/workforce/positions",
                        "operation": "group-by"
                      },
                      "bindings": {
                        "primaryDimension": { "field": "department", "role": "category" },
                        "primaryMetrics": [
                          { "field": "baseSalary", "aggregation": "min", "alias": "min" },
                          { "field": "baseSalary", "aggregation": "max", "alias": "max" }
                        ]
                      }
                    },
                    {
                      "id": "probation-multivariate",
                      "intent": "multivariate",
                      "source": {
                        "kind": "praxis.stats",
                        "resource": "/workforce/evaluations",
                        "operation": "group-by"
                      },
                      "bindings": {
                        "primaryDimension": { "field": "evaluationCycle", "role": "category" },
                        "primaryMetrics": [
                          { "field": "attendanceScore", "aggregation": "avg" },
                          { "field": "deliveryScore", "aggregation": "avg" }
                        ]
                      }
                    }
                  ]
                }
                """);

        assertTrue(schema.validate(extendedProjections).isEmpty());
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
