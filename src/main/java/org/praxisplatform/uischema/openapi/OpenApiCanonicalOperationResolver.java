package org.praxisplatform.uischema.openapi;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
        return openApiDocumentService.resolveGroupFromPath(path);
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
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            CanonicalOperationRef ref = resolve(entry.getValue(), entry.getKey());
            if (operationId.equals(ref.operationId())) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String normalized = path.replaceAll("/+", "/");
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeMethod(String method) {
        if (!StringUtils.hasText(method)) {
            return "GET";
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }
}
