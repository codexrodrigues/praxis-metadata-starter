package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionInteractionPolicy;
import org.praxisplatform.uischema.action.ActionOutcomePolicy;
import org.praxisplatform.uischema.action.ActionPreconditionPolicy;
import org.praxisplatform.uischema.action.ActionRefreshPolicy;
import org.praxisplatform.uischema.action.ActionSelectionPolicy;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOpenApiGroupSnapshot;
import org.praxisplatform.uischema.openapi.CanonicalRequestBodyBinding;
import org.praxisplatform.uischema.openapi.CanonicalRequestSchema;
import org.praxisplatform.uischema.openapi.CanonicalResponseSchema;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.util.UriUtils;

import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Composes the already-governed bulk bindings, action catalog and one strict OpenAPI snapshot.
 *
 * <p>The result is structural evidence only. This compiler has no provider, quota, persistence,
 * generation, CAS or lifecycle dependency, and cannot publish an action or readiness state. A
 * caller must construct and fingerprint the complete operational descriptor, then pass through
 * the durable fence, before any execution surface may use it.</p>
 */
final class BulkOperationStructuralCompiler {
    private final BulkResourceOperationBindings bindings;
    private final CanonicalOperationResolver operationResolver;
    private final OpenApiDocumentService documents;
    private final ActionDefinitionRegistry actionDefinitions;
    private final TypeFactory typeFactory;
    private final SchemaReferenceResolver schemaReferences;
    private final org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilities;

    BulkOperationStructuralCompiler(BulkResourceOperationBindings bindings,
            CanonicalOperationResolver operationResolver,
            OpenApiDocumentService documents,
            ActionDefinitionRegistry actionDefinitions, TypeFactory typeFactory,
            SchemaReferenceResolver schemaReferences) {
        this(bindings, operationResolver, documents, actionDefinitions, typeFactory, schemaReferences,
                new org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver(documents));
    }

    BulkOperationStructuralCompiler(BulkResourceOperationBindings bindings,
            CanonicalOperationResolver operationResolver, OpenApiDocumentService documents,
            ActionDefinitionRegistry actionDefinitions, TypeFactory typeFactory,
            SchemaReferenceResolver schemaReferences,
            org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.operationResolver = Objects.requireNonNull(operationResolver, "operationResolver");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.actionDefinitions = Objects.requireNonNull(actionDefinitions, "actionDefinitions");
        this.typeFactory = Objects.requireNonNull(typeFactory, "typeFactory");
        this.schemaReferences = Objects.requireNonNull(schemaReferences, "schemaReferences");
    }

    /** Compile every valid bulk action, failing closed if any declared binding is incomplete. */
    List<BulkOperationStructuralDescriptor> compileAll() {
        return compileAll(false);
    }

    List<BulkOperationStructuralDescriptor> compileAll(boolean refreshOpenApi) {
        if (!bindings.diagnostics().isEmpty()) {
            throw invalid("Bulk MVC declarations contain diagnostics: " + String.join("; ", bindings.diagnostics()));
        }
        List<BulkOperationBinding> declared = bindings.bulkOperations();
        if (declared.isEmpty()) return List.of();
        Map<String, CanonicalOpenApiGroupSnapshot> snapshots = new java.util.HashMap<>();
        List<BulkOperationStructuralDescriptor> result = new ArrayList<>(declared.size());
        for (BulkOperationBinding binding : declared) result.add(compileBinding(binding, snapshots, refreshOpenApi));
        return List.copyOf(result);
    }

