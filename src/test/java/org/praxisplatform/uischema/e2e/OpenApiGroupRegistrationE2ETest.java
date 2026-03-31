package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiGroupRegistrationE2ETest extends AbstractE2eH2Test {

    @Test
    void openApiGroupsRegisterNewHierarchyAndInfrastructureEndpoints() throws Exception {
        ResponseEntity<String> employeeGroup = get("/v3/api-docs/employees");
        assertEquals(200, employeeGroup.getStatusCode().value());
        JsonNode employeeRoot = body(employeeGroup);
        assertTrue(employeeRoot.path("paths").has("/employees"));
        assertTrue(employeeRoot.path("paths").has("/employees/{id}"));

        ResponseEntity<String> payrollGroup = get("/v3/api-docs/payroll-view");
        assertEquals(200, payrollGroup.getStatusCode().value());
        JsonNode payrollRoot = body(payrollGroup);
        assertTrue(payrollRoot.path("paths").has("/payroll-view/all"));
        assertTrue(payrollRoot.path("paths").has("/payroll-view/{id}"));

        ResponseEntity<String> aggregatedGroup = get("/v3/api-docs/human-resources");
        assertEquals(200, aggregatedGroup.getStatusCode().value());
        JsonNode aggregatedRoot = body(aggregatedGroup);
        assertTrue(aggregatedRoot.path("paths").has("/employees"));
        assertTrue(aggregatedRoot.path("paths").has("/departments"));
        assertTrue(aggregatedRoot.path("paths").has("/payroll-view/all"));
        assertFalse(aggregatedRoot.path("paths").has("/legacy-employees"));
        assertFalse(aggregatedRoot.path("paths").has("/schemas/filtered"));

        ResponseEntity<String> applicationGroup = get("/v3/api-docs/application");
        assertEquals(200, applicationGroup.getStatusCode().value());
        JsonNode applicationRoot = body(applicationGroup);
        assertTrue(applicationRoot.path("paths").has("/employees"));
        assertTrue(applicationRoot.path("paths").has("/legacy-employees"));

        ResponseEntity<String> infraGroup = get("/v3/api-docs/praxis-metadata-infra");
        assertEquals(200, infraGroup.getStatusCode().value());
        JsonNode infraRoot = body(infraGroup);
        assertTrue(infraRoot.path("paths").has("/schemas/filtered"));
        assertTrue(infraRoot.path("paths").has("/schemas/catalog"));
    }
}
