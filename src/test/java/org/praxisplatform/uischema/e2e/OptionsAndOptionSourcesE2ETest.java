package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsAndOptionSourcesE2ETest extends AbstractE2eH2Test {

    @Test
    void standardOptionsAndDerivedOptionSourcesBehaveCanonically() throws Exception {
        ResponseEntity<String> departmentOptionsResponse = postJson("/departments/options/filter?page=0&size=10", "{}");
        assertEquals(200, departmentOptionsResponse.getStatusCode().value());
        JsonNode departmentOptionsBody = body(departmentOptionsResponse);
        assertEquals(2, departmentOptionsBody.path("content").size());
        assertEquals("Human Resources", departmentOptionsBody.path("content").get(0).path("label").asText());
        assertEquals("Operations", departmentOptionsBody.path("content").get(1).path("label").asText());

        ResponseEntity<String> departmentByIdsResponse = get(
                "/departments/options/by-ids?ids=%d&ids=%d".formatted(
                        state.operationsDepartmentId(),
                        state.humanResourcesDepartmentId()
                )
        );
        assertEquals(200, departmentByIdsResponse.getStatusCode().value());
        JsonNode departmentByIdsBody = body(departmentByIdsResponse);
        assertEquals("Operations", departmentByIdsBody.get(0).path("label").asText());
        assertEquals("Human Resources", departmentByIdsBody.get(1).path("label").asText());

        ResponseEntity<String> payrollProfileOptionsResponse = postJson(
                "/employees/option-sources/payrollProfile/options/filter?page=0&size=10",
                """
                {
                  "departmentId": %d,
                  "payrollProfile": "EXEC"
                }
                """.formatted(state.humanResourcesDepartmentId())
        );
        assertEquals(200, payrollProfileOptionsResponse.getStatusCode().value());
        JsonNode payrollProfileOptionsBody = body(payrollProfileOptionsResponse);
        assertEquals(2, payrollProfileOptionsBody.path("content").size());
        assertEquals("EXEC", payrollProfileOptionsBody.path("content").get(0).path("id").asText());
        assertEquals("Executive", payrollProfileOptionsBody.path("content").get(0).path("label").asText());
        assertEquals("SPEC", payrollProfileOptionsBody.path("content").get(1).path("id").asText());
        assertEquals("Specialist", payrollProfileOptionsBody.path("content").get(1).path("label").asText());

        ResponseEntity<String> payrollProfileByIdsResponse = get(
                "/employees/option-sources/payrollProfile/options/by-ids?ids=SPEC&ids=EXEC"
        );
        assertEquals(200, payrollProfileByIdsResponse.getStatusCode().value());
        JsonNode payrollProfileByIdsBody = body(payrollProfileByIdsResponse);
        assertEquals("SPEC", payrollProfileByIdsBody.get(0).path("id").asText());
        assertEquals("EXEC", payrollProfileByIdsBody.get(1).path("id").asText());

        ResponseEntity<String> unknownSourceResponse = postJson(
                "/employees/option-sources/unknown-source/options/filter?page=0&size=10",
                "{}"
        );
        assertEquals(404, unknownSourceResponse.getStatusCode().value());
        assertTrue(unknownSourceResponse.getBody().contains("unknown-source"));

        ResponseEntity<String> unsupportedSourceResponse = postJson(
                "/employees/option-sources/legacyDepartmentLookup/options/filter?page=0&size=10",
                "{}"
        );
        assertEquals(501, unsupportedSourceResponse.getStatusCode().value());
        assertTrue(unsupportedSourceResponse.getBody().contains("Option source type not implemented"));

        ResponseEntity<String> filterSchemaResponse = get(
                "/schemas/filtered?path=/employees/filter&operation=post&schemaType=request"
        );
        assertEquals(200, filterSchemaResponse.getStatusCode().value());
        JsonNode filterSchemaBody = body(filterSchemaResponse);
        JsonNode payrollProfileField = filterSchemaBody.path("properties").path("payrollProfile");
        assertEquals("string", payrollProfileField.path("type").asText());
        JsonNode optionSourceMeta = payrollProfileField.path("x-ui").path("optionSource");
        assertEquals("payrollProfile", optionSourceMeta.path("key").asText());
        assertEquals("DISTINCT_DIMENSION", optionSourceMeta.path("type").asText());
        assertEquals("/employees", optionSourceMeta.path("resourcePath").asText());
        assertEquals("contains", optionSourceMeta.path("searchMode").asText());
        assertTrue(optionSourceMeta.path("excludeSelfField").asBoolean());
    }
}
