package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

/**
 * Descriptive P1 DOMAIN_COMMAND projection of a currently composed and durably READY action.
 * References identify filtered UI variants, not raw HTTP schemas. This value is never admission
 * or authorization; every evaluation/confirmation must revalidate its transactional fence.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BulkExecutionContract(
        BulkMode mode,
        Operation evaluationOperation,
        Operation confirmationOperation,
        Operation proposalOperation,
        Operation proposalResultsOperation,
        Operation executionOperation,
        Operation resultsOperation,
        Operation cancelOperation,
        List<BulkSelectionMode> selectionModes,
        List<BulkExecutionMode> executionModes,
        ActionCollectionAtomicity atomicity,
        Limits limits,
        String parametersPointer) {

    public BulkExecutionContract {
        if (mode != BulkMode.DOMAIN_COMMAND || atomicity != ActionCollectionAtomicity.PER_ITEM)
            throw new IllegalArgumentException("This projection supports only P1 DOMAIN_COMMAND/PER_ITEM");
        Objects.requireNonNull(evaluationOperation, "evaluationOperation");
        Objects.requireNonNull(confirmationOperation, "confirmationOperation");
        Objects.requireNonNull(proposalOperation, "proposalOperation");
        Objects.requireNonNull(proposalResultsOperation, "proposalResultsOperation");
        Objects.requireNonNull(executionOperation, "executionOperation");
        Objects.requireNonNull(resultsOperation, "resultsOperation");
        Objects.requireNonNull(cancelOperation, "cancelOperation");
        selectionModes = List.copyOf(selectionModes);
        executionModes = List.copyOf(executionModes);
        Objects.requireNonNull(limits, "limits");
        if (!selectionModes.equals(List.of(BulkSelectionMode.EXPLICIT))
                || !executionModes.equals(List.of(BulkExecutionMode.SYNC)))
            throw new IllegalArgumentException("This projection supports only P1 EXPLICIT/SYNC");
        if (evaluationOperation.requestSchema() == null || confirmationOperation.requestSchema() == null
                || proposalOperation.requestSchema() != null || proposalResultsOperation.requestSchema() != null
                || executionOperation.requestSchema() != null || resultsOperation.requestSchema() != null
                || cancelOperation.requestSchema() != null)
            throw new IllegalArgumentException("Only evaluation and confirmation may have request schemas");
        if (parametersPointer != null && !"/properties/parameters".equals(parametersPointer))
            throw new IllegalArgumentException("parametersPointer must identify the canonical parameters property");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operation(CanonicalOperationRef operation, CanonicalSchemaRef requestSchema,
            CanonicalSchemaRef responseSchema) {
        public Operation {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(responseSchema, "responseSchema");
            if (!"response".equals(responseSchema.schemaType())
                    || requestSchema != null && !"request".equals(requestSchema.schemaType()))
                throw new IllegalArgumentException("Operation references require their canonical schema roles");
        }
    }

    /** Effective server-owned bounds; durations use exact milliseconds as in the operational fingerprint. */
    public record Limits(int maxTargets, int maxRequestBytes, long proposalLifetimeMillis, long unitDeadlineMillis) {
        public Limits {
            if (maxTargets < 1 || maxRequestBytes < 1 || proposalLifetimeMillis < 1 || unitDeadlineMillis < 1)
                throw new IllegalArgumentException("Bulk limits must be positive");
        }
    }

    static BulkExecutionContract from(BulkOperationalDescriptor descriptor) {
        var structural = descriptor.structural();
        var profile = descriptor.profile();
        var evaluationSchema = structural.operation(BulkOperationStructuralDescriptor.Role.EVALUATION)
                .requestSchema().orElseThrow().schema();
        boolean hasParameters = evaluationSchema.path("properties").has("parameters");
        var evaluationProjection = structural.operation(BulkOperationStructuralDescriptor.Role.EVALUATION).filteredRequest();
        if (hasParameters && (evaluationProjection == null
                || !evaluationProjection.evidence().path("resolvedSchema").path("properties").has("parameters")))
            throw new IllegalStateException("The filtered evaluation schema does not materialize the parameters property");
        return new BulkExecutionContract(structural.mode(),
                operation(structural, BulkOperationStructuralDescriptor.Role.EVALUATION),
                operation(structural, BulkOperationStructuralDescriptor.Role.CONFIRMATION),
                operation(structural, BulkOperationStructuralDescriptor.Role.PROPOSAL),
                operation(structural, BulkOperationStructuralDescriptor.Role.PROPOSAL_RESULTS),
                operation(structural, BulkOperationStructuralDescriptor.Role.EXECUTION),
                operation(structural, BulkOperationStructuralDescriptor.Role.EXECUTION_RESULTS),
                operation(structural, BulkOperationStructuralDescriptor.Role.CANCEL),
                profile.selectionModes().stream().sorted().toList(), profile.executionModes().stream().sorted().toList(),
                structural.atomicity(), new Limits(profile.maxTargets(), profile.maxRequestBytes(),
                        profile.proposalLifetime().toMillis(), profile.unitDeadline().toMillis()),
                hasParameters ? "/properties/parameters" : null);
    }

    private static Operation operation(BulkOperationStructuralDescriptor descriptor, BulkOperationStructuralDescriptor.Role role) {
        var operation = descriptor.operation(role);
        if (operation.filteredResponse() == null
                || operation.requestSchema().isPresent() && operation.filteredRequest() == null)
            throw new IllegalStateException("A bulk role has no materializable filtered schema projection");
        if (role == BulkOperationStructuralDescriptor.Role.CONFIRMATION) {
            var action = descriptor.action();
            return new Operation(action.operation(), action.requestSchema(), action.responseSchema());
        }
        return new Operation(operation.reference(), operation.filteredRequest() == null ? null
                : operation.filteredRequest().reference(), operation.filteredResponse().reference());
    }
}
