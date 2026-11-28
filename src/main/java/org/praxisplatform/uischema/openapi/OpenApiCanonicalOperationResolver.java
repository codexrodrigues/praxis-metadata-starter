package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Hidden;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.bulk.BulkResourceOperationBindings;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.HashMap;

/**
 * Implementacao padrao de {@link CanonicalOperationResolver} baseada no registro de handlers do
 * Spring MVC.
 *
 * <p>
 * No fluxo {@code path + method}, a classe apenas normaliza a rota e resolve o grupo associado.
 * No fluxo {@code HandlerMethod + RequestMappingInfo}, ela escolhe o menor path declarado no
 * mapping, usa o primeiro metodo HTTP disponivel e define o {@code operationId} a partir de
 * {@link Operation#operationId()}, do binding declarado de um papel bulk ou, no fluxo legado de
 * discovery, do nome do metodo Java.
 * </p>
 *
 * <p>
 * A busca por {@code operationId} percorre os handlers registrados no
 * {@link RequestMappingHandlerMapping} e rejeita IDs efetivos duplicados globalmente.
 * </p>
 *
 * <p>
 * A heuristica aqui precisa permanecer estavel porque e compartilhada por discovery semantico,
 * resolucao de schema e capacidades derivadas do contrato OpenAPI.
 * </p>
 */
public class OpenApiCanonicalOperationResolver implements BatchCanonicalOperationResolver {

    private static final Set<String> OPENAPI_METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    private final OpenApiDocumentService openApiDocumentService;
    private final RequestMappingHandlerMapping handlerMapping;
    private final BulkResourceOperationBindings bulkOperationBindings;
    private final Supplier<List<String>> publishedOpenApiGroups;
    private final Object publishedOperationIndexMonitor = new Object();
    private CachedOperationIndexEntry primaryOperationIndex;
    private CachedOperationIndexEntry secondaryOperationIndex;

    private record CachedOperationIndexEntry(
            Object publicationToken,
            Map<String, Object> groupTokens,
            OperationIndex index
    ) {
        boolean matches(Object publication, Map<String, Object> tokens) {
            return publicationToken == publication && tokens.equals(groupTokens);
        }
    }

    public OpenApiCanonicalOperationResolver(
            OpenApiDocumentService openApiDocumentService,
            RequestMappingHandlerMapping handlerMapping
    ) {
        this(openApiDocumentService, handlerMapping, BulkResourceOperationBindings.from(handlerMapping));
    }

    public OpenApiCanonicalOperationResolver(
            OpenApiDocumentService openApiDocumentService,
            RequestMappingHandlerMapping handlerMapping,
            BulkResourceOperationBindings bulkOperationBindings
    ) {
        this(openApiDocumentService, handlerMapping, bulkOperationBindings, List.of());
    }

    public OpenApiCanonicalOperationResolver(
            OpenApiDocumentService openApiDocumentService,
            RequestMappingHandlerMapping handlerMapping,
            BulkResourceOperationBindings bulkOperationBindings,
            List<String> publishedOpenApiGroups
    ) {
        this(openApiDocumentService, handlerMapping, bulkOperationBindings, () -> publishedOpenApiGroups);
    }

    public OpenApiCanonicalOperationResolver(
            OpenApiDocumentService openApiDocumentService,
            RequestMappingHandlerMapping handlerMapping,
            BulkResourceOperationBindings bulkOperationBindings,
            Supplier<List<String>> publishedOpenApiGroups
    ) {
        this.openApiDocumentService = openApiDocumentService;
        this.handlerMapping = handlerMapping;
        this.bulkOperationBindings = bulkOperationBindings == null
                ? BulkResourceOperationBindings.empty() : bulkOperationBindings;
        this.publishedOpenApiGroups = publishedOpenApiGroups == null ? List::of : publishedOpenApiGroups;
    }

    @Override
    public String resolveGroup(String path) {
        return openApiDocumentService.resolveGroupFromPath(normalizePath(path));
    }

    @Override
    public CanonicalOperationRef resolve(String path, String method) {
        return new CanonicalOperationRef(
                resolveGroup(path),
                null,
                normalizePath(path),
                normalizeMethod(method)
        );
    }

