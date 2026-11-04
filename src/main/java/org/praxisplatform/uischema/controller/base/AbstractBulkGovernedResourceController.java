package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * Nine canonical two-phase bulk endpoints over an explicitly composed resource engine.
 * Subclasses declare @BulkResourceOperations, their unit UPDATE_SOURCE, and host security.
 * The four inherited update IDs are resourceKey + .bulk.update[.evaluate] / .bulk.update-items[.evaluate].
 */
public abstract class AbstractBulkGovernedResourceController<R, ID, F extends GenericFilterDTO, C, U>
        extends AbstractResourceController<R, ID, F, C, U> {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.praxisplatform.uischema.action.ActionDefinitionRegistry bulkActions;
    @org.springframework.beans.factory.annotation.Autowired
    private CanonicalOperationResolver bulkOperations;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BulkOperationStatusLookup bulkStatusLookup;
    protected abstract JpaResourceBulkEngine<ID, F> getBulkEngine();

    /** Trusted resource-specific routing of retained proposals/executions to their confirmation operation. */
    protected String bulkProposalOperationId(UUID proposalId) {
        return getBulkEngine().resolveProposalOperationId(requireBulkStatusLookup(), bulkResourceKey(), proposalId);
    }
    protected String bulkExecutionOperationId(UUID executionId) {
        return getBulkEngine().resolveExecutionOperationId(requireBulkStatusLookup(), bulkResourceKey(), executionId);
    }

    private BulkOperationStatusLookup requireBulkStatusLookup() {
        if (bulkStatusLookup == null) throw new IllegalStateException("Bulk operation status lookup is required");
        return bulkStatusLookup;
    }

    private String bulkResourceKey() {
        var resource = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                org.springframework.util.ClassUtils.getUserClass(this), org.praxisplatform.uischema.annotation.ApiResource.class);
        if (resource == null || resource.resourceKey().isBlank()) throw new IllegalStateException("Bulk @ApiResource is required");
        return resource.resourceKey();
    }

    private String updateOperation(boolean items) {
        return BulkControllerOperationIds.updateConfirmationId(org.springframework.util.ClassUtils.getUserClass(this), items);
    }

    @PostMapping("/bulk/update/evaluation")
    @BulkResourceOperation(BulkResourceOperation.Role.UNIFORM_EVALUATION)
    @Operation(summary = "Evaluate uniform bulk changes without updating resources")
    public ResponseEntity<RestApiResponse<BulkProposal>> evaluateBulkUpdate(@RequestBody BulkUniformEvaluationRequest<?, F> request) {
        return proposalResponse(getBulkEngine().evaluateUniform(updateOperation(false), request));
    }

    @PostMapping("/bulk/update")
    @BulkResourceOperation(BulkResourceOperation.Role.UNIFORM_CONFIRMATION)
    @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "", atomicity = ActionCollectionAtomicity.PER_ITEM)
    @Operation(summary = "Confirm a retained uniform bulk proposal")
    public ResponseEntity<RestApiResponse<BulkExecution>> confirmBulkUpdate(@RequestBody BulkConfirmationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return executionResponse(getBulkEngine().confirm(updateOperation(false), request, idempotencyKey));
    }

    @PostMapping("/bulk/update-items/evaluation")
    @BulkResourceOperation(BulkResourceOperation.Role.ITEM_EVALUATION)
    @Operation(summary = "Evaluate distinct changes for each selected resource")
    public ResponseEntity<RestApiResponse<BulkProposal>> evaluateBulkUpdateItems(@RequestBody BulkItemEvaluationRequest<?> request) {
        return proposalResponse(getBulkEngine().evaluateItems(updateOperation(true), request));
    }

    @PostMapping("/bulk/update-items")
    @BulkResourceOperation(BulkResourceOperation.Role.ITEM_CONFIRMATION)
    @BulkOperation(mode = BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "", atomicity = ActionCollectionAtomicity.PER_ITEM)
    @Operation(summary = "Confirm a retained per-item bulk proposal")
    public ResponseEntity<RestApiResponse<BulkExecution>> confirmBulkUpdateItems(@RequestBody BulkConfirmationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return executionResponse(getBulkEngine().confirm(updateOperation(true), request, idempotencyKey));
    }

    @GetMapping("/bulk/proposals/{proposalId}")
    @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
    @Operation(summary = "Read an authorized public bulk proposal")
    public ResponseEntity<RestApiResponse<BulkProposal>> bulkProposal(@PathVariable UUID proposalId) {
        return proposalResponse(getBulkEngine().proposal(bulkProposalOperationId(proposalId), proposalId));
    }

    @GetMapping("/bulk/proposals/{proposalId}/results")
    @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
    @Operation(summary = "Read authorized bulk evaluation results")
    public ResponseEntity<RestApiResponse<CursorPage<BulkProposalItemResult<Object>>>> bulkProposalResults(@PathVariable UUID proposalId,
            @RequestParam(defaultValue = "50") int size, @RequestParam(required = false) String after) {
        return resultsResponse(getBulkEngine().proposalResults(bulkProposalOperationId(proposalId), proposalId, size, after),
                declaration().proposalResultsOperationId(), Map.of("proposalId", proposalId), after);
    }

    @GetMapping("/bulk/executions/{executionId}")
    @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
    @Operation(summary = "Read an authorized bulk execution")
    public ResponseEntity<RestApiResponse<BulkExecution>> bulkExecution(@PathVariable UUID executionId) {
        return executionResponse(getBulkEngine().execution(bulkExecutionOperationId(executionId), executionId));
    }

    @GetMapping("/bulk/executions/{executionId}/results")
    @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
    @Operation(summary = "Read authorized durable item receipts")
    public ResponseEntity<RestApiResponse<CursorPage<BulkItemResult<Object>>>> bulkExecutionResults(@PathVariable UUID executionId,
            @RequestParam(defaultValue = "50") int size, @RequestParam(required = false) String after) {
        return resultsResponse(getBulkEngine().executionResults(bulkExecutionOperationId(executionId), executionId, size, after),
                declaration().executionResultsOperationId(), Map.of("executionId", executionId), after);
    }

    @PostMapping("/bulk/executions/{executionId}/cancel")
    @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
    @Operation(summary = "Request cancellation after current target authorization")
    public ResponseEntity<RestApiResponse<BulkExecution>> cancelBulkExecution(@PathVariable UUID executionId) {
        return executionResponse(getBulkEngine().cancel(bulkExecutionOperationId(executionId), executionId));
    }

    protected final <P> ResponseEntity<RestApiResponse<BulkProposal>> evaluateBulkDomainCommand(String actionId, BulkCommandEvaluationRequest<P, ?, F> request) {
        return proposalResponse(getBulkEngine().evaluateDomainCommand(commandOperation(actionId), request));
    }
    protected final ResponseEntity<RestApiResponse<BulkExecution>> confirmBulkDomainCommand(String actionId, BulkConfirmationRequest request, String idempotencyKey) {
        return executionResponse(getBulkEngine().confirm(commandOperation(actionId), request, idempotencyKey));
    }

    private ResponseEntity<RestApiResponse<BulkProposal>> proposalResponse(BulkProposal proposal) {
        var variables = Map.<String, Object>of("proposalId", proposal.proposalId());
        return successEnvelope(ResponseEntity.ok(), proposal, Links.of(
                readLink(declaration().proposalOperationId(), variables, "self", null, null),
                readLink(declaration().proposalResultsOperationId(), variables, "results", null, null)));
    }

    private ResponseEntity<RestApiResponse<BulkExecution>> executionResponse(BulkExecution execution) {
        var variables = Map.<String, Object>of("executionId", execution.executionId());
        return successEnvelope(ResponseEntity.ok(), execution, Links.of(
                readLink(declaration().executionOperationId(), variables, "self", null, null),
                readLink(declaration().executionResultsOperationId(), variables, "results", null, null),
                readLink(declaration().proposalOperationId(), Map.of("proposalId", execution.proposalId()), "proposal", null, null)));
    }

    private <T> ResponseEntity<RestApiResponse<CursorPage<T>>> resultsResponse(CursorPage<T> page, String operationId,
            Map<String, ?> variables, String after) {
        var links = new ArrayList<Link>();
        links.add(readLink(operationId, variables, "self", page.size(), after));
        if (page.next() != null) links.add(readLink(operationId, variables, "next", page.size(), page.next()));
        if (page.prev() != null) links.add(readLink(operationId, variables, "prev", page.size(), page.prev()));
        return successEnvelope(ResponseEntity.ok(), page, Links.of(links));
    }

    /** Links identify authorized reads only; executable command affordances remain governed by capabilities. */
    private Link readLink(String operationId, Map<String, ?> variables, String rel, Integer size, String after) {
        var operation = bulkOperations.resolveByOperationId(operationId).orElseThrow(() ->
                new IllegalStateException("Bulk read operation is not mapped"));
        if (!"GET".equals(operation.method())) throw new IllegalStateException("Bulk read link requires a GET operation");
        var uri = ServletUriComponentsBuilder.fromCurrentContextPath().path(operation.path());
        if (size != null) uri.queryParam("size", size);
        if (after != null) uri.queryParam("after", after);
        return Link.of(uri.buildAndExpand(variables).encode().toUriString(), rel);
    }

    private BulkResourceOperations declaration() {
        var declaration = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                org.springframework.util.ClassUtils.getUserClass(this), BulkResourceOperations.class);
        if (declaration == null) throw new IllegalStateException("Bulk resource lifecycle declaration is required");
        return declaration;
    }

    private String commandOperation(String actionId) {
        if (bulkActions == null) throw new IllegalStateException("Bulk command action registry is required");
        var resource = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                org.springframework.util.ClassUtils.getUserClass(this), org.praxisplatform.uischema.annotation.ApiResource.class);
        if (resource == null) throw new IllegalStateException("Bulk @ApiResource is required");
        var actions = bulkActions.findByResourceKey(resource.resourceKey()).stream()
                .filter(action -> action.id().equals(actionId) && action.scope() == org.praxisplatform.uischema.action.ActionScope.COLLECTION)
                .toList();
        if (actions.size() != 1 || actions.getFirst().operation() == null)
            throw new IllegalStateException("Bulk command must identify one declared collection action");
        return actions.getFirst().operation().operationId();
    }
}
