package org.praxisplatform.consumer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.bulk.BulkResourceOperation;
import org.praxisplatform.uischema.bulk.BulkResourceOperations;
import org.praxisplatform.uischema.controller.base.AbstractCollectionCommandResourceController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@ApiResource(value = "/artifact-items", resourceKey = "artifact.items",
        title = "Artifact consumer items")
@ApiGroup("artifact-consumer")
@BulkResourceOperations(
        proposalOperationId = ArtifactBulkController.PROPOSAL,
        proposalResultsOperationId = ArtifactBulkController.PROPOSAL_RESULTS,
        executionOperationId = ArtifactBulkController.EXECUTION,
        executionResultsOperationId = ArtifactBulkController.EXECUTION_RESULTS,
        cancelOperationId = ArtifactBulkController.CANCEL)
/** Test-only routes prove MVC binding and HTTP delivery; they do not mutate domain state. */
public class ArtifactBulkController extends AbstractCollectionCommandResourceController {

    static final String PROPOSAL = "artifact.items.bulk.proposal";
    static final String PROPOSAL_RESULTS = "artifact.items.bulk.proposal-results";
    static final String EXECUTION = "artifact.items.bulk.execution";
    static final String EXECUTION_RESULTS = "artifact.items.bulk.execution-results";
    static final String CANCEL = "artifact.items.bulk.cancel";
    static final String EVALUATION = "artifact.items.bulk-approve.evaluation";
    static final String CONFIRMATION = "artifact.items.bulk-approve";

    @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
    @GetMapping("/bulk/proposals/{proposalId}")
    public ArtifactBulkRouteResponse proposal(@PathVariable("proposalId") String proposalId) {
        return new ArtifactBulkRouteResponse("proposal", proposalId);
    }

    @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
    @GetMapping("/bulk/proposals/{proposalId}/results")
    public ArtifactBulkRouteResponse proposalResults(@PathVariable("proposalId") String proposalId) {
        return new ArtifactBulkRouteResponse("proposal-results", proposalId);
    }

    @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
    @GetMapping("/bulk/executions/{executionId}")
    public ArtifactBulkRouteResponse execution(@PathVariable("executionId") String executionId) {
        return new ArtifactBulkRouteResponse("execution", executionId);
    }

    @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
    @GetMapping("/bulk/executions/{executionId}/results")
    public ArtifactBulkRouteResponse executionResults(@PathVariable("executionId") String executionId) {
        return new ArtifactBulkRouteResponse("execution-results", executionId);
    }

    @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
    @PostMapping("/bulk/executions/{executionId}/cancel")
    public ArtifactBulkRouteResponse cancel(@PathVariable("executionId") String executionId) {
        return new ArtifactBulkRouteResponse("cancel", executionId);
    }

    @Operation(operationId = EVALUATION)
    @PostMapping("/actions/bulk-approve/evaluation")
    public ArtifactEvaluationResponse evaluate(@RequestBody ArtifactEvaluationRequest request) {
        return new ArtifactEvaluationResponse("route-dispatch-only", request.targetIds().size());
    }

    @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = EVALUATION,
            atomicity = ActionCollectionAtomicity.PER_ITEM)
    @WorkflowAction(id = "bulk-approve", title = "Approve artifact items",
            scope = ActionScope.COLLECTION, atomicity = ActionCollectionAtomicity.PER_ITEM)
    @Operation(operationId = CONFIRMATION)
    @PostMapping("/actions/bulk-approve")
    public ArtifactConfirmationResponse confirm(@RequestBody ArtifactConfirmationRequest request) {
        return new ArtifactConfirmationResponse("route-dispatch-only", request.proposalId());
    }

    /** Concrete OpenAPI component for the operational routes, with their actual JSON shape. */
    public record ArtifactBulkRouteResponse(String kind, String id) { }

    public record ArtifactEvaluationRequest(
            @ArraySchema(schema = @Schema(type = "string", minLength = 1))
            java.util.List<String> targetIds) { }

    public record ArtifactEvaluationResponse(String state, int itemCount) { }

    public record ArtifactConfirmationRequest(
            @Schema(description = "Proposal identity previously reviewed by the caller") String proposalId) { }

    public record ArtifactConfirmationResponse(String state, String proposalId) { }
}
