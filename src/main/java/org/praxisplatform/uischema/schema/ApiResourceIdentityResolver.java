package org.praxisplatform.uischema.schema;

import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceHierarchy;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
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
        return findControllerTypeByPath(resourcePath)
                .flatMap(this::resolveControllerIdentity);
    }

    public Optional<Map<String, Object>> resolve(String basePath, String canonicalPath, String httpMethod) {
        Optional<HandlerMethod> handlerOpt = findHandlerMethod(canonicalPath, httpMethod);
        if (handlerOpt.isPresent()) {
            HandlerMethod handlerMethod = handlerOpt.get();

            ResourceIdentity methodIdentity = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), ResourceIdentity.class);
            if (methodIdentity != null && isConfigured(methodIdentity)) {
                return Optional.of(toMap(methodIdentity));
            }

            // Inherited resource handlers declare ResponseDTO on a generic base class.
            // Resolve it against the concrete MVC controller before unwrapping the response.
            Class<?> dtoClass = resolveResponseDtoClass(ResolvableType.forMethodReturnType(
                    handlerMethod.getMethod(), handlerMethod.getBeanType()));
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
                    Optional<Map<String, Object>> childByKeyIdentity =
                            findControllerTypeByKey(surface.relatedChildResourceKey())
                                    .flatMap(this::resolveControllerIdentity);
                    if (childByKeyIdentity.isPresent()) {
                        return childByKeyIdentity;
                    }
                }
            }
        }
        return resolve(basePath);
    }

    public Optional<Map<String, Object>> resolveHierarchy(String canonicalPath, String httpMethod) {
        return findHandlerMethod(canonicalPath, httpMethod).flatMap(handler -> {
            Class<?> dto = resolveResponseDtoClass(ResolvableType.forMethodReturnType(
                    handler.getMethod(), handler.getBeanType()));
            ResourceHierarchy hierarchy = dto == null ? null : AnnotationUtils.findAnnotation(dto, ResourceHierarchy.class);
            if (hierarchy == null) {
                hierarchy = AnnotationUtils.findAnnotation(handler.getBeanType(), ResourceHierarchy.class);
            }
            return Optional.ofNullable(hierarchy).map(ApiResourceIdentityResolver::hierarchyMetadata);
        });
    }

    public Optional<Map<String, Object>> resolveCalculationMemory(String canonicalPath, String httpMethod) {
        return findHandlerMethod(canonicalPath, httpMethod).flatMap(handler -> {
            Class<?> dto = resolveResponseDtoClass(ResolvableType.forMethodReturnType(
                    handler.getMethod(), handler.getBeanType()));
            return Optional.ofNullable(CalculationMemoryMetadataResolver.resolve(dto));
        });
    }

    public Optional<Map<String, Object>> resolveProgression(String canonicalPath, String httpMethod) {
        return findHandlerMethod(canonicalPath, httpMethod).flatMap(handler -> {
            Class<?> dto = resolveResponseDtoClass(ResolvableType.forMethodReturnType(
                    handler.getMethod(), handler.getBeanType()));
            var annotation = dto == null ? null : AnnotationUtils.findAnnotation(
                    dto, org.praxisplatform.uischema.annotation.ResourceProgression.class);
            if (annotation == null) {
                annotation = AnnotationUtils.findAnnotation(
                        handler.getBeanType(), org.praxisplatform.uischema.annotation.ResourceProgression.class);
            }
            if (annotation == null) {
                return Optional.empty();
            }
            Map<String, Object> map = org.praxisplatform.uischema.extension.CustomOpenApiResolver.serializeResourceProgression(annotation);
            return map.isEmpty() ? Optional.empty() : Optional.of(map);
        });
    }

    public Optional<Map<String, Object>> resolveTimeline(String canonicalPath, String httpMethod) {
        return findHandlerMethod(canonicalPath, httpMethod).flatMap(handler -> {
            Class<?> dto = resolveResponseDtoClass(ResolvableType.forMethodReturnType(
                    handler.getMethod(), handler.getBeanType()));
            var annotation = dto == null ? null : AnnotationUtils.findAnnotation(
                    dto, org.praxisplatform.uischema.annotation.ResourceTimeline.class);
            if (annotation == null) {
                annotation = AnnotationUtils.findAnnotation(
                        handler.getBeanType(), org.praxisplatform.uischema.annotation.ResourceTimeline.class);
            }
            if (annotation == null) {
                return Optional.empty();
            }
            Map<String, Object> map = org.praxisplatform.uischema.extension.CustomOpenApiResolver.serializeResourceTimeline(annotation);
            return map.isEmpty() ? Optional.empty() : Optional.of(map);
        });
    }

    public Optional<Map<String, Object>> resolveSchedule(String canonicalPath, String httpMethod) {
        return findHandlerMethod(canonicalPath, httpMethod).flatMap(handler -> {
            Class<?> dto = resolveResponseDtoClass(ResolvableType.forMethodReturnType(
                    handler.getMethod(), handler.getBeanType()));
            var annotation = dto == null ? null : AnnotationUtils.findAnnotation(
                    dto, org.praxisplatform.uischema.annotation.ResourceSchedule.class);
            if (annotation == null) {
                annotation = AnnotationUtils.findAnnotation(
                        handler.getBeanType(), org.praxisplatform.uischema.annotation.ResourceSchedule.class);
            }
            if (annotation == null) {
                return Optional.empty();
            }
            Map<String, Object> map = org.praxisplatform.uischema.extension.CustomOpenApiResolver.serializeResourceSchedule(annotation);
            return map.isEmpty() ? Optional.empty() : Optional.of(map);
        });
    }

    public Optional<ResourceDtoPair> resolveResourceDtoPair(String canonicalPath, String httpMethod) {
        return findHandlerMethod(canonicalPath, httpMethod).map(handler -> {
            Class<?> userType = org.springframework.util.ClassUtils.getUserClass(handler.getBeanType());
            Class<?> responseDto = resolveControllerResponseDtoClass(userType);
            Class<?> filterDto = null;
            if (AbstractResourceQueryController.class.isAssignableFrom(userType)) {
                Class<?>[] args = GenericTypeResolver.resolveTypeArguments(userType, AbstractResourceQueryController.class);
                if (args != null && args.length >= 3 && args[2] != null && args[2] != Object.class) {
                    filterDto = args[2];
                }
            }
            if (filterDto == null) {
                for (var param : handler.getMethodParameters()) {
                    if (org.praxisplatform.uischema.filter.dto.GenericFilterDTO.class.isAssignableFrom(param.getParameterType())) {
                        filterDto = ResolvableType.forMethodParameter(param.withContainingClass(userType)).resolve();
                        break;
                    }
                }
            }
            return new ResourceDtoPair(responseDto, filterDto);
        });
    }

    public record ResourceDtoPair(Class<?> responseDtoClass, Class<?> filterDtoClass) {
    }

    /**
     * Resolve {@code @FormLayout} declarado diretamente no {@link HandlerMethod} para operacoes de
     * comando ({@code request}) ou leitura unitaria ({@code GET + response}).
     */
    public Optional<Map<String, Object>> resolveMethodFormLayout(
            String canonicalPath,
            String httpMethod,
            String schemaType
    ) {
        String normalizedSchemaType = schemaType == null ? "" : schemaType.trim();
        String normalizedMethod = httpMethod == null ? "" : httpMethod.trim();
        boolean appliesToMethod = "request".equalsIgnoreCase(normalizedSchemaType)
                || ("response".equalsIgnoreCase(normalizedSchemaType) && "get".equalsIgnoreCase(normalizedMethod));
        if (!appliesToMethod) {
            return Optional.empty();
        }
        return findHandlerMethod(canonicalPath, normalizedMethod).flatMap(handler -> {
            Class<?> userType = resolveUserControllerType(handler);
            Method specificMethod = resolveTargetMethod(handler.getMethod(), userType);
            Map<String, Object> resolved = FormLayoutMetadataResolver.resolve(specificMethod);
            if (resolved == null && specificMethod != handler.getMethod()) {
                resolved = FormLayoutMetadataResolver.resolve(handler.getMethod());
            }
            return Optional.ofNullable(resolved);
        });
    }

    /**
     * Resolve {@code @FormLayout} no DTO de entrada ({@code @RequestBody}) ou de retorno concreto do
     * {@link HandlerMethod}, ignorando payloads de filtro ({@code GenericFilterDTO}), {@code Map} e coleções.
     */
    public Optional<Map<String, Object>> resolveDtoFormLayout(
            String canonicalPath,
            String httpMethod,
            String schemaType
    ) {
        return findHandlerMethod(canonicalPath, httpMethod).flatMap(handler -> {
            Class<?> userType = resolveUserControllerType(handler);
            Method specificMethod = resolveTargetMethod(handler.getMethod(), userType);
            Class<?> dtoClass = null;
            if ("request".equalsIgnoreCase(schemaType)) {
                org.springframework.core.MethodParameter[] handlerParams = handler.getMethodParameters();
                for (int i = 0; i < handlerParams.length; i++) {
                    org.springframework.core.MethodParameter handlerParam = handlerParams[i];
                    org.springframework.core.MethodParameter specificParam =
                            new org.springframework.core.MethodParameter(specificMethod, i);
                    boolean hasRequestBody = specificParam.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)
                            || handlerParam.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
                    if (hasRequestBody) {
                        Class<?> candidate = ResolvableType.forMethodParameter(specificMethod, i, userType).toClass();
                        if (candidate != null
                                && candidate != Object.class
                                && !Map.class.isAssignableFrom(candidate)
                                && !Collection.class.isAssignableFrom(candidate)
                                && !org.praxisplatform.uischema.filter.dto.GenericFilterDTO.class.isAssignableFrom(candidate)) {
                            dtoClass = candidate;
                        }
                        break;
                    }
                }
            } else if ("response".equalsIgnoreCase(schemaType)) {
                dtoClass = resolveResponseDtoClass(ResolvableType.forMethodReturnType(
                        specificMethod, userType));
            }
            return Optional.ofNullable(FormLayoutMetadataResolver.resolve(dtoClass));
        });
    }

    /**
     * Resolves an explicit {@code @CustomFields(resourceKey = ...)} declared on the operation's
     * concrete request body (including {@code GenericFilterDTO}) or response DTO.
     */
    public Optional<String> resolveDtoCustomFieldResourceKey(
            String canonicalPath,
            String httpMethod,
            String schemaType
    ) {
        return findHandlerMethod(canonicalPath, httpMethod).flatMap(handler -> {
            Class<?> userType = resolveUserControllerType(handler);
            Method specificMethod = resolveTargetMethod(handler.getMethod(), userType);
            Class<?> dtoClass = null;
            if ("request".equalsIgnoreCase(schemaType)) {
                org.springframework.core.MethodParameter[] handlerParams = handler.getMethodParameters();
                for (int i = 0; i < handlerParams.length; i++) {
                    org.springframework.core.MethodParameter handlerParam = handlerParams[i];
                    org.springframework.core.MethodParameter specificParam =
                            new org.springframework.core.MethodParameter(specificMethod, i);
                    boolean hasRequestBody = specificParam.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)
                            || handlerParam.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
                    if (hasRequestBody) {
                        Class<?> candidate = ResolvableType.forMethodParameter(specificMethod, i, userType).toClass();
                        if (candidate != null
                                && candidate != Object.class
                                && !Map.class.isAssignableFrom(candidate)
                                && !Collection.class.isAssignableFrom(candidate)) {
                            dtoClass = candidate;
                        }
                        break;
                    }
                }
            } else if ("response".equalsIgnoreCase(schemaType)) {
                dtoClass = resolveResponseDtoClass(ResolvableType.forMethodReturnType(
                        specificMethod, userType));
            }
            return org.praxisplatform.uischema.customfields.CustomFieldSupport.property(dtoClass)
                    .map(org.praxisplatform.uischema.customfields.CustomFieldSupport.Property::resourceKey)
                    .filter(key -> key != null && !key.isBlank());
        });
    }

    private static Method resolveTargetMethod(Method handlerMethod, Class<?> userType) {
        if (handlerMethod == null || userType == null) {
            return handlerMethod;
        }
        Method specific = AopUtils.getMostSpecificMethod(handlerMethod, userType);
        if (specific != null && java.lang.reflect.Proxy.isProxyClass(specific.getDeclaringClass())) {
            Method concrete = ReflectionUtils.findMethod(
                    userType,
                    handlerMethod.getName(),
                    handlerMethod.getParameterTypes()
            );
            if (concrete != null) {
                return concrete;
            }
        }
        return specific != null ? specific : handlerMethod;
    }

    private Class<?> resolveUserControllerType(HandlerMethod handler) {
        Object bean = handler.getBean();
        if (bean instanceof String beanName) {
            try {
                var ctx = handlerMapping != null ? handlerMapping.getApplicationContext() : null;
                if (ctx != null && ctx.containsBean(beanName)) {
                    Object instance = ctx.getBean(beanName);
                    Class<?> ultimate = AopProxyUtils.ultimateTargetClass(instance);
                    if (ultimate != null) {
                        return ClassUtils.getUserClass(ultimate);
                    }
                }
            } catch (RuntimeException ignored) {
                // fallback para getBeanType
            }
        } else if (bean != null) {
            Class<?> ultimate = AopProxyUtils.ultimateTargetClass(bean);
            if (ultimate != null) {
                return ClassUtils.getUserClass(ultimate);
            }
        }
        return ClassUtils.getUserClass(handler.getBeanType());
    }

    public static Map<String, Object> hierarchyMetadata(ResourceHierarchy hierarchy) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        putHierarchyField(metadata, "parentField", hierarchy.parentField());
        putHierarchyField(metadata, "parentLabelField", hierarchy.parentLabelField());
        putHierarchyField(metadata, "hierarchyPathField", hierarchy.hierarchyPathField());
        putHierarchyField(metadata, "levelField", hierarchy.levelField());
        putHierarchyField(metadata, "childrenCountField", hierarchy.childrenCountField());
        putHierarchyField(metadata, "leafIndicatorField", hierarchy.leafIndicatorField());
        putHierarchyField(metadata, "effectiveFromField", hierarchy.effectiveFromField());
        putHierarchyField(metadata, "effectiveToField", hierarchy.effectiveToField());
        putHierarchyField(metadata, "extinctField", hierarchy.extinctField());
        putHierarchyField(metadata, "referenceDateFilterField", hierarchy.referenceDateFilterField());
        putHierarchyField(metadata, "subtreeFilterField", hierarchy.subtreeFilterField());
        putHierarchyField(metadata, "includeSubtreeFilterField", hierarchy.includeSubtreeFilterField());
        putHierarchyField(metadata, "hierarchyKindFilterField", hierarchy.hierarchyKindFilterField());
        metadata.put("auxiliaryParentFields", java.util.Arrays.stream(hierarchy.auxiliaryParentFields())
                .filter(StringUtils::hasText).map(String::trim).distinct().toList());
        return metadata;
    }

    private static void putHierarchyField(Map<String, Object> metadata, String key, String value) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, value.trim());
        }
    }

    /** Resolves semantic resource identity independently of optional presentation metadata. */
    public Optional<String> resolveResourceKey(String basePath, String canonicalPath, String httpMethod) {
        Optional<HandlerMethod> handler = findHandlerMethod(canonicalPath, httpMethod);
        if (handler.isPresent()) {
            UiSurface surface = AnnotationUtils.findAnnotation(handler.get().getMethod(), UiSurface.class);
            if (surface != null) {
                if (StringUtils.hasText(surface.relatedChildResourcePath())) {
                    Optional<String> child = findApiResourceByPath(surface.relatedChildResourcePath())
                            .map(ApiResource::resourceKey).filter(StringUtils::hasText);
                    if (child.isPresent()) return child.map(String::trim);
                }
                if (StringUtils.hasText(surface.relatedChildResourceKey())) return Optional.of(surface.relatedChildResourceKey().trim());
            }
            ApiResource resource = AnnotationUtils.findAnnotation(handler.get().getBeanType(), ApiResource.class);
            if (resource != null && StringUtils.hasText(resource.resourceKey())) return Optional.of(resource.resourceKey().trim());
        }
        return findApiResourceByPath(basePath).map(ApiResource::resourceKey).filter(StringUtils::hasText).map(String::trim);
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

    private Optional<Map<String, Object>> resolveControllerIdentity(Class<?> controllerType) {
        if (controllerType == null) {
            return Optional.empty();
        }
        ApiResource resource = AnnotationUtils.findAnnotation(controllerType, ApiResource.class);
        if (resource != null && isConfigured(resource.identity())) {
            return Optional.of(toMap(resource.identity()));
        }
        ResourceIdentity controllerIdentity = AnnotationUtils.findAnnotation(controllerType, ResourceIdentity.class);
        if (controllerIdentity != null && isConfigured(controllerIdentity)) {
            return Optional.of(toMap(controllerIdentity));
        }
        Class<?> responseDtoClass = resolveControllerResponseDtoClass(controllerType);
        if (responseDtoClass != null) {
            ResourceIdentity dtoIdentity = AnnotationUtils.findAnnotation(responseDtoClass, ResourceIdentity.class);
            if (dtoIdentity != null && isConfigured(dtoIdentity)) {
                return Optional.of(toMap(dtoIdentity));
            }
        }
        return Optional.empty();
    }

    private Class<?> resolveControllerResponseDtoClass(Class<?> controllerType) {
        Class<?> userType = ClassUtils.getUserClass(controllerType);
        if (AbstractResourceQueryController.class.isAssignableFrom(userType)) {
            Class<?>[] args = GenericTypeResolver.resolveTypeArguments(userType, AbstractResourceQueryController.class);
            if (args != null && args.length >= 1 && args[0] != null && args[0] != Object.class) {
                return args[0];
            }
        }
        return handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> ClassUtils.getUserClass(handler.getBeanType()).equals(userType))
                .sorted(java.util.Comparator.comparing(handler -> AnnotationUtils.findAnnotation(
                        handler.getMethod(),
                        org.praxisplatform.uischema.annotation.UiSurface.class
                ) != null))
                .map(handler -> resolveResponseDtoClass(
                        ResolvableType.forMethodReturnType(handler.getMethod(), handler.getBeanType())))
                .filter(dto -> {
                    if (dto == null) {
                        return false;
                    }
                    ResourceIdentity identity = AnnotationUtils.findAnnotation(dto, ResourceIdentity.class);
                    return identity != null && isConfigured(identity);
                })
                .findFirst()
                .orElse(null);
    }

    private Optional<Class<?>> findControllerTypeByPath(String resourcePath) {
        String expectedPath = normalize(resourcePath);
        return handlerMapping.getHandlerMethods().values().stream()
                .<Class<?>>map(HandlerMethod::getBeanType)
                .distinct()
                .filter(type -> {
                    ApiResource annotation = AnnotationUtils.findAnnotation(type, ApiResource.class);
                    return annotation != null && matches(annotation, expectedPath);
                })
                .findFirst();
    }

    private Optional<Class<?>> findControllerTypeByKey(String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            return Optional.empty();
        }
        String expectedKey = resourceKey.trim();
        return handlerMapping.getHandlerMethods().values().stream()
                .<Class<?>>map(HandlerMethod::getBeanType)
                .distinct()
                .filter(type -> {
                    ApiResource annotation = AnnotationUtils.findAnnotation(type, ApiResource.class);
                    return annotation != null && expectedKey.equals(annotation.resourceKey().trim());
                })
                .findFirst();
    }

    private Optional<ApiResource> findApiResourceByPath(String resourcePath) {
        return findControllerTypeByPath(resourcePath)
                .map(type -> AnnotationUtils.findAnnotation(type, ApiResource.class));
    }

    private Optional<ApiResource> findApiResourceByKey(String resourceKey) {
        return findControllerTypeByKey(resourceKey)
                .map(type -> AnnotationUtils.findAnnotation(type, ApiResource.class));
    }

    private Optional<HandlerMethod> findHandlerMethod(String canonicalPath, String httpMethod) {
        if (handlerMapping == null || !StringUtils.hasText(canonicalPath)) {
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
