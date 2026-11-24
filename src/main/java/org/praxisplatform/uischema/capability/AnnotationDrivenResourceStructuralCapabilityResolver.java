package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Associa o path canonico ao controller resource-oriented e consulta o service que realmente
 * executa as operacoes opcionais.
 */
@SuppressWarnings("rawtypes")
public final class AnnotationDrivenResourceStructuralCapabilityResolver
        implements ResourceStructuralCapabilityResolver {

    private final RequestMappingHandlerMapping handlerMapping;
    private final ApplicationContext applicationContext;
    private final Map<String, Optional<AbstractResourceQueryController>> controllerByPathCache =
            new ConcurrentHashMap<>();

    public AnnotationDrivenResourceStructuralCapabilityResolver(
            RequestMappingHandlerMapping handlerMapping,
            ApplicationContext applicationContext
    ) {
        this.handlerMapping = handlerMapping;
        this.applicationContext = applicationContext;
    }

    @Override
    public ResourceStructuralCapabilities resolve(String resourcePath) {
        String expectedPath = normalize(resourcePath);
        Optional<AbstractResourceQueryController> controller =
                controllerByPathCache.computeIfAbsent(expectedPath, this::findControllerForPath);
        return controller
                .map(AbstractResourceQueryController::getStructuralCapabilities)
                .orElseGet(ResourceStructuralCapabilities::unsupported);
    }

    private Optional<AbstractResourceQueryController> findControllerForPath(String expectedPath) {
        Map<Class<?>, Boolean> matchesByType = new HashMap<>();
        Set<Object> matchedBeans = new LinkedHashSet<>();
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            if (handler == null) {
                continue;
            }
            Class<?> userType = ClassUtils.getUserClass(handler.getBeanType());
            if (matchesByType.computeIfAbsent(userType, type -> matches(type, expectedPath))) {
                Object bean = handler.getBean();
                if (bean != null) {
                    matchedBeans.add(bean);
                }
            }
        }
        List<AbstractResourceQueryController> controllers = matchedBeans.stream()
                .map(this::resolveControllerBean)
                .filter(AbstractResourceQueryController.class::isInstance)
                .map(AbstractResourceQueryController.class::cast)
                .distinct()
                .toList();
        if (controllers.size() > 1) {
            throw new IllegalStateException(
                    "Multiple canonical resource controllers publish structural capabilities for " + expectedPath
            );
        }
        return controllers.isEmpty() ? Optional.empty() : Optional.of(controllers.getFirst());
    }

    private boolean matches(Class<?> controllerType, String expectedPath) {
        ApiResource resource = AnnotationUtils.findAnnotation(controllerType, ApiResource.class);
        if (resource == null) {
            return false;
        }
        return Arrays.stream(resource.value()).anyMatch(path -> normalize(path).equals(expectedPath))
                || Arrays.stream(resource.path()).anyMatch(path -> normalize(path).equals(expectedPath));
    }

    private Object resolveControllerBean(Object bean) {
        if (bean instanceof String beanName && applicationContext != null && applicationContext.containsBean(beanName)) {
            return applicationContext.getBean(beanName);
        }
        return bean;
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
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
}
