package org.praxisplatform.uischema.service.base;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecification;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.mapper.base.OptionMapper;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.options.OptionSourceByIdsRequest;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceEligibility;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.UnknownOptionSourceException;
import org.praxisplatform.uischema.options.service.OptionSourceOperation;
import org.praxisplatform.uischema.options.service.OptionSourceQueryExecutor;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn;
import org.praxisplatform.uischema.stats.StatsEligibility;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsProperties;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodResolver;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsCapability;
import org.praxisplatform.uischema.stats.dto.ComparisonBucket;
import org.praxisplatform.uischema.stats.dto.ComparisonMetricValue;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.ComparisonPeriodWindow;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.ResolvedComparisonPeriod;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.stats.service.ResolvedStatsMetric;
import org.praxisplatform.uischema.stats.service.StatsQueryExecutor;
import org.praxisplatform.uischema.capability.ResourceStructuralCapabilities;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.time.Clock;

/**
 * Base query-only do novo core resource-oriented.
 *
 * <p>
 * Esta hierarquia concentra a superficie de leitura, options, option-sources e stats sem carregar
 * semantica de escrita. Recursos read-only nascem diretamente daqui; recursos mutantes sobem para
 * {@link AbstractBaseResourceService} apenas quando realmente precisam de create/update/delete.
 * </p>
 */
