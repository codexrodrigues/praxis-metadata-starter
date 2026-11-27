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
        assertFalse(capsBody.path("canonicalOperations").path("statsSimulation").asBoolean());
        assertFalse(capsBody.path("canonicalOperations").path("statsCounterfactual").asBoolean());
        assertFalse(capsBody.path("operations").path("statsInference").path("supported").asBoolean());
        assertFalse(capsBody.path("operations").path("statsAnomalies").path("supported").asBoolean());
        assertFalse(capsBody.path("operations").path("statsSimulation").path("supported").asBoolean());
        assertFalse(capsBody.path("operations").path("statsCounterfactual").path("supported").asBoolean());

        ResponseEntity<String> statsCapsResponse = get("/intelligent-employees/stats/capabilities");
        assertEquals(200, statsCapsResponse.getStatusCode().value());
        assertFalse(body(statsCapsResponse).path("inference").asBoolean());
        assertFalse(body(statsCapsResponse).path("anomalies").asBoolean());
        assertFalse(body(statsCapsResponse).path("simulation").asBoolean());
        assertFalse(body(statsCapsResponse).path("counterfactual").asBoolean());

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

        ResponseEntity<String> simulationResponse = postJson("/intelligent-employees/stats/simulation", """
                {
                  "filter": {},
                  "modelPoints": [
                    {
                      "pointKey": "pt-1",
                      "segmentLabel": "Active",
                      "weightCount": 10,
                      "baseAmount": 1000.00,
                      "periodGrowthFactors": [],
                      "factorSensitivities": { "wage_index": 1.0 }
                    }
                  ],
                  "stochasticFactors": [
                    {
                      "factorKey": "wage_index",
                      "distribution": "NORMAL",
                      "parameters": { "mean": 0.04, "stdDev": 0.02 },
                      "applyPerPeriod": false
                    }
                  ]
                }
                """);
        assertEquals(503, simulationResponse.getStatusCode().value());
        assertEquals(
                "INTELLIGENCE_ENGINE_UNAVAILABLE",
                body(simulationResponse).path("errors").get(0).path("errorCode").asText()
        );

        ResponseEntity<String> cfResponse = postJson("/intelligent-employees/stats/counterfactual", """
                {
                  "filter": {},
                  "targetMetricKey": "fiscal_ratio",
                  "direction": "DECREASE_TO_TARGET",
                  "baselineValue": 0.55,
                  "targetValue": 0.49,
                  "mutableLevers": [
                    {
                      "leverKey": "headcount_delta",
                      "leverType": "DISCRETE_INT",
                      "currentValue": 20,
                      "minValue": 0,
                      "maxValue": 100,
                      "stepSize": 5,
                      "allowedCategories": [],
                      "costWeight": 1.0,
                      "linearSensitivity": -0.01,
                      "categoryImpacts": {},
                      "monotoneSign": -1
                    }
                  ]
                }
                """);
        assertEquals(503, cfResponse.getStatusCode().value());
        assertEquals(
                "INTELLIGENCE_ENGINE_UNAVAILABLE",
                body(cfResponse).path("errors").get(0).path("errorCode").asText()
        );
    }
}