    private BulkOperationStructuralDescriptor compileBinding(BulkOperationBinding binding,
            Map<String, CanonicalOpenApiGroupSnapshot> snapshots, boolean refreshOpenApi) {
        Objects.requireNonNull(binding, "binding");
        if (bindings.bulkOperationFor(binding.confirmationHandler()).orElse(null) != binding) {
            throw invalid("Bulk operation binding was not produced by this validated MVC binding set");
        }
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(
                binding.confirmationHandler().getBeanType(), ApiResource.class);
        BulkResourceOperations lifecycle = AnnotatedElementUtils.findMergedAnnotation(
                binding.confirmationHandler().getBeanType(), BulkResourceOperations.class);
        BulkOperation declaration = AnnotatedElementUtils.findMergedAnnotation(
                binding.confirmationHandler().getMethod(), BulkOperation.class);
        WorkflowAction workflow = AnnotatedElementUtils.findMergedAnnotation(
                binding.confirmationHandler().getMethod(), WorkflowAction.class);
        if (resource == null || lifecycle == null || declaration == null || workflow == null
                || !binding.resourceKey().equals(resource.resourceKey())
                || !binding.mode().equals(declaration.mode()) || binding.atomicity() != declaration.atomicity()
                || !binding.evaluationOperationId().equals(declaration.evaluationOperationId())) {
            throw invalid("Bulk binding no longer matches its canonical controller annotations");
        }
        if (!binding.confirmationOperationId().equals(operationId(binding.confirmationHandler()))
                || !binding.evaluationOperationId().equals(operationId(binding.evaluationHandler()))) {
            throw invalid("Bulk confirmation/evaluation operation IDs changed after binding");
        }

        Map<BulkResourceOperation.Role, String> lifecycleIds = lifecycleIds(lifecycle);
        List<PendingOperation> pending = new ArrayList<>(BulkOperationStructuralDescriptor.Role.values().length);
        for (BulkResourceOperation.Role role : BulkResourceOperation.Role.values()) {
            String operationId = lifecycleIds.get(role);
            HandlerMethod actual = bindings.handlerFor(operationId).orElseThrow(() ->
                    invalid("Validated lifecycle role " + role + " has no real MVC handler"));
            BulkResourceOperation declaredRole = AnnotatedElementUtils.findMergedAnnotation(
                    actual.getMethod(), BulkResourceOperation.class);
            if (declaredRole == null || declaredRole.value() != role
                    || !actual.getBeanType().equals(binding.confirmationHandler().getBeanType())
                    || !bindings.requiresBodylessLifecycle(operationId)) {
                throw invalid("Lifecycle identity does not match the validated bodyless handler for " + role);
            }
            RequestMappingInfo mapping = bindings.mappingFor(actual).orElseThrow(() ->
                    invalid("Lifecycle role " + role + " does not have exactly one MVC mapping"));
            CanonicalOperationRef reference = operationResolver.resolve(actual, mapping);
            if (!operationId.equals(reference.operationId()) || !role.httpMethod().equals(reference.method())) {
                throw invalid("Lifecycle role " + role + " no longer matches its declared operation identity and method");
            }
            pending.add(new PendingOperation(map(role), reference, actual, null));
        }
        pending.add(bodyOperation(BulkOperationStructuralDescriptor.Role.EVALUATION,
                binding.evaluationOperationId(), binding.evaluationHandler(), binding.resourceKey()));
        pending.add(bodyOperation(BulkOperationStructuralDescriptor.Role.CONFIRMATION,
                binding.confirmationOperationId(), binding.confirmationHandler(), binding.resourceKey()));

        String group = pending.getFirst().reference().group();
        if (pending.stream().anyMatch(operation -> !group.equals(operation.reference().group()))) {
            throw invalid("All seven bulk operations must belong to one exact OpenAPI group");
        }
        CanonicalOpenApiGroupSnapshot snapshot = snapshots.computeIfAbsent(group, exactGroup -> refreshOpenApi
                ? CanonicalOpenApiGroupSnapshot.captureFresh(documents, exactGroup)
                : CanonicalOpenApiGroupSnapshot.capture(documents, exactGroup));
        List<CanonicalOperationRef> resolved = operationResolver.requireResourceOperations(binding.resourceKey(),
                pending.stream().map(PendingOperation::reference).toList(), snapshot);
        if (!resolved.equals(pending.stream().map(PendingOperation::reference).toList())) {
            throw invalid("Batch strict resolver returned a different operation ordering or identity");
        }
        ActionDefinition action = requireAction(binding, workflow, resource, pending.getLast().reference());

        List<BulkOperationStructuralDescriptor.Operation> operations = new ArrayList<>(pending.size());
        for (PendingOperation operation : pending) {
            CanonicalResponseSchema response;
            try {
                response = snapshot.requireResponseSchema(operation.reference());
            } catch (RuntimeException invalidResponseSchema) {
                throw invalid("Response schema composition failed for " + operation.role() + " operation "
                        + operation.reference().operationId(), invalidResponseSchema);
            }
            CanonicalRequestSchema request = operation.requestBody() == null
                    ? null : snapshot.requireRequestSchema(operation.reference());
            if (operation.requestBody() == null) snapshot.requireNoRequestBody(operation.reference());
            if (request != null && !request.operation().equals(operation.reference())) {
                throw invalid("Request schema reader returned a different operation identity");
            }
            if (!response.operation().equals(operation.reference())) {
                throw invalid("Response schema reader returned a different operation identity");
            }
            String requestJavaType = operation.requestBody() == null ? null
                    : operation.requestBody().bodyType().toCanonical();
            String responseJavaType = returnJavaType(operation.handler());
            // UI projection failure never weakens strict raw-schema validation above. It makes
            // this structural evidence unprojectable; operational composition rejects it before READY.
            String idField = operation.role() == BulkOperationStructuralDescriptor.Role.CONFIRMATION
                    ? queryParameters(URI.create(action.responseSchema().url()).getRawQuery()).get("idField") : null;
            Boolean readOnly = operation.role() == BulkOperationStructuralDescriptor.Role.CONFIRMATION ? false : null;
            var filteredResponse = filteredProjection(snapshot, operation.reference(), "response", idField, readOnly);
            String requestIdField = operation.role() == BulkOperationStructuralDescriptor.Role.CONFIRMATION
                    ? queryParameters(URI.create(action.requestSchema().url()).getRawQuery()).get("idField") : null;
            var filteredRequest = request == null ? null
                    : filteredProjection(snapshot, operation.reference(), "request", requestIdField, readOnly);
            if (operation.role() == BulkOperationStructuralDescriptor.Role.CONFIRMATION) {
                if (filteredResponse != null && !filteredResponse.reference().equals(action.responseSchema()))
                    throw invalid("Confirmation filtered response differs from the captured action reference");
                if (filteredRequest != null && !filteredRequest.reference().equals(action.requestSchema()))
                    throw invalid("Confirmation filtered request differs from the captured action reference");
            }
            operations.add(new BulkOperationStructuralDescriptor.Operation(
                    operation.role(), operation.reference(), requestJavaType, responseJavaType, request, response,
                    filteredRequest, filteredResponse));
        }
        return new BulkOperationStructuralDescriptor(binding.resourceKey(), group, binding.mode(),
                binding.atomicity(), BulkOperationStructuralDescriptor.Action.from(action), operations);
    }

