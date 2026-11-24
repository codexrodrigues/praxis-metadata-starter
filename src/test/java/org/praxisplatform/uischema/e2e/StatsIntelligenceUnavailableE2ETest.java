package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ActiveProfiles({"e2e-h2", "e2e-intelligence-unavailable"})
@TestPropertySource(properties = {
        "praxis.stats.enabled=true"
})
class StatsIntelligenceUnavailableE2ETest extends AbstractE2eH2Test {

    @Test
    void reportsFalseCapabilitiesAndReturns503WhenIntelligenceExecutorBeanIsAbsent() throws Exception {
        ResponseEntity<String> capsResponse = get("/intelligent-employees/capabilities");
        assertEquals(200, capsResponse.getStatusCode().value());
        JsonNode capsBody = body(capsResponse);
        assertFalse(capsBody.path("canonicalOperations").path("statsInference").asBoolean());
        assertFalse(capsBody.path("canonicalOperations").path("statsAnomalies").asBoolean());
        assertFalse(capsBody.path("operations").path("statsInference").path("supported").asBoolean());
        assertFalse(capsBody.path("operations").path("statsAnomalies").path("supported").asBoolean());

        ResponseEntity<String> statsCapsResponse = get("/intelligent-employees/stats/capabilities");
        assertEquals(200, statsCapsResponse.getStatusCode().value());
        assertFalse(body(statsCapsResponse).path("inference").asBoolean());
        assertFalse(body(statsCapsResponse).path("anomalies").asBoolean());

        HttpHeaders corrHeaders = new HttpHeaders();
        corrHeaders.setContentType(MediaType.APPLICATION_JSON);
        corrHeaders.add("X-Correlation-ID", "intel-e2e-503");

        ResponseEntity<String> inferenceResponse = rest.getRestTemplate().exchange(
                URI.create(url("/intelligent-employees/stats/inference")),
                HttpMethod.POST,
                new HttpEntity<>("""
                {
                  "filter": {},
                  "tests": ["WILCOXON_SIGNED_RANK"],
                  "expectedField": "salario",
                  "observedField": "salario"
                }
                """, corrHeaders),
                String.class
        );
        assertEquals(503, inferenceResponse.getStatusCode().value());
        assertEquals("intel-e2e-503", inferenceResponse.getHeaders().getFirst("X-Correlation-ID"));
        JsonNode inferenceBody = body(inferenceResponse);
        assertEquals(
                "INTELLIGENCE_ENGINE_UNAVAILABLE",
                inferenceBody.path("errors").get(0).path("errorCode").asText()
        );
        assertEquals(
                "intel-e2e-503",
                inferenceBody.path("errors").get(0).path("correlationId").asText()
        );

        ResponseEntity<String> anomaliesResponse = postJson("/intelligent-employees/stats/anomalies", """
                {
                  "filter": {},
                  "methods": ["ROBUST_MAD_ZSCORE"],
                  "columnMapping": {
                    "entityKeyColumn": "employeeKey",
                    "cohortColumn": "status",
                    "groupColumn": "payrollProfile",
                    "metricColumn": "status",
                    "expectedColumn": "salario",
                    "observedColumn": "salario",
                    "residualColumn": "salario",
                    "variableFlagColumn": "status",
                    "unitColumn": "department"
                  }
                }
                """);
        assertEquals(503, anomaliesResponse.getStatusCode().value());
        JsonNode anomaliesBody = body(anomaliesResponse);
        assertEquals(
                "INTELLIGENCE_ENGINE_UNAVAILABLE",
                anomaliesBody.path("errors").get(0).path("errorCode").asText()
        );
    }
}
