package org.praxisplatform.uischema.bulk;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.controller.base.AbstractBulkGovernedResourceController;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/** Canonical identities for the four reusable update handlers, namespaced by semantic resourceKey. */
public final class BulkControllerOperationIds {
    private BulkControllerOperationIds() { }

    public static String updateConfirmationId(Class<?> controller, boolean items) {
        var role = items ? BulkResourceOperation.Role.ITEM_CONFIRMATION : BulkResourceOperation.Role.UNIFORM_CONFIRMATION;
        var explicitIds = java.util.Arrays.stream(controller.getMethods())
                .filter(method -> {
                    var declaration = AnnotatedElementUtils.findMergedAnnotation(method, BulkResourceOperation.class);
                    return declaration != null && declaration.value() == role;
                })
                .map(method -> AnnotatedElementUtils.findMergedAnnotation(method, Operation.class))
                .filter(java.util.Objects::nonNull).map(Operation::operationId)
                .filter(id -> !id.isBlank()).distinct().toList();
        if (explicitIds.size() > 1) throw new IllegalStateException("Ambiguous bulk confirmation operation IDs");
        if (!explicitIds.isEmpty()) return explicitIds.getFirst();
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(controller, ApiResource.class);
        if (resource == null || resource.resourceKey().isBlank()) throw new IllegalStateException("Bulk @ApiResource is required");
        return resource.resourceKey() + (items ? ".bulk.update-items" : ".bulk.update");
    }

    public static String operationId(HandlerMethod handler) {
        Operation explicit = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Operation.class);
        if (explicit != null && !explicit.operationId().isBlank()) return explicit.operationId();
        BulkResourceOperation role = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), BulkResourceOperation.class);
        if (role == null || !AbstractBulkGovernedResourceController.class.isAssignableFrom(handler.getBeanType())) return null;
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), ApiResource.class);
        if (resource == null || resource.resourceKey().isBlank()) return null;
        return switch (role.value()) {
            case UNIFORM_CONFIRMATION -> updateConfirmationId(handler.getBeanType(), false);
            case UNIFORM_EVALUATION -> updateConfirmationId(handler.getBeanType(), false) + ".evaluate";
            case ITEM_CONFIRMATION -> updateConfirmationId(handler.getBeanType(), true);
            case ITEM_EVALUATION -> updateConfirmationId(handler.getBeanType(), true) + ".evaluate";
            default -> null;
        };
    }

    public static String lifecycleOperationId(
            Class<?> controller,
            BulkResourceOperations declaration,
            BulkResourceOperation.Role role
    ) {
        if (declaration == null || role == null) {
            return "";
        }
        String declared = switch (role) {
            case PROPOSAL -> declaration.proposalOperationId();
            case PROPOSAL_RESULTS -> declaration.proposalResultsOperationId();
            case EXECUTION -> declaration.executionOperationId();
            case EXECUTION_RESULTS -> declaration.executionResultsOperationId();
            case CANCEL -> declaration.cancelOperationId();
            case UPDATE_SOURCE -> declaration.updateSourceOperationId();
            default -> "";
        };
        if (declared != null && !declared.isEmpty()) {
            return declared;
        }
        if (role == BulkResourceOperation.Role.UPDATE_SOURCE) {
            return "";
        }
        if (controller == null) {
            return "";
        }
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(controller, ApiResource.class);
        if (resource == null || resource.resourceKey().isBlank()) {
            return "";
        }
        String key = resource.resourceKey().trim();
        return switch (role) {
            case PROPOSAL -> key + ".bulk.proposal";
            case PROPOSAL_RESULTS -> key + ".bulk.proposal-results";
            case EXECUTION -> key + ".bulk.execution";
            case EXECUTION_RESULTS -> key + ".bulk.execution-results";
            case CANCEL -> key + ".bulk.cancel";
            default -> "";
        };
    }

    public static String evaluationId(HandlerMethod handler, BulkOperation operation) {
        if (!operation.evaluationOperationId().isEmpty()) return operation.evaluationOperationId();
        // Only the canonical inherited confirmation declaration can leave the ID implicit.
        if (handler.getMethod().getDeclaringClass() == AbstractBulkGovernedResourceController.class)
            return operationId(handler) + ".evaluate";
        return operation.evaluationOperationId();
    }
}
