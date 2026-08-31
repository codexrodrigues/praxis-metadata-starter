package org.praxisplatform.uischema.controller.base;

import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.rest.response.RestApiResource;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

/** Canonical resource-key registry backed by the host's resource-oriented MVC controllers. */
public final class AnnotationDrivenResourceRepresentationMaterializer
        implements ResourceRepresentationMaterializer {

    private final RequestMappingHandlerMapping handlerMapping;
    private final ApplicationContext applicationContext;

    public AnnotationDrivenResourceRepresentationMaterializer(
            RequestMappingHandlerMapping handlerMapping,
            ApplicationContext applicationContext
    ) {
        this.handlerMapping = handlerMapping;
        this.applicationContext = applicationContext;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> RestApiResource<T> materialize(String resourceKey, T dto) {
        AbstractResourceQueryController<?, ?, ?> controller = resolveController(resourceKey);
        return (RestApiResource<T>) controller.materializeResourceRepresentation(dto);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<RestApiResource<T>> materializeAll(String resourceKey, List<T> dtos) {
        if (dtos == null) {
            throw new IllegalArgumentException("Resource representation DTO list must not be null");
        }
        AbstractResourceQueryController<?, ?, ?> controller = resolveController(resourceKey);
        return dtos.stream()
                .map(dto -> (RestApiResource<T>) controller.materializeResourceRepresentation(dto))
                .toList();
    }

    private AbstractResourceQueryController<?, ?, ?> resolveController(String resourceKey) {
        String canonicalResourceKey = requireResourceKey(resourceKey);
        List<AbstractResourceQueryController<?, ?, ?>> controllers = handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> hasResourceKey(handler, canonicalResourceKey))
                .map(this::resolveControllerBean)
                .filter(AbstractResourceQueryController.class::isInstance)
                .<AbstractResourceQueryController<?, ?, ?>>map(
                        bean -> (AbstractResourceQueryController<?, ?, ?>) bean
                )
                .distinct()
                .toList();

        if (controllers.isEmpty()) {
            throw new IllegalArgumentException(
                    "No canonical resource controller is registered for resourceKey " + canonicalResourceKey
            );
        }
        if (controllers.size() > 1) {
            throw new IllegalStateException(
                    "Multiple canonical resource controllers are registered for resourceKey " + canonicalResourceKey
            );
        }
        return controllers.getFirst();
    }

    private boolean hasResourceKey(HandlerMethod handlerMethod, String expectedResourceKey) {
        ApiResource resource = AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), ApiResource.class);
        return resource != null
                && StringUtils.hasText(resource.resourceKey())
                && resource.resourceKey().trim().equals(expectedResourceKey);
    }

    private Object resolveControllerBean(HandlerMethod handlerMethod) {
        Object bean = handlerMethod.getBean();
        if (bean instanceof String beanName && applicationContext.containsBean(beanName)) {
            return applicationContext.getBean(beanName);
        }
        return bean;
    }

    private String requireResourceKey(String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            throw new IllegalArgumentException("Resource representation resourceKey must not be blank");
        }
        return resourceKey.trim();
    }
}
