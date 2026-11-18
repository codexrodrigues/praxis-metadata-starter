package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        // 2x2 matrix with 1 rare status cell in HR (INACTIVE=1) and 1 in OPS (LEAVE=1)
        // forces complementary suppression on (HR,ACTIVE)=2 and (OPS,ACTIVE)=2 to block marginal inference
        assertEquals(4, data2D.path("suppressedBucketCount").asInt());
        assertEquals(2, data2D.path("minBucketCount").asInt());
        assertEquals(0, data2D.path("buckets").size());

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

    @Test
    void timeSeriesAndComparisonEnforceMinBucketCountAndComplementarySuppression() throws Exception {
        // Every individual admission month in 2022-11..2023-03 has count = 1 (< 2) -> all non-empty points suppressed!
        ResponseEntity<String> tsNoGaps = postJson("/employees/stats/timeseries", """
                {
                  "filter": {},
                  "field": "admissionDate",
                  "granularity": "MONTH",
                  "metric": { "operation": "COUNT" },
                  "from": "2022-11-01",
                  "to": "2023-03-31",
                  "fillGaps": false
                }
                """);
        assertEquals(200, tsNoGaps.getStatusCode().value());
        JsonNode tsNoGapsData = body(tsNoGaps).path("data");
        assertTrue(tsNoGapsData.path("suppressed").asBoolean());
        assertEquals(3, tsNoGapsData.path("suppressedBucketCount").asInt()); // 2022-11, 2023-01, 2023-02
        assertEquals(2, tsNoGapsData.path("minBucketCount").asInt());
        assertEquals(0, tsNoGapsData.path("points").size());

        ResponseEntity<String> tsWithGaps = postJson("/employees/stats/timeseries", """
                {
                  "filter": {},
                  "field": "admissionDate",
                  "granularity": "MONTH",
                  "metric": { "operation": "COUNT" },
                  "from": "2022-11-01",
                  "to": "2023-03-31",
                  "fillGaps": true
                }
                """);
        assertEquals(200, tsWithGaps.getStatusCode().value());
        JsonNode tsWithGapsData = body(tsWithGaps).path("data");
        assertTrue(tsWithGapsData.path("suppressed").asBoolean());
        assertEquals(3, tsWithGapsData.path("suppressedBucketCount").asInt());
        assertEquals(5, tsWithGapsData.path("points").size());
        // Suppressed slot 2023-01 must have null value and 0 count (no leak of count=1)
        assertTrue(tsWithGapsData.path("points").get(2).path("value").isNull());
        assertEquals(0, tsWithGapsData.path("points").get(2).path("count").asInt());

        // In comparison over Feb 2023 vs Jan 2023: HR=(0 vs 1) and OPS=(1 vs 0); both have <2 on their active side
        // so both comparison buckets must be suppressed instead of leaking false -100%/0 counts!
        ResponseEntity<String> comparison = postJson("/employees/stats/comparison", """
                {
                  "filter": {},
                  "field": "department",
                  "periodField": "admissionDate",
                  "metrics": [{ "operation": "COUNT", "alias": "employees" }],
                  "period": { "from": "2023-02-01", "to": "2023-02-28", "timezone": "UTC" },
                  "orderBy": "KEY_ASC"
                }
                """);
        assertEquals(200, comparison.getStatusCode().value());
        JsonNode compData = body(comparison).path("data");
        assertTrue(compData.path("suppressed").asBoolean());
        assertEquals(2, compData.path("suppressedBucketCount").asInt());
        assertEquals(2, compData.path("minBucketCount").asInt());
        assertEquals(0, compData.path("buckets").size());

        // Asymmetric comparison pair: OPS=(prev=2, curr=1 < 2), HR=(prev=2, curr=2), FIN=(prev=3, curr=3).
        // OPS must be suppressed instead of turning current=1 into 0 (-100%), and HR must be complementarily suppressed.
        insertEmployee(state.humanResourcesDepartmentId(), "HR-J2", new BigDecimal("5000"), LocalDate.of(2023, 1, 12));
        insertEmployee(state.humanResourcesDepartmentId(), "HR-F1", new BigDecimal("5000"), LocalDate.of(2023, 2, 10));
        insertEmployee(state.humanResourcesDepartmentId(), "HR-F2", new BigDecimal("5000"), LocalDate.of(2023, 2, 11));
        insertEmployee(state.operationsDepartmentId(), "OPS-J1", new BigDecimal("5000"), LocalDate.of(2023, 1, 12));
        insertEmployee(state.operationsDepartmentId(), "OPS-J2", new BigDecimal("5000"), LocalDate.of(2023, 1, 13));

        jdbcTemplate.update("INSERT INTO e2e_departments (nome, code) VALUES ('Finance', 'FIN')");
        Long finDeptId = jdbcTemplate.queryForObject("SELECT id FROM e2e_departments WHERE code = 'FIN'", Long.class);
        insertEmployee(finDeptId, "FIN-J1", new BigDecimal("5000"), LocalDate.of(2023, 1, 10));
        insertEmployee(finDeptId, "FIN-J2", new BigDecimal("5000"), LocalDate.of(2023, 1, 11));
        insertEmployee(finDeptId, "FIN-J3", new BigDecimal("5000"), LocalDate.of(2023, 1, 12));
        insertEmployee(finDeptId, "FIN-F1", new BigDecimal("5000"), LocalDate.of(2023, 2, 10));
        insertEmployee(finDeptId, "FIN-F2", new BigDecimal("5000"), LocalDate.of(2023, 2, 11));
        insertEmployee(finDeptId, "FIN-F3", new BigDecimal("5000"), LocalDate.of(2023, 2, 12));

        ResponseEntity<String> asymmetricComparison = postJson("/employees/stats/comparison", """
                {
                  "filter": {},
                  "field": "department",
                  "periodField": "admissionDate",
                  "metrics": [{ "operation": "COUNT", "alias": "employees" }],
                  "period": { "from": "2023-02-01", "to": "2023-02-28", "timezone": "UTC" },
                  "orderBy": "KEY_ASC"
                }
                """);
        assertEquals(200, asymmetricComparison.getStatusCode().value());
        JsonNode asymData = body(asymmetricComparison).path("data");
        assertTrue(asymData.path("suppressed").asBoolean());
        assertEquals(2, asymData.path("suppressedBucketCount").asInt());
        assertEquals(1, asymData.path("buckets").size());
        assertEquals("Finance", asymData.path("buckets").get(0).path("label").asText());
        assertEquals(3, asymData.path("buckets").get(0).path("values").path("employees").path("current").asInt());

        // Numeric comparison LGPD guard: LEGAL has 3 employees in both periods, but Feb has only 1 non-null salario (< 2)
        jdbcTemplate.update("INSERT INTO e2e_departments (nome, code) VALUES ('Legal', 'LEG')");
        Long legDeptId = jdbcTemplate.queryForObject("SELECT id FROM e2e_departments WHERE code = 'LEG'", Long.class);
        insertEmployee(legDeptId, "LEG-J1", new BigDecimal("7000"), LocalDate.of(2023, 1, 10));
        insertEmployee(legDeptId, "LEG-J2", new BigDecimal("7000"), LocalDate.of(2023, 1, 11));
        insertEmployee(legDeptId, "LEG-J3", new BigDecimal("7000"), LocalDate.of(2023, 1, 12));
        insertEmployee(legDeptId, "LEG-F1", new BigDecimal("9999"), LocalDate.of(2023, 2, 10));
        insertEmployee(legDeptId, "LEG-F2", null, LocalDate.of(2023, 2, 11));
        insertEmployee(legDeptId, "LEG-F3", null, LocalDate.of(2023, 2, 12));

        ResponseEntity<String> sumComparison = postJson("/employees/stats/comparison", """
                {
                  "filter": {},
                  "field": "department",
                  "periodField": "admissionDate",
                  "metrics": [{ "operation": "SUM", "field": "salario", "alias": "payroll" }],
                  "period": { "from": "2023-02-01", "to": "2023-02-28", "timezone": "UTC" },
                  "orderBy": "KEY_ASC"
                }
                """);
        assertEquals(200, sumComparison.getStatusCode().value());
        JsonNode sumCompData = body(sumComparison).path("data");
        assertTrue(sumCompData.path("suppressed").asBoolean());
        // Primary suppressed: OPS (count 1) and LEGAL (numericCount 1 in Feb); HR (2) & FIN (3) survive!
        assertEquals(2, sumCompData.path("suppressedBucketCount").asInt());
        assertEquals(2, sumCompData.path("buckets").size());
        assertEquals("Human Resources", sumCompData.path("buckets").get(0).path("label").asText());
        assertEquals("Finance", sumCompData.path("buckets").get(1).path("label").asText());
    }

    private void insertEmployee(Long departmentId, String matricula, BigDecimal salario, LocalDate admissionDate) {
        jdbcTemplate.update(
                "INSERT INTO e2e_employees (nome, matricula, payroll_profile_label, payroll_profile, faixa_pct_desconto, status, salario, admission_date, department_id) "
                        + "VALUES (?, ?, 'Operational', 'OPS', '0-10%', 'ACTIVE', ?, ?, ?)",
                matricula,
                matricula,
                salario,
                Date.valueOf(admissionDate),
                departmentId
        );
    }
}
