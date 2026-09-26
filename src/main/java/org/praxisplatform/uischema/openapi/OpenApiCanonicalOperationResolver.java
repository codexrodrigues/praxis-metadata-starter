package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.JsonNode;
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
public class OpenApiCanonicalOperationResolver implements CanonicalOperationResolver {

    private static final Set<String> OPENAPI_METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    private final OpenApiDocumentService openApiDocumentService;
    private final RequestMappingHandlerMapping handlerMapping;
    private final BulkResourceOperationBindings bulkOperationBindings;
    private final Supplier<List<String>> publishedOpenApiGroups;

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
        if (bulkOperationBindings.declares(operationId)
                && bulkOperationBindings.handlerFor(operationId).isEmpty()) {
            throw invalidBinding(operationId, "declared bulk resource operation is incomplete or ambiguous");
        }
        if (!StringUtils.hasText(operationId) || handlerMapping == null) {
            return Optional.empty();
        }
        List<Map.Entry<RequestMappingInfo, HandlerMethod>> matches = findMappings(operationId);
        rejectDuplicateMappings(operationId, matches);
        if (bulkOperationBindings.declares(operationId)) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : matches) {
                RequestMappingInfo mapping = entry.getKey();
                String path = mapping.getPatternValues().stream().findFirst().orElse("");
                String method = mapping.getMethodsCondition().getMethods().stream()
                        .findFirst().map(Enum::name).orElse("GET");
                verifyPublishedBulkOperation(operationId, path, method);
            }
        }
        return matches.stream().findFirst().map(entry -> resolve(entry.getValue(), entry.getKey()));
    }

    @Override
    public CanonicalOperationRef requireResourceOperation(String resourceKey, String operationId, String method) {
        return requireMapping(resourceKey, operationId, method).operation();
    }

    @Override
    public CanonicalRequestBodyBinding requireResourceRequestBody(String resourceKey, String operationId,
            String method, TypeFactory typeFactory) {
        if (typeFactory == null) throw new IllegalArgumentException("Configured TypeFactory is required");
        StrictMapping binding = requireMapping(resourceKey, operationId, method);
        return new CanonicalRequestBodyBinding(binding.operation(),
                CanonicalRequestBodyTypes.resolve(binding.handler(), typeFactory));
    }

    private StrictMapping requireMapping(String resourceKey, String operationId, String method) {
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
        List<Map.Entry<RequestMappingInfo, HandlerMethod>> matches = findMappings(operationId);
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
        boolean sharedAddress = handlerMapping.getHandlerMethods().keySet().stream()
                .filter(other -> !other.equals(mapping))
                .anyMatch(other -> other.getPatternValues().contains(path)
                        && (other.getMethodsCondition().getMethods().isEmpty()
                        || other.getMethodsCondition().getMethods().contains(expectedMethod)));
        if (sharedAddress) {
            throw invalidBinding(operationId, "path and method are shared by another registered mapping");
        }
        if (StringUtils.hasText(declaredBulkId)) {
            verifyPublishedBulkOperation(declaredBulkId, path, expectedMethod.name());
        }
        return new StrictMapping(new CanonicalOperationRef(resolveGroup(path), operationId, path, expectedMethod.name()), handler);
    }

    /**
     * The MVC declaration is only a candidate identity. A strict bulk binding is usable only
     * when every published group document that contains the route retains that identity and
     * the ID remains unique across published paths, callbacks and webhooks; operation customizers
     * run after MVC inspection and can otherwise rewrite or duplicate it.
     */
    private void verifyPublishedBulkOperation(String operationId, String path, String method) {
        String group = resolveGroup(path);
        JsonNode document = openApiDocumentService.getDocumentForGroupStrict(group);
        JsonNode paths = document == null ? null : document.path("paths");
        if (paths == null || !paths.isObject()) {
            throw invalidBinding(operationId, "served OpenAPI document has no paths object");
        }
        String documentPath = paths.has(path) ? path : openApiDocumentService.resolveDocumentPath(paths, path, method);
        if (!StringUtils.hasText(documentPath)) documentPath = path;
        JsonNode operation = paths.path(documentPath).path(method.toLowerCase(Locale.ROOT));
        if (operation.isMissingNode() || !operationId.equals(operation.path("operationId").asText(null))) {
            throw invalidBinding(operationId, "served OpenAPI operation at " + documentPath + " " + method
                    + " does not retain the declared identity (actual='" + operation.path("operationId").asText(null) + "')");
        }
        String expectedLocation = "/paths/" + escapeJsonPointer(documentPath) + "/"
                + escapeJsonPointer(method.toLowerCase(Locale.ROOT));
        Map<String, Set<String>> locationsByOperationId = new HashMap<>();
        List<String> currentPublishedGroups = publishedOpenApiGroups.get();
        Set<String> groups = new LinkedHashSet<>();
        if (currentPublishedGroups != null) {
            currentPublishedGroups.stream().filter(StringUtils::hasText).forEach(groups::add);
        }
        groups.add(group);
        for (String publishedGroup : groups) {
            JsonNode publishedDocument = publishedGroup.equals(group)
                    ? document : openApiDocumentService.getDocumentForGroupStrict(publishedGroup);
            if (publishedDocument == null || !publishedDocument.path("paths").isObject()) {
                throw invalidBinding(operationId, "published OpenAPI group '" + publishedGroup
                        + "' cannot be inspected for global identity collisions");
            }
            JsonNode publishedPaths = publishedDocument.path("paths");
            String publishedTargetPath = publishedPaths.has(path) ? path
                    : openApiDocumentService.resolveDocumentPath(publishedPaths, path, method);
            if (StringUtils.hasText(publishedTargetPath)) {
                JsonNode targetOperation = publishedPaths.path(publishedTargetPath)
                        .path(method.toLowerCase(Locale.ROOT));
                if (!targetOperation.isMissingNode()
                        && !operationId.equals(targetOperation.path("operationId").asText(null))) {
                    throw invalidBinding(operationId, "published OpenAPI group '" + publishedGroup
                            + "' changes the lifecycle operation identity");
                }
            }
            Map<String, Set<String>> groupLocations = new HashMap<>();
            collectDocumentOperationIds(publishedDocument, groupLocations);
            if (StringUtils.hasText(publishedTargetPath)) {
                JsonNode targetOperation = publishedPaths.path(publishedTargetPath)
                        .path(method.toLowerCase(Locale.ROOT));
                if (operationId.equals(targetOperation.path("operationId").asText(null))) {
                    String publishedTargetLocation = "/paths/" + escapeJsonPointer(publishedTargetPath) + "/"
                            + escapeJsonPointer(method.toLowerCase(Locale.ROOT));
                    Set<String> targetLocations = groupLocations.get(operationId);
                    if (targetLocations != null) {
                        targetLocations.remove(publishedTargetLocation);
                        targetLocations.add(expectedLocation);
                    }
                }
            }
            groupLocations.forEach((id, locations) -> locationsByOperationId
                    .computeIfAbsent(id, ignored -> new LinkedHashSet<>()).addAll(locations));
        }
        Set<String> operationLocations = locationsByOperationId.getOrDefault(operationId, Set.of());
        if (!operationLocations.equals(Set.of(expectedLocation))) {
            throw invalidBinding(operationId, "served OpenAPI operationId is not globally unique across published groups, callbacks and webhooks");
        }
    }

    private void collectDocumentOperationIds(JsonNode document, Map<String, Set<String>> locationsByOperationId) {
        collectPathItems(document.path("paths"), "/paths", locationsByOperationId);
        collectPathItems(document.path("webhooks"), "/webhooks", locationsByOperationId);
        JsonNode callbacks = document.path("components").path("callbacks");
        if (callbacks.isObject()) {
            var callbackFields = callbacks.fields();
            while (callbackFields.hasNext()) {
                Map.Entry<String, JsonNode> callback = callbackFields.next();
                collectCallback(callback.getValue(), "/components/callbacks/" + escapeJsonPointer(callback.getKey()),
                        locationsByOperationId);
            }
        }
    }

    private void collectPathItems(JsonNode pathItems, String pointer,
            Map<String, Set<String>> locationsByOperationId) {
        if (pathItems == null || !pathItems.isObject()) return;
        var pathFields = pathItems.fields();
        while (pathFields.hasNext()) {
            Map.Entry<String, JsonNode> path = pathFields.next();
            collectPathItem(path.getValue(), pointer + "/" + escapeJsonPointer(path.getKey()), locationsByOperationId);
        }
    }

    private void collectPathItem(JsonNode pathItem, String pointer,
            Map<String, Set<String>> locationsByOperationId) {
        for (String method : OPENAPI_METHODS) {
            JsonNode operation = pathItem.path(method);
            if (!operation.isObject()) continue;
            String location = pointer + "/" + method;
            recordOperationId(operation, location, locationsByOperationId);
            collectOperationCallbacks(operation, location, locationsByOperationId);
        }
    }

    private void collectOperationCallbacks(JsonNode operation, String pointer,
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

    private void collectCallback(JsonNode callback, String pointer,
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

    private void recordOperationId(JsonNode operation, String pointer,
            Map<String, Set<String>> locationsByOperationId) {
        JsonNode identity = operation.get("operationId");
        if (identity != null && identity.isTextual() && StringUtils.hasText(identity.asText())) {
            locationsByOperationId.computeIfAbsent(identity.asText(), ignored -> new LinkedHashSet<>()).add(pointer);
        }
    }

    private String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record StrictMapping(CanonicalOperationRef operation, HandlerMethod handler) { }

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
        String normalized = decodePath(path).replaceAll("/+", "/");
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
