package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "praxis.stats.enabled=true",
        "praxis.stats.max-buckets=10",
        "praxis.stats.max-series-points=24",
        "praxis.stats.max-in-memory-sample-rows=4"
})
class StatsSampleLimitE2ETest extends AbstractE2eH2Test {

    @Test
    void enforcesMaxInMemorySampleRowsGuardRailOverHttpForUnfilteredInMemoryQueries() throws Exception {
        // Seed contains 6 employees (> maxInMemorySampleRows=4): unfiltered percentile must return clean HTTP 400
        ResponseEntity<String> unfilteredPercentile = postJson("/employees/stats/group-by", """
                {
                  "filter": {},
                  "field": "department",
                  "metrics": [
                    { "operation": "PERCENTILE_25", "field": "salario", "alias": "p25" }
                  ]
                }
                """);
        assertEquals(400, unfilteredPercentile.getStatusCode().value());
        JsonNode errorBody = body(unfilteredPercentile);
        assertTrue(errorBody.path("message").asText("").toLowerCase().contains("sample rows"));

        // Narrower filter returning 3 HR employees (<= 4) succeeds cleanly
        ResponseEntity<String> filteredPercentile = postJson("/employees/stats/group-by", """
                {
                  "filter": { "departmentId": %d },
                  "field": "department",
                  "metrics": [
                    { "operation": "PERCENTILE_25", "field": "salario", "alias": "p25" }
                  ]
                }
                """.formatted(state.humanResourcesDepartmentId()));
        assertEquals(200, filteredPercentile.getStatusCode().value());
    }
}
