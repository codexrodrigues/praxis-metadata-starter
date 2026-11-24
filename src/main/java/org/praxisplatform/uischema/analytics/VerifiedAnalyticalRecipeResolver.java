package org.praxisplatform.uischema.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.praxisplatform.uischema.annotation.AnalyticalRecipeGovernanceTier;
import org.praxisplatform.uischema.annotation.AnalyticalRecipeParameter;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.VerifiedAnalyticalRecipe;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver;
import org.praxisplatform.uischema.stats.ComparisonPeriodMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsDimensionExtraction;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.praxisplatform.uischema.stats.dto.ComparisonPeriodRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.StatsDimensionRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.VerifiedRecipeExecutionRequest;
import org.praxisplatform.uischema.stats.dto.VerifiedRecipeProvenance;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Resolve, valida (fail-fast) e compila {@link VerifiedAnalyticalRecipe} declarativas e
 * {@link VerifiedAnalyticalRecipeDescriptor} programaticas em contratos canonicos para
 * OpenAPI {@code x-ui.analytics.verifiedRecipes}, {@code /schemas/domain}, {@code GET /stats/recipes}
 * e execucao fail-closed em {@code POST /stats/recipes/{recipeId}}.
 */
public class VerifiedAnalyticalRecipeResolver {

    public static final String STAT_PARAM_LIMIT = "limit";
    public static final String STAT_PARAM_FROM = "from";
    public static final String STAT_PARAM_TO = "to";
    public static final String STAT_PARAM_GRANULARITY = "granularity";
    public static final String STAT_PARAM_TIMEZONE = "timezone";
    public static final String STAT_PARAM_FILL_GAPS = "fillGaps";
    public static final String STAT_PARAM_PERIOD_PRESET = "periodPreset";
    public static final String STAT_PARAM_PERIOD_MODE = "periodMode";

    private static final Set<String> RESERVED_QUERY_PARAMS = Set.of(
            STAT_PARAM_LIMIT,
            STAT_PARAM_FROM,
            STAT_PARAM_TO,
            STAT_PARAM_GRANULARITY,
            STAT_PARAM_TIMEZONE,
            STAT_PARAM_FILL_GAPS,
            STAT_PARAM_PERIOD_PRESET,
            STAT_PARAM_PERIOD_MODE
    );

    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final SchemaCanonicalizer HASH_CANONICALIZER = new SchemaCanonicalizer();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final RequestMappingHandlerMapping handlerMapping;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final List<VerifiedAnalyticalRecipeProvider> providers;
    private final ConcurrentMap<Class<?>, List<ResolvedVerifiedAnalyticalRecipe>> controllerCache = new ConcurrentHashMap<>();
    private volatile List<ControllerCandidate> cachedCandidates;

    public VerifiedAnalyticalRecipeResolver(
            RequestMappingHandlerMapping handlerMapping,
            ObjectMapper objectMapper,
            ApplicationContext applicationContext
    ) {
        this(handlerMapping, objectMapper, applicationContext, List.of());
    }

    public VerifiedAnalyticalRecipeResolver(
            RequestMappingHandlerMapping handlerMapping,
            ObjectMapper objectMapper,
            ApplicationContext applicationContext,
            List<VerifiedAnalyticalRecipeProvider> providers
    ) {
        this.handlerMapping = handlerMapping;
        if (objectMapper == null) {
            this.objectMapper = DEFAULT_OBJECT_MAPPER;
        } else {
            ObjectMapper configured = objectMapper.copy();
            configured.registerModule(new JavaTimeModule());
            configured.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            this.objectMapper = configured;
        }
        this.applicationContext = applicationContext;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /** Resolucao standalone para testes/controllers sem bean gerenciado pelo contexto Spring. */
    public static List<ResolvedVerifiedAnalyticalRecipe> resolveStandalone(
            Class<?> controllerClass,
            Object controllerInstance,
            ObjectMapper objectMapper
    ) {
        return new VerifiedAnalyticalRecipeResolver(null, objectMapper, null, List.of())
                .resolveByController(controllerClass, controllerInstance);
    }

    public List<ResolvedVerifiedAnalyticalRecipe> resolveByController(Class<?> controllerType, Object controllerInstance) {
        if (controllerType == null) {
            return List.of();
        }
        Class<?> userType = ClassUtils.getUserClass(controllerType);
        return controllerCache.computeIfAbsent(userType, key -> computeControllerRecipes(key, controllerInstance));
    }

    public List<ResolvedVerifiedAnalyticalRecipe> resolveByResourcePath(String resourcePath) {
        String expectedPath = normalizePath(resourcePath);
        if (!StringUtils.hasText(expectedPath) || "/".equals(expectedPath)) {
            return List.of();
        }
        List<ControllerCandidate> candidates = discoverCandidates().stream()
                .filter(candidate -> expectedPath.equals(candidate.resourcePath())
                        || matchesPath(candidate.apiResource(), expectedPath))
                .toList();
        return collectRecipesFromCandidates(candidates);
    }

    public List<ResolvedVerifiedAnalyticalRecipe> resolveByResourceKey(String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            return List.of();
        }
        String expected = resourceKey.trim();
        List<ControllerCandidate> candidates = discoverCandidates().stream()
                .filter(candidate -> expected.equals(candidate.resourceKey()))
                .toList();
        return collectRecipesFromCandidates(candidates);
    }

