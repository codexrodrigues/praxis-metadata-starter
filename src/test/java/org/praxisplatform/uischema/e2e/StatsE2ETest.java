package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "praxis.stats.enabled=true",
        "praxis.stats.max-buckets=10",
        "praxis.stats.max-series-points=24"
})
class StatsE2ETest extends AbstractE2eH2Test {

    @Test
    void employeesExposeCanonicalStatsPayloads() throws Exception {
        ResponseEntity<String> groupByResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "status",
                  "metric": {
                    "operation": "COUNT"
                  },
                  "orderBy": "VALUE_DESC"
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, groupByResponse.getStatusCode().value());
        JsonNode groupByBody = body(groupByResponse);
        assertEquals("status", groupByBody.path("data").path("field").asText());
        assertEquals(2, groupByBody.path("data").path("buckets").size());
        assertEquals("ACTIVE", groupByBody.path("data").path("buckets").get(0).path("key").asText());
        assertEquals(2, groupByBody.path("data").path("buckets").get(0).path("value").asInt());
        assertEquals("INACTIVE", groupByBody.path("data").path("buckets").get(1).path("key").asText());
        assertEquals(1, groupByBody.path("data").path("buckets").get(1).path("value").asInt());

        ResponseEntity<String> timeSeriesResponse = postJson("/employees/stats/timeseries", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "admissionDate",
                  "granularity": "MONTH",
                  "metric": {
                    "operation": "COUNT"
                  },
                  "from": "2022-11-01",
                  "to": "2024-03-31",
                  "fillGaps": false
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, timeSeriesResponse.getStatusCode().value());
        JsonNode timeSeriesBody = body(timeSeriesResponse);
        assertEquals("admissionDate", timeSeriesBody.path("data").path("field").asText());
        assertEquals("MONTH", timeSeriesBody.path("data").path("granularity").asText());
        assertEquals(3, timeSeriesBody.path("data").path("points").size());
        assertEquals("2022-11-01", timeSeriesBody.path("data").path("points").get(0).path("start").asText());
        assertEquals(1, timeSeriesBody.path("data").path("points").get(0).path("value").asInt());
        assertEquals("2024-03-01", timeSeriesBody.path("data").path("points").get(2).path("start").asText());
        assertEquals(1, timeSeriesBody.path("data").path("points").get(2).path("value").asInt());

