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
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceEligibility;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.UnknownOptionSourceException;
import org.praxisplatform.uischema.options.service.OptionSourceQueryExecutor;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn;
import org.praxisplatform.uischema.stats.StatsEligibility;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsProperties;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.stats.service.ResolvedStatsMetric;
import org.praxisplatform.uischema.stats.service.StatsQueryExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    private StatsQueryExecutor statsQueryExecutor;

    @Autowired(required = false)
    private StatsEligibility statsEligibility;

    @Autowired(required = false)
    private StatsProperties statsProperties;

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
        if (optionSourceQueryExecutor == null) {
            resolveOptionSource(sourceKey);
            throw new UnsupportedOperationException("Option source options not implemented: " + sourceKey);
        }
        OptionSourceDescriptor descriptor = resolveEffectiveOptionSource(sourceKey);
        FilterDTO effectiveFilter = sanitizeFilter(request == null ? null : request.filter(), descriptor);
        GenericSpecification<E> specification = getSpecificationsBuilder().buildSpecification(effectiveFilter, pageable);
        return optionSourceQueryExecutor.filterOptions(
                entityManager,
                entityClass,
                specification.spec(),
                descriptor,
                request == null ? null : request.search(),
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
        return optionSourceQueryExecutor.byIdsOptions(entityManager, entityClass, descriptor, ids);
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

    protected Page<E> filterEntities(FilterDTO filterDTO, Pageable pageable) {
        Pageable sortedPageable = pageable;
        if (!pageable.getSort().isSorted()) {
            sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), getDefaultSort());
        }

        GenericSpecification<E> specification = getSpecificationsBuilder().buildSpecification(filterDTO, sortedPageable);
        return repository.findAll(specification.spec(), specification.pageable());
    }

    protected Page<E> filterEntitiesWithIncludeIds(FilterDTO filter, Pageable pageable, Collection<ID> includeIds) {
        Page<E> page = filterEntities(filter, pageable);
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
            findEntitiesById(missing).forEach(entity -> ensured.put(extractId(entity), entity));
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
