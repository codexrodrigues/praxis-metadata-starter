package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.intelligence.contracts.IntelligenceContractVersion;
import org.praxisplatform.intelligence.contracts.spi.TabularColumnDescriptor;
import org.praxisplatform.intelligence.contracts.spi.TabularLogicalType;
import org.praxisplatform.uischema.e2e.fixture.E2eIntelligenceFixtureConfiguration.KeysetBatchProbe;
import org.praxisplatform.uischema.e2e.fixture.E2eIntelligenceFixtureConfiguration.RecordingResourceIntelligenceExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles({"e2e-h2", "e2e-intelligence"})
@TestPropertySource(properties = {
        "praxis.stats.enabled=true",
        "praxis.stats.max-buckets=10",
        "praxis.stats.max-series-points=24"
})
class StatsIntelligenceE2ETest extends AbstractE2eH2Test {

    @Autowired
    private RecordingResourceIntelligenceExecutor intelligenceExecutor;

    @Autowired
    private KeysetBatchProbe keysetBatchProbe;

    @BeforeEach
    void resetRecorder() {
        intelligenceExecutor.reset();
    }

    @Test
    void optedInResourcePublishesCapabilitiesAndDomainMetadataWhileOthersStayDisabled() throws Exception {
        ResponseEntity<String> capsResponse = get("/intelligent-employees/capabilities");
        assertEquals(200, capsResponse.getStatusCode().value());
        JsonNode capsBody = body(capsResponse);
        assertTrue(capsBody.path("canonicalOperations").path("statsInference").asBoolean());
        assertTrue(capsBody.path("canonicalOperations").path("statsAnomalies").asBoolean());
        assertTrue(capsBody.path("operations").path("statsInference").path("supported").asBoolean());
        assertTrue(capsBody.path("operations").path("statsAnomalies").path("supported").asBoolean());
        assertTrue(capsBody.path("stats").path("inference").asBoolean());
        assertTrue(capsBody.path("stats").path("anomalies").asBoolean());

        ResponseEntity<String> statsCapsResponse = get("/intelligent-employees/stats/capabilities");
        assertEquals(200, statsCapsResponse.getStatusCode().value());
        JsonNode statsCapsBody = body(statsCapsResponse);
        assertTrue(statsCapsBody.path("inference").asBoolean());
        assertTrue(statsCapsBody.path("anomalies").asBoolean());

        ResponseEntity<String> domainResponse = get("/schemas/domain?resource=human-resources.intelligent-employees");
        assertEquals(200, domainResponse.getStatusCode().value());
        JsonNode domainBody = body(domainResponse);
        JsonNode conceptNode = findNodeByKey(domainBody.path("nodes"), "human-resources.intelligent-employees");
        assertNotNull(conceptNode);
        assertTrue(conceptNode.path("metadata").path("capabilities").path("statsInference").asBoolean());
        assertTrue(conceptNode.path("metadata").path("capabilities").path("statsAnomalies").asBoolean());

        List<String> statsCapabilityKeys = new ArrayList<>();
        for (JsonNode node : domainBody.path("nodes")) {
            if ("stats".equals(node.path("nodeType").asText())) {
                String capKey = node.path("metadata").path("capabilityKey").asText();
                if (!capKey.isBlank()) {
                    statsCapabilityKeys.add(capKey);
                }
            }
        }
        assertTrue(statsCapabilityKeys.contains("stats.inference"),
                "Expected stats.inference node in /schemas/domain: " + statsCapabilityKeys);
        assertTrue(statsCapabilityKeys.contains("stats.anomalies"),
                "Expected stats.anomalies node in /schemas/domain: " + statsCapabilityKeys);

        ResponseEntity<String> standardCaps = get("/employees/capabilities");
        assertEquals(200, standardCaps.getStatusCode().value());
        assertFalse(body(standardCaps).path("canonicalOperations").path("statsInference").asBoolean());
        assertFalse(body(standardCaps).path("canonicalOperations").path("statsAnomalies").asBoolean());

        ResponseEntity<String> disabledInference = postJson("/employees/stats/inference", """
                {
                  "filter": {},
                  "tests": ["WELCH_T"],
                  "expectedField": "salario",
                  "observedField": "salario"
                }
                """);
        assertEquals(501, disabledInference.getStatusCode().value());

        ResponseEntity<String> disabledAnomalies = postJson("/employees/stats/anomalies", """
                {
                  "filter": {},
                  "methods": ["ROBUST_MAD_ZSCORE"]
                }
                """);
        assertEquals(501, disabledAnomalies.getStatusCode().value());
    }

