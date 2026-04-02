package org.praxisplatform.uischema.analytics;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.praxisplatform.uischema.OperationProperties;
import org.praxisplatform.uischema.annotation.UiAnalytics;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publica {@code x-ui.analytics} em operacoes OpenAPI reais a partir de {@link UiAnalytics}.
 */
public class UiAnalyticsOpenApiCustomizer implements GlobalOpenApiCustomizer {

    private static final Logger logger = LoggerFactory.getLogger(UiAnalyticsOpenApiCustomizer.class);
    private static final String X_UI = "x-ui";

    private final RequestMappingHandlerMapping handlerMapping;
    private final CanonicalOperationResolver canonicalOperationResolver;
    private final UiAnalyticsAnnotationMapper mapper;

    public UiAnalyticsOpenApiCustomizer(
            RequestMappingHandlerMapping handlerMapping,
            CanonicalOperationResolver canonicalOperationResolver,
            UiAnalyticsAnnotationMapper mapper
    ) {
        this.handlerMapping = handlerMapping;
        this.canonicalOperationResolver = canonicalOperationResolver;
        this.mapper = mapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void customise(OpenAPI openApi) {
        if (openApi == null || openApi.getPaths() == null) {
            return;
        }

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            UiAnalytics annotation = AnnotationUtils.findAnnotation(entry.getValue().getMethod(), UiAnalytics.class);
            if (annotation == null) {
                continue;
            }

            CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(entry.getValue(), entry.getKey());
            Operation operation = findOperation(openApi, operationRef);
            if (operation == null) {
                logger.debug("Operacao OpenAPI nao encontrada para @UiAnalytics em {} {}",
                        operationRef.path(), operationRef.method());
                continue;
            }

            Map<String, Object> extensions = operation.getExtensions();
            if (extensions == null) {
                extensions = new LinkedHashMap<>();
                operation.setExtensions(extensions);
            }

            Map<String, Object> xUi = (Map<String, Object>) extensions.computeIfAbsent(X_UI, key -> new LinkedHashMap<>());
            xUi.put(OperationProperties.ANALYTICS, mapper.toXUiAnalytics(annotation, operationRef.path()));
        }
    }

    private Operation findOperation(OpenAPI openApi, CanonicalOperationRef operationRef) {
        if (operationRef == null || operationRef.path() == null || operationRef.method() == null) {
            return null;
        }

        PathItem pathItem = openApi.getPaths().get(operationRef.path());
        if (pathItem == null) {
            return null;
        }

        return switch (operationRef.method().toLowerCase()) {
            case "get" -> pathItem.getGet();
            case "post" -> pathItem.getPost();
            case "put" -> pathItem.getPut();
            case "patch" -> pathItem.getPatch();
            case "delete" -> pathItem.getDelete();
            case "head" -> pathItem.getHead();
            case "options" -> pathItem.getOptions();
            case "trace" -> pathItem.getTrace();
            default -> null;
        };
    }
}
