package org.praxisplatform.uischema.schema;

import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Resolves the canonical record identity and resource metadata declared by {@link ApiResource}. */
@Component
public class ApiResourceIdentityResolver {

    private final RequestMappingHandlerMapping handlerMapping;

    public ApiResourceIdentityResolver(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping
    ) {
        this.handlerMapping = handlerMapping;
    }

    public Optional<Map<String, Object>> resolve(String resourcePath) {
        return findApiResourceByPath(resourcePath)
                .map(ApiResource::identity)
                .filter(ApiResourceIdentityResolver::isConfigured)
                .map(ApiResourceIdentityResolver::toMap);
    }

    public Optional<Map<String, Object>> resolve(String basePath, String canonicalPath, String httpMethod) {
        Optional<HandlerMethod> handlerOpt = findHandlerMethod(canonicalPath, httpMethod);
        if (handlerOpt.isPresent()) {
            HandlerMethod handlerMethod = handlerOpt.get();

            ResourceIdentity methodIdentity = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), ResourceIdentity.class);
            if (methodIdentity != null && isConfigured(methodIdentity)) {
                return Optional.of(toMap(methodIdentity));
            }

            Class<?> dtoClass = resolveResponseDtoClass(ResolvableType.forMethodReturnType(handlerMethod.getMethod()));
            if (dtoClass != null) {
                ResourceIdentity dtoIdentity = AnnotationUtils.findAnnotation(dtoClass, ResourceIdentity.class);
                if (dtoIdentity != null && isConfigured(dtoIdentity)) {
                    return Optional.of(toMap(dtoIdentity));
                }
            }