    @Test
    void inferenceEndpointStreamsFilteredScopeEnforcesKAnonymityAndRejectsInvalidFields() throws Exception {
        ResponseEntity<String> unfiltered = postJson("/intelligent-employees/stats/inference", """
                {
                  "filter": {},
                  "tests": ["WILCOXON_SIGNED_RANK"],
                  "groupByField": "protectedProfile",
                  "expectedField": "salario",
                  "observedField": "salario",
                  "minBucketCount": 1,
                  "seed": 42
                }
                """);
        assertEquals(200, unfiltered.getStatusCode().value());
        JsonNode unfilteredBody = body(unfiltered);
        assertEquals(IntelligenceContractVersion.V1, unfilteredBody.path("data").path("contractVersion").asText());
        assertEquals("CALIBRATED_PROBABILITY", unfilteredBody.path("data").path("provenance").path("epistemicKind").asText());
        assertEquals(6L, unfilteredBody.path("data").path("provenance").path("sampleSize").asLong());
        assertEquals(2, intelligenceExecutor.lastInferenceRequest().minBucketCount());
        assertEquals(6, intelligenceExecutor.lastRows().size());
        assertTrue(intelligenceExecutor.lastTransactionActiveInsideStream());
        assertTrue(intelligenceExecutor.lastTransactionReadOnlyInsideStream());
        assertFalse(intelligenceExecutor.lastTransactionActiveAfterStream());
        assertFalse(unfilteredBody.path("_links").path("filter").path("href").asText().isBlank());
        List<String> schemaHrefs = extractSchemaHrefs(unfilteredBody.path("_links").path("schema"));
        String requestSchemaHref = schemaHrefs.stream()
                .filter(href -> href.contains("schemaType=request")
                        && (href.contains("/intelligent-employees/stats/inference")
                        || href.contains("%2Fintelligent-employees%2Fstats%2Finference")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing inference request schema link: " + schemaHrefs));
        String responseSchemaHref = schemaHrefs.stream()
                .filter(href -> href.contains("schemaType=response")
                        && (href.contains("/intelligent-employees/stats/inference")
                        || href.contains("%2Fintelligent-employees%2Fstats%2Finference")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing inference response schema link: " + schemaHrefs));

        ResponseEntity<String> reqSchema = getHref(requestSchemaHref);
        assertEquals(200, reqSchema.getStatusCode().value());
        assertTrue(body(reqSchema).has("properties"));
        ResponseEntity<String> respSchema = getHref(responseSchemaHref);
        assertEquals(200, respSchema.getStatusCode().value());
        assertTrue(body(respSchema).has("properties"));

        ResponseEntity<String> activeOnly = postJson("/intelligent-employees/stats/inference", """
                {
                  "filter": { "status": "ACTIVE" },
                  "tests": ["WELCH_T"],
                  "groupByField": "status",
                  "expectedField": "salario",
                  "observedField": "salario"
                }
                """);
        assertEquals(200, activeOnly.getStatusCode().value());
        assertEquals(4, intelligenceExecutor.lastRows().size());

        ResponseEntity<String> hrOnly = postWithPrincipal("/intelligent-employees/stats/inference", "hr-resource-user", """
                {
                  "filter": {},
                  "tests": ["WELCH_T"],
                  "groupByField": "status",
                  "expectedField": "salario",
                  "observedField": "salario"
                }
                """);
        assertEquals(200, hrOnly.getStatusCode().value());
        assertEquals(3, intelligenceExecutor.lastRows().size());

        ResponseEntity<String> blocked = postWithPrincipal("/intelligent-employees/stats/inference", "blocked-resource-user", """
                {
                  "filter": {},
                  "tests": ["WELCH_T"],
                  "groupByField": "status",
                  "expectedField": "salario",
                  "observedField": "salario"
                }
                """);
        assertEquals(200, blocked.getStatusCode().value());
        assertEquals(0, intelligenceExecutor.lastRows().size());

        ResponseEntity<String> missingExpectedForWelch = postJson("/intelligent-employees/stats/inference", """
                {
                  "filter": {},
                  "tests": ["WELCH_T"],
                  "groupByField": "status",
                  "observedField": "salario"
                }
                """);
        assertEquals(400, missingExpectedForWelch.getStatusCode().value());

        ResponseEntity<String> spoofedDatasetKey = postJson("/intelligent-employees/stats/inference", """
                {
                  "filter": {},
                  "datasetKey": "other.unauthorized.dataset",
                  "tests": ["WELCH_T"],
                  "expectedField": "salario",
                  "observedField": "salario"
                }
                """);
        assertEquals(400, spoofedDatasetKey.getStatusCode().value());

        ResponseEntity<String> unknownField = postJson("/intelligent-employees/stats/inference", """
                {
                  "filter": {},
                  "tests": ["WELCH_T"],
                  "expectedField": "salario",
                  "observedField": "nonExistentField"
                }
                """);
        assertEquals(400, unknownField.getStatusCode().value());
    }

    @Test
    void anomalyScanEndpointStreamsPseudonymizedIdsAndRejectsUnsafeColumnReuse() throws Exception {
        String validPayload = """
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
                  },
                  "alpha": 0.05,
                  "madThreshold": 3.5,
                  "maxFindings": 50
                }
                """;
        ResponseEntity<String> response = postJson("/intelligent-employees/stats/anomalies", validPayload);
        assertEquals(200, response.getStatusCode().value());
        JsonNode json = body(response);
        assertEquals(IntelligenceContractVersion.V1, json.path("data").path("contractVersion").asText());
        assertEquals(6L, json.path("data").path("summary").path("rowsScanned").asLong());
        assertEquals(
                new TabularColumnDescriptor("employeeKey", TabularLogicalType.ENTITY_ID_TO_PSEUDONYMIZE),
                intelligenceExecutor.lastColumns().getFirst()
        );
        assertEquals(6, intelligenceExecutor.lastRows().size());
        for (String schemaHref : extractSchemaHrefs(json.path("_links").path("schema"))) {
            ResponseEntity<String> schemaRes = getHref(schemaHref);
            assertEquals(200, schemaRes.getStatusCode().value());
            assertTrue(body(schemaRes).has("properties"));
        }

        ResponseEntity<String> hrResponse = postWithPrincipal("/intelligent-employees/stats/anomalies", "hr-resource-user", validPayload);
        assertEquals(200, hrResponse.getStatusCode().value());
        assertEquals(3, intelligenceExecutor.lastRows().size());

        ResponseEntity<String> reusedKey = postJson("/intelligent-employees/stats/anomalies", """
                {
                  "filter": {},
                  "methods": ["ROBUST_MAD_ZSCORE"],
                  "columnMapping": {
                    "entityKeyColumn": "status",
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
        assertEquals(400, reusedKey.getStatusCode().value());

        List<String> streamedKeys = keysetBatchProbe.streamAllEmployeeKeysWithBatchSize(2);
        List<String> expectedSortedKeys = state.employeeIdsInSeedOrder().stream()
                .sorted()
                .map(String::valueOf)
                .toList();
        assertEquals(expectedSortedKeys, streamedKeys);
    }

    private ResponseEntity<String> postWithPrincipal(String path, String principal, String bodyJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Test-Principal", principal);
        return rest.getRestTemplate().exchange(
                URI.create(url(path)),
                HttpMethod.POST,
                new HttpEntity<>(bodyJson, headers),
                String.class
        );
    }

    private static List<String> extractSchemaHrefs(JsonNode schemaLinksNode) {
        List<String> hrefs = new ArrayList<>();
        if (schemaLinksNode.isArray()) {
            schemaLinksNode.forEach(node -> hrefs.add(node.path("href").asText()));
        } else if (schemaLinksNode.isObject()) {
            hrefs.add(schemaLinksNode.path("href").asText());
        }
        return hrefs;
    }

    private static JsonNode findNodeByKey(JsonNode nodes, String nodeKey) {
        for (JsonNode node : nodes) {
            if (nodeKey.equals(node.path("nodeKey").asText())) {
                return node;
            }
        }
        return null;
    }
}
