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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica {@code x-ui.analytics} em operacoes OpenAPI reais a partir de {@link UiAnalytics}.
 *
 * <p>Suporta declaracao em nivel de metodo ({@code @UiAnalytics} direto no endpoint) e em nivel de
 * classe de controller, anexando automaticamente {@code x-ui.analytics} na operacao
 * {@code /stats/group-by} do recurso (ou em {@code /filter} quando {@code /stats/group-by} nao
 * existir), preservando precedencia de declaracoes em nivel de metodo.</p>
 */
public class UiAnalyticsOpenApiCustomizer implements GlobalOpenApiCustomizer {

    private static final Logger logger = LoggerFactory.getLogger(UiAnalyticsOpenApiCustomizer.class);
    private static final String X_UI = "x-ui";
    private static final String STATS_GROUP_BY_SUFFIX = "/stats/group-by";
    private static final String FILTER_SUFFIX = "/filter";

    private final RequestMappingHandlerMapping handlerMapping;
    private final CanonicalOperationResolver canonicalOperationResolver;
    private final UiAnalyticsAnnotationMapper mapper;
    private final VerifiedAnalyticalRecipeResolver recipeResolver;

    public UiAnalyticsOpenApiCustomizer(
            RequestMappingHandlerMapping handlerMapping,
            CanonicalOperationResolver canonicalOperationResolver,
            UiAnalyticsAnnotationMapper mapper
    ) {
        this(handlerMapping, canonicalOperationResolver, mapper, null);
    }

    public UiAnalyticsOpenApiCustomizer(
            RequestMappingHandlerMapping handlerMapping,
            CanonicalOperationResolver canonicalOperationResolver,
            UiAnalyticsAnnotationMapper mapper,
            VerifiedAnalyticalRecipeResolver recipeResolver
    ) {
        this.handlerMapping = handlerMapping;
        this.canonicalOperationResolver = canonicalOperationResolver;
        this.mapper = mapper;
        this.recipeResolver = recipeResolver;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null || openApi.getPaths() == null) {
            return;
        }

        Map<Class<?>, List<Map.Entry<RequestMappingInfo, HandlerMethod>>> handlersByBeanType = new LinkedHashMap<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            handlersByBeanType
                    .computeIfAbsent(handlerMethod.getBeanType(), ignored -> new ArrayList<>())
                    .add(entry);

            UiAnalytics annotation = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), UiAnalytics.class);
            if (annotation == null) {
                continue;
            }

            CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(handlerMethod, entry.getKey());
            attachAnalytics(openApi, operationRef, annotation);
        }

        for (Map.Entry<Class<?>, List<Map.Entry<RequestMappingInfo, HandlerMethod>>> beanEntry : handlersByBeanType.entrySet()) {
            Class<?> beanType = beanEntry.getKey();
            Object controllerInstance = beanEntry.getValue().stream()
                    .map(e -> e.getValue().getBean())
                    .filter(b -> b != null && !(b instanceof String))
                    .findFirst()
                    .orElse(null);
            UiAnalytics classAnnotation = AnnotatedElementUtils.findMergedAnnotation(beanType, UiAnalytics.class);
            List<ResolvedVerifiedAnalyticalRecipe> recipes = recipeResolver != null
                    ? recipeResolver.resolveByController(beanType, controllerInstance)
                    : List.of();
            if (classAnnotation == null && recipes.isEmpty()) {
                continue;
            }

            ResolvedTarget target = resolveClassLevelTarget(openApi, beanEntry.getValue());
            if (target == null) {
                logger.debug("Nenhuma operacao /stats/group-by ou /filter encontrada para @UiAnalytics em {}",
                        beanType.getName());
                continue;
            }

            if (classAnnotation != null) {
                Map<String, Object> existingXUi = getOrNullXUi(target.operation());
                if (existingXUi == null || !existingXUi.containsKey(OperationProperties.ANALYTICS)) {
                    attachAnalytics(target.operation(), target.operationRef(), classAnnotation);
                }
            }
            if (!recipes.isEmpty()) {
                attachVerifiedRecipes(target.operation(), recipes);
            }
        }
    }

    private ResolvedTarget resolveClassLevelTarget(
            OpenAPI openApi,
            List<Map.Entry<RequestMappingInfo, HandlerMethod>> entries
    ) {
        ResolvedTarget filterFallback = null;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : entries) {
            CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(entry.getValue(), entry.getKey());
            if (operationRef == null || operationRef.path() == null) {
                continue;
            }
            Operation operation = findOperation(openApi, operationRef);
            if (operation == null) {
                continue;
            }
            if (operationRef.path().endsWith(STATS_GROUP_BY_SUFFIX)) {
                return new ResolvedTarget(operationRef, operation);
            }
            if (filterFallback == null && operationRef.path().endsWith(FILTER_SUFFIX)) {
                filterFallback = new ResolvedTarget(operationRef, operation);
            }
        }
        return filterFallback;
    }

    private void attachAnalytics(OpenAPI openApi, CanonicalOperationRef operationRef, UiAnalytics annotation) {
        Operation operation = findOperation(openApi, operationRef);
        if (operation == null) {
            logger.debug("Operacao OpenAPI nao encontrada para @UiAnalytics em {} {}",
                    operationRef != null ? operationRef.path() : null,
                    operationRef != null ? operationRef.method() : null);
            return;
        }
        attachAnalytics(operation, operationRef, annotation);
    }

    @SuppressWarnings("unchecked")
    private void attachAnalytics(Operation operation, CanonicalOperationRef operationRef, UiAnalytics annotation) {
        Map<String, Object> extensions = operation.getExtensions();
        if (extensions == null) {
            extensions = new LinkedHashMap<>();
            operation.setExtensions(extensions);
        }

        Map<String, Object> xUi = (Map<String, Object>) extensions.computeIfAbsent(X_UI, key -> new LinkedHashMap<>());
        xUi.put(OperationProperties.ANALYTICS, mapper.toXUiAnalytics(annotation, operationRef.path()));
    }

    @SuppressWarnings("unchecked")
    private void attachVerifiedRecipes(Operation operation, List<ResolvedVerifiedAnalyticalRecipe> recipes) {
        Map<String, Object> extensions = operation.getExtensions();
        if (extensions == null) {
            extensions = new LinkedHashMap<>();
            operation.setExtensions(extensions);
        }

        Map<String, Object> xUi = (Map<String, Object>) extensions.computeIfAbsent(X_UI, key -> new LinkedHashMap<>());
        Object existingAnalytics = xUi.get(OperationProperties.ANALYTICS);
        Map<String, Object> analyticsMap = existingAnalytics instanceof Map<?, ?> raw
                ? new LinkedHashMap<>((Map<String, Object>) raw)
                : new LinkedHashMap<>();
        analyticsMap.put("verifiedRecipes", recipes.stream().map(ResolvedVerifiedAnalyticalRecipe::toMetadataMap).toList());
        xUi.put(OperationProperties.ANALYTICS, analyticsMap);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrNullXUi(Operation operation) {
        if (operation.getExtensions() == null) {
            return null;
        }
        Object raw = operation.getExtensions().get(X_UI);
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private record ResolvedTarget(CanonicalOperationRef operationRef, Operation operation) {
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
