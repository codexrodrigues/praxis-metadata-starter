package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.List;

/**
 * Associa o path canonico ao controller resource-oriented e consulta o service que realmente
 * executa as operacoes opcionais.
 */
public final class AnnotationDrivenResourceStructuralCapabilityResolver
        implements ResourceStructuralCapabilityResolver {

    private final RequestMappingHandlerMapping handlerMapping;
    private final ApplicationContext applicationContext;

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
        List<AbstractResourceQueryController> controllers = handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> matches(handler, expectedPath))
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
        return controllers.isEmpty()
                ? ResourceStructuralCapabilities.unsupported()
                : controllers.getFirst().getStructuralCapabilities();
    }

    private boolean matches(HandlerMethod handlerMethod, String expectedPath) {
        ApiResource resource = AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), ApiResource.class);
        if (resource == null) {
            return false;
        }
        return Arrays.stream(resource.value()).anyMatch(path -> normalize(path).equals(expectedPath))
                || Arrays.stream(resource.path()).anyMatch(path -> normalize(path).equals(expectedPath));
    }

    private Object resolveControllerBean(HandlerMethod handlerMethod) {
        Object bean = handlerMethod.getBean();
        if (bean instanceof String beanName && applicationContext.containsBean(beanName)) {
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
