package org.praxisplatform.uischema.bulk;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.annotation.ApiResource;
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
    private final Set<String> declaredOperationIds;
    private final List<String> diagnostics;

    private BulkResourceOperationBindings(Map<HandlerMethod, String> operationIdsByHandler,
            Map<String, HandlerMethod> handlersByOperationId, Set<String> declaredOperationIds,
            List<String> diagnostics) {
        this.operationIdsByHandler = Map.copyOf(operationIdsByHandler);
        this.handlersByOperationId = Map.copyOf(handlersByOperationId);
        this.declaredOperationIds = Set.copyOf(declaredOperationIds);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static BulkResourceOperationBindings empty() {
        return new BulkResourceOperationBindings(Map.of(), Map.of(), Set.of(), List.of());
    }

    /**
     * Derives the bindings from the configured MVC mappings and their controller annotations.
     * Malformed opt-in declarations are recorded and excluded instead of preventing application
     * startup. The descriptor compiler must treat these diagnostics as non-ready composition.
     */
    public static BulkResourceOperationBindings from(RequestMappingHandlerMapping mapping) {
        if (mapping == null) return empty();

        Map<Class<?>, ResourceCandidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            BulkResourceOperations declaration = AnnotatedElementUtils.findMergedAnnotation(
                    handler.getBeanType(), BulkResourceOperations.class);
            if (declaration == null) continue;
            ResourceCandidate candidate = candidates.computeIfAbsent(handler.getBeanType(),
                    type -> new ResourceCandidate(type, declaration));
            BulkResourceOperation role = AnnotatedElementUtils.findMergedAnnotation(
                    handler.getMethod(), BulkResourceOperation.class);
            if (role != null) candidate.roles.computeIfAbsent(role.value(), ignored -> new ArrayList<>())
                    .add(new MappedHandler(entry.getKey(), handler, role.value()));
        }

        List<String> diagnostics = new ArrayList<>();
        Set<String> declaredIds = new HashSet<>();
        List<BoundResource> valid = new ArrayList<>();
        for (ResourceCandidate candidate : candidates.values()) {
            operationIds(candidate.declaration).values().stream().filter(StringUtils::hasText).forEach(declaredIds::add);
            List<String> errors = validate(candidate, mapping);
            if (errors.isEmpty()) valid.add(bind(candidate));
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

        Map<String, List<MappedHandler>> declarationsById = new HashMap<>();
        valid.forEach(resource -> resource.byRole.values().forEach(binding ->
                declarationsById.computeIfAbsent(binding.operationId, ignored -> new ArrayList<>())
                        .add(binding.mappedHandler)));
        Set<Class<?>> collidingResources = new HashSet<>();
        collidingResources.addAll(duplicateResourceKeys);
        for (Map.Entry<String, List<MappedHandler>> entry : declarationsById.entrySet()) {
            if (entry.getValue().size() > 1) {
                entry.getValue().forEach(binding -> collidingResources.add(binding.handler.getBeanType()));
            }
            for (Map.Entry<RequestMappingInfo, HandlerMethod> registered : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod other = registered.getValue();
                if (entry.getValue().stream().anyMatch(candidate -> candidate.handler.equals(other))) continue;
                String explicitId = explicitOperationId(other);
                String fallbackId = StringUtils.hasText(explicitId) ? explicitId : other.getMethod().getName();
                if (entry.getKey().equals(fallbackId)) {
                    entry.getValue().forEach(candidate -> collidingResources.add(candidate.handler.getBeanType()));
                }
            }
        }
        for (Class<?> collision : collidingResources) {
            diagnostics.add(collision.getName() + ": a declared bulk operationId collides with another MVC operationId");
        }

        Map<HandlerMethod, String> byHandler = new HashMap<>();
        Map<String, HandlerMethod> byId = new HashMap<>();
        for (BoundResource resource : valid) {
            if (collidingResources.contains(resource.controllerType)) continue;
            resource.byRole.values().forEach(binding -> {
                byHandler.put(binding.mappedHandler.handler, binding.operationId);
                byId.put(binding.operationId, binding.mappedHandler.handler);
            });
        }
        return new BulkResourceOperationBindings(byHandler, byId, declaredIds, diagnostics);
    }

    public Optional<String> operationIdFor(HandlerMethod handler) {
        return Optional.ofNullable(operationIdsByHandler.get(handler));
    }

    public Optional<HandlerMethod> handlerFor(String operationId) {
        return Optional.ofNullable(handlersByOperationId.get(operationId));
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
        if (resource == null || !StringUtils.hasText(resource.resourceKey())) errors.add("@ApiResource resourceKey is required");
        Map<BulkResourceOperation.Role, String> ids = operationIds(candidate.declaration);
        Set<String> uniqueIds = new HashSet<>();
        ids.forEach((role, id) -> {
            if (!StringUtils.hasText(id)) errors.add("operationId for " + role + " must not be blank");
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
        return errors;
    }

    private static BoundResource bind(ResourceCandidate candidate) {
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(candidate.controllerType, ApiResource.class);
        Map<BulkResourceOperation.Role, RoleBinding> byRole = new EnumMap<>(BulkResourceOperation.Role.class);
        operationIds(candidate.declaration).forEach((role, id) ->
                byRole.put(role, new RoleBinding(id, candidate.roles.get(role).getFirst())));
        return new BoundResource(candidate.controllerType, resource.resourceKey(), Collections.unmodifiableMap(byRole));
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

        private ResourceCandidate(Class<?> controllerType, BulkResourceOperations declaration) {
            this.controllerType = controllerType;
            this.declaration = declaration;
        }
    }

    private record MappedHandler(RequestMappingInfo mapping, HandlerMethod handler, BulkResourceOperation.Role role) { }
    private record RoleBinding(String operationId, MappedHandler mappedHandler) { }
    private record BoundResource(Class<?> controllerType, String resourceKey,
            Map<BulkResourceOperation.Role, RoleBinding> byRole) { }
}