    @Override
    public Optional<Class<?>> resolveRequestBodyType(String path, String method) {
        if (handlerMapping == null) return Optional.empty();
        String canonicalPath = normalizePath(path);
        RequestMethod expectedMethod = RequestMethod.valueOf(normalizeMethod(method));
        List<HandlerMethod> matches = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().stream()
                        .anyMatch(pattern -> canonicalPath.equals(normalizePath(pattern))))
                .filter(entry -> entry.getKey().getMethodsCondition().getMethods().isEmpty()
                        || entry.getKey().getMethodsCondition().getMethods().contains(expectedMethod))
                .map(Map.Entry::getValue).toList();
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous MVC request body binding for "
                + canonicalPath + " " + expectedMethod);
        if (matches.isEmpty()) return Optional.empty();
        HandlerMethod handler = matches.getFirst();
        org.springframework.core.MethodParameter body = null;
        for (org.springframework.core.MethodParameter parameter : handler.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)) {
                if (body != null) throw new IllegalStateException("Multiple MVC request bodies for " + canonicalPath);
                body = parameter;
            }
        }
        if (body == null) return Optional.empty();
        Class<?> type = org.springframework.core.ResolvableType
                .forMethodParameter(body.withContainingClass(handler.getBeanType())).resolve();
        return type == null || type == Object.class ? Optional.empty() : Optional.of(type);
    }

    @Override
    public CanonicalOperationRef resolve(HandlerMethod handlerMethod, RequestMappingInfo mappingInfo) {
        String path = mappingInfo.getPatternValues().stream()
                .min(Comparator.comparingInt(String::length).thenComparing(String::compareTo))
                .orElse("");
        String method = mappingInfo.getMethodsCondition().getMethods().stream()
                .findFirst()
                .map(Enum::name)
                .orElse("GET");
        String operationId = effectiveOperationId(handlerMethod);
        return new CanonicalOperationRef(
                resolveGroup(path),
                operationId,
                normalizePath(path),
                normalizeMethod(method)
        );
    }

    @Override
    public Optional<CanonicalOperationRef> resolveByOperationId(String operationId) {
        if (!StringUtils.hasText(operationId)) {
            return Optional.empty();
        }
        if (bulkOperationBindings.declares(operationId)
                && bulkOperationBindings.handlerFor(operationId).isEmpty()) {
            throw invalidBinding(operationId, "declared bulk resource operation is incomplete or ambiguous");
        }
        if (handlerMapping == null) {
            return Optional.empty();
        }
        List<Map.Entry<RequestMappingInfo, HandlerMethod>> matches = findMappings(operationId);
        rejectDuplicateMappings(operationId, matches);
        if (bulkOperationBindings.declares(operationId)) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : matches) {
                RequestMappingInfo mapping = entry.getKey();
                String path = mapping.getPatternValues().stream()
                        .min(Comparator.comparingInt(String::length).thenComparing(String::compareTo))
                        .orElse("");
                String method = mapping.getMethodsCondition().getMethods().stream()
                        .findFirst().map(Enum::name).orElse("GET");
                verifyPublishedBulkOperation(operationId, path, method);
            }
        }
        return matches.stream().findFirst().map(entry -> resolve(entry.getValue(), entry.getKey()));
    }

    /**
     * Resolves a batch of {@code operationIds} from MVC handler mappings only, validating
     * incomplete bulk declarations and rejecting duplicate mappings without inspecting OpenAPI
     * documents across groups.
     */
    @Override
    public Map<String, ResolvedMvcOperation> resolveMvcOperationsByIds(java.util.Collection<String> operationIds) {
        if (operationIds == null || operationIds.isEmpty()) {
            return Map.of();
        }
        validateDeclaredBulkIds(operationIds);
        if (handlerMapping == null) {
            return Map.of();
        }
        Map<String, List<Map.Entry<RequestMappingInfo, HandlerMethod>>> byOperationId = indexHandlersByOperationId();
        Map<String, ResolvedMvcOperation> resolved = new LinkedHashMap<>();
        for (String operationId : operationIds) {
            if (!StringUtils.hasText(operationId) || resolved.containsKey(operationId)) {
                continue;
            }
            List<Map.Entry<RequestMappingInfo, HandlerMethod>> matches =
                    byOperationId.getOrDefault(operationId, List.of());
            rejectDuplicateMappings(operationId, matches);
            if (matches.isEmpty()) {
                continue;
            }
            Map.Entry<RequestMappingInfo, HandlerMethod> entry = matches.getFirst();
            resolved.put(operationId, new ResolvedMvcOperation(resolve(entry.getValue(), entry.getKey()), entry.getValue()));
        }
        return java.util.Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves a batch of {@code operationIds} from MVC handler mappings first, applies
     * {@code scopeFilter} before inspecting OpenAPI documents, and verifies any in-scope declared
     * bulk operations using at most one call-scoped {@link OperationIndex} under
     * {@link OpenApiDocumentService#withSchemaCacheReadLock(Supplier)}. Unpublished OpenAPI sources
     * are never memoized across separate calls.
     */
    @Override
    public Map<String, CanonicalOperationRef> resolveByOperationIdsForScope(
            java.util.Collection<String> operationIds,
            java.util.function.Predicate<CanonicalOperationRef> scopeFilter
    ) {
        if (operationIds == null || operationIds.isEmpty()) {
            return Map.of();
        }
        validateDeclaredBulkIds(operationIds);
        if (handlerMapping == null) {
            return Map.of();
        }
        Map<String, List<Map.Entry<RequestMappingInfo, HandlerMethod>>> byOperationId = indexHandlersByOperationId();

        Map<String, CanonicalOperationRef> selected = new LinkedHashMap<>();
        List<Map.Entry<String, Map.Entry<RequestMappingInfo, HandlerMethod>>> bulkToVerify = new java.util.ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (String operationId : operationIds) {
            if (!StringUtils.hasText(operationId) || !seenIds.add(operationId)) {
                continue;
            }
            List<Map.Entry<RequestMappingInfo, HandlerMethod>> matches =
                    byOperationId.getOrDefault(operationId, List.of());
            rejectDuplicateMappings(operationId, matches);
            if (matches.isEmpty()) {
                continue;
            }
            Map.Entry<RequestMappingInfo, HandlerMethod> entry = matches.getFirst();
            CanonicalOperationRef candidate = resolve(entry.getValue(), entry.getKey());
            if (scopeFilter == null || scopeFilter.test(candidate)) {
                selected.put(operationId, candidate);
                if (bulkOperationBindings.declares(operationId)) {
                    bulkToVerify.add(Map.entry(operationId, entry));
                }
            }
        }

        if (!bulkToVerify.isEmpty()) {
            String firstBulkOperationId = bulkToVerify.getFirst().getKey();
            Boolean verified = openApiDocumentService.withSchemaCacheReadLock(() -> {
                verifyPublishedBulkOperationsWithinFence(bulkToVerify);
                return Boolean.TRUE;
            });
            if (!Boolean.TRUE.equals(verified)) {
                throw invalidBinding(firstBulkOperationId, "document fence did not complete identity verification");
            }
        }
        return java.util.Collections.unmodifiableMap(selected);
    }

    private void validateDeclaredBulkIds(java.util.Collection<String> operationIds) {
        for (String operationId : operationIds) {
            if (StringUtils.hasText(operationId)
                    && bulkOperationBindings.declares(operationId)
                    && bulkOperationBindings.handlerFor(operationId).isEmpty()) {
                throw invalidBinding(operationId, "declared bulk resource operation is incomplete or ambiguous");
            }
        }
    }

    private Map<String, List<Map.Entry<RequestMappingInfo, HandlerMethod>>> indexHandlersByOperationId() {
        Map<String, List<Map.Entry<RequestMappingInfo, HandlerMethod>>> byOperationId = new HashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            String effectiveId = effectiveOperationId(entry.getValue());
            byOperationId.computeIfAbsent(effectiveId, ignored -> new java.util.ArrayList<>()).add(entry);
        }
        return byOperationId;
    }

    @Override
    public CanonicalOperationRef requireResourceOperation(String resourceKey, String operationId, String method) {
        return requireMapping(resourceKey, operationId, method).operation();
    }

    @Override
    public List<CanonicalOperationRef> requireResourceOperations(String resourceKey,
            List<CanonicalOperationRef> operations, CanonicalOpenApiGroupSnapshot snapshot) {
        if (!StringUtils.hasText(resourceKey) || operations == null || operations.isEmpty() || snapshot == null) {
            throw new IllegalArgumentException("resourceKey, operations and exact OpenAPI snapshot are required");
        }
        Set<String> operationIds = new HashSet<>();
        for (CanonicalOperationRef operation : operations) {
            if (operation == null || !StringUtils.hasText(operation.operationId())
                    || !operationIds.add(operation.operationId())) {
                throw new IllegalStateException("Bulk operation IDs must be present and unique");
            }
        }
        if (handlerMapping == null) throw invalidBinding(operations.getFirst().operationId(), "MVC handler registry is unavailable");
        // One index for the bounded composition, shared by its exact-group snapshots.
        // Live registry equality is checked before and after every binding batch; no READY is cached.
        MappingIndex mappings = snapshot.mappingIndex(this, this::indexMappings, this::requireCurrentMappings);
        List<CanonicalOperationRef> validated = new java.util.ArrayList<>(operations.size());
        for (CanonicalOperationRef operation : operations) {
            StrictMapping mapping = requireMapping(resourceKey, operation.operationId(), operation.method(), false, mappings);
            if (!mapping.operation().equals(operation) || !snapshot.group().equals(operation.group())) {
                throw invalidBinding(operation.operationId(), "operation does not match the exact MVC binding and captured group");
            }
            validated.add(mapping.operation());
        }

        Set<String> groups = new LinkedHashSet<>();
        List<String> published = publishedOpenApiGroups.get();
        if (published != null) published.stream().filter(StringUtils::hasText).forEach(groups::add);
        groups.add(snapshot.group());
        OperationIndex index = snapshot.operationIndex(openApiDocumentService, groups,
                () -> indexOperations(snapshot, groups, validated.getFirst().operationId()));
        Map<String, JsonNode> pathsByGroup = index.pathsByGroup();
        // Route aliases normalize only the requested identities. Never mutate the shared
        // index: later bindings must still see duplicates in paths, callbacks and webhooks.
        Map<String, Set<String>> locationsByOperationId = new HashMap<>();
        for (CanonicalOperationRef operation : validated)
            locationsByOperationId.put(operation.operationId(),
                    new HashSet<>(index.locations().getOrDefault(operation.operationId(), Set.of())));
        for (CanonicalOperationRef operation : validated) {
            String method = operation.method().toLowerCase(Locale.ROOT);
            String canonicalLocation = "/paths/" + escapeJsonPointer(operation.path())
                    + "/" + escapeJsonPointer(method);
            for (Map.Entry<String, JsonNode> entry : pathsByGroup.entrySet()) {
                JsonNode paths = entry.getValue();
                String targetPath = resolveSummaryDocumentPath(paths, operation.path(), method);
                if (!StringUtils.hasText(targetPath)) continue;
                JsonNode targetOperation = paths.path(targetPath).path(method);
                if (targetOperation.isObject()) {
                    String publishedId = targetOperation.path("operationId").asText(null);
                    if (!operation.operationId().equals(publishedId)) {
                        throw invalidBinding(operation.operationId(), "published OpenAPI group '" + entry.getKey()
                                + "' changes the identity at the same structural route");
                    }
                    Set<String> locations = locationsByOperationId.get(operation.operationId());
                    String publishedLocation = entry.getKey() + "\u001f/paths/"
                            + escapeJsonPointer(targetPath) + "/" + escapeJsonPointer(method);
                    if (locations != null && locations.remove(publishedLocation)) {
                        locations.add(canonicalLocation);
                    }
                }
            }
            JsonNode paths = pathsByGroup.get(operation.group());
            if (paths == null || !paths.isObject()) {
                throw invalidBinding(operation.operationId(), "captured OpenAPI group has no paths object");
            }
            String path = resolveSummaryDocumentPath(paths, operation.path(), method);
            JsonNode operationNode = StringUtils.hasText(path) ? paths.path(path).path(method) : null;
            if (operationNode == null || !operation.operationId().equals(operationNode.path("operationId").asText(null))) {
                throw invalidBinding(operation.operationId(), "captured OpenAPI operation does not retain the exact MVC identity");
            }
            if (!locationsByOperationId.getOrDefault(operation.operationId(), Set.of())
                    .equals(Set.of(canonicalLocation))) {
                throw invalidBinding(operation.operationId(), "captured OpenAPI operationId is not globally unique across published groups, callbacks and webhooks");
            }
        }
        snapshot.validateMappings(mappings, this::requireCurrentMappings);
        return List.copyOf(validated);
    }

    record GroupOperationSummary(JsonNode pathsSkeleton, Map<String, Set<String>> locations) { }

    record OperationIndex(Map<String, JsonNode> pathsByGroup, Map<String, Set<String>> locations) { }

    private OperationIndex indexOperations(CanonicalOpenApiGroupSnapshot snapshot, Set<String> groups,
            String firstOperationId) {
        Map<String, JsonNode> pathsByGroup = new LinkedHashMap<>();
        Map<String, Set<String>> locationsByOperationId = new HashMap<>();
        GroupOperationSummary snapshotSummary = extractGroupOperationSummary(snapshot.readOnlyDocument());
        if (snapshotSummary == null) {
            throw invalidBinding(firstOperationId, "captured OpenAPI group has no paths object");
        }
        mergeGroupSummary(snapshot.group(), snapshotSummary, pathsByGroup, locationsByOperationId);
        List<String> remainingGroups = groups.stream()
                .filter(group -> !pathsByGroup.containsKey(group))
                .toList();
        if (!remainingGroups.isEmpty()) {
            if (openApiDocumentService.getClass() != CachedOpenApiDocumentService.class
                    && isDirectOrSpringProxyCachedDocumentService()) {
                Boolean loaded = openApiDocumentService.withSchemaCacheReadLock(() -> {
                    for (String group : remainingGroups) {
                        GroupOperationSummary summary = loadGroupOperationSummaryStrictWithinFence(group, firstOperationId);
                        mergeGroupSummary(group, summary, pathsByGroup, locationsByOperationId);
                    }
                    return Boolean.TRUE;
                });
                if (!Boolean.TRUE.equals(loaded)) {
                    throw invalidBinding(firstOperationId, "document fence did not complete identity verification");
                }
            } else {
                for (String group : remainingGroups) {
                    GroupOperationSummary summary = loadGroupOperationSummaryStrictWithinFence(group, firstOperationId);
                    mergeGroupSummary(group, summary, pathsByGroup, locationsByOperationId);
                }
            }
        }
        locationsByOperationId.replaceAll((id, locations) -> Set.copyOf(locations));
        return new OperationIndex(Map.copyOf(pathsByGroup), Map.copyOf(locationsByOperationId));
    }

    @Override
    public List<String> refreshPublishedOpenApiGroupsStrict(Set<String> requiredGroups) {
        List<String> ordered = publishedOpenApiGroups(requiredGroups);
        if (openApiDocumentService.localPublishedBulkOpenApiPublicationToken() != null
                && openApiDocumentService instanceof CachedOpenApiDocumentService cachedService) {
            for (String group : ordered) {
                GroupOperationSummary summary = cachedService.inspectGroupOperationSummaryStrict(group);
                if (summary == null || summary.pathsSkeleton() == null)
                    throw new IllegalStateException("Published OpenAPI group cannot be refreshed exactly: " + group);
            }
            return ordered;
        }
        for (String group : ordered) {
            JsonNode document = openApiDocumentService.refreshDocumentForGroupStrict(group);
            if (document == null || !document.path("paths").isObject())
                throw new IllegalStateException("Published OpenAPI group cannot be refreshed exactly: " + group);
        }
        return ordered;
    }

    @Override
    public List<String> publishedOpenApiGroups(Set<String> requiredGroups) {
        Set<String> groups = new LinkedHashSet<>();
        List<String> published = publishedOpenApiGroups.get();
        if (published != null) {
            for (String group : published) {
                if (!StringUtils.hasText(group) || !group.equals(group.strip()))
                    throw new IllegalStateException("Published OpenAPI group identities must be canonical");
                groups.add(group);
            }
        }
        if (requiredGroups == null || requiredGroups.isEmpty())
            throw new IllegalArgumentException("At least one required OpenAPI group is required");
        for (String group : requiredGroups) {
            if (!StringUtils.hasText(group) || !group.equals(group.strip()))
                throw new IllegalArgumentException("Required OpenAPI group identities must be canonical");
            groups.add(group);
        }
        return groups.stream().sorted().toList();
    }

    @Override
    public CanonicalRequestBodyBinding requireResourceRequestBody(String resourceKey, String operationId,
            String method, TypeFactory typeFactory) {
        if (typeFactory == null) throw new IllegalArgumentException("Configured TypeFactory is required");
        StrictMapping binding = requireMapping(resourceKey, operationId, method);
        return new CanonicalRequestBodyBinding(binding.operation(),
                CanonicalRequestBodyTypes.resolve(binding.handler(), typeFactory));
    }

    @Override
    public CanonicalRequestBodyBinding requireResourceRequestBody(String resourceKey,
            CanonicalOperationRef operation, TypeFactory typeFactory) {
        if (operation == null || typeFactory == null) {
            throw new IllegalArgumentException("Canonical operation and configured TypeFactory are required");
        }
        StrictMapping binding = requireMapping(resourceKey, operation.operationId(), operation.method(), false);
        if (!binding.operation().equals(operation)) {
            throw invalidBinding(operation.operationId(), "operation does not match its strict MVC handler mapping");
        }
        return new CanonicalRequestBodyBinding(operation,
                CanonicalRequestBodyTypes.resolve(binding.handler(), typeFactory));
    }

    @Override
    public CanonicalRequestBodyBinding requireResourceRequestBody(String resourceKey,
            CanonicalOperationRef operation, TypeFactory typeFactory, CanonicalOpenApiGroupSnapshot snapshot) {
        if (operation == null) throw new IllegalArgumentException("Canonical operation is required");
        return requireResourceRequestBodies(resourceKey, List.of(operation), typeFactory, snapshot).getFirst();
    }

    @Override
    public List<CanonicalRequestBodyBinding> requireResourceRequestBodies(String resourceKey,
            List<CanonicalOperationRef> operations, TypeFactory typeFactory, CanonicalOpenApiGroupSnapshot snapshot) {
        if (operations == null || operations.isEmpty() || typeFactory == null || snapshot == null
                || operations.stream().anyMatch(operation -> operation == null || !snapshot.group().equals(operation.group())))
            throw new IllegalArgumentException("Canonical operation, TypeFactory and exact-group snapshot are required");
        if (handlerMapping == null) throw invalidBinding(operations.getFirst().operationId(), "MVC handler registry is unavailable");
        MappingIndex mappings = snapshot.mappingIndex(this, this::indexMappings, this::requireCurrentMappings);
        var result = new java.util.ArrayList<CanonicalRequestBodyBinding>(operations.size());
        for (var operation : operations) {
            StrictMapping binding = requireMapping(resourceKey, operation.operationId(), operation.method(), false, mappings);
            if (!binding.operation().equals(operation))
                throw invalidBinding(operation.operationId(), "operation does not match its strict MVC handler mapping");
            result.add(new CanonicalRequestBodyBinding(operation,
                    CanonicalRequestBodyTypes.resolve(binding.handler(), typeFactory)));
        }
        snapshot.validateMappings(mappings, this::requireCurrentMappings);
        return List.copyOf(result);
    }

    private StrictMapping requireMapping(String resourceKey, String operationId, String method) {
        return requireMapping(resourceKey, operationId, method, true);
    }

    private StrictMapping requireMapping(String resourceKey, String operationId, String method,
            boolean verifyPublishedDocument) {
        return requireMapping(resourceKey, operationId, method, verifyPublishedDocument, null);
    }

    private StrictMapping requireMapping(String resourceKey, String operationId, String method,
            boolean verifyPublishedDocument, MappingIndex mappings) {
        if (!StringUtils.hasText(resourceKey) || !StringUtils.hasText(operationId) || !StringUtils.hasText(method)) {
            throw new IllegalArgumentException("resourceKey, operationId and method must not be blank");
        }
        if (bulkOperationBindings.declares(operationId)
                && bulkOperationBindings.handlerFor(operationId).isEmpty()) {
            throw invalidBinding(operationId, "declared bulk resource operation is incomplete or ambiguous");
        }
        RequestMethod expectedMethod;
        try {
            expectedMethod = RequestMethod.valueOf(normalizeMethod(method));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("method must be a supported HTTP method");
        }
        if (handlerMapping == null) {
            throw invalidBinding(operationId, "MVC handler registry is unavailable");
        }
        boolean standalone = mappings == null;
        if (standalone) mappings = indexMappings();
        List<Map.Entry<RequestMappingInfo, HandlerMethod>> matches = mappings.operations().getOrDefault(operationId, List.of());
        rejectDuplicateMappings(operationId, matches);
        if (matches.isEmpty()) {
            throw invalidBinding(operationId, "no registered operation");
        }
        Map.Entry<RequestMappingInfo, HandlerMethod> entry = matches.getFirst();
        HandlerMethod handler = entry.getValue();
        RequestMappingInfo mapping = entry.getKey();
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Operation.class);
        String declaredBulkId = bulkOperationBindings.operationIdFor(handler).orElse(null);
        if ((operation == null || !StringUtils.hasText(operation.operationId())) && !StringUtils.hasText(declaredBulkId)) {
            throw invalidBinding(operationId, "an explicit operationId binding is required");
        }
        if (operation != null && StringUtils.hasText(operation.operationId())
                && StringUtils.hasText(declaredBulkId) && !operationId.equals(declaredBulkId)) {
            throw invalidBinding(operationId, "declared bulk identity conflicts with @Operation operationId");
        }
        if ((operation != null && operation.hidden()) || handler.hasMethodAnnotation(Hidden.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), Hidden.class)) {
            throw invalidBinding(operationId, "operation is explicitly hidden");
        }
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), ApiResource.class);
        if (resource == null || !resourceKey.equals(resource.resourceKey())) {
            throw invalidBinding(operationId, "registered resource does not match the expected resourceKey");
        }
        if (mapping.getPatternValues().size() != 1 || mapping.getMethodsCondition().getMethods().size() != 1
                || !mapping.getMethodsCondition().getMethods().contains(expectedMethod)) {
            throw invalidBinding(operationId, "exactly one path and the expected HTTP method are required");
        }
        if (!mapping.getParamsCondition().isEmpty() || !mapping.getHeadersCondition().isEmpty()
                || mapping.getCustomCondition() != null) {
            throw invalidBinding(operationId, "conditional params, headers or custom routing cannot be represented");
        }
        if (bulkOperationBindings.requiresBodylessLifecycle(operationId)
                && !mapping.getConsumesCondition().getExpressions().isEmpty()) {
            throw invalidBinding(operationId, "bulk lifecycle routes cannot declare request consumes constraints");
        }
        String path = mapping.getPatternValues().iterator().next();
        // The schema reference contract normalizes these paths. Reject, rather than silently
        // publishing a different route from the one actually registered in Spring MVC.
        if (!path.startsWith("/") || !path.equals(normalizePath(path))) {
            throw invalidBinding(operationId, "registered path must already be canonical");
        }
        boolean sharedAddress = mappings.paths().getOrDefault(path, List.of()).stream()
                .filter(other -> !other.equals(mapping))
                .anyMatch(other -> other.getMethodsCondition().getMethods().isEmpty()
                        || other.getMethodsCondition().getMethods().contains(expectedMethod));
        if (sharedAddress) {
            throw invalidBinding(operationId, "path and method are shared by another registered mapping");
        }
        if (verifyPublishedDocument && StringUtils.hasText(declaredBulkId)) {
            verifyPublishedBulkOperation(declaredBulkId, path, expectedMethod.name());
        }
        if (standalone) requireCurrentMappings(mappings);
        return new StrictMapping(new CanonicalOperationRef(resolveGroup(path), operationId, path, expectedMethod.name()), handler);
    }

    /**
     * The MVC declaration is only a candidate identity. A strict bulk binding is usable only
     * when every published group document that contains the route retains that identity and
     * the ID remains unique across published paths, callbacks and webhooks; operation customizers
     * run after MVC inspection and can otherwise rewrite or duplicate it.
     */
    private void verifyPublishedBulkOperation(String operationId, String path, String method) {
        // One semantic identity is derived from every published group. Separate document
        // guards can observe different publications and repeatedly attest the same read.
        // Reuse the canonical scope: immutable local photograph, live entry/exit guards,
        // no authority cache and no domain work while holding the scope.
        Boolean verified = openApiDocumentService.withSchemaCacheReadLock(() -> {
            verifyPublishedBulkOperationWithinFence(operationId, path, method);
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(verified))
            throw invalidBinding(operationId, "document fence did not complete identity verification");
    }

    private void verifyPublishedBulkOperationsWithinFence(
            List<Map.Entry<String, Map.Entry<RequestMappingInfo, HandlerMethod>>> bulkEntries) {
        List<String> currentPublishedGroups = publishedOpenApiGroups.get();
        Set<String> groups = new LinkedHashSet<>();
        if (currentPublishedGroups != null) {
            currentPublishedGroups.stream().filter(StringUtils::hasText).forEach(groups::add);
        }
        List<String[]> targets = new java.util.ArrayList<>(bulkEntries.size());
        for (var bulkEntry : bulkEntries) {
            String operationId = bulkEntry.getKey();
            RequestMappingInfo mapping = bulkEntry.getValue().getKey();
            String path = mapping.getPatternValues().stream()
                    .min(Comparator.comparingInt(String::length).thenComparing(String::compareTo))
                    .orElse("");
            String method = mapping.getMethodsCondition().getMethods().stream()
                    .findFirst().map(Enum::name).orElse("GET");
            String group = resolveGroup(path);
            groups.add(group);
            targets.add(new String[] { operationId, path, method, group });
        }
        String firstOperationId = targets.getFirst()[0];
        OperationIndex index = publishedOperationIndexWithinFence(groups, firstOperationId);
        if (index == null) {
            // Unpublished sources must never be memoized across calls, but can share one
            // call-scoped index built within this single cache-read fence.
            index = buildEphemeralOperationIndexWithinFence(groups, firstOperationId);
        }
        for (String[] target : targets) {
            verifyPublishedBulkOperationFromIndex(target[0], target[1], target[2], target[3], groups, index);
        }
    }

    private OperationIndex buildEphemeralOperationIndexWithinFence(Set<String> groups, String firstOperationId) {
        Map<String, JsonNode> pathsByGroup = new LinkedHashMap<>();
        Map<String, Set<String>> locationsByOperationId = new HashMap<>();
        for (String publishedGroup : groups) {
            GroupOperationSummary summary = loadGroupOperationSummaryStrictWithinFence(publishedGroup, firstOperationId);
            mergeGroupSummary(publishedGroup, summary, pathsByGroup, locationsByOperationId);
        }
        locationsByOperationId.replaceAll((id, locations) -> Set.copyOf(locations));
        return new OperationIndex(Map.copyOf(pathsByGroup), Map.copyOf(locationsByOperationId));
    }

    private GroupOperationSummary loadGroupOperationSummaryStrictWithinFence(String group, String firstOperationId) {
        GroupOperationSummary summary;
        if (openApiDocumentService.getClass() == CachedOpenApiDocumentService.class) {
            summary = ((CachedOpenApiDocumentService) openApiDocumentService).inspectGroupOperationSummaryStrict(group);
        } else if (isDirectOrSpringProxyCachedDocumentService()) {
            Object singletonTarget = org.springframework.aop.framework.AopProxyUtils
                    .getSingletonTarget(openApiDocumentService);
            if (singletonTarget instanceof CachedOpenApiDocumentService target) {
                summary = target.inspectGroupOperationSummaryStrict(group);
            } else if (openApiDocumentService instanceof CachedOpenApiDocumentService cached) {
                summary = cached.inspectGroupOperationSummaryStrict(group);
            } else {
                summary = extractGroupOperationSummary(openApiDocumentService.getDocumentForGroupStrict(group));
            }
        } else {
            summary = extractGroupOperationSummary(openApiDocumentService.getDocumentForGroupStrict(group));
        }
        if (summary == null) {
            throw invalidBinding(firstOperationId, "published OpenAPI group '" + group
                    + "' cannot be inspected for global identity collisions");
        }
        return summary;
    }

    private boolean isDirectOrSpringProxyCachedDocumentService() {
        if (openApiDocumentService == null) {
            return false;
        }
        if (openApiDocumentService.getClass() == CachedOpenApiDocumentService.class) {
            return true;
        }
        return org.springframework.aop.support.AopUtils.isAopProxy(openApiDocumentService)
                && org.springframework.aop.framework.AopProxyUtils.ultimateTargetClass(openApiDocumentService)
                        == CachedOpenApiDocumentService.class;
    }

    private String resolveSummaryDocumentPath(JsonNode pathsSkeleton, String path, String method) {
        if (pathsSkeleton.has(path)) {
            return path;
        }
        JsonNode argument = openApiDocumentService != null
                && openApiDocumentService.getClass() == CachedOpenApiDocumentService.class
                ? pathsSkeleton
                : pathsSkeleton.deepCopy();
        return openApiDocumentService.resolveDocumentPath(argument, path, method);
    }

    private static void mergeGroupSummary(
            String group,
            GroupOperationSummary summary,
            Map<String, JsonNode> pathsByGroup,
            Map<String, Set<String>> locationsByOperationId
    ) {
        pathsByGroup.put(group, summary.pathsSkeleton());
        summary.locations().forEach((id, locations) -> {
            Set<String> merged = locationsByOperationId.computeIfAbsent(id, ignored -> new HashSet<>());
            for (String location : locations) {
                merged.add(group + "\u001f" + location);
            }
        });
    }

    static GroupOperationSummary extractGroupOperationSummary(JsonNode document) {
        if (document == null || !document.path("paths").isObject()) {
            return null;
        }
        ObjectNode pathsSkeleton = JsonNodeFactory.instance.objectNode();
        Map<String, Set<String>> groupLocations = new HashMap<>();
        JsonNode pathsNode = document.path("paths");
        var pathFields = pathsNode.fields();
        while (pathFields.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathFields.next();
            JsonNode pathItem = pathEntry.getValue();
            if (!pathItem.isObject()) {
                continue;
            }
            ObjectNode pathSkeleton = pathsSkeleton.putObject(pathEntry.getKey());
            String pathPointer = "/paths/" + escapeJsonPointer(pathEntry.getKey());
            for (String method : OPENAPI_METHODS) {
                JsonNode operation = pathItem.path(method);
                if (!operation.isObject()) {
                    continue;
                }
                ObjectNode opSkeleton = pathSkeleton.putObject(method);
                JsonNode identity = operation.get("operationId");
                if (identity != null && identity.isTextual()) {
                    opSkeleton.put("operationId", identity.asText());
                }
                String location = pathPointer + "/" + method;
                recordOperationId(operation, location, groupLocations);
                collectOperationCallbacks(operation, location, groupLocations);
            }
        }
        collectPathItems(document.path("webhooks"), "/webhooks", groupLocations);
        JsonNode callbacks = document.path("components").path("callbacks");
        if (callbacks.isObject()) {
            var callbackFields = callbacks.fields();
            while (callbackFields.hasNext()) {
                Map.Entry<String, JsonNode> callback = callbackFields.next();
                collectCallback(callback.getValue(), "/components/callbacks/" + escapeJsonPointer(callback.getKey()),
                        groupLocations);
            }
        }
        groupLocations.replaceAll((id, locations) -> Set.copyOf(locations));
        return new GroupOperationSummary(pathsSkeleton, Map.copyOf(groupLocations));
    }

    private void verifyPublishedBulkOperationWithinFence(String operationId, String path, String method) {
        String group = resolveGroup(path);
        List<String> currentPublishedGroups = publishedOpenApiGroups.get();
        Set<String> groups = new LinkedHashSet<>();
        if (currentPublishedGroups != null) {
            currentPublishedGroups.stream().filter(StringUtils::hasText).forEach(groups::add);
        }
        groups.add(group);
        OperationIndex index = publishedOperationIndexWithinFence(groups, operationId);
        if (index == null) {
            index = buildEphemeralOperationIndexWithinFence(groups, operationId);
        }
        verifyPublishedBulkOperationFromIndex(operationId, path, method, group, groups, index);
    }

    private OperationIndex publishedOperationIndexWithinFence(Set<String> groups, String operationId) {
        Object publication = openApiDocumentService.localPublishedBulkOpenApiPublicationToken();
        if (publication == null) return null;
        Map<String, Object> tokens = groupTokensWithinFence(groups);
        if (tokens == null) return null;
        synchronized (publishedOperationIndexMonitor) {
            OperationIndex hit = lookupPublishedOperationIndexLocked(publication, tokens);
            if (hit != null) {
                return hit;
            }
        }
        // Build outside the monitor so custom SPIs doing I/O never stall unrelated threads or bypass timed locks.
        OperationIndex built = buildEphemeralOperationIndexWithinFence(groups, operationId);
        if (publication != openApiDocumentService.localPublishedBulkOpenApiPublicationToken()
                || !tokens.equals(groupTokensWithinFence(groups))) {
            throw invalidBinding(operationId, "published OpenAPI photograph changed during identity indexing");
        }
        synchronized (publishedOperationIndexMonitor) {
            OperationIndex hit = lookupPublishedOperationIndexLocked(publication, tokens);
            if (hit != null) {
                return hit;
            }
            CachedOperationIndexEntry entry = new CachedOperationIndexEntry(publication, tokens, built);
            // Two-slot LRU retention prevents concurrent preparedCandidateScope reads from evicting the live index.
            secondaryOperationIndex = primaryOperationIndex;
            primaryOperationIndex = entry;
            return built;
        }
    }

    private OperationIndex lookupPublishedOperationIndexLocked(Object publication, Map<String, Object> tokens) {
        if (primaryOperationIndex != null && primaryOperationIndex.matches(publication, tokens)) {
            return primaryOperationIndex.index();
        }
        if (secondaryOperationIndex != null && secondaryOperationIndex.matches(publication, tokens)) {
            CachedOperationIndexEntry promoted = secondaryOperationIndex;
            secondaryOperationIndex = primaryOperationIndex;
            primaryOperationIndex = promoted;
            return promoted.index();
        }
        return null;
    }

    private Map<String, Object> groupTokensWithinFence(Set<String> groups) {
        Map<String, Object> tokens = new HashMap<>();
        for (String group : groups) {
            Object token = openApiDocumentService.groupDocumentCacheToken(group);
            if (token == null) return null;
            tokens.put(group, token);
        }
        return Map.copyOf(tokens);
    }

    private void verifyPublishedBulkOperationFromIndex(String operationId, String path, String method,
            String group, Set<String> groups, OperationIndex index) {
        String lowerMethod = method.toLowerCase(Locale.ROOT);
        JsonNode paths = index.pathsByGroup().get(group);
        if (paths == null || !paths.isObject()) {
            throw invalidBinding(operationId, "served OpenAPI document has no paths object");
        }
        String documentPath = resolveSummaryDocumentPath(paths, path, method);
        if (!StringUtils.hasText(documentPath)) documentPath = path;
        JsonNode operation = paths.path(documentPath).path(lowerMethod);
        if (operation.isMissingNode() || !operationId.equals(operation.path("operationId").asText(null))) {
            throw invalidBinding(operationId, "served OpenAPI operation at " + documentPath + " " + method
                    + " does not retain the declared identity (actual='" + operation.path("operationId").asText(null) + "')");
        }
        String expectedLocation = "/paths/" + escapeJsonPointer(documentPath) + "/" + escapeJsonPointer(lowerMethod);
        Set<String> operationLocations = new HashSet<>(index.locations().getOrDefault(operationId, Set.of()));
        for (String publishedGroup : groups) {
            JsonNode publishedPaths = index.pathsByGroup().get(publishedGroup);
            if (publishedPaths == null || !publishedPaths.isObject()) {
                throw invalidBinding(operationId, "published OpenAPI group '" + publishedGroup
                        + "' cannot be inspected for global identity collisions");
            }
            String publishedTargetPath = resolveSummaryDocumentPath(publishedPaths, path, method);
            if (StringUtils.hasText(publishedTargetPath)) {
                JsonNode targetOperation = publishedPaths.path(publishedTargetPath).path(lowerMethod);
                if (!targetOperation.isMissingNode()
                        && !operationId.equals(targetOperation.path("operationId").asText(null))) {
                    throw invalidBinding(operationId, "published OpenAPI group '" + publishedGroup
                            + "' changes the lifecycle operation identity");
                }
                if (operationId.equals(targetOperation.path("operationId").asText(null))) {
                    String publishedTargetLocation = publishedGroup + "\u001f/paths/"
                            + escapeJsonPointer(publishedTargetPath) + "/" + escapeJsonPointer(lowerMethod);
                    if (operationLocations.remove(publishedTargetLocation)) {
                        operationLocations.add(expectedLocation);
                    }
                }
            }
        }
        if (!operationLocations.equals(Set.of(expectedLocation))) {
            throw invalidBinding(operationId, "served OpenAPI operationId is not globally unique across published groups, callbacks and webhooks");
        }
    }

    private static void collectPathItems(JsonNode pathItems, String pointer,
            Map<String, Set<String>> locationsByOperationId) {
        if (pathItems == null || !pathItems.isObject()) return;
        var pathFields = pathItems.fields();
        while (pathFields.hasNext()) {
            Map.Entry<String, JsonNode> path = pathFields.next();
            collectPathItem(path.getValue(), pointer + "/" + escapeJsonPointer(path.getKey()), locationsByOperationId);
        }
    }

    private static void collectPathItem(JsonNode pathItem, String pointer,
            Map<String, Set<String>> locationsByOperationId) {
        for (String method : OPENAPI_METHODS) {
            JsonNode operation = pathItem.path(method);
            if (!operation.isObject()) continue;
            String location = pointer + "/" + method;
            recordOperationId(operation, location, locationsByOperationId);
            collectOperationCallbacks(operation, location, locationsByOperationId);
        }
    }

    private static void collectOperationCallbacks(JsonNode operation, String pointer,
            Map<String, Set<String>> locationsByOperationId) {
        JsonNode callbacks = operation.path("callbacks");
        if (!callbacks.isObject()) return;
        var callbackFields = callbacks.fields();
        while (callbackFields.hasNext()) {
            Map.Entry<String, JsonNode> callback = callbackFields.next();
            collectCallback(callback.getValue(), pointer + "/callbacks/" + escapeJsonPointer(callback.getKey()),
                    locationsByOperationId);
        }
    }

    private static void collectCallback(JsonNode callback, String pointer,
            Map<String, Set<String>> locationsByOperationId) {
        if (!callback.isObject()) return;
        var expressions = callback.fields();
        while (expressions.hasNext()) {
            Map.Entry<String, JsonNode> expression = expressions.next();
            if (expression.getKey().startsWith("$") || !expression.getValue().isObject()) continue;
            collectPathItem(expression.getValue(), pointer + "/" + escapeJsonPointer(expression.getKey()),
                    locationsByOperationId);
        }
    }

    private static void recordOperationId(JsonNode operation, String pointer,
            Map<String, Set<String>> locationsByOperationId) {
        JsonNode identity = operation.get("operationId");
        if (identity != null && identity.isTextual() && StringUtils.hasText(identity.asText())) {
            locationsByOperationId.computeIfAbsent(identity.asText(), ignored -> new LinkedHashSet<>()).add(pointer);
        }
    }

    private static String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record StrictMapping(CanonicalOperationRef operation, HandlerMethod handler) { }

    record MappingIndex(Map<RequestMappingInfo, HandlerMethod> handlers,
            Map<String, List<Map.Entry<RequestMappingInfo, HandlerMethod>>> operations,
            Map<String, List<RequestMappingInfo>> paths) { }

    MappingIndex indexMappings() {
        Map<RequestMappingInfo, HandlerMethod> handlers = Map.copyOf(handlerMapping.getHandlerMethods());
        Map<String, List<Map.Entry<RequestMappingInfo, HandlerMethod>>> operations = new HashMap<>();
        Map<String, List<RequestMappingInfo>> paths = new HashMap<>();
        handlers.forEach((mapping, handler) -> {
            operations.computeIfAbsent(effectiveOperationId(handler),
                    ignored -> new java.util.ArrayList<>()).add(Map.entry(mapping, handler));
            mapping.getPatternValues().forEach(path -> paths.computeIfAbsent(path,
                    ignored -> new java.util.ArrayList<>()).add(mapping));
        });
        operations.replaceAll((id, entries) -> List.copyOf(entries));
        paths.replaceAll((path, entries) -> List.copyOf(entries));
        return new MappingIndex(handlers, Map.copyOf(operations), Map.copyOf(paths));
    }

    private void requireCurrentMappings(MappingIndex mappings) {
        if (!mappings.handlers().equals(handlerMapping.getHandlerMethods()))
            throw new IllegalStateException("MVC handler registry changed during strict operation binding");
    }

    private List<Map.Entry<RequestMappingInfo, HandlerMethod>> findMappings(String operationId) {
        // Match identity before resolving groups. Neither resource nor method filtering may
        // hide a global collision; no OpenAPI document fetch is needed to inspect handlers.
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> {
                    HandlerMethod handler = entry.getValue();
                    String effectiveId = effectiveOperationId(handler);
                    return operationId.equals(effectiveId);
                }).toList();
    }

    private String effectiveOperationId(HandlerMethod handler) {
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Operation.class);
        String explicitId = operation == null ? null : operation.operationId();
        String declaredBulkId = bulkOperationBindings.operationIdFor(handler).orElse(null);
        if (StringUtils.hasText(explicitId) && StringUtils.hasText(declaredBulkId)
                && !explicitId.equals(declaredBulkId)) {
            throw invalidBinding(declaredBulkId, "declared bulk identity conflicts with @Operation operationId");
        }
        if (StringUtils.hasText(explicitId)) return explicitId;
        if (StringUtils.hasText(declaredBulkId)) return declaredBulkId;
        return handler.getMethod().getName();
    }

    private void rejectDuplicateMappings(String operationId,
            List<Map.Entry<RequestMappingInfo, HandlerMethod>> matches) {
        if (matches.size() > 1) {
            throw invalidBinding(operationId, "operationId is ambiguous across registered mappings");
        }
    }

    private IllegalStateException invalidBinding(String operationId, String reason) {
        return new IllegalStateException("Cannot bind operationId '" + operationId + "': " + reason);
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String normalized = decodePath(path).replaceAll("/+", "/").trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String decodePath(String path) {
        try {
            return UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return path;
        }
    }

    private String normalizeMethod(String method) {
        if (!StringUtils.hasText(method)) {
            return "GET";
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }
}
