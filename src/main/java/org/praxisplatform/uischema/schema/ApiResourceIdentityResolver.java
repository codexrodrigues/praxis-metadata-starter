package org.praxisplatform.uischema.schema;

import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Resolves the canonical record identity declared by {@link ApiResource}. */
@Component
public class ApiResourceIdentityResolver {

    private final RequestMappingHandlerMapping handlerMapping;

    public ApiResourceIdentityResolver(RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    public Optional<Map<String, Object>> resolve(String resourcePath) {
        String expectedPath = normalize(resourcePath);
        return handlerMapping.getHandlerMethods().values().stream()
                .map(HandlerMethod::getBeanType)
                .distinct()
                .map(type -> AnnotationUtils.findAnnotation(type, ApiResource.class))
                .filter(annotation -> annotation != null && matches(annotation, expectedPath))
                .map(ApiResource::identity)
                .filter(ApiResourceIdentityResolver::isConfigured)
                .findFirst()
                .map(ApiResourceIdentityResolver::toMap);
    }

    private static boolean matches(ApiResource resource, String expectedPath) {
        return Arrays.stream(resource.value()).anyMatch(path -> normalize(path).equals(expectedPath))
                || Arrays.stream(resource.path()).anyMatch(path -> normalize(path).equals(expectedPath));
    }

    private static boolean isConfigured(ResourceIdentity identity) {
        return !identity.keyField().isBlank()
                || !identity.titleField().isBlank()
                || identity.metadataFields().length > 0
                || !identity.displayLabelField().isBlank();
    }

    private static Map<String, Object> toMap(ResourceIdentity identity) {
        Map<String, Object> result = new LinkedHashMap<>();
        putIfPresent(result, "keyField", identity.keyField());
        putIfPresent(result, "titleField", identity.titleField());
        if (identity.metadataFields().length > 0) {
            result.put("metadataFields", Arrays.asList(identity.metadataFields()));
        }
        putIfPresent(result, "displayLabelField", identity.displayLabelField());
        return result;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.trim().replaceAll("/+$", "");
        return normalized.isBlank() ? "/" : normalized;
    }
}