    private org.praxisplatform.uischema.schema.FilteredSchemaProjection.Resolved filteredProjection(
            CanonicalOpenApiGroupSnapshot snapshot, CanonicalOperationRef operation, String type,
            String idField, Boolean readOnly) {
        try {
            return snapshot.resolveFilteredProjection(operation, type, schemaReferences, capabilities, idField, readOnly);
        } catch (RuntimeException unavailableProjection) {
            return null;
        }
    }

    private PendingOperation bodyOperation(BulkOperationStructuralDescriptor.Role role, String operationId,
            HandlerMethod handler, String resourceKey) {
        if (!bindings.handlerFor(operationId).filter(handler::equals).isPresent()) {
            throw invalid("Body operation does not resolve to its validated MVC handler: " + operationId);
        }
        RequestMappingInfo mapping = bindings.mappingFor(handler).orElseThrow(() ->
                invalid("Body operation does not have exactly one MVC mapping: " + operationId));
        CanonicalOperationRef reference = operationResolver.resolve(handler, mapping);
        if (!operationId.equals(reference.operationId()) || !"POST".equals(reference.method())) {
            throw invalid("Bulk body operation is not the declared POST handler: " + operationId);
        }
        CanonicalRequestBodyBinding body = operationResolver.requireResourceRequestBody(resourceKey,
                reference, typeFactory);
        if (!body.operation().equals(reference)) throw invalid("Request DTO binding changed its operation identity");
        return new PendingOperation(role, reference, handler, body);
    }

