package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "praxis.stats.enabled=true",
        "praxis.stats.max-buckets=10",
        "praxis.stats.max-series-points=24",
        "praxis.stats.max-time-series-window-days=45",
        "praxis.stats.default-min-bucket-count=2"
})
class StatsGovernanceE2ETest extends AbstractE2eH2Test {

    @Test
    void capabilitiesExposeGovernedTimeSeriesWindowAndDefaultSuppressionThreshold() throws Exception {
        ResponseEntity<String> response = get("/employees/capabilities");
        assertEquals(200, response.getStatusCode().value());

        JsonNode stats = body(response).path("stats");
        assertEquals(45, stats.path("maxTimeSeriesWindowDays").asInt());
        assertEquals(2, stats.path("defaultMinBucketCount").asInt());
        assertEquals("status", stats.path("fields").get(0).path("field").asText());
        assertTrue(stats.path("fields").get(0).path("minBucketCount").isMissingNode());
        assertEquals("protectedProfile", stats.path("fields").get(7).path("field").asText());
        assertEquals(2, stats.path("fields").get(7).path("minBucketCount").asInt());
    }

    @Test
    void defaultMinBucketCountProtectsStandard1D2DAndHistogramQueriesWithoutOrderingLeakage() throws Exception {
        // Seed counts for status: ACTIVE=4, INACTIVE=1, LEAVE=1
        // VALUE_ASC with limit=1 proves suppressed singletons do not consume limit or reveal rank via sort
        ResponseEntity<String> groupBy1D = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "field": "status",
                  "metric": { "operation": "COUNT" },
                  "orderBy": "VALUE_ASC",
                  "limit": 1
                }
                """);
        assertEquals(200, groupBy1D.getStatusCode().value());
        JsonNode data1D = body(groupBy1D).path("data");
        assertTrue(data1D.path("suppressed").asBoolean());
        assertEquals(2, data1D.path("suppressedBucketCount").asInt());
        assertEquals(2, data1D.path("minBucketCount").asInt());
        assertEquals(1, data1D.path("buckets").size());
        assertEquals("ACTIVE", data1D.path("buckets").get(0).path("key").asText());
        assertEquals(4, data1D.path("buckets").get(0).path("count").asInt());

        // When all groups meet the threshold (department: HR=3, OPS=3), suppressed=false explicitly
        ResponseEntity<String> unsuppressed = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "field": "department",
                  "metric": { "operation": "COUNT" },
                  "orderBy": "KEY_ASC"
                }
                """);
        assertEquals(200, unsuppressed.getStatusCode().value());
        JsonNode unsuppressedData = body(unsuppressed).path("data");
        assertFalse(unsuppressedData.path("suppressed").asBoolean());
        assertEquals(0, unsuppressedData.path("suppressedBucketCount").asInt());
        assertEquals(2, unsuppressedData.path("minBucketCount").asInt());
        assertEquals(2, unsuppressedData.path("buckets").size());

        // 2D fillGaps=true must omit both 0-count gap cells and <2 cells so 0 vs 1 cannot be distinguished
        ResponseEntity<String> groupBy2D = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "dimensions": [
                    { "field": "department", "alias": "dept" },
                    { "field": "status", "alias": "status" }
                  ],
                  "metric": { "operation": "COUNT" },
                  "orderBy": "VALUE_ASC",
                  "fillGaps": true,
                  "limit": 20
                }
                """);
        assertEquals(200, groupBy2D.getStatusCode().value());
        JsonNode data2D = body(groupBy2D).path("data");
        assertTrue(data2D.path("suppressed").asBoolean());
        assertEquals(2, data2D.path("suppressedBucketCount").asInt());
        assertEquals(2, data2D.path("minBucketCount").asInt());
        assertEquals(2, data2D.path("buckets").size());
        for (JsonNode cell : data2D.path("buckets")) {
            assertEquals("ACTIVE", cell.path("coordinates").path("status").asText());
            assertEquals(2, cell.path("count").asInt());
        }

        // HISTOGRAM also enforces defaultMinBucketCount=2: only [6000,7000) has 2 records (6050, 6800)
        ResponseEntity<String> histogram = postJson("/employees/stats/distribution", """
                {
                  "filter": {},
                  "field": "salario",
                  "mode": "HISTOGRAM",
                  "metric": { "operation": "COUNT" },
                  "bucketSize": 1000,
                  "orderBy": "VALUE_ASC"
                }
                """);
        assertEquals(200, histogram.getStatusCode().value());
        JsonNode histData = body(histogram).path("data");
        assertTrue(histData.path("suppressed").asBoolean());
        assertEquals(4, histData.path("suppressedBucketCount").asInt());
        assertEquals(2, histData.path("minBucketCount").asInt());
        assertEquals(1, histData.path("buckets").size());
        assertEquals(6000.0, histData.path("buckets").get(0).path("from").asDouble());
    }

    @Test
    void maxTimeSeriesWindowDaysRejectsOversizedIntervalAndDaySeriesWhileAllowingPointInTimeMonths() throws Exception {
        ResponseEntity<String> validInterval = postJson("/employees/stats/timeseries", """
                {
                  "filter": {},
                  "field": "activePeriod",
                  "granularity": "MONTH",
                  "metric": { "operation": "COUNT" },
                  "from": "2023-01-01",
                  "to": "2023-01-31"
                }
                """);
        assertEquals(200, validInterval.getStatusCode().value());
        assertEquals(4, body(validInterval).path("data").path("points").get(0).path("value").asInt());

        ResponseEntity<String> exceededInterval = postJson("/employees/stats/timeseries", """
                {
                  "filter": {},
                  "field": "activePeriod",
                  "granularity": "MONTH",
                  "metric": { "operation": "COUNT" },
                  "from": "2023-01-01",
                  "to": "2023-03-01"
                }
                """);
        assertEquals(400, exceededInterval.getStatusCode().value());
        assertTrue(body(exceededInterval).path("message").asText("").toLowerCase().contains("45 days"));

        ResponseEntity<String> exceededDailyPoint = postJson("/employees/stats/timeseries", """
                {
                  "filter": {},
                  "field": "admissionDate",
                  "granularity": "DAY",
                  "metric": { "operation": "COUNT" },
                  "from": "2023-01-01",
                  "to": "2023-02-20"
                }
                """);
        assertEquals(400, exceededDailyPoint.getStatusCode().value());

        ResponseEntity<String> pointInTimeMonths = postJson("/employees/stats/timeseries", """
                {
                  "filter": {},
                  "field": "admissionDate",
                  "granularity": "MONTH",
                  "metric": { "operation": "COUNT" },
                  "from": "2022-11-01",
                  "to": "2024-03-31"
                }
                """);
        assertEquals(200, pointInTimeMonths.getStatusCode().value());
    }
}
