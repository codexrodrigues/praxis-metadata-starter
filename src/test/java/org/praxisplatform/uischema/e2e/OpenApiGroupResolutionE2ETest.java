package org.praxisplatform.uischema.e2e;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenApiGroupResolutionE2ETest extends AbstractE2eH2Test {

    @Autowired
    private OpenApiDocumentService openApiDocumentService;

    @Test
    void actionPathsResolveToOwningResourceGroup() {
        assertEquals("employees", openApiDocumentService.resolveGroupFromPath("/employees/actions/bulk-approve"));
        assertEquals("employees", openApiDocumentService.resolveGroupFromPath("/employees/{id}/actions/approve"));
    }
}