    public Optional<String> resolveResourcePathByResourceKey(String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            return Optional.empty();
        }
        String expected = resourceKey.trim();
        return discoverCandidates().stream()
                .filter(candidate -> expected.equals(candidate.resourceKey()))
                .map(ControllerCandidate::resourcePath)
                .filter(StringUtils::hasText)
                .findFirst();
    }

    public List<ResolvedVerifiedAnalyticalRecipe> resolveAll() {
        return collectRecipesFromCandidates(discoverCandidates());
    }

    public Map<String, List<ResolvedVerifiedAnalyticalRecipe>> resolveAllGroupedByResourceKey() {
        Map<String, List<ResolvedVerifiedAnalyticalRecipe>> grouped = new LinkedHashMap<>();
        for (ControllerCandidate candidate : discoverCandidates()) {
            List<ResolvedVerifiedAnalyticalRecipe> resolved = resolveByController(
                    candidate.controllerType(), candidate.controllerInstance());
            if (!resolved.isEmpty() && StringUtils.hasText(candidate.resourceKey())) {
                grouped.merge(candidate.resourceKey(), resolved, (a, b) -> {
                    List<ResolvedVerifiedAnalyticalRecipe> merged = new ArrayList<>(a);
                    merged.addAll(b);
                    return List.copyOf(merged);
                });
            }
        }
        return Collections.unmodifiableMap(grouped);
    }

    /**
     * Valida parametros ajustaveis (fail-closed), aplica lockedFilters com precedencia absoluta
     * do servidor e compila o {@link CompiledRecipeExecution} pronto para execucao no service.
     */
    public <FD extends GenericFilterDTO> CompiledRecipeExecution<FD> compileExecution(
            ResolvedVerifiedAnalyticalRecipe recipe,
            Class<FD> filterDtoClass,
            StatsFieldRegistry statsRegistry,
            VerifiedRecipeExecutionRequest request
    ) {
        if (recipe == null) {
            throw new IllegalArgumentException("Verified analytical recipe must not be null.");
        }
        validateAgainstStatsRegistry(recipe, statsRegistry, true);
        VerifiedRecipeExecutionRequest safeRequest = request != null
                ? request
                : new VerifiedRecipeExecutionRequest();

        Map<String, VerifiedAnalyticalRecipeDescriptor.ParameterSpec> allowedParams = new LinkedHashMap<>();
        for (VerifiedAnalyticalRecipeDescriptor.ParameterSpec spec : recipe.adjustableParams()) {
            allowedParams.put(spec.name(), spec);
        }

        // Fail-closed: rejeita qualquer parametro nao declarado em adjustableParams.
        for (String key : safeRequest.parameters().keySet()) {
            if (!allowedParams.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Unsupported adjustable parameter '" + key
                                + "' for verified analytical recipe '" + recipe.id() + "'."
                );
            }
        }

        String requestedTimezone = StringUtils.hasText(safeRequest.timezone())
                ? safeRequest.timezone().trim()
                : null;
        if (requestedTimezone != null) {
            if (!allowedParams.containsKey(STAT_PARAM_TIMEZONE)) {
                if (!StringUtils.hasText(recipe.timezone()) || !requestedTimezone.equals(recipe.timezone().trim())) {
                    throw new IllegalArgumentException(
                            "Timezone override is not permitted for verified analytical recipe '" + recipe.id() + "'."
                    );
                }
            } else if (safeRequest.parameters().containsKey(STAT_PARAM_TIMEZONE)) {
                String paramTz = String.valueOf(safeRequest.parameters().get(STAT_PARAM_TIMEZONE)).trim();
                if (!requestedTimezone.equals(paramTz)) {
                    throw new IllegalArgumentException(
                            "Conflicting timezone values between request.timezone and parameters.timezone."
                    );
                }
            }
        }

        Map<String, Field> filterFields = filterDtoClass != null
                ? ApiResourceQuickFilterResolver.collectAllInstanceFields(filterDtoClass)
                : Map.of();

        Map<String, Object> appliedParams = new LinkedHashMap<>();
        Map<String, Object> adjustableFilterValues = new LinkedHashMap<>();
        Integer limitOverride = null;
        LocalDate fromOverride = null;
        LocalDate toOverride = null;
        TimeSeriesGranularity granularityOverride = null;
        String timezoneOverride = null;
        Boolean fillGapsOverride = null;
        ComparisonPeriodPreset presetOverride = null;
        ComparisonPeriodMode modeOverride = null;

        for (VerifiedAnalyticalRecipeDescriptor.ParameterSpec spec : recipe.adjustableParams()) {
            Object rawValue;
            if (safeRequest.parameters().containsKey(spec.name())) {
                rawValue = safeRequest.parameters().get(spec.name());
            } else if (STAT_PARAM_TIMEZONE.equals(spec.name()) && requestedTimezone != null) {
                rawValue = requestedTimezone;
            } else {
                rawValue = spec.defaultValue();
            }
            if (rawValue == null || (rawValue instanceof String s && s.isBlank())) {
                if (spec.required()) {
                    throw new IllegalArgumentException(
                            "Missing required adjustable parameter '" + spec.name()
                                    + "' for verified analytical recipe '" + recipe.id() + "'."
                    );
                }
                continue;
            }
            Object validatedValue = validateAndCoerceParameterValue(spec, rawValue);
            appliedParams.put(spec.name(), validatedValue);

            if (isReservedQueryParam(spec)) {
                switch (spec.name()) {
                    case STAT_PARAM_LIMIT -> {
                        int parsed = ((Number) validatedValue).intValue();
                        if (parsed <= 0) {
                            throw new IllegalArgumentException("limit must be greater than zero.");
                        }
                        limitOverride = parsed;
                    }
                    case STAT_PARAM_FROM -> fromOverride = (LocalDate) validatedValue;
                    case STAT_PARAM_TO -> toOverride = (LocalDate) validatedValue;
                    case STAT_PARAM_GRANULARITY -> granularityOverride = TimeSeriesGranularity.valueOf(
                            validatedValue.toString().trim().toUpperCase(Locale.ROOT));
                    case STAT_PARAM_TIMEZONE -> timezoneOverride = validatedValue.toString().trim();
                    case STAT_PARAM_FILL_GAPS -> fillGapsOverride = (Boolean) validatedValue;
                    case STAT_PARAM_PERIOD_PRESET -> presetOverride = ComparisonPeriodPreset.valueOf(
                            validatedValue.toString().trim().toUpperCase(Locale.ROOT));
                    case STAT_PARAM_PERIOD_MODE -> modeOverride = ComparisonPeriodMode.valueOf(
                            validatedValue.toString().trim().toUpperCase(Locale.ROOT));
                    default -> { }
                }
            } else {
                String targetField = resolveTargetFilterField(spec);
                if (recipe.lockedFilters().containsKey(targetField)) {
                    throw new IllegalStateException(
                            "Adjustable parameter '" + spec.name() + "' cannot override locked filter '"
                                    + targetField + "' in recipe '" + recipe.id() + "'."
                    );
                }
                adjustableFilterValues.put(targetField, validatedValue);
            }
        }

        // Server wins: lockedFilters sempre sobrescreve por ultimo.
        Map<String, Object> effectiveFilterMap = new LinkedHashMap<>(adjustableFilterValues);
        effectiveFilterMap.putAll(recipe.lockedFilters());
        FD compiledFilter = buildFilterDto(filterDtoClass, filterFields, effectiveFilterMap);

        int effectiveLimit = limitOverride != null ? limitOverride : recipe.limit();
        TimeSeriesGranularity effectiveGranularity = granularityOverride != null
                ? granularityOverride
                : recipe.granularity();
        String effectiveTimezone = StringUtils.hasText(timezoneOverride)
                ? timezoneOverride.trim()
                : (StringUtils.hasText(recipe.timezone()) ? recipe.timezone().trim() : null);
        ZoneId resolvedZone = null;
        if (StringUtils.hasText(effectiveTimezone)) {
            try {
                resolvedZone = ZoneId.of(effectiveTimezone);
            } catch (DateTimeException ex) {
                throw new IllegalArgumentException("Invalid IANA timezone: " + effectiveTimezone);
            }
        }
        boolean effectiveFillGaps = fillGapsOverride != null ? fillGapsOverride : recipe.fillGaps();
        ComparisonPeriodPreset effectivePreset = presetOverride != null ? presetOverride : recipe.periodPreset();
        ComparisonPeriodMode effectiveMode = modeOverride != null ? modeOverride : recipe.periodMode();

        List<StatsMetricRequest> metricRequests = recipe.metrics().stream()
                .map(m -> new StatsMetricRequest(m.operation(), m.field(), m.alias()))
                .toList();
        StatsMetricRequest primaryMetric = metricRequests.isEmpty() ? null : metricRequests.get(0);

        List<StatsDimensionRequest> dimensionRequests = recipe.dimensions().stream()
                .map(d -> new StatsDimensionRequest(d.field(), d.extraction(), d.alias()))
                .toList();

        GroupByStatsRequest<FD> groupByRequest = null;
        TimeSeriesStatsRequest<FD> timeSeriesRequest = null;
        DistributionStatsRequest<FD> distributionRequest = null;
        ComparisonStatsRequest<FD> comparisonRequest = null;

        switch (recipe.operation()) {
            case GROUP_BY -> {
                String primaryField = StringUtils.hasText(recipe.field())
                        ? recipe.field()
                        : (!dimensionRequests.isEmpty() ? dimensionRequests.get(0).field() : null);
                boolean hasPeriodField = StringUtils.hasText(recipe.periodField());
                ComparisonPeriodPreset groupByPreset = (fromOverride != null || toOverride != null)
                        ? null
                        : (presetOverride != null ? presetOverride : (hasPeriodField ? effectivePreset : null));
                Boolean groupByFillGaps = fillGapsOverride != null
                        ? fillGapsOverride
                        : (hasPeriodField ? recipe.fillGaps() : null);
                groupByRequest = new GroupByStatsRequest<>(
                        compiledFilter,
                        primaryField,
                        primaryMetric,
                        effectiveLimit,
                        recipe.orderBy(),
                        metricRequests,
                        dimensionRequests.isEmpty() ? null : dimensionRequests,
                        effectiveTimezone,
                        groupByPreset,
                        fromOverride,
                        toOverride,
                        groupByFillGaps,
                        hasPeriodField ? recipe.periodField() : null
                );
            }
            case TIMESERIES -> {
                LocalDate today = resolvedZone != null ? LocalDate.now(resolvedZone) : LocalDate.now();
                LocalDate resolvedFrom = fromOverride != null ? fromOverride : today.minusDays(365);
                LocalDate resolvedTo = toOverride != null ? toOverride : today;
                appliedParams.putIfAbsent(STAT_PARAM_FROM, resolvedFrom.toString());
                appliedParams.putIfAbsent(STAT_PARAM_TO, resolvedTo.toString());
                timeSeriesRequest = new TimeSeriesStatsRequest<>(
                        compiledFilter,
                        recipe.field(),
                        effectiveGranularity,
                        primaryMetric,
                        resolvedFrom,
                        resolvedTo,
                        effectiveFillGaps,
                        metricRequests,
                        effectiveTimezone
                );
            }
            case DISTRIBUTION -> distributionRequest = new DistributionStatsRequest<>(
                    compiledFilter,
                    recipe.field(),
                    recipe.distributionMode(),
                    primaryMetric,
                    recipe.bucketSize(),
                    recipe.bucketCount(),
                    effectiveLimit,
                    recipe.orderBy()
            );
            case COMPARISON -> {
                ComparisonPeriodRequest period = new ComparisonPeriodRequest(
                        (fromOverride != null && toOverride != null) ? null : effectivePreset,
                        fromOverride,
                        toOverride,
                        effectiveTimezone,
                        effectiveMode
                );
                comparisonRequest = new ComparisonStatsRequest<>(
                        compiledFilter,
                        recipe.field(),
                        StringUtils.hasText(recipe.periodField()) ? recipe.periodField() : recipe.field(),
                        metricRequests,
                        period,
                        effectiveLimit,
                        recipe.orderBy()
                );
            }
            case INFERENCE, ANOMALIES -> throw new UnsupportedOperationException(
                    "Verified analytical recipes currently support GROUP_BY, TIMESERIES, DISTRIBUTION, and COMPARISON operations."
            );
        }

        List<String> adjustableNames = recipe.adjustableParams().stream()
                .map(VerifiedAnalyticalRecipeDescriptor.ParameterSpec::name)
                .toList();
        VerifiedRecipeProvenance provenance = new VerifiedRecipeProvenance(
                recipe.id(),
                recipe.title(),
                recipe.resourceKey(),
                recipe.version(),
                recipe.governanceTier(),
                recipe.provenanceDigest(),
                recipe.operation(),
                recipe.lockedFilters(),
                Collections.unmodifiableMap(appliedParams),
                adjustableNames,
                recipe.endpointPath()
        );

        return new CompiledRecipeExecution<>(
                provenance,
                groupByRequest,
                timeSeriesRequest,
                distributionRequest,
                comparisonRequest
        );
    }

    private List<ResolvedVerifiedAnalyticalRecipe> computeControllerRecipes(
            Class<?> controllerType,
            Object controllerInstance
    ) {
        ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(controllerType, ApiResource.class);
        String resourceKey = resolveResourceKey(apiResource, controllerType);
        String resourcePath = primaryResourcePath(apiResource, controllerType);
        Class<?> filterDtoClass = ApiResourceQuickFilterResolver.resolveControllerFilterDtoType(controllerType);
        Object resolvedInstance = controllerInstance != null ? unwrapTarget(controllerInstance) : resolveControllerBean(controllerType);
        StatsFieldRegistry statsRegistry = resolveControllerStatsRegistry(resolvedInstance);

        List<OrderedRecipeEntry> entries = new ArrayList<>();
        int index = 0;

        // 1) Programaticas declaradas pelo controller via defineAnalyticalRecipes()
        if (resolvedInstance instanceof AbstractResourceQueryController<?, ?, ?> queryController) {
            List<? extends VerifiedAnalyticalRecipeDescriptor<?>> defined =
                    queryController.resolvedAnalyticalRecipeDescriptors();
            if (defined != null) {
                for (VerifiedAnalyticalRecipeDescriptor<?> descriptor : defined) {
                    if (descriptor == null) {
                        continue;
                    }
                    ResolvedVerifiedAnalyticalRecipe resolved = resolveDescriptor(
                            descriptor, resourceKey, resourcePath, filterDtoClass, statsRegistry);
                    entries.add(new OrderedRecipeEntry(resolved.order(), index++, resolved));
                }
            }
        }

        // 2) Declarativas na classe (@VerifiedAnalyticalRecipe)
        Set<VerifiedAnalyticalRecipe> classAnnotations = AnnotatedElementUtils.findMergedRepeatableAnnotations(
                controllerType, VerifiedAnalyticalRecipe.class);
        for (VerifiedAnalyticalRecipe annotation : classAnnotations) {
            ResolvedVerifiedAnalyticalRecipe resolved = resolveAnnotation(
                    annotation, resourceKey, resourcePath, filterDtoClass, statsRegistry);
            entries.add(new OrderedRecipeEntry(resolved.order(), index++, resolved));
        }

        // 3) Declarativas em metodos
        for (Method method : controllerType.getDeclaredMethods()) {
            Set<VerifiedAnalyticalRecipe> methodAnnotations = AnnotatedElementUtils.findMergedRepeatableAnnotations(
                    method, VerifiedAnalyticalRecipe.class);
            for (VerifiedAnalyticalRecipe annotation : methodAnnotations) {
                ResolvedVerifiedAnalyticalRecipe resolved = resolveAnnotation(
                        annotation, resourceKey, resourcePath, filterDtoClass, statsRegistry);
                entries.add(new OrderedRecipeEntry(resolved.order(), index++, resolved));
            }
        }

        // 4) SPI externo VerifiedAnalyticalRecipeProvider
        for (VerifiedAnalyticalRecipeProvider provider : providers) {
            List<VerifiedAnalyticalRecipeDescriptor<? extends GenericFilterDTO>> extra =
                    provider.findRecipes(resourceKey, resourcePath);
            if (extra != null) {
                for (VerifiedAnalyticalRecipeDescriptor<?> descriptor : extra) {
                    if (descriptor == null) {
                        continue;
                    }
                    ResolvedVerifiedAnalyticalRecipe resolved = resolveDescriptor(
                            descriptor, resourceKey, resourcePath, filterDtoClass, statsRegistry);
                    entries.add(new OrderedRecipeEntry(resolved.order(), index++, resolved));
                }
            }
        }

        if (entries.isEmpty()) {
            return List.of();
        }

        entries.sort(Comparator.comparingInt(OrderedRecipeEntry::order)
                .thenComparingInt(OrderedRecipeEntry::index));

        Set<String> seenIds = new LinkedHashSet<>();
        List<ResolvedVerifiedAnalyticalRecipe> result = new ArrayList<>(entries.size());
        for (OrderedRecipeEntry entry : entries) {
            if (!seenIds.add(entry.recipe().id())) {
                throw new IllegalStateException(
                        "Duplicate @VerifiedAnalyticalRecipe id '" + entry.recipe().id()
                                + "' on resource '" + resourceKey + "'."
                );
            }
            result.add(entry.recipe());
        }
        return List.copyOf(result);
    }

    private ResolvedVerifiedAnalyticalRecipe resolveAnnotation(
            VerifiedAnalyticalRecipe ann,
            String resourceKey,
            String resourcePath,
            Class<?> filterDtoClass,
            StatsFieldRegistry statsRegistry
    ) {
        if (!StringUtils.hasText(ann.id()) || !StringUtils.hasText(ann.title())) {
            throw new IllegalStateException(
                    "@VerifiedAnalyticalRecipe on resource '" + resourceKey + "' requires non-blank id and title.");
        }
        List<VerifiedAnalyticalRecipeDescriptor.DimensionSpec> dimensions = parseDimensionSpecs(ann.dimensions());
        if (!StringUtils.hasText(ann.field()) && dimensions.isEmpty()) {
            throw new IllegalStateException(
                    "@VerifiedAnalyticalRecipe '" + ann.id() + "' on resource '" + resourceKey
                            + "' requires 'field' or 'dimensions'."
            );
        }
        Map<String, Field> filterFields = filterDtoClass != null
                ? ApiResourceQuickFilterResolver.collectAllInstanceFields(filterDtoClass)
                : Map.of();
        Map<String, Object> lockedFilters = ApiResourceQuickFilterResolver.compileCriteria(
                ann.lockedCriteria(), filterFields);

        List<VerifiedAnalyticalRecipeDescriptor.ParameterSpec> params = new ArrayList<>();
        for (AnalyticalRecipeParameter p : ann.adjustableParams()) {
            params.add(new VerifiedAnalyticalRecipeDescriptor.ParameterSpec(
                    p.name(),
                    p.filterField(),
                    p.description(),
                    p.dataType(),
                    p.required(),
                    p.defaultValue(),
                    Arrays.stream(p.allowedValues()).filter(StringUtils::hasText).map(String::trim).toList(),
                    p.minValue(),
                    p.maxValue()
            ));
        }

        BigDecimal bucketSize = StringUtils.hasText(ann.bucketSize())
                ? new BigDecimal(ann.bucketSize().trim())
                : null;
        Integer bucketCount = ann.bucketCount() > 0 ? ann.bucketCount() : null;

        List<VerifiedAnalyticalRecipeDescriptor.MetricSpec> metrics = List.of(
                new VerifiedAnalyticalRecipeDescriptor.MetricSpec(
                        ann.metric(),
                        StringUtils.hasText(ann.metricField()) ? ann.metricField().trim() : null,
                        StringUtils.hasText(ann.metricAlias()) ? ann.metricAlias().trim() : null
                )
        );

        return finalizeAndValidate(
                ann.id().trim(),
                ann.title().trim(),
                ann.description().trim(),
                ann.intent().trim(),
                resourceKey,
                resourcePath,
                ann.operation(),
                ann.field().trim(),
                dimensions,
                metrics,
                ann.orderBy(),
                ann.limit() <= 0 ? 10 : ann.limit(),
                ann.granularity(),
                ann.fillGaps(),
                ann.distributionMode(),
                bucketCount,
                bucketSize,
                ann.timezone().trim(),
                ann.periodField().trim(),
                ann.periodPreset(),
                ann.periodMode(),
                lockedFilters,
                params,
                ann.governanceTier(),
                Arrays.stream(ann.domainTags()).filter(StringUtils::hasText).map(String::trim).toList(),
                StringUtils.hasText(ann.version()) ? ann.version().trim() : "1.0.0",
                ann.order(),
                filterDtoClass,
                filterFields,
                statsRegistry
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResolvedVerifiedAnalyticalRecipe resolveDescriptor(
            VerifiedAnalyticalRecipeDescriptor<?> descriptor,
            String resourceKey,
            String resourcePath,
            Class<?> filterDtoClass,
            StatsFieldRegistry statsRegistry
    ) {
        Map<String, Field> filterFields = filterDtoClass != null
                ? ApiResourceQuickFilterResolver.collectAllInstanceFields(filterDtoClass)
                : Map.of();
        Map<String, Object> lockedFilters = new LinkedHashMap<>();
        if (descriptor.lockedFilterConfigurer() != null && filterDtoClass != null) {
            Object rawFilter = instantiateFilterDto(filterDtoClass);
            if (rawFilter instanceof GenericFilterDTO filterInstance) {
                ((Consumer) descriptor.lockedFilterConfigurer()).accept(filterInstance);
                Map<String, Object> serialized = objectMapper.convertValue(filterInstance, MAP_TYPE_REF);
                if (serialized != null) {
                    serialized.forEach((k, v) -> {
                        if (v != null && filterFields.containsKey(k)) {
                            lockedFilters.put(k, v);
                        }
                    });
                }
            }
        }
        descriptor.explicitLockedCriteria().forEach((k, v) -> {
            if (StringUtils.hasText(k) && v != null) {
                if (filterDtoClass != null && !filterFields.containsKey(k)) {
                    throw new IllegalStateException(
                            "Unknown locked filter field '" + k + "' on FilterDTO " + filterDtoClass.getSimpleName()
                                    + " in verified recipe '" + descriptor.id() + "'."
                    );
                }
                lockedFilters.put(k, v);
            }
        });

        return finalizeAndValidate(
                descriptor.id(),
                descriptor.title(),
                descriptor.description(),
                descriptor.intent(),
                resourceKey,
                resourcePath,
                descriptor.operation(),
                descriptor.field(),
                descriptor.dimensions(),
                descriptor.metrics(),
                descriptor.orderBy(),
                descriptor.limit(),
                descriptor.granularity(),
                descriptor.fillGaps(),
                descriptor.distributionMode(),
                descriptor.bucketCount(),
                descriptor.bucketSize(),
                descriptor.timezone(),
                descriptor.periodField(),
                descriptor.periodPreset(),
                descriptor.periodMode(),
                lockedFilters,
                descriptor.adjustableParams(),
                descriptor.governanceTier(),
                descriptor.domainTags(),
                descriptor.version(),
                descriptor.order(),
                filterDtoClass,
                filterFields,
                statsRegistry
        );
    }

    private ResolvedVerifiedAnalyticalRecipe finalizeAndValidate(
            String id,
            String title,
            String description,
            String intent,
            String resourceKey,
            String resourcePath,
            AnalyticsOperation operation,
            String field,
            List<VerifiedAnalyticalRecipeDescriptor.DimensionSpec> dimensions,
            List<VerifiedAnalyticalRecipeDescriptor.MetricSpec> metrics,
            org.praxisplatform.uischema.stats.StatsBucketOrder orderBy,
            int limit,
            TimeSeriesGranularity granularity,
            boolean fillGaps,
            DistributionMode distributionMode,
            Integer bucketCount,
            BigDecimal bucketSize,
            String timezone,
            String periodField,
            ComparisonPeriodPreset periodPreset,
            ComparisonPeriodMode periodMode,
            Map<String, Object> lockedFilters,
            List<VerifiedAnalyticalRecipeDescriptor.ParameterSpec> adjustableParams,
            AnalyticalRecipeGovernanceTier governanceTier,
            List<String> domainTags,
            String version,
            int order,
            Class<?> filterDtoClass,
            Map<String, Field> filterFields,
            StatsFieldRegistry statsRegistry
    ) {
        // Validate parameter definitions fail-fast against lockedFilters and FilterDTO.
        Set<String> seenParams = new LinkedHashSet<>();
        for (VerifiedAnalyticalRecipeDescriptor.ParameterSpec param : adjustableParams) {
            if (!StringUtils.hasText(param.name())) {
                throw new IllegalStateException("Verified analytical recipe '" + id + "' declares blank parameter name.");
            }
            if (!seenParams.add(param.name())) {
                throw new IllegalStateException("Duplicate adjustable parameter '" + param.name() + "' in recipe '" + id + "'.");
            }
            if (!isReservedQueryParam(param)) {
                String targetField = resolveTargetFilterField(param);
                if (lockedFilters.containsKey(targetField)) {
                    throw new IllegalStateException(
                            "Adjustable parameter '" + param.name() + "' targets locked filter field '"
                                    + targetField + "' in verified analytical recipe '" + id + "'."
                    );
                }
                if (filterDtoClass != null && !filterFields.containsKey(targetField)) {
                    throw new IllegalStateException(
                            "Adjustable parameter '" + param.name() + "' targets unknown FilterDTO field '"
                                    + targetField + "' on " + filterDtoClass.getSimpleName()
                                    + " in verified analytical recipe '" + id + "'."
                    );
                }
            }
        }

        String normalizedBasePath = normalizePath(resourcePath);
        String endpointPath = "/".equals(normalizedBasePath)
                ? "/stats/recipes/" + id
                : normalizedBasePath + "/stats/recipes/" + id;

        // Build provisional recipe so validation against StatsFieldRegistry can inspect it uniformly.
        Map<String, Object> canonicalSpec = new LinkedHashMap<>();
        canonicalSpec.put("resourceKey", resourceKey);
        canonicalSpec.put("id", id);
        canonicalSpec.put("version", version);
        canonicalSpec.put("governanceTier", governanceTier.wireValue());
        canonicalSpec.put("operation", operation.wireValue());
        canonicalSpec.put("field", field);
        canonicalSpec.put("dimensions", dimensions);
        canonicalSpec.put("metrics", metrics);
        canonicalSpec.put("orderBy", orderBy.name());
        canonicalSpec.put("limit", limit);
        canonicalSpec.put("granularity", granularity.name());
        canonicalSpec.put("fillGaps", fillGaps);
        canonicalSpec.put("distributionMode", distributionMode.name());
        canonicalSpec.put("bucketCount", bucketCount);
        canonicalSpec.put("bucketSize", bucketSize);
        canonicalSpec.put("timezone", timezone);
        canonicalSpec.put("periodField", periodField);
        canonicalSpec.put("periodPreset", periodPreset.name());
        canonicalSpec.put("periodMode", periodMode.name());
        canonicalSpec.put("lockedFilters", lockedFilters);
        canonicalSpec.put("adjustableParams", adjustableParams);
        String provenanceDigest = SchemaHashUtil.sha256Hex(
                HASH_CANONICALIZER.canonicalize(objectMapper.valueToTree(canonicalSpec))
        );

        ResolvedVerifiedAnalyticalRecipe resolved = new ResolvedVerifiedAnalyticalRecipe(
                id,
                title,
                description,
                intent,
                resourceKey,
                normalizedBasePath,
                endpointPath,
                operation,
                field,
                dimensions,
                metrics,
                orderBy,
                limit,
                granularity,
                fillGaps,
                distributionMode,
                bucketCount,
                bucketSize,
                timezone,
                periodField,
                periodPreset,
                periodMode,
                lockedFilters,
                adjustableParams,
                governanceTier,
                domainTags,
                version,
                order,
                provenanceDigest
        );

        validateAgainstStatsRegistry(resolved, statsRegistry, false);
        return resolved;
    }

    private void validateAgainstStatsRegistry(
            ResolvedVerifiedAnalyticalRecipe recipe,
            StatsFieldRegistry statsRegistry,
            boolean requireNonEmptyRegistry
    ) {
        if (statsRegistry == null || statsRegistry.isEmpty()) {
            if (requireNonEmptyRegistry) {
                throw new IllegalStateException(
                        "StatsFieldRegistry is required to execute verified analytical recipe '"
                                + recipe.id() + "' in resource '" + recipe.resourceKey() + "'."
                );
            }
            return;
        }
        List<String> targetFields = new ArrayList<>();
        if (StringUtils.hasText(recipe.field())) {
            targetFields.add(recipe.field());
        }
        for (VerifiedAnalyticalRecipeDescriptor.DimensionSpec dim : recipe.dimensions()) {
            if (StringUtils.hasText(dim.field()) && !targetFields.contains(dim.field())) {
                targetFields.add(dim.field());
            }
        }
        for (String targetField : targetFields) {
            StatsFieldDescriptor descriptor = statsRegistry.resolve(targetField).orElseThrow(() ->
                    new IllegalStateException(
                            "Verified analytical recipe '" + recipe.id() + "' references unregistered stats field '"
                                    + targetField + "' in resource '" + recipe.resourceKey() + "'."
                    )
            );
            switch (recipe.operation()) {
                case GROUP_BY, COMPARISON -> {
                    if (!descriptor.groupByEligible()) {
                        throw new IllegalStateException(
                                "Field '" + targetField + "' is not groupByEligible for recipe '" + recipe.id() + "'."
                        );
                    }
                }
                case TIMESERIES -> {
                    if (!descriptor.timeSeriesEligible()) {
                        throw new IllegalStateException(
                                "Field '" + targetField + "' is not timeSeriesEligible for recipe '" + recipe.id() + "'."
                        );
                    }
                }
                case DISTRIBUTION -> {
                    boolean eligible = recipe.distributionMode() == DistributionMode.HISTOGRAM
                            ? descriptor.distributionHistogramEligible()
                            : descriptor.distributionTermsEligible();
                    if (!eligible) {
                        throw new IllegalStateException(
                                "Field '" + targetField + "' is not eligible for distribution mode "
                                        + recipe.distributionMode() + " in recipe '" + recipe.id() + "'."
                        );
                    }
                }
                case INFERENCE, ANOMALIES -> throw new IllegalStateException(
                        "Verified analytical recipe '" + recipe.id() + "' uses unsupported operation '"
                                + recipe.operation() + "'."
                );
            }
            for (VerifiedAnalyticalRecipeDescriptor.MetricSpec metric : recipe.metrics()) {
                if (!StringUtils.hasText(metric.field())
                        && metric.operation() != StatsMetric.AVG_PER_CYCLE_OCCURRENCE
                        && !descriptor.metrics().isEmpty()
                        && !descriptor.metrics().contains(metric.operation())) {
                    throw new IllegalStateException(
                            "Metric " + metric.operation() + " is not allowed on field '" + targetField
                                    + "' for recipe '" + recipe.id() + "'."
                    );
                }
            }
        }
        if (recipe.operation() == AnalyticsOperation.COMPARISON
                || (recipe.operation() == AnalyticsOperation.GROUP_BY && StringUtils.hasText(recipe.periodField()))) {
            String pField = StringUtils.hasText(recipe.periodField()) ? recipe.periodField() : recipe.field();
            StatsFieldDescriptor periodDescriptor = statsRegistry.resolve(pField).orElseThrow(() ->
                    new IllegalStateException(
                            "Verified analytical recipe '" + recipe.id() + "' references unregistered periodField '"
                                    + pField + "' in resource '" + recipe.resourceKey() + "'."
                    )
            );
            if (!periodDescriptor.timeSeriesEligible()) {
                throw new IllegalStateException(
                        "Period field '" + pField + "' is not timeSeriesEligible for recipe '" + recipe.id() + "'."
                );
            }
        }
        for (VerifiedAnalyticalRecipeDescriptor.MetricSpec metric : recipe.metrics()) {
            if (StringUtils.hasText(metric.field())) {
                StatsFieldDescriptor metricDescriptor = statsRegistry.resolve(metric.field()).orElseThrow(() ->
                        new IllegalStateException(
                                "Metric field '" + metric.field() + "' in recipe '" + recipe.id()
                                        + "' is not registered in StatsFieldRegistry."
                        )
                );
                if (!metricDescriptor.metricFieldEligible()) {
                    throw new IllegalStateException(
                            "Metric field '" + metric.field() + "' in recipe '" + recipe.id()
                                    + "' is not metricFieldEligible."
                    );
                }
                if (metric.operation() == StatsMetric.AVG_PER_CYCLE_OCCURRENCE) {
                    if (!metricDescriptor.supports(StatsMetric.AVG_PER_CYCLE_OCCURRENCE)
                            && !metricDescriptor.supports(StatsMetric.SUM)
                            && !metricDescriptor.supports(StatsMetric.AVG)) {
                        throw new IllegalStateException(
                                "Metric " + metric.operation() + " is not allowed on metric field '" + metric.field()
                                        + "' for recipe '" + recipe.id() + "'."
                        );
                    }
                } else if (!metricDescriptor.metrics().isEmpty()
                        && !metricDescriptor.supports(metric.operation())) {
                    throw new IllegalStateException(
                            "Metric " + metric.operation() + " is not allowed on metric field '" + metric.field()
                                    + "' for recipe '" + recipe.id() + "'."
                    );
                }
            }
        }
    }

    private Object validateAndCoerceParameterValue(
            VerifiedAnalyticalRecipeDescriptor.ParameterSpec spec,
            Object rawValue
    ) {
        String text = String.valueOf(rawValue).trim();
        if (!spec.allowedValues().isEmpty() && !spec.allowedValues().contains(text)) {
            throw new IllegalArgumentException(
                    "Invalid value '" + text + "' for parameter '" + spec.name()
                            + "'. Allowed values: " + spec.allowedValues()
            );
        }
        String type = spec.dataType().toLowerCase(Locale.ROOT);
        Object coerced = switch (type) {
            case "integer", "int", "long" -> {
                long parsed;
                if (rawValue instanceof Byte || rawValue instanceof Short
                        || rawValue instanceof Integer || rawValue instanceof Long) {
                    parsed = ((Number) rawValue).longValue();
                } else if (rawValue instanceof BigDecimal bd) {
                    if (bd.stripTrailingZeros().scale() > 0) {
                        throw new IllegalArgumentException(
                                "Parameter '" + spec.name() + "' expects an integer value, got '" + text + "'."
                        );
                    }
                    parsed = bd.longValueExact();
                } else if (rawValue instanceof Double || rawValue instanceof Float) {
                    double d = ((Number) rawValue).doubleValue();
                    if (!Double.isFinite(d) || Math.floor(d) != d) {
                        throw new IllegalArgumentException(
                                "Parameter '" + spec.name() + "' expects an integer value, got '" + text + "'."
                        );
                    }
                    parsed = (long) d;
                } else {
                    try {
                        parsed = Long.parseLong(text);
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException(
                                "Parameter '" + spec.name() + "' expects an integer value, got '" + text + "'."
                        );
                    }
                }
                if (spec.minValue() != null && parsed < Long.parseLong(spec.minValue())) {
                    throw new IllegalArgumentException("Parameter '" + spec.name() + "' below minValue " + spec.minValue());
                }
                if (spec.maxValue() != null && parsed > Long.parseLong(spec.maxValue())) {
                    throw new IllegalArgumentException("Parameter '" + spec.name() + "' above maxValue " + spec.maxValue());
                }
                yield parsed;
            }
            case "number", "decimal", "double" -> {
                BigDecimal parsed = rawValue instanceof BigDecimal bd ? bd : new BigDecimal(text);
                if (spec.minValue() != null && parsed.compareTo(new BigDecimal(spec.minValue())) < 0) {
                    throw new IllegalArgumentException("Parameter '" + spec.name() + "' below minValue " + spec.minValue());
                }
                if (spec.maxValue() != null && parsed.compareTo(new BigDecimal(spec.maxValue())) > 0) {
                    throw new IllegalArgumentException("Parameter '" + spec.name() + "' above maxValue " + spec.maxValue());
                }
                yield parsed;
            }
            case "boolean", "bool" -> {
                if (rawValue instanceof Boolean b) {
                    yield b;
                }
                if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                    yield Boolean.parseBoolean(text);
                }
                throw new IllegalArgumentException("Parameter '" + spec.name() + "' expects boolean.");
            }
            case "date", "localdate" -> {
                LocalDate parsed = rawValue instanceof LocalDate d ? d : LocalDate.parse(text);
                if (spec.minValue() != null && parsed.isBefore(LocalDate.parse(spec.minValue()))) {
                    throw new IllegalArgumentException("Parameter '" + spec.name() + "' before minValue " + spec.minValue());
                }
                if (spec.maxValue() != null && parsed.isAfter(LocalDate.parse(spec.maxValue()))) {
                    throw new IllegalArgumentException("Parameter '" + spec.name() + "' after maxValue " + spec.maxValue());
                }
                yield parsed;
            }
            case "date-range", "daterange" -> {
                List<?> rawItems;
                if (rawValue instanceof Collection<?> coll) {
                    rawItems = new ArrayList<>(coll);
                } else if (rawValue instanceof String str && str.contains(",")) {
                    rawItems = Arrays.asList(str.split(",", -1));
                } else {
                    throw new IllegalArgumentException(
                            "Parameter '" + spec.name() + "' expects a 2-element ISO date range.");
                }
                if (rawItems.size() != 2) {
                    throw new IllegalArgumentException(
                            "Parameter '" + spec.name() + "' expects exactly 2 ISO dates, got " + rawItems.size());
                }
                LocalDate start = parseDateRangeElement(spec.name(), rawItems.get(0));
                LocalDate end = parseDateRangeElement(spec.name(), rawItems.get(1));
                if (start.isAfter(end)) {
                    throw new IllegalArgumentException(
                            "Parameter '" + spec.name() + "' has inverted date range: " + start + " > " + end);
                }
                if (spec.minValue() != null && start.isBefore(LocalDate.parse(spec.minValue()))) {
                    throw new IllegalArgumentException("Parameter '" + spec.name() + "' before minValue " + spec.minValue());
                }
                if (spec.maxValue() != null && end.isAfter(LocalDate.parse(spec.maxValue()))) {
                    throw new IllegalArgumentException("Parameter '" + spec.name() + "' after maxValue " + spec.maxValue());
                }
                yield List.of(start, end);
            }
            default -> rawValue instanceof String ? text : rawValue;
        };
        return coerced;
    }

    private static LocalDate parseDateRangeElement(String paramName, Object element) {
        if (element instanceof LocalDate d) {
            return d;
        }
        String str = element == null ? "" : String.valueOf(element).trim();
        if (str.isEmpty()) {
            throw new IllegalArgumentException("Parameter '" + paramName + "' contains a blank date element.");
        }
        try {
            return LocalDate.parse(str);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException(
                    "Parameter '" + paramName + "' expects ISO dates (yyyy-MM-dd), got '" + str + "'.", ex);
        }
    }

    private <FD extends GenericFilterDTO> FD buildFilterDto(
            Class<FD> filterDtoClass,
            Map<String, Field> filterFields,
            Map<String, Object> effectiveFilterMap
    ) {
        if (filterDtoClass == null) {
            return null;
        }
        Map<String, Object> compatibleMap = new LinkedHashMap<>();
        effectiveFilterMap.forEach((key, value) -> {
            if (filterFields.containsKey(key)) {
                compatibleMap.put(key, value);
            }
        });
        if (compatibleMap.isEmpty()) {
            return instantiateFilterDto(filterDtoClass);
        }
        return objectMapper.convertValue(compatibleMap, filterDtoClass);
    }

    private <T> T instantiateFilterDto(Class<T> filterDtoClass) {
        try {
            Constructor<T> ctor = filterDtoClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception ex) {
            try {
                return objectMapper.convertValue(Map.of(), filterDtoClass);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static boolean isReservedQueryParam(VerifiedAnalyticalRecipeDescriptor.ParameterSpec spec) {
        return !StringUtils.hasText(spec.filterField()) && RESERVED_QUERY_PARAMS.contains(spec.name());
    }

    private static String resolveTargetFilterField(VerifiedAnalyticalRecipeDescriptor.ParameterSpec spec) {
        return StringUtils.hasText(spec.filterField()) ? spec.filterField().trim() : spec.name().trim();
    }

    private List<ResolvedVerifiedAnalyticalRecipe> collectRecipesFromCandidates(List<ControllerCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ResolvedVerifiedAnalyticalRecipe> combined = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (ControllerCandidate candidate : candidates) {
            for (ResolvedVerifiedAnalyticalRecipe recipe : resolveByController(
                    candidate.controllerType(), candidate.controllerInstance())) {
                if (seenIds.add(candidate.resourceKey() + "::" + recipe.id())) {
                    combined.add(recipe);
                }
            }
        }
        return List.copyOf(combined);
    }

    private List<ControllerCandidate> discoverCandidates() {
        List<ControllerCandidate> snapshot = this.cachedCandidates;
        if (snapshot != null) {
            return snapshot;
        }
        Map<Class<?>, ControllerCandidate> candidates = new LinkedHashMap<>();
        if (handlerMapping != null) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
                HandlerMethod hm = entry.getValue();
                Object bean = hm.getBean();
                Object beanInstance = null;
                Class<?> beanType = hm.getBeanType();
                if (bean != null && !(bean instanceof String)) {
                    beanInstance = unwrapTarget(bean);
                    beanType = AopProxyUtils.ultimateTargetClass(bean);
                } else if (bean instanceof String beanName && applicationContext != null
                        && applicationContext.containsBean(beanName)) {
                    Object ctxBean = applicationContext.getBean(beanName);
                    beanInstance = unwrapTarget(ctxBean);
                    beanType = AopProxyUtils.ultimateTargetClass(ctxBean);
                }
                Class<?> userClass = ClassUtils.getUserClass(beanType);
                ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(userClass, ApiResource.class);
                if (apiResource != null || hasRecipeDeclaration(userClass)) {
                    candidates.putIfAbsent(userClass, new ControllerCandidate(
                            userClass,
                            beanInstance,
                            apiResource,
                            resolveResourceKey(apiResource, userClass),
                            primaryResourcePath(apiResource, userClass)
                    ));
                }
            }
        }
        if (applicationContext != null) {
            for (String beanName : applicationContext.getBeanDefinitionNames()) {
                try {
                    Class<?> beanType = applicationContext.getType(beanName);
                    if (beanType == null) {
                        continue;
                    }
                    Class<?> userClass = ClassUtils.getUserClass(beanType);
                    ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(userClass, ApiResource.class);
                    if (apiResource != null || hasRecipeDeclaration(userClass)) {
                        Object instance = unwrapTarget(applicationContext.getBean(beanName));
                        candidates.putIfAbsent(userClass, new ControllerCandidate(
                                userClass,
                                instance,
                                apiResource,
                                resolveResourceKey(apiResource, userClass),
                                primaryResourcePath(apiResource, userClass)
                        ));
                    }
                } catch (Exception ignored) {
                    // Ignora beans que nao podem ser resolvidos durante scan.
                }
            }
        }
        List<ControllerCandidate> result = List.copyOf(candidates.values());
        if (!result.isEmpty()) {
            this.cachedCandidates = result;
        }
        return result;
    }

    private static boolean hasRecipeDeclaration(Class<?> controllerType) {
        if (controllerType == null) {
            return false;
        }
        if (AbstractResourceQueryController.class.isAssignableFrom(controllerType)) {
            return true;
        }
        if (!AnnotatedElementUtils.findMergedRepeatableAnnotations(controllerType, VerifiedAnalyticalRecipe.class).isEmpty()) {
            return true;
        }
        for (Method m : controllerType.getDeclaredMethods()) {
            if (!AnnotatedElementUtils.findMergedRepeatableAnnotations(m, VerifiedAnalyticalRecipe.class).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Object resolveControllerBean(Class<?> controllerType) {
        if (applicationContext == null || controllerType == null) {
            return null;
        }
        try {
            Map<String, ?> beans = applicationContext.getBeansOfType(controllerType);
            if (!beans.isEmpty()) {
                return unwrapTarget(beans.values().iterator().next());
            }
        } catch (Exception ignored) {
            // fallback
        }
        return null;
    }

    private static StatsFieldRegistry resolveControllerStatsRegistry(Object controllerInstance) {
        if (controllerInstance instanceof AbstractResourceQueryController<?, ?, ?> qCtrl) {
            try {
                return qCtrl.resolveStatsFieldRegistry();
            } catch (Exception ignored) {
                return StatsFieldRegistry.empty();
            }
        }
        return StatsFieldRegistry.empty();
    }

    private static List<VerifiedAnalyticalRecipeDescriptor.DimensionSpec> parseDimensionSpecs(String[] rawDimensions) {
        if (rawDimensions == null || rawDimensions.length == 0) {
            return List.of();
        }
        List<VerifiedAnalyticalRecipeDescriptor.DimensionSpec> specs = new ArrayList<>(rawDimensions.length);
        for (String raw : rawDimensions) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String trimmed = raw.trim();
            int sep = trimmed.indexOf(':');
            if (sep > 0 && sep < trimmed.length() - 1) {
                String fieldName = trimmed.substring(0, sep).trim();
                String extractionName = trimmed.substring(sep + 1).trim().toUpperCase(Locale.ROOT);
                StatsDimensionExtraction extraction = StatsDimensionExtraction.valueOf(extractionName);
                specs.add(new VerifiedAnalyticalRecipeDescriptor.DimensionSpec(fieldName, extraction, null));
            } else {
                specs.add(new VerifiedAnalyticalRecipeDescriptor.DimensionSpec(trimmed, StatsDimensionExtraction.NONE, null));
            }
        }
        return List.copyOf(specs);
    }

    private static Object unwrapTarget(Object candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            if (AopUtils.isAopProxy(candidate)) {
                return AopProxyUtils.getSingletonTarget(candidate);
            }
        } catch (Exception ignored) {
            // fallback
        }
        return candidate;
    }

    private static String resolveResourceKey(ApiResource resource, Class<?> controllerType) {
        if (resource != null && StringUtils.hasText(resource.resourceKey())) {
            return resource.resourceKey().trim();
        }
        String primaryPath = primaryResourcePath(resource, controllerType);
        if (!StringUtils.hasText(primaryPath) || "/".equals(primaryPath)) {
            return "";
        }
        String trimmed = primaryPath.replaceAll("^/+|/+$", "");
        if (trimmed.startsWith("api/")) {
            trimmed = trimmed.substring(4);
        }
        return trimmed.replace('/', '.');
    }

    private static String primaryResourcePath(ApiResource resource, Class<?> controllerType) {
        if (resource != null) {
            if (resource.value().length > 0 && StringUtils.hasText(resource.value()[0])) {
                return normalizePath(resource.value()[0]);
            }
            if (resource.path().length > 0 && StringUtils.hasText(resource.path()[0])) {
                return normalizePath(resource.path()[0]);
            }
        }
        RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(controllerType, RequestMapping.class);
        if (rm != null) {
            if (rm.value().length > 0 && StringUtils.hasText(rm.value()[0])) {
                return normalizePath(rm.value()[0]);
            }
            if (rm.path().length > 0 && StringUtils.hasText(rm.path()[0])) {
                return normalizePath(rm.path()[0]);
            }
        }
        return "";
    }

    private static boolean matchesPath(ApiResource resource, String expectedPath) {
        if (resource == null) {
            return false;
        }
        return Arrays.stream(resource.value()).anyMatch(p -> normalizePath(p).equals(expectedPath))
                || Arrays.stream(resource.path()).anyMatch(p -> normalizePath(p).equals(expectedPath));
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.trim().replaceAll("/+$", "");
        return normalized.isBlank() ? "/" : normalized;
    }

    public record CompiledRecipeExecution<FD extends GenericFilterDTO>(
            VerifiedRecipeProvenance provenance,
            GroupByStatsRequest<FD> groupByRequest,
            TimeSeriesStatsRequest<FD> timeSeriesRequest,
            DistributionStatsRequest<FD> distributionRequest,
            ComparisonStatsRequest<FD> comparisonRequest
    ) { }

    private record ControllerCandidate(
            Class<?> controllerType,
            Object controllerInstance,
            ApiResource apiResource,
            String resourceKey,
            String resourcePath
    ) { }

    private record OrderedRecipeEntry(int order, int index, ResolvedVerifiedAnalyticalRecipe recipe) { }
}
