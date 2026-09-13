package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Hidden;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementacao padrao de {@link CanonicalOperationResolver} baseada no registro de handlers do
 * Spring MVC.
 *
 * <p>
 * No fluxo {@code path + method}, a classe apenas normaliza a rota e resolve o grupo associado.
 * No fluxo {@code HandlerMethod + RequestMappingInfo}, ela escolhe o menor path declarado no
 * mapping, usa o primeiro metodo HTTP disponivel e define o {@code operationId} a partir de
 * {@link Operation#operationId()} ou, na falta dele, do nome do metodo Java.
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

    private final OpenApiDocumentService openApiDocumentService;
    private final RequestMappingHandlerMapping handlerMapping;

    public OpenApiCanonicalOperationResolver(
            OpenApiDocumentService openApiDocumentService,
            RequestMappingHandlerMapping handlerMapping
    ) {
        this.openApiDocumentService = openApiDocumentService;
        this.handlerMapping = handlerMapping;
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
        Operation operation = handlerMethod.getMethodAnnotation(Operation.class);
        String operationId = operation != null && StringUtils.hasText(operation.operationId())
                ? operation.operationId()
                : handlerMethod.getMethod().getName();
        return new CanonicalOperationRef(
                resolveGroup(path),
                operationId,
                normalizePath(path),
                normalizeMethod(method)
        );
    }

    @Override
    public Optional<CanonicalOperationRef> resolveByOperationId(String operationId) {
        if (!StringUtils.hasText(operationId) || handlerMapping == null) {
            return Optional.empty();
        }
        List<Map.Entry<RequestMappingInfo, HandlerMethod>> matches = findMappings(operationId);
        rejectDuplicateMappings(operationId, matches);
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
        Operation operation = handler.getMethodAnnotation(Operation.class);
        if (operation == null || !StringUtils.hasText(operation.operationId())) {
            throw invalidBinding(operationId, "an explicit @Operation operationId is required");
        }
        if (operation.hidden() || handler.hasMethodAnnotation(Hidden.class)
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
        return new StrictMapping(new CanonicalOperationRef(resolveGroup(path), operationId, path, expectedMethod.name()), handler);
    }

    private record StrictMapping(CanonicalOperationRef operation, HandlerMethod handler) { }

    private List<Map.Entry<RequestMappingInfo, HandlerMethod>> findMappings(String operationId) {
        // Match identity before resolving groups. Neither resource nor method filtering may
        // hide a global collision; no OpenAPI document fetch is needed to inspect handlers.
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> {
                    HandlerMethod handler = entry.getValue();
                    Operation operation = handler.getMethodAnnotation(Operation.class);
                    String effectiveId = operation != null && StringUtils.hasText(operation.operationId())
                            ? operation.operationId() : handler.getMethod().getName();
                    return operationId.equals(effectiveId);
                }).toList();
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
