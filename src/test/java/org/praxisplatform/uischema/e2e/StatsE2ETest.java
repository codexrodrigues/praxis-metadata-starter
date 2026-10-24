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
    void employeesCapabilitiesExposeStatsFieldDiscovery() throws Exception {
        ResponseEntity<String> response = get("/employees/capabilities");
        assertEquals(200, response.getStatusCode().value());

        JsonNode body = body(response);
        assertTrue(body.path("canonicalOperations").path("statsComparison").asBoolean());
        assertEquals(2, body.path("stats").path("maxDimensions").asInt());
        assertEquals(500, body.path("stats").path("maxCells").asInt());
        JsonNode fields = body.path("stats").path("fields");
        assertEquals(6, fields.size());
        assertEquals("status", fields.get(0).path("field").asText());
        assertEquals("Status", fields.get(0).path("label").asText());
        assertEquals("COUNT", fields.get(0).path("metrics").get(0).asText());
        assertEquals("GROUP_BY", fields.get(0).path("modes").get(0).asText());
        assertEquals("DISTRIBUTION_TERMS", fields.get(0).path("modes").get(1).asText());
        assertTrue(fields.get(0).path("groupByEligible").asBoolean());
        assertTrue(fields.get(0).path("distributionTermsEligible").asBoolean());
        assertEquals("NONE", fields.get(0).path("supportedExtractions").get(0).asText());

        assertEquals("department", fields.get(1).path("field").asText());
        assertTrue(fields.get(1).path("keyAndLabelDistinct").asBoolean());
        assertFalse(fields.get(1).has("propertyPath"));

        assertEquals("admissionDate", fields.get(2).path("field").asText());
        assertEquals("TIME_SERIES", fields.get(2).path("modes").get(0).asText());
        assertTrue(fields.get(2).path("timeSeriesEligible").asBoolean());
        assertTrue(fields.get(2).path("supportedExtractions").toString().contains("DAY_OF_WEEK"));
        assertTrue(fields.get(2).path("supportedExtractions").toString().contains("HOUR_OF_DAY"));

        assertEquals("salario", fields.get(4).path("field").asText());
        assertEquals("DISTRIBUTION_HISTOGRAM", fields.get(4).path("modes").get(0).asText());
        assertEquals("METRIC_FIELD", fields.get(4).path("modes").get(1).asText());

        assertEquals("lastCheckInAt", fields.get(5).path("field").asText());
        assertTrue(fields.get(5).path("timeSeriesEligible").asBoolean());
    }

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

        ResponseEntity<String> departmentGroupByResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "field": "department",
                  "metric": {
                    "operation": "COUNT"
                  },
                  "orderBy": "KEY_ASC"
                }
                """);
        assertEquals(200, departmentGroupByResponse.getStatusCode().value());
        JsonNode departmentGroupByBody = body(departmentGroupByResponse);
        assertEquals(2, departmentGroupByBody.path("data").path("buckets").size());
        assertEquals(state.humanResourcesDepartmentId().longValue(), departmentGroupByBody.path("data").path("buckets").get(0).path("key").asLong());
        assertEquals("Human Resources", departmentGroupByBody.path("data").path("buckets").get(0).path("label").asText());

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
    void employeesCompareUnionedBucketsAcrossCustomPeriods() throws Exception {
        ResponseEntity<String> response = postJson("/employees/stats/comparison", """
                {
                  "filter": {},
                  "field": "department",
                  "periodField": "admissionDate",
                  "metrics": [{ "operation": "COUNT", "alias": "employees" }],
                  "period": { "from": "2023-02-01", "to": "2023-02-28", "timezone": "UTC" },
                  "orderBy": "KEY_ASC"
                }
                """);

        assertEquals(200, response.getStatusCode().value());
        JsonNode comparison = body(response).path("data");
        assertEquals("2023-02-01", comparison.path("currentPeriod").path("from").asText());
        assertEquals("2023-02-28", comparison.path("currentPeriod").path("to").asText());
        assertEquals("UTC", comparison.path("currentPeriod").path("timezone").asText());
        assertEquals("2023-01-04", comparison.path("previousPeriod").path("from").asText());
        assertEquals("2023-01-31", comparison.path("previousPeriod").path("to").asText());
        JsonNode buckets = comparison.path("buckets");
        assertEquals(2, buckets.size());
        assertEquals("Human Resources", buckets.get(0).path("label").asText());
        assertEquals(0, buckets.get(0).path("values").path("employees").path("current").asInt());
        assertEquals(1, buckets.get(0).path("values").path("employees").path("previous").asInt());
        assertEquals("Operations", buckets.get(1).path("label").asText());
        assertEquals(1, buckets.get(1).path("values").path("employees").path("current").asInt());
        assertEquals(0, buckets.get(1).path("values").path("employees").path("previous").asInt());
        assertTrue(buckets.get(1).path("values").path("employees").path("baselineMissing").asBoolean());
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
    void employeesExposeDistinctCountMetric() throws Exception {
        ResponseEntity<String> groupByDistinctCountResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "field": "status",
                  "metric": {
                    "operation": "DISTINCT_COUNT",
                    "field": "payrollProfile",
                    "alias": "profiles"
                  },
                  "orderBy": "VALUE_DESC"
                }
                """);
        assertEquals(200, groupByDistinctCountResponse.getStatusCode().value());
        JsonNode groupByDistinctCountBody = body(groupByDistinctCountResponse);
        assertEquals("DISTINCT_COUNT", groupByDistinctCountBody.path("data").path("metric").path("operation").asText());
        assertEquals("payrollProfile", groupByDistinctCountBody.path("data").path("metric").path("field").asText());
        assertEquals("profiles", groupByDistinctCountBody.path("data").path("metric").path("alias").asText());
        assertEquals("ACTIVE", groupByDistinctCountBody.path("data").path("buckets").get(0).path("key").asText());
        assertEquals(4, groupByDistinctCountBody.path("data").path("buckets").get(0).path("value").asInt());
        assertEquals(4, groupByDistinctCountBody.path("data").path("buckets").get(0).path("count").asInt());

        ResponseEntity<String> timeSeriesDistinctCountResponse = postJson("/employees/stats/timeseries", """
                {
                  "filter": {},
                  "field": "admissionDate",
                  "granularity": "MONTH",
                  "metric": {
                    "operation": "DISTINCT_COUNT",
                    "field": "payrollProfile",
                    "alias": "profiles"
                  },
                  "from": "2022-11-01",
                  "to": "2024-03-31",
                  "fillGaps": false
                }
                """);
        assertEquals(200, timeSeriesDistinctCountResponse.getStatusCode().value());
        JsonNode timeSeriesDistinctCountBody = body(timeSeriesDistinctCountResponse);
        assertEquals("DISTINCT_COUNT", timeSeriesDistinctCountBody.path("data").path("metric").path("operation").asText());
        assertEquals("2023-01-01", timeSeriesDistinctCountBody.path("data").path("points").get(1).path("start").asText());
        assertEquals(1, timeSeriesDistinctCountBody.path("data").path("points").get(1).path("value").asInt());
        assertEquals(1, timeSeriesDistinctCountBody.path("data").path("points").get(1).path("count").asInt());
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

        ResponseEntity<String> comparisonRequestSchema = get("/schemas/filtered?path=/employees/stats/comparison&operation=post&schemaType=request");
        assertEquals(200, comparisonRequestSchema.getStatusCode().value());
        JsonNode comparisonRequestBody = body(comparisonRequestSchema);
        assertTrue(comparisonRequestBody.path("properties").has("field"));
        assertTrue(comparisonRequestBody.path("properties").has("periodField"));
        assertTrue(comparisonRequestBody.path("properties").has("period"));

        ResponseEntity<String> comparisonResponseSchema = get("/schemas/filtered?path=/employees/stats/comparison&operation=post&schemaType=response");
        assertEquals(200, comparisonResponseSchema.getStatusCode().value());
        JsonNode comparisonResponseBody = body(comparisonResponseSchema);
        assertTrue(comparisonResponseBody.path("properties").has("field"));
        assertTrue(comparisonResponseBody.path("properties").has("currentPeriod"));
        assertTrue(comparisonResponseBody.path("properties").has("previousPeriod"));
        assertTrue(comparisonResponseBody.path("properties").has("buckets"));

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

    @Test
    void employeesExposeMultiDimensionalCategoricalAndTemporalCrossTabs() throws Exception {
        ResponseEntity<String> crossTabResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "dimensions": [
                    { "field": "department", "extraction": "NONE", "alias": "department" },
                    { "field": "status", "extraction": "NONE", "alias": "status" }
                  ],
                  "metrics": [
                    { "operation": "COUNT", "alias": "headcount" },
                    { "operation": "SUM", "field": "salario", "alias": "payroll" }
                  ],
                  "fillGaps": true,
                  "orderBy": "KEY_ASC"
                }
                """);
        assertEquals(200, crossTabResponse.getStatusCode().value());
        JsonNode crossTabData = body(crossTabResponse).path("data");
        assertEquals(2, crossTabData.path("dimensions").size());
        assertEquals("department", crossTabData.path("dimensions").get(0).path("alias").asText());
        assertEquals("status", crossTabData.path("dimensions").get(1).path("alias").asText());

        // 2 departments (HR, OPS) x 2 observed statuses (ACTIVE, INACTIVE, LEAVE -> 3 statuses) = 6 cells
        JsonNode buckets = crossTabData.path("buckets");
        assertEquals(6, buckets.size());

        JsonNode hrActive = findBucketByCoordinates(buckets, "department", state.humanResourcesDepartmentId(), "status", "ACTIVE");
        assertNotNull(hrActive);
        assertEquals("Human Resources", hrActive.path("labels").get(0).asText());
        assertEquals("ACTIVE", hrActive.path("labels").get(1).asText());
        assertEquals(2, hrActive.path("count").asInt());
        assertEquals(2, hrActive.path("values").path("headcount").asInt());
        assertEquals(13250.0, hrActive.path("values").path("payroll").asDouble());

        // HR x LEAVE has 0 observations: count=0, headcount=0, payroll=null
        JsonNode hrLeave = findBucketByCoordinates(buckets, "department", state.humanResourcesDepartmentId(), "status", "LEAVE");
        assertNotNull(hrLeave);
        assertEquals(0, hrLeave.path("count").asInt());
        assertEquals(0, hrLeave.path("values").path("headcount").asInt());
        assertTrue(hrLeave.path("values").path("payroll").isNull());

        // Categorical x Temporal (department x admissionDate[YEAR])
        ResponseEntity<String> deptByYearResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "dimensions": [
                    { "field": "department", "extraction": "NONE", "alias": "department" },
                    { "field": "admissionDate", "extraction": "YEAR", "alias": "hireYear" }
                  ],
                  "timezone": "UTC",
                  "metrics": [
                    { "operation": "COUNT", "alias": "hires" }
                  ],
                  "orderBy": "KEY_ASC"
                }
                """);
        assertEquals(200, deptByYearResponse.getStatusCode().value());
        JsonNode deptByYearBuckets = body(deptByYearResponse).path("data").path("buckets");
        assertEquals(6, deptByYearBuckets.size());
        JsonNode hr2023 = findBucketByCoordinates(deptByYearBuckets, "department", state.humanResourcesDepartmentId(), "hireYear", "2023");
        assertNotNull(hr2023);
        assertEquals(1, hr2023.path("values").path("hires").asInt());
    }

    @Test
    void employeesExposeCyclicTemporalMatrixWithTimezoneFillGapsAndCycleOccurrenceAverage() throws Exception {
        // 14-day window [2024-03-04 .. 2024-03-17] in America/Sao_Paulo:
        // Every (weekday, hour) slot occurs exactly 2 times in this 14-day window.
        // Carol (2024-03-04T01:30:00Z = 2024-03-03T22:30-03:00) falls on Sunday 2024-03-03 in Sao Paulo (outside window!).
        // Alice (2024-03-04T12:15:00Z = Mon 09:15 BRT), Bob (2024-03-04T12:45:00Z = Mon 09:45 BRT),
        // and Diana (2024-03-11T12:20:00Z = Mon 09:20 BRT) all fall into (weekday=1, hour=9) in America/Sao_Paulo!
        ResponseEntity<String> matrixResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "dimensions": [
                    { "field": "lastCheckInAt", "extraction": "DAY_OF_WEEK", "alias": "weekday" },
                    { "field": "lastCheckInAt", "extraction": "HOUR_OF_DAY", "alias": "hour" }
                  ],
                  "timezone": "America/Sao_Paulo",
                  "from": "2024-03-04",
                  "to": "2024-03-17",
                  "fillGaps": true,
                  "limit": 168,
                  "metrics": [
                    { "operation": "COUNT", "alias": "count" },
                    { "operation": "AVG_PER_CYCLE_OCCURRENCE", "alias": "avgCountPerSlot" },
                    { "operation": "SUM", "field": "salario", "alias": "totalSalary" },
                    { "operation": "AVG_PER_CYCLE_OCCURRENCE", "field": "salario", "alias": "avgSalaryPerSlot" }
                  ],
                  "orderBy": "KEY_ASC"
                }
                """);
        assertEquals(200, matrixResponse.getStatusCode().value());
        JsonNode data = body(matrixResponse).path("data");
        assertEquals("2024-03-04", data.path("window").path("from").asText());
        assertEquals("2024-03-17", data.path("window").path("to").asText());
        assertEquals("America/Sao_Paulo", data.path("window").path("timezone").asText());

        JsonNode buckets = data.path("buckets");
        assertEquals(168, buckets.size());

        JsonNode monHour9 = findBucketByIntCoordinates(buckets, "weekday", 1, "hour", 9);
        assertNotNull(monHour9);
        assertEquals(3, monHour9.path("count").asInt());
        assertEquals(3, monHour9.path("values").path("count").asInt());
        assertEquals(1.5, monHour9.path("values").path("avgCountPerSlot").asDouble(), 0.0001);
        assertEquals(23100.0, monHour9.path("values").path("totalSalary").asDouble(), 0.001);
        assertEquals(11550.0, monHour9.path("values").path("avgSalaryPerSlot").asDouble(), 0.001);

        // Observed empty slot in the 14-day window: Tuesday (2) at 00:00
        JsonNode tueHour0 = findBucketByIntCoordinates(buckets, "weekday", 2, "hour", 0);
        assertNotNull(tueHour0);
        assertEquals(0, tueHour0.path("count").asInt());
        assertEquals(0, tueHour0.path("values").path("count").asInt());
        assertEquals(0.0, tueHour0.path("values").path("avgCountPerSlot").asDouble(), 0.0001);
        assertTrue(tueHour0.path("values").path("totalSalary").isNull());
        assertEquals(0.0, tueHour0.path("values").path("avgSalaryPerSlot").asDouble(), 0.0001);

        // In UTC over the same [2024-03-04 .. 2024-03-17] window, Carol (2024-03-04T01:30:00Z) IS inside the window at (weekday=1, hour=1)
        ResponseEntity<String> utcMatrixResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "dimensions": [
                    { "field": "lastCheckInAt", "extraction": "DAY_OF_WEEK", "alias": "weekday" },
                    { "field": "lastCheckInAt", "extraction": "HOUR_OF_DAY", "alias": "hour" }
                  ],
                  "timezone": "UTC",
                  "from": "2024-03-04",
                  "to": "2024-03-17",
                  "fillGaps": true,
                  "limit": 168,
                  "metrics": [
                    { "operation": "COUNT", "alias": "count" }
                  ],
                  "orderBy": "KEY_ASC"
                }
                """);
        assertEquals(200, utcMatrixResponse.getStatusCode().value());
        JsonNode utcBuckets = body(utcMatrixResponse).path("data").path("buckets");
        JsonNode utcMonHour1 = findBucketByIntCoordinates(utcBuckets, "weekday", 1, "hour", 1);
        assertNotNull(utcMonHour1);
        assertEquals(1, utcMonHour1.path("values").path("count").asInt());
    }

    @Test
    void employeesDistinguishUnobservedCycleSlotsInPartialWindowsAndDstTransitions() throws Exception {
        // 1-day window [2024-03-04 .. 2024-03-04] (Monday only) in UTC:
        // Monday (weekday=1) slots occur 1 time; Tuesday..Sunday (weekday=2..7) slots occur 0 times -> null!
        ResponseEntity<String> partialWindowResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "dimensions": [
                    { "field": "lastCheckInAt", "extraction": "DAY_OF_WEEK", "alias": "weekday" },
                    { "field": "lastCheckInAt", "extraction": "HOUR_OF_DAY", "alias": "hour" }
                  ],
                  "timezone": "UTC",
                  "from": "2024-03-04",
                  "to": "2024-03-04",
                  "fillGaps": true,
                  "limit": 168,
                  "metrics": [
                    { "operation": "COUNT", "alias": "count" },
                    { "operation": "AVG_PER_CYCLE_OCCURRENCE", "alias": "avgCount" }
                  ]
                }
                """);
        assertEquals(200, partialWindowResponse.getStatusCode().value());
        JsonNode partialBuckets = body(partialWindowResponse).path("data").path("buckets");
        JsonNode monHour0 = findBucketByIntCoordinates(partialBuckets, "weekday", 1, "hour", 0);
        assertNotNull(monHour0);
        assertEquals(0, monHour0.path("values").path("count").asInt());
        assertEquals(0.0, monHour0.path("values").path("avgCount").asDouble(), 0.0001);

        JsonNode tueHour0 = findBucketByIntCoordinates(partialBuckets, "weekday", 2, "hour", 0);
        assertNotNull(tueHour0);
        assertTrue(tueHour0.path("value").isNull());
        assertTrue(tueHour0.path("values").path("count").isNull());
        assertTrue(tueHour0.path("values").path("avgCount").isNull());

        // US DST spring-forward on Sunday 2024-03-10 in America/New_York: hour 2 does not exist!
        ResponseEntity<String> dstResponse = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "dimensions": [
                    { "field": "lastCheckInAt", "extraction": "DAY_OF_WEEK", "alias": "weekday" },
                    { "field": "lastCheckInAt", "extraction": "HOUR_OF_DAY", "alias": "hour" }
                  ],
                  "timezone": "America/New_York",
                  "from": "2024-03-10",
                  "to": "2024-03-10",
                  "fillGaps": true,
                  "limit": 168,
                  "metrics": [
                    { "operation": "COUNT", "alias": "count" },
                    { "operation": "AVG_PER_CYCLE_OCCURRENCE", "alias": "avgCount" }
                  ]
                }
                """);
        assertEquals(200, dstResponse.getStatusCode().value());
        JsonNode dstBuckets = body(dstResponse).path("data").path("buckets");
        JsonNode sunHour2 = findBucketByIntCoordinates(dstBuckets, "weekday", 7, "hour", 2);
        assertNotNull(sunHour2);
        assertTrue(sunHour2.path("values").path("count").isNull());
        assertTrue(sunHour2.path("values").path("avgCount").isNull());

        JsonNode sunHour3 = findBucketByIntCoordinates(dstBuckets, "weekday", 7, "hour", 3);
        assertNotNull(sunHour3);
        assertEquals(0, sunHour3.path("values").path("count").asInt());
        assertEquals(0.0, sunHour3.path("values").path("avgCount").asDouble(), 0.0001);
    }

    @Test
    void employeesEnforceServerSideRowScopeAcrossGroupByStats() throws Exception {
        HttpHeaders hrHeaders = new HttpHeaders();
        hrHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        hrHeaders.add("X-Test-Principal", "hr-resource-user");

        ResponseEntity<String> hrResponse = rest.getRestTemplate().exchange(
                java.net.URI.create(url("/employees/stats/group-by")),
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("""
                        {
                          "filter": {},
                          "dimensions": [
                            { "field": "department", "extraction": "NONE", "alias": "department" },
                            { "field": "status", "extraction": "NONE", "alias": "status" }
                          ],
                          "metrics": [
                            { "operation": "COUNT", "alias": "headcount" }
                          ],
                          "orderBy": "KEY_ASC"
                        }
                        """, hrHeaders),
                String.class
        );
        assertEquals(200, hrResponse.getStatusCode().value());
        JsonNode hrBuckets = body(hrResponse).path("data").path("buckets");
        assertEquals(2, hrBuckets.size());
        for (JsonNode bucket : hrBuckets) {
            assertEquals(state.humanResourcesDepartmentId().longValue(), bucket.path("coordinates").path("department").asLong());
        }

        HttpHeaders blockedHeaders = new HttpHeaders();
        blockedHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        blockedHeaders.add("X-Test-Principal", "blocked-resource-user");

        ResponseEntity<String> blockedResponse = rest.getRestTemplate().exchange(
                java.net.URI.create(url("/employees/stats/group-by")),
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("""
                        {
                          "filter": {},
                          "field": "status",
                          "metric": { "operation": "COUNT" }
                        }
                        """, blockedHeaders),
                String.class
        );
        assertEquals(200, blockedResponse.getStatusCode().value());
        assertEquals(0, body(blockedResponse).path("data").path("buckets").size());
    }

    private JsonNode findBucketByCoordinates(
            JsonNode buckets,
            String dim1,
            Long key1,
            String dim2,
            String key2
    ) {
        for (JsonNode bucket : buckets) {
            JsonNode coords = bucket.path("coordinates");
            if (coords.path(dim1).asLong(-1L) == key1 && key2.equals(coords.path(dim2).asText())) {
                return bucket;
            }
        }
        return null;
    }

    private JsonNode findBucketByIntCoordinates(
            JsonNode buckets,
            String dim1,
            int key1,
            String dim2,
            int key2
    ) {
        for (JsonNode bucket : buckets) {
            JsonNode coords = bucket.path("coordinates");
            if (coords.path(dim1).asInt(-1) == key1 && coords.path(dim2).asInt(-1) == key2) {
                return bucket;
            }
        }
        return null;
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