public abstract class AbstractBaseQueryResourceService<
        E,
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO
> implements BaseResourceQueryService<ResponseDTO, ID, FilterDTO> {

    @Override
    public ResourceStructuralCapabilities getStructuralCapabilities() {
        StatsProperties properties = statsProperties != null ? statsProperties : StatsProperties.defaults();
        StatsFieldRegistry registry = getStatsFieldRegistry() == null
                ? StatsFieldRegistry.empty()
                : getStatsFieldRegistry();
        boolean statsInfrastructure = properties.enabled() && statsQueryExecutor != null && statsEligibility != null;
        boolean groupBy = statsInfrastructure
                && getGroupByStatsSupportMode() != StatsSupportMode.DISABLED
                && registry.descriptors().stream().anyMatch(StatsFieldDescriptor::groupByEligible);
        boolean timeSeries = statsInfrastructure
                && getTimeSeriesStatsSupportMode() != StatsSupportMode.DISABLED
                && registry.descriptors().stream().anyMatch(StatsFieldDescriptor::timeSeriesEligible);
        boolean distribution = statsInfrastructure
                && getDistributionStatsSupportMode() != StatsSupportMode.DISABLED
                && registry.descriptors().stream().anyMatch(descriptor ->
                        descriptor.distributionTermsEligible() || descriptor.distributionHistogramEligible());
        boolean comparison = statsInfrastructure
                && getComparisonStatsSupportMode() != StatsSupportMode.DISABLED
                && groupBy
                && timeSeries;
        boolean optionSources = optionSourceQueryExecutor != null
                && optionSourceEligibility != null
                && getOptionSourceRegistry().containsAny(getEntityClass());
        boolean export = supportsCollectionExport();
        return new ResourceStructuralCapabilities(
                true,
                optionSources,
                groupBy,
                timeSeries,
                distribution,
                comparison,
                export,
                statsInfrastructure
                        ? StatsCapability.from(
                                registry,
                                groupBy ? StatsSupportMode.AUTO : StatsSupportMode.DISABLED,
                                timeSeries ? StatsSupportMode.AUTO : StatsSupportMode.DISABLED,
                                distribution ? StatsSupportMode.AUTO : StatsSupportMode.DISABLED
                        )
                        : StatsCapability.empty(),
                getCollectionExportCapability().orElse(null)
        );
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    private StatsQueryExecutor statsQueryExecutor;

    @Autowired(required = false)
    private StatsEligibility statsEligibility;

    @Autowired(required = false)
    private StatsProperties statsProperties;

    @Autowired(required = false)
    private Clock statsClock;

    @Autowired(required = false)
    private ObjectProvider<OptionSourceRegistry> optionSourceRegistryProvider;

    @Autowired(required = false)
    private OptionSourceQueryExecutor optionSourceQueryExecutor;

    @Autowired(required = false)
    private OptionSourceEligibility optionSourceEligibility;

    private final BaseCrudRepository<E, ID> repository;
    private final GenericSpecificationsBuilder<E> specificationsBuilder;
    private final Class<E> entityClass;

    protected AbstractBaseQueryResourceService(
            BaseCrudRepository<E, ID> repository,
            GenericSpecificationsBuilder<E> specificationsBuilder,
            Class<E> entityClass
    ) {
        this.repository = repository;
        this.specificationsBuilder = specificationsBuilder;
        this.entityClass = entityClass;
    }

    protected AbstractBaseQueryResourceService(BaseCrudRepository<E, ID> repository, Class<E> entityClass) {
        this(repository, new GenericSpecificationsBuilder<>(), entityClass);
    }

    protected abstract ResourceMapper<E, ResponseDTO, ?, ?, ID> getResourceMapper();

    public BaseCrudRepository<E, ID> getRepository() {
        return repository;
    }

    public GenericSpecificationsBuilder<E> getSpecificationsBuilder() {
        return specificationsBuilder;
    }

    public Class<E> getEntityClass() {
        return entityClass;
    }

    protected EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public String getIdFieldName() {
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    return field.getName();
                }
            }
            current = current.getSuperclass();
        }
        return "id";
    }

    @Override
    public Sort getDefaultSort() {
        List<Field> sortedFields = getAllFields(getEntityClass()).stream()
                .filter(field -> field.isAnnotationPresent(DefaultSortColumn.class))
                .sorted(Comparator.comparingInt(field -> field.getAnnotation(DefaultSortColumn.class).priority()))
                .toList();

        if (sortedFields.isEmpty()) {
            return Sort.unsorted();
        }

        List<Sort.Order> orders = sortedFields.stream()
                .map(field -> {
                    DefaultSortColumn annotation = field.getAnnotation(DefaultSortColumn.class);
                    return new Sort.Order(
                            annotation.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                            field.getName()
                    );
                })
                .toList();

        return Sort.by(orders);
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.empty();
    }

    @Override
    public StatsSupportMode getGroupByStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    @Override
    public StatsSupportMode getTimeSeriesStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    @Override
    public StatsSupportMode getDistributionStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    public StatsSupportMode getComparisonStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return StatsFieldRegistry.empty();
    }

    @Override
    public OptionSourceRegistry getOptionSourceRegistry() {
        OptionSourceRegistry declaredRegistry = getDeclaredOptionSourceRegistry();
        if (declaredRegistry != null && !declaredRegistry.isEmpty()) {
            return declaredRegistry;
        }
        OptionSourceRegistry sharedRegistry = optionSourceRegistryProvider != null
                ? optionSourceRegistryProvider.getIfAvailable()
                : null;
        return sharedRegistry != null ? sharedRegistry : OptionSourceRegistry.empty();
    }

    public OptionSourceRegistry getDeclaredOptionSourceRegistry() {
        return OptionSourceRegistry.empty();
    }

    @Override
    public boolean hasOptionSource(String sourceKey) {
        return getOptionSourceRegistry().contains(getEntityClass(), sourceKey);
    }

    @Override
    public OptionSourceDescriptor resolveOptionSource(String sourceKey) {
        return getOptionSourceRegistry()
                .resolve(getEntityClass(), sourceKey)
                .orElseThrow(() -> new UnknownOptionSourceException(getEntityClass(), sourceKey));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDTO findById(ID id) {
        return getResourceMapper().toResponse(findEntityById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponseDTO> findAll() {
        return findAllEntities().stream()
                .map(getResourceMapper()::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponseDTO> findAllById(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<ID, E> byId = findEntitiesById(ids).stream()
                .collect(Collectors.toMap(this::extractId, Function.identity(), (left, right) -> left));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(getResourceMapper()::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseDTO> filter(FilterDTO filter, Pageable pageable, Collection<ID> includeIds) {
        return filterEntitiesWithIncludeIds(filter, pageable, includeIds).map(getResourceMapper()::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPage<ResponseDTO> filterByCursor(FilterDTO filter, Sort sort, String after, String before, int size) {
        CursorPage<E> page = filterEntitiesByCursor(filter, sort, after, before, size);
        return new CursorPage<>(
                page.content().stream().map(getResourceMapper()::toResponse).toList(),
                page.next(),
                page.prev(),
                page.size()
        );
    }

    @Override
    public OptionalLong locate(FilterDTO filter, Sort sort, ID id) {
        return OptionalLong.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OptionDTO<ID>> filterOptions(FilterDTO filter, Pageable pageable) {
        return filterEntities(filter, pageable).map(getOptionMapper()::toOption);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OptionDTO<ID>> byIdsOptions(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<OptionDTO<ID>> list = findEntitiesById(ids).stream()
                .map(getOptionMapper()::toOption)
                .toList();
        Map<ID, OptionDTO<ID>> byId = list.stream()
                .collect(Collectors.toMap(OptionDTO::id, Function.identity(), (left, right) -> left));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OptionDTO<Object>> filterOptionSourceOptions(
            String sourceKey,
            OptionSourceFilterRequest<FilterDTO> request,
            Pageable pageable
    ) {
        return filterOptionSourceOptions(sourceKey, request, pageable, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OptionDTO<Object>> filterOptionSourceOptions(
            String sourceKey,
            OptionSourceFilterRequest<FilterDTO> request,
            Pageable pageable,
            Object providerFilterPayload
    ) {
        if (optionSourceQueryExecutor == null) {
            resolveOptionSource(sourceKey);
            throw new UnsupportedOperationException("Option source options not implemented: " + sourceKey);
        }
        OptionSourceDescriptor descriptor = resolveEffectiveOptionSource(sourceKey);
        FilterDTO effectiveFilter = normalizeOptionSourceFilter(
                descriptor,
                OptionSourceOperation.FILTER,
                sanitizeFilter(request == null ? null : request.filter(), descriptor)
        );
        GenericSpecification<E> specification = effectiveFilter == null
                ? null
                : getSpecificationsBuilder().buildSpecification(effectiveFilter, pageable);
        return optionSourceQueryExecutor.filterOptions(
                entityManager,
                entityClass,
                specification == null ? null : specification.spec(),
                providerFilterPayload == null ? effectiveFilter : providerFilterPayload,
                descriptor,
                request == null ? null : request.search(),
                request == null ? null : request.searchStrategy(),
                request == null ? List.of() : request.filters(),
                request == null ? null : request.sort(),
                pageable,
                request == null ? List.of() : request.includeIds()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<OptionDTO<Object>> byIdsOptionSourceOptions(String sourceKey, Collection<Object> ids) {
        if (optionSourceQueryExecutor == null) {
            resolveOptionSource(sourceKey);
            throw new UnsupportedOperationException("Option source by-ids not implemented: " + sourceKey);
        }
        OptionSourceDescriptor descriptor = resolveEffectiveOptionSource(sourceKey);
        FilterDTO effectiveFilter = normalizeOptionSourceFilter(descriptor, OptionSourceOperation.BY_IDS, null);
        GenericSpecification<E> specification = effectiveFilter == null
                ? null
                : getSpecificationsBuilder().buildSpecification(effectiveFilter, Pageable.unpaged());
        return optionSourceQueryExecutor.byIdsOptions(
                entityManager,
                entityClass,
                specification == null ? null : specification.spec(),
                effectiveFilter,
                descriptor,
                List.of(),
                ids
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<OptionDTO<Object>> byIdsOptionSourceOptions(
            String sourceKey,
            OptionSourceByIdsRequest<FilterDTO> request
    ) {
        return byIdsOptionSourceOptions(sourceKey, request, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OptionDTO<Object>> byIdsOptionSourceOptions(
            String sourceKey,
            OptionSourceByIdsRequest<FilterDTO> request,
            Object providerFilterPayload
    ) {
        if (optionSourceQueryExecutor == null) {
            resolveOptionSource(sourceKey);
            throw new UnsupportedOperationException("Option source by-ids not implemented: " + sourceKey);
        }
        OptionSourceDescriptor descriptor = resolveEffectiveOptionSource(sourceKey);
        FilterDTO effectiveFilter = normalizeOptionSourceFilter(
                descriptor,
                OptionSourceOperation.BY_IDS,
                sanitizeFilter(request == null ? null : request.filter(), descriptor)
        );
        GenericSpecification<E> specification = effectiveFilter == null
                ? null
                : getSpecificationsBuilder().buildSpecification(effectiveFilter, Pageable.unpaged());
        return optionSourceQueryExecutor.byIdsOptions(
                entityManager,
                entityClass,
                specification == null ? null : specification.spec(),
                providerFilterPayload == null ? effectiveFilter : providerFilterPayload,
                descriptor,
                request == null ? List.of() : request.filters(),
                request == null ? List.of() : request.ids()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public GroupByStatsResponse groupByStats(GroupByStatsRequest<FilterDTO> request) {
        StatsProperties properties = statsProperties != null ? statsProperties : StatsProperties.defaults();
        if (!properties.enabled() || getGroupByStatsSupportMode() == StatsSupportMode.DISABLED
                || statsQueryExecutor == null || statsEligibility == null) {
            throw new UnsupportedOperationException("Group-by stats not implemented");
        }

        StatsFieldDescriptor descriptor = statsEligibility.validateGroupBy(
                request,
                getStatsFieldRegistry(),
                properties.maxBuckets()
        );
        List<ResolvedStatsMetric> resolvedMetrics = resolveMetrics(request.effectiveMetrics(), "group-by");
        GenericSpecification<E> specification = getSpecificationsBuilder().buildSpecification(request.filter(), Pageable.unpaged());
        return statsQueryExecutor.executeGroupBy(
                entityManager,
                entityClass,
                specification.spec(),
                descriptor,
                resolvedMetrics,
                request,
                properties.maxBuckets()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<FilterDTO> request) {
        StatsProperties properties = statsProperties != null ? statsProperties : StatsProperties.defaults();
        if (!properties.enabled() || getTimeSeriesStatsSupportMode() == StatsSupportMode.DISABLED
                || statsQueryExecutor == null || statsEligibility == null) {
            throw new UnsupportedOperationException("Time-series stats not implemented");
        }

        StatsFieldDescriptor descriptor = statsEligibility.validateTimeSeries(
                request,
                getStatsFieldRegistry(),
                properties.maxSeriesPoints()
        );
        List<ResolvedStatsMetric> resolvedMetrics = resolveMetrics(request.effectiveMetrics(), "time-series");
        GenericSpecification<E> specification = getSpecificationsBuilder().buildSpecification(request.filter(), Pageable.unpaged());
        return statsQueryExecutor.executeTimeSeries(
                entityManager,
                entityClass,
                specification.spec(),
                descriptor,
                resolvedMetrics,
                request,
                properties.maxSeriesPoints()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public DistributionStatsResponse distributionStats(DistributionStatsRequest<FilterDTO> request) {
        StatsProperties properties = statsProperties != null ? statsProperties : StatsProperties.defaults();
        if (!properties.enabled() || getDistributionStatsSupportMode() == StatsSupportMode.DISABLED
                || statsQueryExecutor == null || statsEligibility == null) {
            throw new UnsupportedOperationException("Distribution stats not implemented");
        }

        StatsFieldDescriptor descriptor = statsEligibility.validateDistribution(
                request,
                getStatsFieldRegistry(),
                properties.maxBuckets()
        );
        StatsFieldDescriptor metricDescriptor = request.mode() == org.praxisplatform.uischema.stats.DistributionMode.TERMS
                ? statsEligibility.resolveMetricField(request.metric(), getStatsFieldRegistry(), "distribution")
                : null;
        GenericSpecification<E> specification = getSpecificationsBuilder().buildSpecification(request.filter(), Pageable.unpaged());
        return statsQueryExecutor.executeDistribution(
                entityManager,
                entityClass,
                specification.spec(),
                descriptor,
                metricDescriptor,
                request,
                properties.maxBuckets()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<FilterDTO> request) {
        StatsProperties properties = statsProperties != null ? statsProperties : StatsProperties.defaults();
        if (!properties.enabled() || getComparisonStatsSupportMode() == StatsSupportMode.DISABLED
                || statsQueryExecutor == null || statsEligibility == null) {
            throw new UnsupportedOperationException("Comparison stats not implemented");
        }
        StatsFieldDescriptor descriptor = statsEligibility.validateComparison(request, getStatsFieldRegistry(), properties.maxBuckets());
        StatsFieldDescriptor periodDescriptor = getStatsFieldRegistry().resolve(request.periodField()).orElseThrow();
        ResolvedComparisonPeriod period = new ComparisonPeriodResolver(statsClock == null ? Clock.systemUTC() : statsClock).resolve(request.period());
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(period.currentFrom(), period.currentTo()) + 1;
        if (periodDays > properties.maxComparisonPeriodDays()) {
            throw new IllegalArgumentException("Maximum comparison period exceeded: " + properties.maxComparisonPeriodDays() + " days.");
        }
        List<ResolvedStatsMetric> metrics = resolveMetrics(request.metrics(), "comparison");
        GenericSpecification<E> base = getSpecificationsBuilder().buildSpecification(request.filter(), Pageable.unpaged());
        int candidateLimit = properties.maxComparisonCandidates();
        GroupByStatsRequest<FilterDTO> groupRequest = new GroupByStatsRequest<>(
                request.filter(), request.field(), request.metrics().get(0), candidateLimit + 1,
                StatsBucketOrder.KEY_ASC, request.metrics()
        );
        GroupByStatsResponse current = statsQueryExecutor.executeGroupBy(entityManager, entityClass,
                base.spec().and(ComparisonPeriodSpecifications.forPeriod(periodDescriptor.keyPropertyPath(), period.currentFrom(), period.currentTo(), period.timezone())),
                descriptor, metrics, groupRequest, candidateLimit + 1);
        GroupByStatsResponse previous = statsQueryExecutor.executeGroupBy(entityManager, entityClass,
                base.spec().and(ComparisonPeriodSpecifications.forPeriod(periodDescriptor.keyPropertyPath(), period.previousFrom(), period.previousTo(), period.timezone())),
                descriptor, metrics, groupRequest, candidateLimit + 1);
        if (current.buckets().size() > candidateLimit || previous.buckets().size() > candidateLimit) {
            throw new IllegalArgumentException("Comparison candidate limit exceeded: " + candidateLimit);
        }
        return new ComparisonStatsResponse(request.field(), request.periodField(), request.metrics(),
                new ComparisonPeriodWindow(period.currentFrom(), period.currentTo(), period.timezone()),
                new ComparisonPeriodWindow(period.previousFrom(), period.previousTo(), period.timezone()),
                mergeComparisonBuckets(current.buckets(), previous.buckets(), request.metrics(), request.orderBy(), request.limit(), properties.maxBuckets()));
    }

    private List<ComparisonBucket> mergeComparisonBuckets(List<GroupByBucket> current, List<GroupByBucket> previous,
                                                            List<StatsMetricRequest> metrics, StatsBucketOrder order,
                                                            Integer requestedLimit, int maxBuckets) {
        Map<Object, GroupByBucket> currentByKey = current.stream().collect(Collectors.toMap(GroupByBucket::key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Object, GroupByBucket> previousByKey = previous.stream().collect(Collectors.toMap(GroupByBucket::key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<Object> keys = new LinkedHashSet<>(); keys.addAll(currentByKey.keySet()); keys.addAll(previousByKey.keySet());
        List<ComparisonBucket> buckets = keys.stream().map(key -> {
            GroupByBucket now = currentByKey.get(key); GroupByBucket before = previousByKey.get(key);
            Map<String, ComparisonMetricValue> values = new LinkedHashMap<>();
            for (StatsMetricRequest metric : metrics) {
                String alias = metric.effectiveAlias();
                Number nowValue = value(now, alias); Number beforeValue = value(before, alias);
                BigDecimal currentValue = decimal(nowValue); BigDecimal previousValue = decimal(beforeValue);
                BigDecimal delta = currentValue.subtract(previousValue);
                boolean baselineMissing = previousValue.signum() == 0;
                values.put(alias, new ComparisonMetricValue(currentValue, previousValue, delta,
                        baselineMissing ? null : delta.divide(previousValue, 8, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue(), baselineMissing));
            }
            String label = now != null ? now.label() : before.label();
            return new ComparisonBucket(key, label, Map.copyOf(values));
        }).sorted(comparisonOrder(metrics.get(0).effectiveAlias(), order)).toList();
        int limit = requestedLimit == null ? maxBuckets : Math.min(requestedLimit, maxBuckets);
        return buckets.stream().limit(limit).toList();
    }

    private Number value(GroupByBucket bucket, String alias) {
        if (bucket == null) return BigDecimal.ZERO;
        if (bucket.values() != null && bucket.values().containsKey(alias)) return bucket.values().get(alias);
        return bucket.value() == null ? BigDecimal.ZERO : bucket.value();
    }
    private BigDecimal decimal(Number value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    private Comparator<ComparisonBucket> comparisonOrder(String alias, StatsBucketOrder order) {
        Comparator<ComparisonBucket> key = (left, right) -> compareBucketKeys(left.key(), right.key());
        Comparator<ComparisonBucket> value = Comparator.comparing(bucket -> decimal(bucket.values().get(alias).current()));
        if (order == StatsBucketOrder.KEY_DESC) return key.reversed();
        if (order == StatsBucketOrder.VALUE_ASC) return value.thenComparing(key);
        if (order == StatsBucketOrder.VALUE_DESC) return value.reversed().thenComparing(key);
        return key;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareBucketKeys(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        if (left instanceof Number && right instanceof Number) return decimal((Number) left).compareTo(decimal((Number) right));
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) return comparable.compareTo(right);
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    protected CursorPage<E> filterEntitiesByCursor(FilterDTO filter, Sort sort, String after, String before, int size) {
        throw new UnsupportedOperationException("Cursor pagination not implemented");
    }

    protected ID extractId(E entity) {
        return getResourceMapper().extractId(entity);
    }

    protected OptionMapper<E, ID> getOptionMapper() {
        return entity -> new OptionDTO<>(extractId(entity), computeOptionLabel(entity), null);
    }

    protected String computeOptionLabel(E entity) {
        if (entity == null) {
            return null;
        }

        Class<?> clazz = entity.getClass();
        for (Method method : getAllMethods(clazz)) {
            if (hasOptionLabelAnnotation(method)) {
                Object value = invokeSilently(entity, method);
                String stringValue = toNonBlankString(value);
                if (stringValue != null) {
                    return stringValue;
                }
            }
        }

        for (Field field : getAllFields(clazz)) {
            if (hasOptionLabelAnnotation(field)) {
                Object value = getFieldValueSilently(entity, field);
                String stringValue = toNonBlankString(value);
                if (stringValue != null) {
                    return stringValue;
                }
            }
        }

        for (String getterName : List.of("getLabel", "getNomeCompleto", "getNome", "getDescricao", "getTitle")) {
            Method method = findMethodIgnoreCase(clazz, getterName);
            if (method == null) {
                continue;
            }
            Object value = invokeSilently(entity, method);
            String stringValue = toNonBlankString(value);
            if (stringValue != null) {
                return stringValue;
            }
        }

        return String.valueOf(extractId(entity));
    }

    protected EntityNotFoundException getNotFoundException() {
        return new EntityNotFoundException("Registro nao encontrado");
    }

    protected E findEntityById(ID id) {
        return repository.findById(id).orElseThrow(this::getNotFoundException);
    }

    protected List<E> findAllEntities() {
        return repository.findAll(getDefaultSort());
    }

    protected List<E> findEntitiesById(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return repository.findAllById(ids);
    }

    /**
     * Resolves the mandatory server-side row scope for resource filter queries.
     *
     * <p>The default is explicitly unrestricted so existing global resources preserve their
     * behavior. Applications with row-level authorization should override this hook and return a
     * restricted or denied scope from authenticated server context. A client {@code FilterDTO},
     * {@code includeIds}, aliases, or textual matching must never decide this scope.</p>
     */
    protected ResourceFilterAccessScope<E> resolveResourceFilterAccessScope() {
        return ResourceFilterAccessScope.unrestricted();
    }

    protected Page<E> filterEntities(FilterDTO filterDTO, Pageable pageable) {
        ResourceFilterQuery<E> query = resolveResourceFilterQuery(filterDTO, pageable);
        return repository.findAll(query.effectiveSpecification(), query.pageable());
    }

    protected Page<E> filterEntitiesWithIncludeIds(FilterDTO filter, Pageable pageable, Collection<ID> includeIds) {
        ResourceFilterQuery<E> query = resolveResourceFilterQuery(filter, pageable);
        Page<E> page = repository.findAll(query.effectiveSpecification(), query.pageable());
        if (includeIds == null || includeIds.isEmpty()) {
            return page;
        }

        Set<ID> orderedIds = new LinkedHashSet<>(includeIds);
        Map<ID, E> ensured = new HashMap<>();
        List<E> remaining = new ArrayList<>();

        for (E entity : page.getContent()) {
            ID entityId = extractId(entity);
            if (orderedIds.contains(entityId)) {
                ensured.put(entityId, entity);
            } else {
                remaining.add(entity);
            }
        }

        if (pageable.getPageNumber() != 0) {
            return new PageImpl<>(remaining, pageable, page.getTotalElements());
        }

        List<ID> missing = orderedIds.stream().filter(id -> !ensured.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            findEntitiesByIdWithinAccessScope(missing, query.accessSpecification())
                    .forEach(entity -> ensured.put(extractId(entity), entity));
        }

        List<E> merged = new ArrayList<>(orderedIds.size() + remaining.size());
        orderedIds.forEach(id -> {
            E entity = ensured.get(id);
            if (entity != null) {
                merged.add(entity);
            }
        });
        merged.addAll(remaining);

        return new PageImpl<>(merged, pageable, page.getTotalElements());
    }

    private ResourceFilterQuery<E> resolveResourceFilterQuery(FilterDTO filter, Pageable pageable) {
        Pageable sortedPageable = pageable;
        if (!pageable.getSort().isSorted()) {
            sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), getDefaultSort());
        }

        GenericSpecification<E> functional = getSpecificationsBuilder().buildSpecification(filter, sortedPageable);
        ResourceFilterAccessScope<E> accessScope = Objects.requireNonNull(
                resolveResourceFilterAccessScope(),
                "resolveResourceFilterAccessScope() must return an explicit scope"
        );
        Specification<E> access = accessScope.specification();
        return new ResourceFilterQuery<>(access, access.and(functional.spec()), functional.pageable());
    }

    private List<E> findEntitiesByIdWithinAccessScope(Collection<ID> ids, Specification<E> accessSpecification) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Specification<E> requestedIds = (root, query, criteriaBuilder) -> root.get(getIdFieldName()).in(ids);
        return repository.findAll(accessSpecification.and(requestedIds));
    }

    private record ResourceFilterQuery<E>(
            Specification<E> accessSpecification,
            Specification<E> effectiveSpecification,
            Pageable pageable
    ) {
    }

    protected FilterDTO sanitizeFilter(FilterDTO filter, OptionSourceDescriptor descriptor) {
        if (filter == null || descriptor == null || !descriptor.policy().excludeSelfField()) {
            return filter;
        }
        try {
            var constructor = filter.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            FilterDTO copy = (FilterDTO) constructor.newInstance();
            for (Field field : getAllFields(filter.getClass())) {
                field.setAccessible(true);
                Object value = field.get(filter);
                if (field.getName().equals(descriptor.effectiveFilterField())) {
                    value = null;
                }
                field.set(copy, value);
            }
            return copy;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("Option source filter could not be sanitized: " + descriptor.key(), ex);
        }
    }

    /**
     * Applies resource-owned constraints to every execution path of a derived option source.
     *
     * <p>The option-source descriptor describes discovery and query capabilities, but it must not
     * become the authority for row-level access. A resource with a principal-dependent scope can
     * override this hook to intersect the supplied filter (or create one for GET by-ids) before
     * the JPA specification is derived.</p>
     */
    protected FilterDTO normalizeOptionSourceFilter(
            OptionSourceDescriptor descriptor,
            OptionSourceOperation operation,
            FilterDTO filter
    ) {
        return filter;
    }

    protected OptionSourceDescriptor resolveEffectiveOptionSource(String sourceKey) {
        OptionSourceDescriptor descriptor = resolveOptionSource(sourceKey);
        if (optionSourceEligibility == null) {
            return descriptor;
        }
        return optionSourceEligibility.resolveEffectiveDescriptor(descriptor, getStatsFieldRegistry());
    }

    protected List<ResolvedStatsMetric> resolveMetrics(List<StatsMetricRequest> metrics, String operationName) {
        return metrics.stream()
                .map(metric -> new ResolvedStatsMetric(
                        metric,
                        statsEligibility.resolveMetricField(metric, getStatsFieldRegistry(), operationName)
                ))
                .toList();
    }

    private boolean hasOptionLabelAnnotation(java.lang.reflect.AnnotatedElement element) {
        return Arrays.stream(element.getAnnotations())
                .anyMatch(annotation -> "OptionLabel".equals(annotation.annotationType().getSimpleName()));
    }

    private Method findMethodIgnoreCase(Class<?> clazz, String methodName) {
        for (Method method : getAllMethods(clazz)) {
            if (method.getParameterCount() == 0 && method.getName().equalsIgnoreCase(methodName)) {
                return method;
            }
        }
        return null;
    }

    private Object invokeSilently(E entity, Method method) {
        try {
            method.setAccessible(true);
            return method.invoke(entity);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private Object getFieldValueSilently(E entity, Field field) {
        try {
            field.setAccessible(true);
            return field.get(entity);
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    private String toNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    protected List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    protected Method[] getAllMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            methods.addAll(Arrays.asList(current.getDeclaredMethods()));
            current = current.getSuperclass();
        }
        return methods.toArray(new Method[0]);
    }
}
