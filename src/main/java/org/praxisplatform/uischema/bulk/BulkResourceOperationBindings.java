package org.praxisplatform.uischema.bulk;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.HttpEntity;
import org.springframework.http.RequestEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable projection from declared bulk lifecycle roles to their real Spring MVC handlers.
 *
 * <p>This object only binds identities. It neither creates endpoints nor indicates that the bulk
 * protocol is ready. Incomplete or ambiguous resource declarations are omitted as a whole so the
 * ordinary OpenAPI document can still be served; strict consumers then fail closed when they ask
 * for one of the omitted operation identities.</p>
 */
public final class BulkResourceOperationBindings {

    private final Map<HandlerMethod, String> operationIdsByHandler;
    private final Map<String, HandlerMethod> handlersByOperationId;
    private final Map<HandlerMethod, BulkOperationBinding> bulkOperationsByConfirmation;
    private final Set<String> bodylessLifecycleOperationIds;
    private final Set<String> declaredOperationIds;
    private final List<String> diagnostics;

    private BulkResourceOperationBindings(Map<HandlerMethod, String> operationIdsByHandler,
            Map<String, HandlerMethod> handlersByOperationId,
            Map<HandlerMethod, BulkOperationBinding> bulkOperationsByConfirmation,
            Set<String> bodylessLifecycleOperationIds, Set<String> declaredOperationIds, List<String> diagnostics) {
        this.operationIdsByHandler = Map.copyOf(operationIdsByHandler);
        this.handlersByOperationId = Map.copyOf(handlersByOperationId);
        this.bulkOperationsByConfirmation = Map.copyOf(bulkOperationsByConfirmation);
        this.bodylessLifecycleOperationIds = Set.copyOf(bodylessLifecycleOperationIds);
        this.declaredOperationIds = Set.copyOf(declaredOperationIds);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static BulkResourceOperationBindings empty() {
        return new BulkResourceOperationBindings(Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), List.of());
    }

