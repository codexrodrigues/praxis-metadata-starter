package org.praxisplatform.uischema.bulk;

import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalRequestSchema;
import org.praxisplatform.uischema.openapi.CanonicalResponseSchema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable structural composition of one validated bulk action and its seven real HTTP operations.
 *
 * <p>This value contains no provider, limits, persistence authority, fingerprint or readiness state.
 * It is an input to a later complete operational descriptor and must never be used as a
 * {@code descriptorFingerprint}, a {@link BulkOperationControlExpectation}, a capability or an
 * authorization to execute. Instances can only be produced by
 * {@link BulkOperationStructuralCompiler} after binding and strict same-snapshot schema checks.</p>
 */
final class BulkOperationStructuralDescriptor {

    private final String resourceKey;
    private final String openApiGroup;
    private final BulkMode mode;
    private final ActionCollectionAtomicity atomicity;
    private final Action action;
    private final List<Operation> operations;

    BulkOperationStructuralDescriptor(String resourceKey, String openApiGroup, BulkMode mode,
            ActionCollectionAtomicity atomicity, Action action, List<Operation> operations) {
        this.resourceKey = requireText(resourceKey, "resourceKey");
        this.openApiGroup = requireText(openApiGroup, "openApiGroup");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.atomicity = Objects.requireNonNull(atomicity, "atomicity");
        this.action = Objects.requireNonNull(action, "action");
        this.operations = List.copyOf(operations);
        if (this.operations.size() != Role.values().length) {
            throw new IllegalArgumentException("A structural bulk descriptor requires all seven operation roles");
        }
        for (int index = 0; index < Role.values().length; index++) {
            if (this.operations.get(index).role() != Role.values()[index]) {
                throw new IllegalArgumentException("Bulk operation roles must use their canonical order");
            }
        }
    }

    String resourceKey() { return resourceKey; }
    String openApiGroup() { return openApiGroup; }
    BulkMode mode() { return mode; }
    ActionCollectionAtomicity atomicity() { return atomicity; }
    Action action() { return action; }
    List<Operation> operations() { return operations; }

    Operation operation(Role role) {
        return operations.get(Objects.requireNonNull(role, "role").ordinal());
    }

    /** Fixed role ordering is part of structural descriptor canonicalization. */
    enum Role {
        PROPOSAL,
        PROPOSAL_RESULTS,
        EXECUTION,
        EXECUTION_RESULTS,
        CANCEL,
        EVALUATION,
        CONFIRMATION
    }

    /** One verified MVC/OpenAPI operation and its canonical request/response contracts. */
    static final class Operation {
        private final Role role;
        private final CanonicalOperationRef reference;
        private final String requestJavaType;
        private final String responseJavaType;
        private final CanonicalRequestSchema requestSchema;
        private final CanonicalResponseSchema responseSchema;

        Operation(Role role, CanonicalOperationRef reference, String requestJavaType,
                String responseJavaType, CanonicalRequestSchema requestSchema,
                CanonicalResponseSchema responseSchema) {
            this.role = Objects.requireNonNull(role, "role");
            this.reference = Objects.requireNonNull(reference, "reference");
            this.requestJavaType = requestJavaType;
            this.responseJavaType = requireText(responseJavaType, "responseJavaType");
            this.requestSchema = requestSchema;
            this.responseSchema = Objects.requireNonNull(responseSchema, "responseSchema");
            if ((role == Role.EVALUATION || role == Role.CONFIRMATION)
                    != (requestJavaType != null && requestSchema != null)) {
                throw new IllegalArgumentException("Only evaluation and confirmation roles have request bodies");
            }
            if ((requestJavaType == null) != (requestSchema == null)) {
                throw new IllegalArgumentException("Request Java type and schema must be present together");
            }
        }

        Role role() { return role; }
        CanonicalOperationRef reference() { return reference; }
        Optional<String> requestJavaType() { return Optional.ofNullable(requestJavaType); }
        String responseJavaType() { return responseJavaType; }
        Optional<CanonicalRequestSchema> requestSchema() { return Optional.ofNullable(requestSchema); }
        CanonicalResponseSchema responseSchema() { return responseSchema; }
    }

    /** Defensive projection of the canonical action registry entry; schemas come from the group snapshot. */
    record Action(
            String id,
            String resourceKey,
            String resourcePath,
            String group,
            ActionScope scope,
            String title,
            String description,
            CanonicalOperationRef operation,
            int order,
            String successMessage,
            List<String> requiredAuthorities,
            List<String> allowedStates,
            List<String> tags,
            ActionExecutionContract execution) {

        public Action {
            id = requireText(id, "action.id");
            resourceKey = requireText(resourceKey, "action.resourceKey");
            resourcePath = requireText(resourcePath, "action.resourcePath");
            group = requireText(group, "action.group");
            scope = Objects.requireNonNull(scope, "action.scope");
            title = Objects.requireNonNull(title, "action.title");
            description = Objects.requireNonNull(description, "action.description");
            operation = Objects.requireNonNull(operation, "action.operation");
            successMessage = Objects.requireNonNull(successMessage, "action.successMessage");
            requiredAuthorities = List.copyOf(requiredAuthorities);
            allowedStates = List.copyOf(allowedStates);
            tags = List.copyOf(tags);
            execution = Objects.requireNonNull(execution, "action.execution");
        }

        static Action from(ActionDefinition definition) {
            return new Action(definition.id(), definition.resourceKey(), definition.resourcePath(),
                    definition.group(), definition.scope(), definition.title(), definition.description(),
                    definition.operation(), definition.order(), definition.successMessage(),
                    definition.requiredAuthorities(), definition.allowedStates(), definition.tags(),
                    definition.execution());
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be canonical nonblank text");
        }
        return value;
    }
}