        ResponseEntity<String> distributionResponse = postJson("/employees/stats/distribution", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "salario",
                  "mode": "HISTOGRAM",
                  "metric": {
                    "operation": "COUNT"
                  },
                  "bucketSize": 1000,
                  "orderBy": "KEY_ASC"
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, distributionResponse.getStatusCode().value());
        JsonNode distributionBody = body(distributionResponse);
        assertEquals("salario", distributionBody.path("data").path("field").asText());
        assertEquals("HISTOGRAM", distributionBody.path("data").path("mode").asText());
        assertEquals(3, distributionBody.path("data").path("buckets").size());
        assertEquals(5000.0, distributionBody.path("data").path("buckets").get(0).path("from").asDouble());
        assertEquals(6000.0, distributionBody.path("data").path("buckets").get(0).path("to").asDouble());
        assertEquals(1, distributionBody.path("data").path("buckets").get(0).path("value").asInt());
        assertEquals(7000.0, distributionBody.path("data").path("buckets").get(2).path("from").asDouble());
        assertEquals(8000.0, distributionBody.path("data").path("buckets").get(2).path("to").asDouble());
        assertEquals(1, distributionBody.path("data").path("buckets").get(2).path("value").asInt());
    }

    @Test
    void employeesExposeAggregateMetricsBeyondCount() throws Exception {
        ResponseEntity<String> groupBySumResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "status",
                  "metric": {
                    "operation": "SUM",
                    "field": "salario"
                  },
                  "orderBy": "VALUE_DESC"
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, groupBySumResponse.getStatusCode().value());
        JsonNode groupBySumBody = body(groupBySumResponse);
        assertEquals("SUM", groupBySumBody.path("data").path("metric").path("operation").asText());
        assertEquals("salario", groupBySumBody.path("data").path("metric").path("field").asText());
        assertEquals("ACTIVE", groupBySumBody.path("data").path("buckets").get(0).path("key").asText());
        assertEquals(13250.0, groupBySumBody.path("data").path("buckets").get(0).path("value").asDouble());
        assertEquals(2, groupBySumBody.path("data").path("buckets").get(0).path("count").asInt());
        assertEquals("INACTIVE", groupBySumBody.path("data").path("buckets").get(1).path("key").asText());
        assertEquals(5300.0, groupBySumBody.path("data").path("buckets").get(1).path("value").asDouble());

        ResponseEntity<String> timeSeriesSumResponse = postJson("/employees/stats/timeseries", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "admissionDate",
                  "granularity": "MONTH",
                  "metric": {
                    "operation": "SUM",
                    "field": "salario"
                  },
                  "from": "2022-11-01",
                  "to": "2024-03-31",
                  "fillGaps": false
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, timeSeriesSumResponse.getStatusCode().value());
        JsonNode timeSeriesSumBody = body(timeSeriesSumResponse);
        assertEquals("SUM", timeSeriesSumBody.path("data").path("metric").path("operation").asText());
        assertEquals("salario", timeSeriesSumBody.path("data").path("metric").path("field").asText());
        assertEquals(5300.0, timeSeriesSumBody.path("data").path("points").get(0).path("value").asDouble());
        assertEquals(1, timeSeriesSumBody.path("data").path("points").get(0).path("count").asInt());
        assertEquals(7200.0, timeSeriesSumBody.path("data").path("points").get(1).path("value").asDouble());
        assertEquals(6050.0, timeSeriesSumBody.path("data").path("points").get(2).path("value").asDouble());

        ResponseEntity<String> distributionTermsResponse = postJson("/employees/stats/distribution", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "status",
                  "mode": "TERMS",
                  "metric": {
                    "operation": "SUM",
                    "field": "salario"
                  },
                  "orderBy": "VALUE_DESC"
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, distributionTermsResponse.getStatusCode().value());
        JsonNode distributionTermsBody = body(distributionTermsResponse);
        assertEquals("TERMS", distributionTermsBody.path("data").path("mode").asText());
        assertEquals("SUM", distributionTermsBody.path("data").path("metric").path("operation").asText());
        assertEquals("ACTIVE", distributionTermsBody.path("data").path("buckets").get(0).path("key").asText());
        assertEquals(13250.0, distributionTermsBody.path("data").path("buckets").get(0).path("value").asDouble());
        assertEquals(2, distributionTermsBody.path("data").path("buckets").get(0).path("count").asInt());
        assertEquals("INACTIVE", distributionTermsBody.path("data").path("buckets").get(1).path("key").asText());
        assertEquals(5300.0, distributionTermsBody.path("data").path("buckets").get(1).path("value").asDouble());
    }

    @Test
    void employeesExposeAverageMinAndMaxMetrics() throws Exception {
        ResponseEntity<String> groupByAverageResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "status",
                  "metric": {
                    "operation": "AVG",
                    "field": "salario"
                  },
                  "orderBy": "VALUE_DESC"
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, groupByAverageResponse.getStatusCode().value());
        JsonNode groupByAverageBody = body(groupByAverageResponse);
        assertEquals("AVG", groupByAverageBody.path("data").path("metric").path("operation").asText());
        assertEquals("ACTIVE", groupByAverageBody.path("data").path("buckets").get(0).path("key").asText());
        assertEquals(6625.0, groupByAverageBody.path("data").path("buckets").get(0).path("value").asDouble());
        assertEquals("INACTIVE", groupByAverageBody.path("data").path("buckets").get(1).path("key").asText());
        assertEquals(5300.0, groupByAverageBody.path("data").path("buckets").get(1).path("value").asDouble());

        ResponseEntity<String> timeSeriesMinResponse = postJson("/employees/stats/timeseries", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "admissionDate",
                  "granularity": "MONTH",
                  "metric": {
                    "operation": "MIN",
                    "field": "salario"
                  },
                  "from": "2022-11-01",
                  "to": "2024-03-31",
                  "fillGaps": false
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, timeSeriesMinResponse.getStatusCode().value());
        JsonNode timeSeriesMinBody = body(timeSeriesMinResponse);
        assertEquals("MIN", timeSeriesMinBody.path("data").path("metric").path("operation").asText());
        assertEquals(5300.0, timeSeriesMinBody.path("data").path("points").get(0).path("value").asDouble());
        assertEquals(7200.0, timeSeriesMinBody.path("data").path("points").get(1).path("value").asDouble());
        assertEquals(6050.0, timeSeriesMinBody.path("data").path("points").get(2).path("value").asDouble());

        ResponseEntity<String> distributionMaxResponse = postJson("/employees/stats/distribution", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "status",
                  "mode": "TERMS",
                  "metric": {
                    "operation": "MAX",
                    "field": "salario"
                  },
                  "orderBy": "VALUE_DESC"
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, distributionMaxResponse.getStatusCode().value());
        JsonNode distributionMaxBody = body(distributionMaxResponse);
        assertEquals("MAX", distributionMaxBody.path("data").path("metric").path("operation").asText());
        assertEquals("ACTIVE", distributionMaxBody.path("data").path("buckets").get(0).path("key").asText());
        assertEquals(7200.0, distributionMaxBody.path("data").path("buckets").get(0).path("value").asDouble());
        assertEquals(2, distributionMaxBody.path("data").path("buckets").get(0).path("count").asInt());
        assertEquals("INACTIVE", distributionMaxBody.path("data").path("buckets").get(1).path("key").asText());
        assertEquals(5300.0, distributionMaxBody.path("data").path("buckets").get(1).path("value").asDouble());
    }

    @Test
    void statsSurfacePreservesStableErrorSemantics() throws Exception {
        ResponseEntity<String> invalidGroupByResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {
                    "departmentId": %d
                  },
                  "field": "status"
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(400, invalidGroupByResponse.getStatusCode().value());
        JsonNode invalidGroupByBody = body(invalidGroupByResponse);
        assertContainsReason(invalidGroupByBody, "metric");

        ResponseEntity<String> unsupportedStatsResponse = postJson("/payroll-view/stats/group-by", """
                {
                  "filter": {
                    "employeeNome": "Alice"
                  },
                  "field": "payrollStatus",
                  "metric": {
                    "operation": "COUNT"
                  }
                }
                """);
        assertEquals(501, unsupportedStatsResponse.getStatusCode().value());
        JsonNode unsupportedStatsBody = body(unsupportedStatsResponse);
        assertContainsReason(unsupportedStatsBody, "Not implemented");
        assertNoStackTrace(unsupportedStatsBody);
    }

    @Test
    void statsSchemasAndCatalogRemainResolvable() throws Exception {
        ResponseEntity<String> groupByRequestSchema = get("/schemas/filtered?path=/employees/stats/group-by&operation=post&schemaType=request");
        assertEquals(200, groupByRequestSchema.getStatusCode().value());
        assertNotNull(groupByRequestSchema.getHeaders().getETag());
        assertNotNull(groupByRequestSchema.getHeaders().getFirst("X-Schema-Hash"));
        JsonNode groupByRequestBody = body(groupByRequestSchema);
        assertTrue(groupByRequestBody.path("properties").has("field"));
        assertTrue(groupByRequestBody.path("properties").has("metric"));

        HttpHeaders ifNoneMatch = new HttpHeaders();
        ifNoneMatch.set("If-None-Match", groupByRequestSchema.getHeaders().getETag());
        ResponseEntity<String> notModifiedResponse = exchange(
                "/schemas/filtered?path=/employees/stats/group-by&operation=post&schemaType=request",
                HttpMethod.GET,
                ifNoneMatch
        );
        assertEquals(304, notModifiedResponse.getStatusCode().value());

        ResponseEntity<String> timeSeriesResponseSchema = get("/schemas/filtered?path=/employees/stats/timeseries&operation=post&schemaType=response");
        assertEquals(200, timeSeriesResponseSchema.getStatusCode().value());
        JsonNode timeSeriesResponseBody = body(timeSeriesResponseSchema);
        assertTrue(timeSeriesResponseBody.path("properties").has("field"));
        assertTrue(timeSeriesResponseBody.path("properties").has("points"));
        assertFalse(timeSeriesResponseBody.path("x-ui").path("resource").path("readOnly").asBoolean());

        ResponseEntity<String> timeSeriesExpandedSchema = get("/schemas/filtered?path=/employees/stats/timeseries&operation=post&schemaType=response&includeInternalSchemas=true");
        assertEquals(200, timeSeriesExpandedSchema.getStatusCode().value());
        JsonNode timeSeriesExpandedBody = body(timeSeriesExpandedSchema);
        assertTrue(timeSeriesExpandedBody.path("properties").path("points").path("items").has("properties"));
        assertFalse(timeSeriesExpandedBody.path("properties").path("points").path("items").has("$ref"));
        assertNotNull(timeSeriesExpandedSchema.getHeaders().getETag());
        assertNotNull(timeSeriesExpandedSchema.getHeaders().getFirst("X-Schema-Hash"));
        assertFalse(timeSeriesExpandedSchema.getHeaders().getETag().equals(timeSeriesResponseSchema.getHeaders().getETag()));

        ResponseEntity<String> payrollStatsRequestSchema = get("/schemas/filtered?path=/payroll-view/stats/group-by&operation=post&schemaType=request");
        assertEquals(200, payrollStatsRequestSchema.getStatusCode().value());
        JsonNode payrollStatsSchemaBody = body(payrollStatsRequestSchema);
        assertTrue(payrollStatsSchemaBody.path("x-ui").path("resource").path("readOnly").asBoolean());

        ResponseEntity<String> catalogResponse = get("/schemas/catalog?group=human-resources");
        assertEquals(200, catalogResponse.getStatusCode().value());
        JsonNode catalogBody = body(catalogResponse);
        JsonNode groupByEndpoint = findEndpoint(catalogBody.path("endpoints"), "/employees/stats/group-by", "POST");
        assertNotNull(groupByEndpoint);
        assertResolvable(groupByEndpoint.path("schemaLinks").path("request").asText());
        assertResolvable(groupByEndpoint.path("schemaLinks").path("response").asText());

        JsonNode distributionEndpoint = findEndpoint(catalogBody.path("endpoints"), "/employees/stats/distribution", "POST");
        assertNotNull(distributionEndpoint);
        assertResolvable(distributionEndpoint.path("schemaLinks").path("request").asText());
    }

    private void assertResolvable(String relativePath) {
        ResponseEntity<String> response = get(relativePath);
        assertEquals(200, response.getStatusCode().value());
    }

    private JsonNode findEndpoint(JsonNode endpoints, String path, String method) {
        for (JsonNode endpoint : endpoints) {
            if (path.equals(endpoint.path("path").asText()) && method.equalsIgnoreCase(endpoint.path("method").asText())) {
                return endpoint;
            }
        }
        return null;
    }

    private void assertContainsReason(JsonNode errorBody, String expectedFragment) {
        String message = errorBody.path("message").asText("");
        String detail = errorBody.path("errors").isArray() && !errorBody.path("errors").isEmpty()
                ? errorBody.path("errors").get(0).path("message").asText("")
                : "";
        assertTrue(message.toLowerCase().contains(expectedFragment.toLowerCase())
                || detail.toLowerCase().contains(expectedFragment.toLowerCase()));
    }

    private void assertNoStackTrace(JsonNode errorBody) {
        String payload = errorBody.toString().toLowerCase();
        assertFalse(payload.contains("stacktrace"));
        assertFalse(payload.contains("exception"));
    }
}
