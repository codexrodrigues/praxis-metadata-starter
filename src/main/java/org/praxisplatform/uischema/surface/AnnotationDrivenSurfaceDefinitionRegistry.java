package org.praxisplatform.uischema.surface;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController;
import org.praxisplatform.uischema.controller.base.AbstractResourceController;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.validation.AnnotationConflictMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Descobre surfaces a partir de metodos reais registrados no Spring MVC.
 *
 * <p>
 * O registry apoia-se em {@link RequestMappingHandlerMapping}, {@link CanonicalOperationResolver}
 * e {@link SchemaReferenceResolver}. Ele nao constroi contrato paralelo; apenas aponta para
 * operacoes e schemas canonicamente publicados.
 * </p>
 */
public class AnnotationDrivenSurfaceDefinitionRegistry implements SurfaceDefinitionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationDrivenSurfaceDefinitionRegistry.class);
    private static final Method GET_ID_FIELD_METHOD = resolveControllerMethod("getIdFieldName");

    private final RequestMappingHandlerMapping handlerMapping;
    private final ApplicationContext applicationContext;
    private final CanonicalOperationResolver canonicalOperationResolver;
    private final SchemaReferenceResolver schemaReferenceResolver;
    private final AnnotationConflictMode conflictMode;
    private volatile SurfaceRegistrySnapshot cachedSnapshot;

    public AnnotationDrivenSurfaceDefinitionRegistry(
            RequestMappingHandlerMapping handlerMapping,
            ApplicationContext applicationContext,
            CanonicalOperationResolver canonicalOperationResolver,
            SchemaReferenceResolver schemaReferenceResolver,
            AnnotationConflictMode conflictMode
    ) {
        this.handlerMapping = handlerMapping;
        this.applicationContext = applicationContext;
        this.canonicalOperationResolver = canonicalOperationResolver;
        this.schemaReferenceResolver = schemaReferenceResolver;
        this.conflictMode = conflictMode;
    }

    @Override
    public List<SurfaceDefinition> findByResourceKey(String resourceKey) {
        return snapshot().byResourceKey().getOrDefault(resourceKey, List.of());
    }

    @Override
    public List<SurfaceDefinition> findByGroup(String group) {
        return snapshot().byGroup().getOrDefault(group, List.of());
    }

    private SurfaceRegistrySnapshot snapshot() {
        SurfaceRegistrySnapshot current = cachedSnapshot;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = cachedSnapshot;
            if (current == null) {
                current = buildSnapshot();
                cachedSnapshot = current;
            }
            return current;
        }
    }

    private SurfaceRegistrySnapshot buildSnapshot() {
        List<SurfaceDefinition> definitions = handlerMapping.getHandlerMethods().entrySet().stream()
                .flatMap(entry -> toDefinitions(entry).stream())
                .sorted(Comparator
                        .comparing(SurfaceDefinition::resourceKey, Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(SurfaceDefinition::order)
                        .thenComparing(SurfaceDefinition::id, Comparator.nullsLast(String::compareTo))
                )
                .toList();
        Map<String, List<SurfaceDefinition>> byResourceKey = indexBy(definitions, SurfaceDefinition::resourceKey);
        Map<String, List<SurfaceDefinition>> byGroup = indexBy(definitions, SurfaceDefinition::group);
        return new SurfaceRegistrySnapshot(definitions, byResourceKey, byGroup);
    }

    private List<SurfaceDefinition> toDefinitions(Map.Entry<RequestMappingInfo, HandlerMethod> entry) {
        HandlerMethod handlerMethod = entry.getValue();
        Class<?> controllerClass = handlerMethod.getBeanType();
        if (!AbstractResourceQueryController.class.isAssignableFrom(controllerClass)) {
            return List.of();
        }
        if (isWorkflowLikeMapping(entry.getKey())) {
            return List.of();
        }

        ResourceDescriptor resource = resolveResourceDescriptor(handlerMethod, controllerClass);
        if (resource == null) {
            return List.of();
        }

        UiSurface explicitSurface = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), UiSurface.class);
        WorkflowAction workflowAction = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), WorkflowAction.class);
        if (workflowAction != null) {
            handleConflictIfPresent(handlerMethod, explicitSurface);
            return List.of();
        }
        if (explicitSurface != null) {
            SurfaceDefinition definition = buildExplicitSurface(entry.getKey(), handlerMethod, resource, explicitSurface);
            return definition != null ? List.of(definition) : List.of();
        }

        SurfaceDefinition automatic = buildAutomaticSurface(entry.getKey(), handlerMethod, resource);
        return automatic != null ? List.of(automatic) : List.of();
    }

    private void handleConflictIfPresent(HandlerMethod handlerMethod, UiSurface explicitSurface) {
        if (explicitSurface == null) {
            return;
        }

        String message = "Method %s#%s declares both @UiSurface and @WorkflowAction. Choose exactly one semantic catalog."
                .formatted(
                        handlerMethod.getBeanType().getName(),
                        handlerMethod.getMethod().getName()
                );

        switch (conflictMode) {
            case FAIL -> throw new IllegalStateException(message);
            case WARN -> logger.warn(message);
            case IGNORE -> {
                // no-op
            }
        }
    }

    private SurfaceDefinition buildExplicitSurface(
            RequestMappingInfo mappingInfo,
            HandlerMethod handlerMethod,
            ResourceDescriptor resource,
            UiSurface surface
    ) {
        CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(handlerMethod, mappingInfo);
        String schemaType = schemaTypeFor(surface.kind());
        CanonicalSchemaRef schemaRef = resolveSchema(operationRef, schemaType, resource);

        return new SurfaceDefinition(
                surface.id(),
                resource.resourceKey(),
                resource.resourcePath(),
                resource.group(),
                surface.kind(),
                surface.scope(),
                surface.title(),
                surface.description(),
                StringUtils.hasText(surface.intent()) ? surface.intent() : surface.id(),
                schemaType,
                operationRef,
                schemaRef,
                surface.order(),
                List.copyOf(Arrays.asList(surface.requiredAuthorities())),
                List.copyOf(Arrays.asList(surface.allowedStates())),
                List.copyOf(Arrays.asList(surface.tags()))
        );
    }

    private SurfaceDefinition buildAutomaticSurface(
            RequestMappingInfo mappingInfo,
            HandlerMethod handlerMethod,
            ResourceDescriptor resource
    ) {
        AutomaticSurface automaticSurface = resolveAutomaticSurface(handlerMethod, resource.readOnly());
        if (automaticSurface == null) {
            return null;
        }

        CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(handlerMethod, mappingInfo);
        CanonicalSchemaRef schemaRef = resolveSchema(operationRef, automaticSurface.schemaType(), resource);
        Operation operation = handlerMethod.getMethodAnnotation(Operation.class);
        String title = operation != null && StringUtils.hasText(operation.summary())
                ? operation.summary()
                : automaticSurface.defaultTitle();
        String description = operation != null && StringUtils.hasText(operation.description())
                ? operation.description()
                : "";

        return new SurfaceDefinition(
                automaticSurface.id(),
                resource.resourceKey(),
                resource.resourcePath(),
                resource.group(),
                automaticSurface.kind(),
                automaticSurface.scope(),
                title,
                description,
                automaticSurface.intent(),
                automaticSurface.schemaType(),
                operationRef,
                schemaRef,
                automaticSurface.order(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private AutomaticSurface resolveAutomaticSurface(HandlerMethod handlerMethod, boolean readOnly) {
        String methodName = handlerMethod.getMethod().getName();
        return switch (methodName) {
            case "getAll" -> new AutomaticSurface("list", SurfaceKind.VIEW, SurfaceScope.COLLECTION, "list", "response", 20, "Listar");
            case "getById" -> new AutomaticSurface("detail", SurfaceKind.VIEW, SurfaceScope.ITEM, "detail", "response", 30, "Detalhar");
            case "create" -> readOnly || !AbstractResourceController.class.isAssignableFrom(handlerMethod.getBeanType())
                    ? null
                    : new AutomaticSurface("create", SurfaceKind.FORM, SurfaceScope.COLLECTION, "create", "request", 10, "Criar");
            case "update" -> readOnly || !AbstractResourceController.class.isAssignableFrom(handlerMethod.getBeanType())
                    ? null
                    : new AutomaticSurface("edit", SurfaceKind.FORM, SurfaceScope.ITEM, "edit", "request", 40, "Editar");
            default -> null;
        };
    }

    private CanonicalSchemaRef resolveSchema(
            CanonicalOperationRef operationRef,
            String schemaType,
            ResourceDescriptor resource
    ) {
        return schemaReferenceResolver.resolve(
                operationRef.path(),
                operationRef.method(),
                schemaType,
                false,
                null,
                null,
                resource.idField(),
                resource.readOnly()
        );
    }

    private ResourceDescriptor resolveResourceDescriptor(HandlerMethod handlerMethod, Class<?> controllerClass) {
        String resourcePath = extractControllerBasePath(controllerClass);
        if (!StringUtils.hasText(resourcePath)) {
            return null;
        }

        ApiResource apiResource = AnnotationUtils.findAnnotation(controllerClass, ApiResource.class);
        ApiGroup apiGroup = AnnotationUtils.findAnnotation(controllerClass, ApiGroup.class);
        String resourceKey = apiResource != null
                ? apiResource.resourceKey().trim()
                : deriveResourceKey(resourcePath);
        Object controllerBean = resolveControllerBean(handlerMethod);
        String idField = resolveIdField(controllerBean);

        return new ResourceDescriptor(
                resourceKey,
                normalizePath(resourcePath),
                apiGroup != null && StringUtils.hasText(apiGroup.value())
                        ? apiGroup.value()
                        : canonicalOperationResolver.resolve(resourcePath, "GET").group(),
                AbstractReadOnlyResourceController.class.isAssignableFrom(controllerClass),
                idField
        );
    }

    private Object resolveControllerBean(HandlerMethod handlerMethod) {
        Object bean = handlerMethod.getBean();
        if (bean instanceof String beanName && applicationContext != null && applicationContext.containsBean(beanName)) {
            return applicationContext.getBean(beanName);
        }
        return bean;
    }

    private String resolveIdField(Object controllerBean) {
        if (controllerBean == null) {
            return "id";
        }
        if (!GET_ID_FIELD_METHOD.canAccess(controllerBean)) {
            GET_ID_FIELD_METHOD.setAccessible(true);
        }
        try {
            Object value = GET_ID_FIELD_METHOD.invoke(controllerBean);
            return value instanceof String stringValue && StringUtils.hasText(stringValue)
                    ? stringValue
                    : "id";
        } catch (Exception ex) {
            return "id";
        }
    }

    private String extractControllerBasePath(Class<?> controllerClass) {
        ApiResource apiResource = AnnotationUtils.findAnnotation(controllerClass, ApiResource.class);
        if (apiResource != null) {
            return firstPath(apiResource.value(), apiResource.path());
        }

        RequestMapping requestMapping = AnnotationUtils.findAnnotation(controllerClass, RequestMapping.class);
        if (requestMapping != null) {
            return firstPath(requestMapping.value(), requestMapping.path());
        }
        return null;
    }

    private String firstPath(String[] values, String[] paths) {
        if (values != null && values.length > 0 && StringUtils.hasText(values[0])) {
            return values[0];
        }
        if (paths != null && paths.length > 0 && StringUtils.hasText(paths[0])) {
            return paths[0];
        }
        return null;
    }

    private String deriveResourceKey(String resourcePath) {
        String normalized = normalizePath(resourcePath);
        String withoutApiPrefix = normalized.startsWith("/api/")
                ? normalized.substring("/api/".length())
                : normalized.substring(1);
        return withoutApiPrefix.replace('/', '.');
    }

    private boolean isWorkflowLikeMapping(RequestMappingInfo mappingInfo) {
        return mappingInfo.getPatternValues().stream().anyMatch(this::isWorkflowLikePath);
    }

    private boolean isWorkflowLikePath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String normalized = normalizePath(path);
        return normalized.contains("/actions/") || normalized.matches(".+:[A-Za-z][A-Za-z0-9-]*$");
    }

    private Map<String, List<SurfaceDefinition>> indexBy(
            List<SurfaceDefinition> definitions,
            java.util.function.Function<SurfaceDefinition, String> keyExtractor
    ) {
        Map<String, List<SurfaceDefinition>> index = new LinkedHashMap<>();
        for (SurfaceDefinition definition : definitions) {
            String key = keyExtractor.apply(definition);
            if (!StringUtils.hasText(key)) {
                continue;
            }
            index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(definition);
        }
        Map<String, List<SurfaceDefinition>> immutableIndex = new LinkedHashMap<>();
        index.forEach((key, value) -> immutableIndex.put(key, List.copyOf(value)));
        return java.util.Collections.unmodifiableMap(immutableIndex);
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        String normalized = path.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String schemaTypeFor(SurfaceKind kind) {
        return switch (kind) {
            case FORM, PARTIAL_FORM -> "request";
            case VIEW, READ_PROJECTION -> "response";
        };
    }

    private static Method resolveControllerMethod(String name) {
        try {
            return AbstractResourceQueryController.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Metodo canonico do controller nao encontrado: " + name, ex);
        }
    }

    private record AutomaticSurface(
            String id,
            SurfaceKind kind,
            SurfaceScope scope,
            String intent,
            String schemaType,
            int order,
            String defaultTitle
    ) {
    }

    private record ResourceDescriptor(
            String resourceKey,
            String resourcePath,
            String group,
            boolean readOnly,
            String idField
    ) {
    }

    private record SurfaceRegistrySnapshot(
            List<SurfaceDefinition> definitions,
            Map<String, List<SurfaceDefinition>> byResourceKey,
            Map<String, List<SurfaceDefinition>> byGroup
    ) {
    }
}