    /**
     * Derives the bindings from the configured MVC mappings and their controller annotations.
     * Malformed opt-in declarations are recorded and excluded instead of preventing application
     * startup. The descriptor compiler must treat these diagnostics as non-ready composition.
     */
    public static BulkResourceOperationBindings from(RequestMappingHandlerMapping mapping) {
        if (mapping == null) return empty();

        Map<Class<?>, ResourceCandidate> candidates = new LinkedHashMap<>();
        Set<String> orphanDeclaredIds = new HashSet<>();
        List<String> orphanDiagnostics = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            BulkResourceOperations declaration = AnnotatedElementUtils.findMergedAnnotation(
                    handler.getBeanType(), BulkResourceOperations.class);
            if (declaration == null) {
                BulkOperation orphan = AnnotatedElementUtils.findMergedAnnotation(
                        handler.getMethod(), BulkOperation.class);
                if (orphan != null) {
                    String confirmationId = explicitOperationId(handler);
                    if (StringUtils.hasText(confirmationId)) orphanDeclaredIds.add(confirmationId);
                    if (StringUtils.hasText(orphan.evaluationOperationId()))
                        orphanDeclaredIds.add(orphan.evaluationOperationId());
                    orphanDiagnostics.add(handler.getBeanType().getName()
                            + ": @BulkOperation requires @BulkResourceOperations on the same @ApiResource controller");
                }
                continue;
            }
            ResourceCandidate candidate = candidates.computeIfAbsent(handler.getBeanType(),
                    type -> new ResourceCandidate(type, declaration));
            BulkResourceOperation role = AnnotatedElementUtils.findMergedAnnotation(
                    handler.getMethod(), BulkResourceOperation.class);
            if (role != null) candidate.roles.computeIfAbsent(role.value(), ignored -> new ArrayList<>())
                    .add(new MappedHandler(entry.getKey(), handler, role.value()));
            BulkOperation action = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), BulkOperation.class);
            if (action != null) candidate.actions.add(new MappedBulkOperation(entry.getKey(), handler, action));
        }

        List<String> diagnostics = new ArrayList<>();
        diagnostics.addAll(orphanDiagnostics);
        Set<String> declaredIds = new HashSet<>(orphanDeclaredIds);
        Set<String> bodylessLifecycleIds = new HashSet<>();
        List<BoundResource> valid = new ArrayList<>();
        for (ResourceCandidate candidate : candidates.values()) {
            operationIds(candidate.declaration).values().stream().filter(StringUtils::hasText).forEach(declaredIds::add);
            operationIds(candidate.declaration).values().stream().filter(StringUtils::hasText)
                    .forEach(bodylessLifecycleIds::add);
            candidate.actions.forEach(action -> {
                String confirmationId = explicitOperationId(action.handler);
                if (StringUtils.hasText(confirmationId)) declaredIds.add(confirmationId);
                if (StringUtils.hasText(action.declaration.evaluationOperationId()))
                    declaredIds.add(action.declaration.evaluationOperationId());
            });
            List<String> errors = validate(candidate, mapping);
            if (errors.isEmpty()) valid.add(bind(candidate, mapping));
            else errors.forEach(error -> diagnostics.add(candidate.controllerType.getName() + ": " + error));
        }

        Map<String, List<Class<?>>> resourcesByKey = new HashMap<>();
        for (ResourceCandidate candidate : candidates.values()) {
            ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(candidate.controllerType, ApiResource.class);
            if (resource != null && StringUtils.hasText(resource.resourceKey())) {
                resourcesByKey.computeIfAbsent(resource.resourceKey(), ignored -> new ArrayList<>())
                        .add(candidate.controllerType);
            }
        }
        Set<Class<?>> duplicateResourceKeys = new HashSet<>();
        resourcesByKey.forEach((resourceKey, resources) -> {
            if (resources.size() > 1) {
                duplicateResourceKeys.addAll(resources);
                diagnostics.add("resourceKey '" + resourceKey + "' must have exactly one bulk operation declaration");
            }
        });

        Map<String, List<HandlerMethod>> declarationsById = new HashMap<>();
        valid.forEach(resource -> resource.byRole.values().forEach(binding ->
                declarationsById.computeIfAbsent(binding.operationId, ignored -> new ArrayList<>())
                        .add(binding.mappedHandler.handler)));
        valid.forEach(resource -> resource.bulkOperations.forEach(binding -> {
            declarationsById.computeIfAbsent(binding.confirmationOperationId(), ignored -> new ArrayList<>())
                    .add(binding.confirmationHandler());
            declarationsById.computeIfAbsent(binding.evaluationOperationId(), ignored -> new ArrayList<>())
                    .add(binding.evaluationHandler());
        }));
        Set<Class<?>> collidingResources = new HashSet<>();
        collidingResources.addAll(duplicateResourceKeys);
        Set<HandlerMethod> declaredHandlers = new HashSet<>();
        valid.forEach(resource -> {
            declaredHandlers.addAll(resource.bulkOperationHandlers());
            resource.byRole.values().forEach(binding -> declaredHandlers.add(binding.mappedHandler.handler));
        });
        for (Map.Entry<String, List<HandlerMethod>> entry : declarationsById.entrySet()) {
            if (entry.getValue().size() > 1) {
                entry.getValue().forEach(handler -> collidingResources.add(handler.getBeanType()));
            }
            for (Map.Entry<RequestMappingInfo, HandlerMethod> registered : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod other = registered.getValue();
                if (declaredHandlers.contains(other)) continue;
                String explicitId = explicitOperationId(other);
                String fallbackId = StringUtils.hasText(explicitId) ? explicitId : other.getMethod().getName();
                if (entry.getKey().equals(fallbackId)) {
                    entry.getValue().forEach(candidate -> collidingResources.add(candidate.getBeanType()));
                }
            }
        }
        for (Class<?> collision : collidingResources) {
            diagnostics.add(collision.getName() + ": a declared bulk operationId collides with another MVC operationId");
        }

        Map<HandlerMethod, String> byHandler = new HashMap<>();
        Map<String, HandlerMethod> byId = new HashMap<>();
        Map<HandlerMethod, BulkOperationBinding> bulkByConfirmation = new HashMap<>();
        for (BoundResource resource : valid) {
            if (collidingResources.contains(resource.controllerType)) continue;
            resource.byRole.values().forEach(binding -> {
                byHandler.put(binding.mappedHandler.handler, binding.operationId);
                byId.put(binding.operationId, binding.mappedHandler.handler);
            });
            resource.bulkOperations.forEach(binding -> {
                byHandler.put(binding.confirmationHandler(), binding.confirmationOperationId());
                byHandler.put(binding.evaluationHandler(), binding.evaluationOperationId());
                byId.put(binding.confirmationOperationId(), binding.confirmationHandler());
                byId.put(binding.evaluationOperationId(), binding.evaluationHandler());
                bulkByConfirmation.put(binding.confirmationHandler(), binding);
            });
        }
        return new BulkResourceOperationBindings(byHandler, byId, bulkByConfirmation,
                bodylessLifecycleIds, declaredIds, diagnostics);
    }

    public Optional<String> operationIdFor(HandlerMethod handler) {
        return Optional.ofNullable(operationIdsByHandler.get(handler));
    }

    public Optional<HandlerMethod> handlerFor(String operationId) {
        return Optional.ofNullable(handlersByOperationId.get(operationId));
    }

    /** Returns the validated confirmation/evaluation pair for a confirmation handler. */
    public Optional<BulkOperationBinding> bulkOperationFor(HandlerMethod confirmationHandler) {
        return Optional.ofNullable(bulkOperationsByConfirmation.get(confirmationHandler));
    }

    /** True only for the five shared lifecycle operations whose request contract is bodyless. */
    public boolean requiresBodylessLifecycle(String operationId) {
        return bodylessLifecycleOperationIds.contains(operationId);
    }

    /** True when an opt-in resource claimed the ID, including a declaration omitted as invalid. */
    public boolean declares(String operationId) {
        return declaredOperationIds.contains(operationId);
    }

    /** Diagnostics indicate omitted opt-in declarations; callers must not project them as ready. */
    public List<String> diagnostics() {
        return diagnostics;
    }

    private static List<String> validate(ResourceCandidate candidate, RequestMappingHandlerMapping registry) {
        List<String> errors = new ArrayList<>();
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(candidate.controllerType, ApiResource.class);
        if (resource == null || !isCanonicalText(resource.resourceKey()))
            errors.add("@ApiResource resourceKey must be canonical nonblank text");
        Map<BulkResourceOperation.Role, String> ids = operationIds(candidate.declaration);
        Set<String> uniqueIds = new HashSet<>();
        ids.forEach((role, id) -> {
            if (!isCanonicalText(id)) errors.add("operationId for " + role + " must be canonical nonblank text");
            else if (!uniqueIds.add(id)) errors.add("declared bulk operationIds must be unique");
        });
        for (BulkResourceOperation.Role role : BulkResourceOperation.Role.values()) {
            List<MappedHandler> matches = candidate.roles.getOrDefault(role, List.of());
            if (matches.size() != 1) {
                errors.add("exactly one MVC handler is required for role " + role);
                continue;
            }
            MappedHandler match = matches.getFirst();
            RequestMappingInfo mapping = match.mapping;
            if (mapping.getPatternValues().size() != 1 || mapping.getMethodsCondition().getMethods().size() != 1
                    || !mapping.getMethodsCondition().getMethods().contains(RequestMethod.valueOf(role.httpMethod()))) {
                errors.add("role " + role + " requires one canonical path and HTTP " + role.httpMethod());
            }
            if (!mapping.getParamsCondition().isEmpty() || !mapping.getHeadersCondition().isEmpty()
                    || !mapping.getConsumesCondition().getExpressions().isEmpty()
                    || mapping.getCustomCondition() != null) {
                errors.add("conditional routing and request consumes constraints are not supported for role " + role);
            }
            if (mapping.getPatternValues().size() == 1) {
                String path = mapping.getPatternValues().iterator().next();
                boolean sharedAddress = registry.getHandlerMethods().keySet().stream()
                        .filter(other -> !other.equals(mapping))
                        .anyMatch(other -> other.getPatternValues().contains(path)
                                && (other.getMethodsCondition().getMethods().isEmpty()
                                || other.getMethodsCondition().getMethods().contains(RequestMethod.valueOf(role.httpMethod()))));
                if (sharedAddress) errors.add("path/method is shared by another handler for role " + role);
            }
            HandlerMethod handler = match.handler;
            if (AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), Hidden.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getMethod(), Hidden.class)) {
                errors.add("hidden handlers cannot provide bulk role " + role);
            }
            String explicitId = explicitOperationId(handler);
            if (StringUtils.hasText(explicitId) && !explicitId.equals(ids.get(role))) {
                errors.add("@Operation operationId conflicts with the declared identity for role " + role);
            }
            if (java.util.Arrays.stream(handler.getMethodParameters()).anyMatch(parameter -> {
                Class<?> parameterType = parameter.getParameterType();
                return AnnotatedElementUtils.hasAnnotation(parameter.getParameter(), RequestBody.class)
                        || AnnotatedElementUtils.hasAnnotation(parameter.getParameter(), RequestPart.class)
                        || HttpEntity.class.isAssignableFrom(parameterType)
                        || RequestEntity.class.isAssignableFrom(parameterType);
            })) {
                errors.add("request bodies and HTTP entity parameters are not supported for shared lifecycle role " + role);
            }
        }
        errors.addAll(validateActions(candidate, registry, uniqueIds));
        return errors;
    }

    private static List<String> validateActions(ResourceCandidate candidate, RequestMappingHandlerMapping registry,
            Set<String> uniqueIds) {
        List<String> errors = new ArrayList<>();
        Set<HandlerMethod> confirmations = new HashSet<>();
        for (MappedBulkOperation action : candidate.actions) {
            HandlerMethod confirmation = action.handler;
            if (!confirmations.add(confirmation)) {
                errors.add("bulk confirmation handler must have exactly one canonical MVC mapping");
                continue;
            }
            String confirmationId = explicitOperationId(confirmation);
            if (!isCanonicalText(confirmationId)) {
                errors.add("bulk confirmation handler requires a canonical explicit @Operation operationId");
            } else if (!uniqueIds.add(confirmationId)) {
                errors.add("bulk confirmation and lifecycle operationIds must be unique");
            }
            String evaluationId = action.declaration.evaluationOperationId();
            if (!isCanonicalText(evaluationId)) {
                errors.add("bulk evaluationOperationId must be canonical nonblank text");
            } else if (!uniqueIds.add(evaluationId)) {
                errors.add("bulk evaluation and confirmation operationIds must be unique");
            }
            ActionCollectionAtomicity atomicity = action.declaration.atomicity();
            if (atomicity == null || atomicity == ActionCollectionAtomicity.NOT_APPLICABLE) {
                errors.add("bulk atomicity must be ATOMIC or PER_ITEM");
            }
            if (action.declaration.mode() == null) errors.add("bulk mode is required");

            WorkflowAction workflow = AnnotatedElementUtils.findMergedAnnotation(confirmation.getMethod(),
                    WorkflowAction.class);
            if (workflow == null) {
                errors.add("bulk confirmation handler must also declare @WorkflowAction");
            } else if (workflow.atomicity() != atomicity) {
                errors.add("@WorkflowAction atomicity must match @BulkOperation atomicity");
            }
            if (AnnotatedElementUtils.hasAnnotation(confirmation.getBeanType(), Hidden.class)
                    || AnnotatedElementUtils.hasAnnotation(confirmation.getMethod(), Hidden.class)) {
                errors.add("hidden handlers cannot provide a bulk confirmation operation");
            }
            if (!hasRequestBody(confirmation)) {
                errors.add("bulk confirmation handler requires a request body");
            }
            if (!isCanonicalPostMapping(action.mapping, registry, confirmation)) {
                errors.add("bulk confirmation requires one unconditional canonical POST path");
            }

            List<Map.Entry<RequestMappingInfo, HandlerMethod>> evaluations = registry.getHandlerMethods().entrySet().stream()
                    .filter(entry -> entry.getValue().getBeanType().equals(candidate.controllerType))
                    .filter(entry -> evaluationId.equals(explicitOperationId(entry.getValue())))
                    .toList();
            Set<HandlerMethod> evaluationHandlers = evaluations.stream().map(Map.Entry::getValue)
                    .collect(java.util.stream.Collectors.toSet());
            if (evaluationHandlers.size() != 1 || evaluations.size() != 1) {
                errors.add("evaluationOperationId must resolve to exactly one handler on the same resource controller");
                continue;
            }
            HandlerMethod evaluation = evaluations.getFirst().getValue();
            if (evaluation.equals(confirmation)) {
                errors.add("evaluation and confirmation operations must use distinct handlers");
            }
            if (AnnotatedElementUtils.hasAnnotation(evaluation.getBeanType(), Hidden.class)
                    || AnnotatedElementUtils.hasAnnotation(evaluation.getMethod(), Hidden.class)) {
                errors.add("hidden handlers cannot provide a bulk evaluation operation");
            }
            if (!hasRequestBody(evaluation)) {
                errors.add("bulk evaluation handler requires a request body");
            }
            if (!isCanonicalPostMapping(evaluations.getFirst().getKey(), registry, evaluation)) {
                errors.add("bulk evaluation requires one unconditional canonical POST path");
            }
        }
        return errors;
    }

    private static boolean hasRequestBody(HandlerMethod handler) {
        return java.util.Arrays.stream(handler.getMethodParameters()).anyMatch(parameter -> {
            Class<?> type = parameter.getParameterType();
            return AnnotatedElementUtils.hasAnnotation(parameter.getParameter(), RequestBody.class)
                    || AnnotatedElementUtils.hasAnnotation(parameter.getParameter(), RequestPart.class)
                    || HttpEntity.class.isAssignableFrom(type) || RequestEntity.class.isAssignableFrom(type);
        });
    }

    private static boolean isCanonicalText(String value) {
        return StringUtils.hasText(value) && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean isCanonicalPostMapping(RequestMappingInfo mapping, RequestMappingHandlerMapping registry,
            HandlerMethod handler) {
        if (mapping.getPatternValues().size() != 1 || mapping.getMethodsCondition().getMethods().size() != 1
                || !mapping.getMethodsCondition().getMethods().contains(RequestMethod.POST)
                || !mapping.getParamsCondition().isEmpty() || !mapping.getHeadersCondition().isEmpty()
                || mapping.getCustomCondition() != null) return false;
        String path = mapping.getPatternValues().iterator().next();
        return registry.getHandlerMethods().entrySet().stream()
                .filter(entry -> !entry.getValue().equals(handler))
                .noneMatch(entry -> entry.getKey().getPatternValues().contains(path)
                        && (entry.getKey().getMethodsCondition().getMethods().isEmpty()
                        || entry.getKey().getMethodsCondition().getMethods().contains(RequestMethod.POST)));
    }

    private static BoundResource bind(ResourceCandidate candidate, RequestMappingHandlerMapping registry) {
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(candidate.controllerType, ApiResource.class);
        Map<BulkResourceOperation.Role, RoleBinding> byRole = new EnumMap<>(BulkResourceOperation.Role.class);
        operationIds(candidate.declaration).forEach((role, id) ->
                byRole.put(role, new RoleBinding(id, candidate.roles.get(role).getFirst())));
        List<BulkOperationBinding> actions = candidate.actions.stream().map(action -> {
            String confirmationId = explicitOperationId(action.handler);
            HandlerMethod evaluationHandler = registry.getHandlerMethods().values().stream()
                    .filter(handler -> handler.getBeanType().equals(candidate.controllerType))
                    .filter(handler -> action.declaration.evaluationOperationId().equals(explicitOperationId(handler)))
                    .findFirst().orElseThrow();
            return new BulkOperationBinding(resource.resourceKey(), confirmationId,
                    action.declaration.evaluationOperationId(), action.declaration.mode(),
                    action.declaration.atomicity(), action.handler, evaluationHandler);
        }).toList();
        return new BoundResource(candidate.controllerType, resource.resourceKey(),
                Collections.unmodifiableMap(byRole), actions);
    }

    private static Map<BulkResourceOperation.Role, String> operationIds(BulkResourceOperations declaration) {
        Map<BulkResourceOperation.Role, String> result = new EnumMap<>(BulkResourceOperation.Role.class);
        result.put(BulkResourceOperation.Role.PROPOSAL, declaration.proposalOperationId());
        result.put(BulkResourceOperation.Role.PROPOSAL_RESULTS, declaration.proposalResultsOperationId());
        result.put(BulkResourceOperation.Role.EXECUTION, declaration.executionOperationId());
        result.put(BulkResourceOperation.Role.EXECUTION_RESULTS, declaration.executionResultsOperationId());
        result.put(BulkResourceOperation.Role.CANCEL, declaration.cancelOperationId());
        return result;
    }

    private static String explicitOperationId(HandlerMethod handler) {
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Operation.class);
        return operation == null ? null : operation.operationId();
    }

    private static final class ResourceCandidate {
        private final Class<?> controllerType;
        private final BulkResourceOperations declaration;
        private final Map<BulkResourceOperation.Role, List<MappedHandler>> roles = new EnumMap<>(BulkResourceOperation.Role.class);
        private final List<MappedBulkOperation> actions = new ArrayList<>();

        private ResourceCandidate(Class<?> controllerType, BulkResourceOperations declaration) {
            this.controllerType = controllerType;
            this.declaration = declaration;
        }
    }

    private record MappedHandler(RequestMappingInfo mapping, HandlerMethod handler, BulkResourceOperation.Role role) { }
    private record MappedBulkOperation(RequestMappingInfo mapping, HandlerMethod handler, BulkOperation declaration) { }
    private record RoleBinding(String operationId, MappedHandler mappedHandler) { }
    private record BoundResource(Class<?> controllerType, String resourceKey,
            Map<BulkResourceOperation.Role, RoleBinding> byRole, List<BulkOperationBinding> bulkOperations) {
        private List<HandlerMethod> bulkOperationHandlers() {
            return bulkOperations.stream().flatMap(binding -> java.util.stream.Stream.of(
                    binding.confirmationHandler(), binding.evaluationHandler())).toList();
        }
    }
}