    private ActionDefinition requireAction(BulkOperationBinding binding, WorkflowAction workflow, ApiResource resource,
            CanonicalOperationRef confirmation) {
        List<ActionDefinition> matches = actionDefinitions.findByResourceKey(binding.resourceKey()).stream()
                .filter(Objects::nonNull)
                .filter(action -> Objects.equals(action.id(), workflow.id()))
                .filter(action -> Objects.equals(action.resourceKey(), binding.resourceKey()))
                .filter(action -> Objects.equals(action.operation(), confirmation))
                .toList();
        if (matches.size() != 1) {
            throw invalid("Bulk confirmation must resolve to exactly one canonical workflow action definition");
        }
        ActionDefinition action = matches.getFirst();
        String[] resourcePaths = resource.value().length > 0 ? resource.value() : resource.path();
        if (!hasCanonicalActionSchemaReference(action.requestSchema(), confirmation, "request")
                || !hasCanonicalActionSchemaReference(action.responseSchema(), confirmation, "response")) {
            throw invalid("Canonical workflow action schema references do not resolve to its operation");
        }
        if (!binding.resourceKey().equals(action.resourceKey())
                || resourcePaths.length == 0 || !normalizePath(resourcePaths[0]).equals(action.resourcePath()))
            throw invalid("Canonical workflow action resource identity/path differs from @ApiResource/OpenAPI");
        if (!workflowMatches(action, workflow))
            throw invalid("Canonical workflow action semantics differ from @WorkflowAction");
        return action;
    }

    private static boolean hasSchemaReference(org.praxisplatform.uischema.schema.CanonicalSchemaRef reference,
            String expectedType) {
        return reference != null && expectedType.equals(reference.schemaType())
                && reference.schemaId() != null && !reference.schemaId().isBlank()
                && reference.url() != null && !reference.url().isBlank();
    }

    /**
     * Action catalog references intentionally include the resource's {@code idField} and
     * {@code readOnly=false} schema projections. They must be the canonical resolver output for
     * the same action operation, not byte-for-byte equal to the generic operation references.
     */
    private boolean hasCanonicalActionSchemaReference(
            org.praxisplatform.uischema.schema.CanonicalSchemaRef reference,
            CanonicalOperationRef operation,
            String expectedType) {
        if (!hasSchemaReference(reference, expectedType)) return false;
        try {
            Map<String, String> query = queryParameters(URI.create(reference.url()).getRawQuery());
            String idField = query.get("idField");
            if (!operation.path().equals(query.get("path"))
                    || !operation.method().equalsIgnoreCase(query.get("operation"))
                    || !expectedType.equalsIgnoreCase(query.get("schemaType"))
                    || idField == null || idField.isBlank()
                    || !"false".equals(query.get("readOnly"))
                    || query.containsKey("includeInternalSchemas")) {
                return false;
            }
            var expected = schemaReferences.resolve(operation.path(), operation.method(), expectedType,
                    false, null, null, idField, false);
            return reference.equals(expected);
        } catch (IllegalArgumentException invalidReference) {
            return false;
        }
    }

