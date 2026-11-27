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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
    private final Object indexLock = new Object();
    private volatile CachedAnalyticsIndex cachedIndex;

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
        if (openApi == null || openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            return;
        }

        AnalyticsIndex index = resolveIndex();
        if (index.methodBindings().isEmpty() && index.classBindings().isEmpty()) {
            return;
        }

        if (!index.methodBindings().isEmpty()) {
            if (openApi.getPaths().size() <= index.methodBindings().size()) {
                for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
                    List<MethodAnalyticsBinding> bindings = index.methodBindingsByPath().get(pathEntry.getKey());
                    if (bindings == null) {
                        continue;
                    }
                    PathItem pathItem = pathEntry.getValue();
                    for (MethodAnalyticsBinding binding : bindings) {
                        Operation operation = findOperation(pathItem, binding.operationRef());
                        if (operation != null) {
                            attachAnalytics(operation, binding.operationRef(), binding.annotation());
                        }
                    }
                }
            } else {
                for (MethodAnalyticsBinding binding : index.methodBindings()) {
                    attachAnalytics(openApi, binding.operationRef(), binding.annotation());
                }
            }
        }

        for (ClassAnalyticsBinding binding : index.classBindings()) {
            ResolvedTarget target = resolveClassLevelTarget(openApi, binding.candidateTargets());
            if (target == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Nenhuma operacao /stats/group-by ou /filter encontrada para @UiAnalytics em {}",
                            binding.beanType().getName());
                }
                continue;
            }

            if (binding.classAnnotation() != null) {
                Map<String, Object> existingXUi = getOrNullXUi(target.operation());
                if (existingXUi == null || !existingXUi.containsKey(OperationProperties.ANALYTICS)) {
                    attachAnalytics(target.operation(), target.operationRef(), binding.classAnnotation());
                }
            }
            if (!binding.recipes().isEmpty()) {
                attachVerifiedRecipes(target.operation(), binding.recipes());
            }
        }
    }

    private AnalyticsIndex resolveIndex() {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
        if (handlerMethods == null || handlerMethods.isEmpty()) {
            return AnalyticsIndex.EMPTY;
        }
        CachedAnalyticsIndex current = cachedIndex;
        if (current != null && current.matches(handlerMethods)) {
            return current.index();
        }
        synchronized (indexLock) {
            current = cachedIndex;
            if (current != null && current.matches(handlerMethods)) {
                return current.index();
            }
            AnalyticsIndex built = buildIndex(handlerMethods);
            cachedIndex = CachedAnalyticsIndex.of(handlerMethods, built);
            return built;
        }
    }

    private AnalyticsIndex buildIndex(Map<RequestMappingInfo, HandlerMethod> handlerMethods) {
        Map<Class<?>, List<Map.Entry<RequestMappingInfo, HandlerMethod>>> handlersByBeanType = new LinkedHashMap<>();
        List<MethodAnalyticsBinding> methodBindings = new ArrayList<>();
        Map<String, List<MethodAnalyticsBinding>> methodBindingsByPath = new LinkedHashMap<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            handlersByBeanType
                    .computeIfAbsent(handlerMethod.getBeanType(), ignored -> new ArrayList<>())
                    .add(entry);

            UiAnalytics annotation = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), UiAnalytics.class);
            if (annotation == null) {
                continue;
            }

            CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(handlerMethod, entry.getKey());
            if (operationRef == null || operationRef.path() == null || operationRef.method() == null) {
                continue;
            }
            MethodAnalyticsBinding binding = new MethodAnalyticsBinding(operationRef, annotation);
            methodBindings.add(binding);
            methodBindingsByPath
                    .computeIfAbsent(operationRef.path(), ignored -> new ArrayList<>(1))
                    .add(binding);
        }

        List<ClassAnalyticsBinding> classBindings = new ArrayList<>();
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

            List<CanonicalOperationRef> candidateTargets = new ArrayList<>(2);
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : beanEntry.getValue()) {
                CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(entry.getValue(), entry.getKey());
                if (operationRef == null || operationRef.path() == null) {
                    continue;
                }
                String path = operationRef.path();
                if (path.endsWith(STATS_GROUP_BY_SUFFIX) || path.endsWith(FILTER_SUFFIX)) {
                    candidateTargets.add(operationRef);
                }
            }
            classBindings.add(new ClassAnalyticsBinding(
                    beanType,
                    classAnnotation,
                    recipes.isEmpty() ? List.of() : List.copyOf(recipes),
                    List.copyOf(candidateTargets)
            ));
        }

        Map<String, List<MethodAnalyticsBinding>> frozenMethodBindingsByPath = new LinkedHashMap<>(methodBindingsByPath.size());
        for (Map.Entry<String, List<MethodAnalyticsBinding>> entry : methodBindingsByPath.entrySet()) {
            frozenMethodBindingsByPath.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return new AnalyticsIndex(
                List.copyOf(methodBindings),
                Map.copyOf(frozenMethodBindingsByPath),
                List.copyOf(classBindings)
        );
    }

    private ResolvedTarget resolveClassLevelTarget(
            OpenAPI openApi,
            List<CanonicalOperationRef> candidateTargets
    ) {
        ResolvedTarget filterFallback = null;
        for (CanonicalOperationRef operationRef : candidateTargets) {
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
            if (logger.isDebugEnabled()) {
                logger.debug("Operacao OpenAPI nao encontrada para @UiAnalytics em {} {}",
                        operationRef != null ? operationRef.path() : null,
                        operationRef != null ? operationRef.method() : null);
            }
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

    private record MethodAnalyticsBinding(CanonicalOperationRef operationRef, UiAnalytics annotation) {
    }

    private record ClassAnalyticsBinding(
            Class<?> beanType,
            UiAnalytics classAnnotation,
            List<ResolvedVerifiedAnalyticalRecipe> recipes,
            List<CanonicalOperationRef> candidateTargets
    ) {
    }

    private record AnalyticsIndex(
            List<MethodAnalyticsBinding> methodBindings,
            Map<String, List<MethodAnalyticsBinding>> methodBindingsByPath,
            List<ClassAnalyticsBinding> classBindings
    ) {
        private static final AnalyticsIndex EMPTY = new AnalyticsIndex(List.of(), Map.of(), List.of());
    }

    private static final class CachedAnalyticsIndex {
        private final RequestMappingInfo[] sourceKeys;
        private final HandlerMethod[] sourceValues;
        private final AnalyticsIndex index;

        private CachedAnalyticsIndex(
                RequestMappingInfo[] sourceKeys,
                HandlerMethod[] sourceValues,
                AnalyticsIndex index
        ) {
            this.sourceKeys = sourceKeys;
            this.sourceValues = sourceValues;
            this.index = index;
        }

        private static CachedAnalyticsIndex of(
                Map<RequestMappingInfo, HandlerMethod> handlerMethods,
                AnalyticsIndex index
        ) {
            int size = handlerMethods.size();
            RequestMappingInfo[] keys = new RequestMappingInfo[size];
            HandlerMethod[] values = new HandlerMethod[size];
            int i = 0;
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
                keys[i] = entry.getKey();
                values[i] = entry.getValue();
                i++;
            }
            return new CachedAnalyticsIndex(keys, values, index);
        }

        private boolean matches(Map<RequestMappingInfo, HandlerMethod> current) {
            if (current == null || current.size() != sourceKeys.length) {
                return false;
            }
            int i = 0;
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : current.entrySet()) {
                if (i >= sourceKeys.length) {
                    return false;
                }
                RequestMappingInfo key = entry.getKey();
                HandlerMethod value = entry.getValue();
                if ((key != sourceKeys[i] && !Objects.equals(key, sourceKeys[i]))
                        || (value != sourceValues[i] && !Objects.equals(value, sourceValues[i]))) {
                    return false;
                }
                i++;
            }
            return i == sourceKeys.length;
        }

        private AnalyticsIndex index() {
            return index;
        }
    }

    private Operation findOperation(OpenAPI openApi, CanonicalOperationRef operationRef) {
        if (operationRef == null || operationRef.path() == null || operationRef.method() == null) {
            return null;
        }

        PathItem pathItem = openApi.getPaths().get(operationRef.path());
        return findOperation(pathItem, operationRef);
    }

    private Operation findOperation(PathItem pathItem, CanonicalOperationRef operationRef) {
        if (pathItem == null || operationRef == null || operationRef.method() == null) {
            return null;
        }

        return switch (operationRef.method().toLowerCase(Locale.ROOT)) {
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