            UiSurface surface = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), UiSurface.class);
            if (surface != null) {
                if (StringUtils.hasText(surface.relatedChildResourcePath())) {
                    Optional<Map<String, Object>> childIdentity = resolve(surface.relatedChildResourcePath());
                    if (childIdentity.isPresent()) {
                        return childIdentity;
                    }
                }
                if (StringUtils.hasText(surface.relatedChildResourceKey())) {
                    Optional<Map<String, Object>> childByKeyIdentity = findApiResourceByKey(surface.relatedChildResourceKey())
                            .map(ApiResource::identity)
                            .filter(ApiResourceIdentityResolver::isConfigured)
                            .map(ApiResourceIdentityResolver::toMap);
                    if (childByKeyIdentity.isPresent()) {
                        return childByKeyIdentity;
                    }
                }
            }
        }
        return resolve(basePath);
    }

    public Optional<Map<String, Object>> resolveResourceMetadata(String resourcePath) {
        return findApiResourceByPath(resourcePath)
                .map(ApiResourceIdentityResolver::toResourceMetadataMap)
                .filter(map -> !map.isEmpty());
    }

    public Optional<Map<String, Object>> resolveResourceMetadata(String basePath, String canonicalPath, String httpMethod) {
        Map<String, Object> result = new LinkedHashMap<>();
        findApiResourceByPath(basePath)
                .map(ApiResourceIdentityResolver::toResourceMetadataMap)
                .ifPresent(result::putAll);

        Optional<HandlerMethod> handlerOpt = findHandlerMethod(canonicalPath, httpMethod);
        if (handlerOpt.isPresent()) {
            HandlerMethod handlerMethod = handlerOpt.get();
            UiSurface surface = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), UiSurface.class);
            if (surface != null) {
                Optional<ApiResource> childResource = Optional.empty();
                if (StringUtils.hasText(surface.relatedChildResourcePath())) {
                    childResource = findApiResourceByPath(surface.relatedChildResourcePath());
                }
                if (childResource.isEmpty() && StringUtils.hasText(surface.relatedChildResourceKey())) {
                    childResource = findApiResourceByKey(surface.relatedChildResourceKey());
                }
                childResource.map(ApiResourceIdentityResolver::toResourceMetadataMap)
                        .ifPresent(result::putAll);
                if (StringUtils.hasText(surface.icon())) {
                    result.put("icon", surface.icon().trim());
                }
            }
        }

        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    private Optional<ApiResource> findApiResourceByPath(String resourcePath) {
        String expectedPath = normalize(resourcePath);
        return handlerMapping.getHandlerMethods().values().stream()
                .map(HandlerMethod::getBeanType)
                .distinct()
                .map(type -> AnnotationUtils.findAnnotation(type, ApiResource.class))
                .filter(annotation -> annotation != null && matches(annotation, expectedPath))
                .findFirst();
    }

    private Optional<ApiResource> findApiResourceByKey(String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            return Optional.empty();
        }
        String expectedKey = resourceKey.trim();
        return handlerMapping.getHandlerMethods().values().stream()
                .map(HandlerMethod::getBeanType)
                .distinct()
                .map(type -> AnnotationUtils.findAnnotation(type, ApiResource.class))
                .filter(annotation -> annotation != null && expectedKey.equals(annotation.resourceKey().trim()))
                .findFirst();
    }

    private Optional<HandlerMethod> findHandlerMethod(String canonicalPath, String httpMethod) {
        if (!StringUtils.hasText(canonicalPath)) {
            return Optional.empty();
        }
        String normalizedTarget = normalizeTemplate(canonicalPath);
        String normalizedMethod = StringUtils.hasText(httpMethod) ? httpMethod.trim().toUpperCase() : null;

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (info == null) {
                continue;
            }
            if (normalizedMethod != null && !info.getMethodsCondition().getMethods().isEmpty()) {
                boolean methodMatches = info.getMethodsCondition().getMethods().stream()
                        .map(RequestMethod::name)
                        .anyMatch(normalizedMethod::equalsIgnoreCase);
                if (!methodMatches) {
                    continue;
                }
            }
            boolean pathMatches = info.getPatternValues().stream()
                    .map(ApiResourceIdentityResolver::normalizeTemplate)
                    .anyMatch(normalizedTarget::equals);
            if (pathMatches) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private static Class<?> resolveResponseDtoClass(ResolvableType type) {
        if (type == null || type == ResolvableType.NONE) {
            return null;
        }
        Class<?> rawClass = type.toClass();
        if (rawClass == Void.TYPE || rawClass == Void.class || rawClass == Object.class) {
            return null;
        }
        if (ResponseEntity.class.isAssignableFrom(rawClass)
                || HttpEntity.class.isAssignableFrom(rawClass)
                || RestApiResponse.class.isAssignableFrom(rawClass)
                || PagedModel.class.isAssignableFrom(rawClass)
                || CollectionModel.class.isAssignableFrom(rawClass)
                || EntityModel.class.isAssignableFrom(rawClass)
                || Collection.class.isAssignableFrom(rawClass)
                || rawClass.getName().startsWith("org.springframework.data.domain.Page")
                || rawClass.getName().startsWith("org.praxisplatform.uischema.dto.CursorPage")) {
            return resolveResponseDtoClass(type.getGeneric(0));
        }
        if (rawClass.isArray()) {
            return rawClass.getComponentType();
        }
        return rawClass;
    }

    private static boolean matches(ApiResource resource, String expectedPath) {
        return Arrays.stream(resource.value()).anyMatch(path -> normalize(path).equals(expectedPath))
                || Arrays.stream(resource.path()).anyMatch(path -> normalize(path).equals(expectedPath));
    }

    private static boolean isConfigured(ResourceIdentity identity) {
        return !identity.keyField().isBlank()
                || !identity.titleField().isBlank()
                || !identity.subtitleField().isBlank()
                || !identity.avatarField().isBlank()
                || identity.metadataFields().length > 0
                || !identity.displayLabelField().isBlank();
    }

    private static Map<String, Object> toMap(ResourceIdentity identity) {
        Map<String, Object> result = new LinkedHashMap<>();
        putIfPresent(result, "keyField", identity.keyField());
        putIfPresent(result, "titleField", identity.titleField());
        putIfPresent(result, "subtitleField", identity.subtitleField());
        putIfPresent(result, "avatarField", identity.avatarField());
        if (identity.metadataFields().length > 0) {
            result.put("metadataFields", Arrays.asList(identity.metadataFields()));
        }
        putIfPresent(result, "displayLabelField", identity.displayLabelField());
        return result;
    }

    private static Map<String, Object> toResourceMetadataMap(ApiResource resource) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean hasExplicitVisualMetadata = StringUtils.hasText(resource.title())
                || StringUtils.hasText(resource.description())
                || StringUtils.hasText(resource.icon())
                || StringUtils.hasText(resource.visualTone());
        if (hasExplicitVisualMetadata && StringUtils.hasText(resource.resourceKey())) {
            putIfPresent(result, "resourceKey", resource.resourceKey().trim());
        }
        putIfPresent(result, "title", resource.title().trim());
        putIfPresent(result, "description", resource.description().trim());
        putIfPresent(result, "icon", resource.icon().trim());
        putIfPresent(result, "visualTone", resource.visualTone().trim());
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

    private static String normalizeTemplate(String path) {
        return normalize(path).replaceAll("\\{[^/]+}", "{}");
    }
}