    private static Map<String, String> queryParameters(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) throw new IllegalArgumentException("Missing schema query");
        Map<String, String> result = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("Malformed schema query");
            // These are URI query components, not form-urlencoded values: '+' is literal data.
            String name = UriUtils.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = UriUtils.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (result.putIfAbsent(name, value) != null) throw new IllegalArgumentException("Duplicate schema query parameter");
        }
        return result;
    }

    static boolean workflowMatches(ActionDefinition action, WorkflowAction workflow) {
        ActionExecutionContract execution = new ActionExecutionContract(
                new ActionInteractionPolicy(workflow.interactionMode(), workflow.riskLevel(),
                        workflow.confirmationRequired(), workflow.reversible()),
                new ActionPreconditionPolicy(workflow.idempotencyKey(), workflow.correlationId(),
                        workflow.resourceVersion(), workflow.resourceVersionTransport(),
                        workflow.resourceVersionField(), workflow.resourceVersionTargetResourceKey(),
                        workflow.resourceVersionTargetIdField()),
                new ActionSelectionPolicy(workflow.selectionIdsField(), workflow.selectionVersionsField(),
                        workflow.maxSelection()),
                new ActionOutcomePolicy(workflow.outcomeMode(), workflow.atomicity()),
                new ActionRefreshPolicy(workflow.refreshItem(), workflow.refreshCollection(),
                        workflow.refreshActions(), workflow.refreshCapabilities(), List.of(workflow.invalidatesResourceKeys())));
        try {
            execution.validateFor(workflow.scope());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return action.id().equals(workflow.id())
                && action.scope() == workflow.scope()
                && Objects.equals(action.title(), workflow.title())
                && Objects.equals(action.description(), workflow.description())
                && action.order() == workflow.order()
                && Objects.equals(action.successMessage(), workflow.successMessage())
                && Objects.equals(action.requiredAuthorities(), List.of(workflow.requiredAuthorities()))
                && Objects.equals(action.allowedStates(), List.of(workflow.allowedStates()))
                && Objects.equals(action.tags(), List.of(workflow.tags()))
                && Objects.equals(action.execution(), execution);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        String normalized = path.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        if (normalized.length() > 1 && normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static Map<BulkResourceOperation.Role, String> lifecycleIds(BulkResourceOperations declaration) {
        Map<BulkResourceOperation.Role, String> result = new EnumMap<>(BulkResourceOperation.Role.class);
        result.put(BulkResourceOperation.Role.PROPOSAL, declaration.proposalOperationId());
        result.put(BulkResourceOperation.Role.PROPOSAL_RESULTS, declaration.proposalResultsOperationId());
        result.put(BulkResourceOperation.Role.EXECUTION, declaration.executionOperationId());
        result.put(BulkResourceOperation.Role.EXECUTION_RESULTS, declaration.executionResultsOperationId());
        result.put(BulkResourceOperation.Role.CANCEL, declaration.cancelOperationId());
        return result;
    }

    private static BulkOperationStructuralDescriptor.Role map(BulkResourceOperation.Role role) {
        return switch (role) {
            case PROPOSAL -> BulkOperationStructuralDescriptor.Role.PROPOSAL;
            case PROPOSAL_RESULTS -> BulkOperationStructuralDescriptor.Role.PROPOSAL_RESULTS;
            case EXECUTION -> BulkOperationStructuralDescriptor.Role.EXECUTION;
            case EXECUTION_RESULTS -> BulkOperationStructuralDescriptor.Role.EXECUTION_RESULTS;
            case CANCEL -> BulkOperationStructuralDescriptor.Role.CANCEL;
        };
    }

    private static String operationId(HandlerMethod handler) {
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Operation.class);
        return operation == null ? null : operation.operationId();
    }

    private String returnJavaType(HandlerMethod handler) {
        Type responseType = handler.getMethod().getGenericReturnType();
        return typeFactory.constructType(responseType, handler.getBeanType()).toCanonical();
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Bulk structural composition is unavailable: " + message);
    }

    private static IllegalStateException invalid(String message, RuntimeException cause) {
        return new IllegalStateException("Bulk structural composition is unavailable: " + message, cause);
    }

    private record PendingOperation(BulkOperationStructuralDescriptor.Role role,
            CanonicalOperationRef reference, HandlerMethod handler, CanonicalRequestBodyBinding requestBody) { }
}
