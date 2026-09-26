package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.bulk.BulkResourceOperation;
import org.praxisplatform.uischema.bulk.BulkResourceOperations;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Import(BulkResourceOperationOpenApiHttpTest.BulkLifecycleController.class)
class BulkResourceOperationOpenApiHttpTest extends AbstractE2eH2Test {

    @Autowired CanonicalOperationResolver operations;
    @Autowired OpenApiDocumentService documents;
    @Autowired OpenApiDocsSupport docsSupport;

    @Test
    void declaredIdentitiesResolveAndAreServedByTheRealOpenApiDocument() throws Exception {
        ReflectionTestUtils.setField(docsSupport, "openApiInternalBaseUrl", url(""));

        String[][] bindings = {
                {"bulk-binding.items", "items.bulk.proposal", "GET", "/bulk-binding-items/bulk/proposals/{proposalId}", "get"},
                {"bulk-binding.items", "items.bulk.proposal-results", "GET", "/bulk-binding-items/bulk/proposals/{proposalId}/results", "get"},
                {"bulk-binding.items", "items.bulk.execution", "GET", "/bulk-binding-items/bulk/executions/{executionId}", "get"},
                {"bulk-binding.items", "items.bulk.execution-results", "GET", "/bulk-binding-items/bulk/executions/{executionId}/results", "get"},
                {"bulk-binding.items", "items.bulk.cancel", "POST", "/bulk-binding-items/bulk/executions/{executionId}/cancel", "post"}
        };
        JsonNode document = body(get("/v3/api-docs/bulk-binding"));
        for (String[] binding : bindings) {
            var operation = operations.requireResourceOperation(binding[0], binding[1], binding[2]);
            assertEquals("bulk-binding", operation.group());
            assertEquals(binding[1], document.at("/paths/" + pointer(binding[3]) + "/" + binding[4] + "/operationId").asText());
        }
    }

    private String pointer(String path) {
        return path.replace("~", "~0").replace("/", "~1");
    }

    @ApiResource(value = "/bulk-binding-items", resourceKey = "bulk-binding.items")
    @ApiGroup("bulk-binding")
    @BulkResourceOperations(
            proposalOperationId = "items.bulk.proposal",
            proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution",
            executionResultsOperationId = "items.bulk.execution-results",
            cancelOperationId = "items.bulk.cancel")
    public static class BulkLifecycleController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/bulk/proposals/{proposalId}")
        public void readProposal(@PathVariable("proposalId") String proposalId) { }

        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/bulk/proposals/{proposalId}/results")
        public void readProposalResults(@PathVariable("proposalId") String proposalId) { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/bulk/executions/{executionId}")
        public void readExecution(@PathVariable("executionId") String executionId) { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/bulk/executions/{executionId}/results")
        public void readExecutionResults(@PathVariable("executionId") String executionId) { }

        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @PostMapping("/bulk/executions/{executionId}/cancel")
        public void cancelExecution(@PathVariable("executionId") String executionId) { }
    }
}
