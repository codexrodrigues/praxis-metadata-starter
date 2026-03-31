package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.WorkflowAction;
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
import org.springframework.web.bind.annotation.RequestMethod;
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
 * Descobre workflow actions a partir de metodos reais registrados no Spring MVC.
 */
public class AnnotationDrivenActionDefinitionRegistry implements ActionDefinitionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationDrivenActionDefinitionRegistry.class);
    private static final Method GET_ID_FIELD_METHOD = resolveControllerMethod("getIdFieldName");

    private final RequestMappingHandlerMapping handlerMapping;
    private final ApplicationContext applicationContext;
    private final CanonicalOperationResolver canonicalOperationResolver;
    private final SchemaReferenceResolver schemaReferenceResolver;
    private final AnnotationConflictMode conflictMode;
    private final AnnotationConflictMode workflowActionShapeMode;
    private volatile ActionRegistrySnapshot cachedSnapshot;

    public AnnotationDrivenActionDefinitionRegistry(
            RequestMappingHandlerMapping handlerMapping,
            ApplicationContext applicationContext,
            CanonicalOperationResolver canonicalOperationResolver,
            SchemaReferenceResolver schemaReferenceResolver,
            AnnotationConflictMode conflictMode,
            AnnotationConflictMode workflowActionShapeMode
    ) {
        this.handlerMapping = handlerMapping;
        this.applicationContext = applicationContext;
        this.canonicalOperationResolver = canonicalOperationResolver;
        this.schemaReferenceResolver = schemaReferenceResolver;
        this.conflictMode = conflictMode;
        this.workflowActionShapeMode = workflowActionShapeMode;
    }

    @Override
    public List<ActionDefinition> findByResourceKey(String resourceKey) {
        return snapshot().byResourceKey().getOrDefault(resourceKey, List.of());
    }

    @Override
    public List<ActionDefinition> findByGroup(String group) {
        return snapshot().byGroup().getOrDefault(group, List.of());
    }

    private ActionRegistrySnapshot snapshot() {
        ActionRegistrySnapshot current = cachedSnapshot;
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

    private ActionRegistrySnapshot buildSnapshot() {
        List<ActionDefinition> definitions = handlerMapping.getHandlerMethods().entrySet().stream()
                .flatMap(entry -> toDefinitions(entry).stream())
                .sorted(Comparator
                        .comparing(ActionDefinition::resourceKey, Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(ActionDefinition::order)
                        .thenComparing(ActionDefinition::id, Comparator.nullsLast(String::compareTo))
                )
                .toList();
        Map<String, List<ActionDefinition>> byResourceKey = indexBy(definitions, ActionDefinition::resourceKey);
        Map<String, List<ActionDefinition>> byGroup = indexBy(definitions, ActionDefinition::group);
        return new ActionRegistrySnapshot(definitions, byResourceKey, byGroup);
    }

    private List<ActionDefinition> toDefinitions(Map.Entry<RequestMappingInfo, HandlerMethod> entry) {
        HandlerMethod handlerMethod = entry.getValue();
        Class<?> controllerClass = handlerMethod.getBeanType();
        if (!AbstractResourceQueryController.class.isAssignableFrom(controllerClass)) {
            return List.of();
        }

        WorkflowAction workflowAction = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), WorkflowAction.class);
        if (workflowAction == null) {
            return List.of();
        }

        handleConflictIfPresent(handlerMethod);
        validateWorkflowActionShape(entry.getKey(), handlerMethod);

        ResourceDescriptor resource = resolveResourceDescriptor(handlerMethod, controllerClass);
        if (resource == null) {
            return List.of();
        }

        CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(handlerMethod, entry.getKey());
        CanonicalSchemaRef requestSchemaRef = resolveSchema(operationRef, "request", resource);
        CanonicalSchemaRef responseSchemaRef = resolveSchema(operationRef, "response", resource);
        ActionDefinition definition = new ActionDefinition(
                workflowAction.id(),
                resource.resourceKey(),
                resource.resourcePath(),
                resource.group(),
                workflowAction.scope(),
                workflowAction.title(),
                workflowAction.description(),
                operationRef,
                requestSchemaRef,
                responseSchemaRef,
                workflowAction.order(),
                workflowAction.successMessage(),
                List.copyOf(Arrays.asList(workflowAction.requiredAuthorities())),
                List.copyOf(Arrays.asList(workflowAction.allowedStates())),
                List.copyOf(Arrays.asList(workflowAction.tags()))
        );
        return List.of(definition);
    }

    private void handleConflictIfPresent(HandlerMethod handlerMethod) {
        if (AnnotationUtils.findAnnotation(handlerMethod.getMethod(), org.praxisplatform.uischema.annotation.UiSurface.class) == null) {
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

    private void validateWorkflowActionShape(RequestMappingInfo mappingInfo, HandlerMethod handlerMethod) {
        if (isWorkflowActionShapeValid(mappingInfo)) {
            return;
        }

        String message = "Method %s#%s declares @WorkflowAction on a non-canonical command shape. "
                + "Expected explicit POST/PATCH mapping under /actions/... or alias path ':action'. "
                + "Resolved methods=%s, paths=%s."
                .formatted(
                        handlerMethod.getBeanType().getName(),
                        handlerMethod.getMethod().getName(),
                        mappingInfo.getMethodsCondition().getMethods(),
                        mappingInfo.getPatternValues()
                );

        switch (workflowActionShapeMode) {
            case FAIL -> throw new IllegalStateException(message);
            case WARN -> logger.warn(message);
            case IGNORE -> {
                // no-op
            }
        }
    }

    private boolean isWorkflowActionShapeValid(RequestMappingInfo mappingInfo) {
        return hasCanonicalWorkflowMethod(mappingInfo) && hasCanonicalWorkflowPath(mappingInfo);
    }

    private boolean hasCanonicalWorkflowMethod(RequestMappingInfo mappingInfo) {
        return mappingInfo != null
                && !mappingInfo.getMethodsCondition().getMethods().isEmpty()
                && mappingInfo.getMethodsCondition().getMethods().stream().allMatch(this::isWorkflowActionMethod);
    }

    private boolean isWorkflowActionMethod(RequestMethod requestMethod) {
        return requestMethod == RequestMethod.POST || requestMethod == RequestMethod.PATCH;
    }

    private boolean hasCanonicalWorkflowPath(RequestMappingInfo mappingInfo) {
        return mappingInfo != null
                && !mappingInfo.getPatternValues().isEmpty()
                && mappingInfo.getPatternValues().stream().anyMatch(this::isWorkflowActionPath);
    }

    private boolean isWorkflowActionPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String normalized = normalizePath(path);
        return normalized.contains("/actions/") || normalized.matches(".+:[A-Za-z][A-Za-z0-9-]*$");
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
                false
        );
    }

    private Map<String, List<ActionDefinition>> indexBy(
            List<ActionDefinition> definitions,
            java.util.function.Function<ActionDefinition, String> keyExtractor
    ) {
        Map<String, List<ActionDefinition>> index = new LinkedHashMap<>();
        for (ActionDefinition definition : definitions) {
            String key = keyExtractor.apply(definition);
            if (!StringUtils.hasText(key)) {
                continue;
            }
            index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(definition);
        }
        Map<String, List<ActionDefinition>> immutableIndex = new LinkedHashMap<>();
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

    private static Method resolveControllerMethod(String name) {
        try {
            return AbstractResourceQueryController.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Metodo canonico do controller nao encontrado: " + name, ex);
        }
    }

    private record ResourceDescriptor(
            String resourceKey,
            String resourcePath,
            String group,
            String idField
    ) {
    }

    private record ActionRegistrySnapshot(
            List<ActionDefinition> definitions,
            Map<String, List<ActionDefinition>> byResourceKey,
            Map<String, List<ActionDefinition>> byGroup
    ) {
    }
}
