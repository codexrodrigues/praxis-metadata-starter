package org.praxisplatform.uischema.service.base;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Version;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.exporting.*;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.support.DefaultConversionService;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecification;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.id.JpaCriteriaPathSupport;
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
import org.praxisplatform.uischema.stats.ComparisonPeriodMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodResolver;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsCapability;
import org.praxisplatform.uischema.stats.dto.ComparisonBucket;
import org.praxisplatform.uischema.stats.dto.ComparisonMetricValue;
import org.praxisplatform.uischema.stats.dto.ComparisonPeriodRequest;
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
import org.praxisplatform.uischema.stats.service.ResolvedStatsDimension;
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
                BaseResourceQueryService.super.getStructuralCapabilities().duplicateDraft(),
                supportsCursorPagination(),
                statsInfrastructure
                        ? StatsCapability.from(
                                registry,
                                groupBy ? StatsSupportMode.AUTO : StatsSupportMode.DISABLED,
                                timeSeries ? StatsSupportMode.AUTO : StatsSupportMode.DISABLED,
                                distribution ? StatsSupportMode.AUTO : StatsSupportMode.DISABLED,
                                properties.maxDimensions(),
                                properties.maxCells()
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

    @Autowired(required = false)
    private CollectionExportExecutor collectionExportExecutor;

    private static final ClassValue<Optional<java.lang.reflect.AccessibleObject>> VERSION_ACCESSORS = new ClassValue<>() {
        @Override protected Optional<java.lang.reflect.AccessibleObject> computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Version.class) && method.getParameterCount() == 0) {
                        method.setAccessible(true);
                        return Optional.of(method);
                    }
                }
                for (Field field : c.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Version.class)) {
                        field.setAccessible(true);
                        return Optional.of(field);
                    }
                }
            }
            return Optional.empty();
        }
    };

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
        CompositeIdSupport.registerEntityCompositeIdTypes(entityClass);
    }

    protected AbstractBaseQueryResourceService(BaseCrudRepository<E, ID> repository, Class<E> entityClass) {
        this(repository, new GenericSpecificationsBuilder<>(), entityClass);
    }

    private org.praxisplatform.uischema.customfields.CustomFieldDefinitionProvider customFieldDefinitions =
            new org.praxisplatform.uischema.customfields.NoOpCustomFieldDefinitionProvider();
    private org.praxisplatform.uischema.customfields.ResourceTenantContextResolver customFieldTenant = java.util.Optional::empty;

    @Autowired(required = false)
    public void configureCustomFieldFiltering(
            org.praxisplatform.uischema.customfields.CustomFieldDefinitionProvider definitions,
            org.praxisplatform.uischema.customfields.ResourceTenantContextResolver tenant) {
        this.customFieldDefinitions = java.util.Objects.requireNonNull(definitions);
        this.customFieldTenant = java.util.Objects.requireNonNull(tenant);
    }

    /** Stable semantic key; override when the service is not itself annotated. */
    protected String resolveResourceKey() {
        return org.praxisplatform.uischema.customfields.CustomFieldSupport.resourceKey(getClass(),
                org.praxisplatform.uischema.customfields.CustomFieldSupport.resourceKey(entityClass, entityClass.getSimpleName()));
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
                if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(EmbeddedId.class)) {
                    return field.getName();
                }
            }
            current = current.getSuperclass();
        }
        for (Method method : getAllMethods(entityClass)) {
            if (method.isAnnotationPresent(Id.class) || method.isAnnotationPresent(EmbeddedId.class))
                return java.beans.Introspector.decapitalize(method.getName().substring(3));
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

        return JpaCriteriaPathSupport.expandCompositeSort(getEntityClass(), Sort.by(orders));
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.empty();
    }

    @Override
    public StatsSupportMode getGroupByStatsSupportMode() {
        return getStatsFieldRegistry() == null || getStatsFieldRegistry().descriptors().isEmpty()
                ? StatsSupportMode.DISABLED : StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getTimeSeriesStatsSupportMode() {
        return getStatsFieldRegistry() == null || getStatsFieldRegistry().descriptors().isEmpty()
                ? StatsSupportMode.DISABLED : StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getDistributionStatsSupportMode() {
        return getStatsFieldRegistry() == null || getStatsFieldRegistry().descriptors().isEmpty()
                ? StatsSupportMode.DISABLED : StatsSupportMode.AUTO;
    }

    public StatsSupportMode getComparisonStatsSupportMode() {
        return getStatsFieldRegistry() == null || getStatsFieldRegistry().descriptors().isEmpty()
                ? StatsSupportMode.DISABLED : StatsSupportMode.AUTO;
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
    public ResourceRepresentationResult<ResponseDTO> findById(ID id) {
        E entity = findEntityById(id);
        if (org.praxisplatform.uischema.capability.ResourceStateSnapshotScope.isOpen()) {
            org.praxisplatform.uischema.capability.ResourceStateSnapshotScope.seedServiceSnapshot(this, id, toResourceStateSnapshot(entity));
        }
        return toResourceRepresentation(entity);
    }

    /**
     * Reads a numeric JPA @Version from the supplied entity through a cached field/getter accessor.
     * Overrides may project another persisted numeric revision, but must never reload the entity,
     * query a second revision or substitute an expected client version. Missing/non-numeric
     * revisions remain empty; versioned mutations reject them before reporting success.
     */
    protected OptionalLong getEntityResourceVersion(E entity) {
        if (entity == null) return OptionalLong.empty();
        var accessor = VERSION_ACCESSORS.get(entityClass);
        if (accessor.isEmpty()) return OptionalLong.empty();
        try {
            Object versionOwner = entity instanceof org.hibernate.proxy.HibernateProxy proxy
                    ? proxy.getHibernateLazyInitializer().getImplementation() : entity;
            Object value = accessor.get() instanceof Field field ? field.get(versionOwner) : ((Method) accessor.get()).invoke(versionOwner);
            if (value == null) return OptionalLong.empty();
            if (!(value instanceof Number number)) return OptionalLong.empty();
            return OptionalLong.of(number.longValue());
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot read resource @Version", ex); }
    }

    /** Called once for each materialized response batch, inside its read/write transaction. */
    protected void enrichResponses(List<ResponseDTO> responses) { }

    protected final ResponseDTO enrichSingleResponse(ResponseDTO response) {
        if (response != null) enrichResponses(List.of(response));
        return response;
    }

    /** Resolves a selected proxy before field-based mapping, authorization or state snapshots. */
    protected final E unproxyEntity(E entity) {
        return entity == null ? null : entityClass.cast(org.hibernate.Hibernate.unproxy(entity));
    }

    private List<ResponseDTO> toResponses(Collection<E> entities) {
        List<ResponseDTO> responses = entities.stream().map(this::unproxyEntity).map(getResourceMapper()::toResponse).toList();
        enrichResponses(responses);
        return responses;
    }

    protected Optional<ResourceStateSnapshot> toResourceStateSnapshot(E entity) { return Optional.empty(); }

    /** Public transactional bridge for the default state-snapshot provider. */
    @Override
    @Transactional(readOnly = true)
    public Optional<ResourceStateSnapshot> findResourceStateSnapshot(ID id) {
        return org.praxisplatform.uischema.capability.ResourceStateSnapshotScope.findSeededServiceSnapshot(this, id)
                .orElseGet(() -> toResourceStateSnapshot(findEntityById(id)));
    }

    /** Stable semantic identity used by service-backed discovery. */
    @Override
    public String getResourceKey() { return resolveResourceKey(); }

    /**
     * Captures the projection and revision while the supplied entity still belongs to the
     * caller's transaction. Mutation implementations call this after their last modifying
     * hook and flush, so optimistic-lock failure occurs before returning a successful result.
     */
    protected final ResourceRepresentationResult<ResponseDTO> toResourceRepresentation(E entity) {
        entity = unproxyEntity(entity);
        ResourceRepresentationResult<ResponseDTO> result = new ResourceRepresentationResult<>(
                enrichSingleResponse(getResourceMapper().toResponse(entity)), getEntityResourceVersion(entity));
        if (this instanceof VersionedCreateUpdateResourceService<?, ?, ?, ?, ?>) {
            result.requirePersistedVersion();
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponseDTO> findAll() {
        return toResponses(findAllEntities());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponseDTO> findAllById(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<ID, E> byId = findEntitiesById(ids).stream()
                .collect(Collectors.toMap(this::extractId, Function.identity(), (left, right) -> left));
        return toResponses(ids.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseDTO> filter(FilterDTO filter, Pageable pageable, Collection<ID> includeIds) {
        Page<E> page = filterEntitiesWithIncludeIds(filter, pageable, includeIds);
        return new PageImpl<>(toResponses(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPage<ResponseDTO> filterByCursor(FilterDTO filter, Sort sort, String after, String before, int size) {
        CursorPage<E> page = filterEntitiesByCursor(filter, sort, after, before, size);
        assertEntitiesAccessAllowed(page.content());
        return new CursorPage<>(
                toResponses(page.content()),
                page.next(),
                page.prev(),
                page.size()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalLong locate(FilterDTO filter, Sort sort, ID id) {
        if (supportsCursorPagination()) {
            return executeDefaultLocate(filter, sort, id);
        }
        return OptionalLong.empty();
    }

    protected Class<?> getResponseType() {
        return ResolvableType.forClass(getClass()).as(AbstractBaseQueryResourceService.class).getGeneric(1).resolve();
    }

    protected List<CollectionExportField> defaultExportFields() { return ResourceExportSupport.defaults(getResponseType()); }

    protected List<CollectionExportField> exportFields() { return defaultExportFields(); }

    /** Reapplies DTO governance even to host-authored field allowlists. */
    protected final List<CollectionExportField> governedExportFields() {
        return exportFields().stream().filter(Objects::nonNull).filter(field -> field.exportable() != Boolean.FALSE)
                .filter(field -> ResourceExportSupport.exportable(getResponseType(),
                        field.valuePath() == null || field.valuePath().isBlank() ? field.key() : field.valuePath())).toList();
    }

    protected int maxExportRows(CollectionExportFormat format) { return 10_000; }

    @Override public boolean supportsCollectionExport() {
        return collectionExportExecutor != null && !governedExportFields().isEmpty();
    }

    @Override public Optional<CollectionExportCapability> getCollectionExportCapability() {
        if (!supportsCollectionExport()) return Optional.empty();
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (CollectionExportFormat format : CollectionExportFormat.values()) limits.put(format.value(), maxExportRows(format));
        return Optional.of(new CollectionExportCapability(List.of(CollectionExportFormat.values()),
                List.of(CollectionExportScope.values()), limits, false));
    }

    @Override
    @Transactional(readOnly = true)
    public CollectionExportResult exportCollection(CollectionExportRequest<FilterDTO> request) {
        Objects.requireNonNull(request, "request");
        if (!supportsCollectionExport()) throw new UnsupportedOperationException("Collection export is not configured for this resource");
        int limit = maxExportRows(request.format());
        if (limit < 1 || limit == Integer.MAX_VALUE) throw new IllegalStateException("Invalid resource export limit");
        int maxRows = request.maxRows() == null ? limit : request.maxRows();
        if (maxRows < 1 || maxRows > limit) throw new IllegalArgumentException("maxRows exceeds the resource export limit");
        CollectionExportSelection selection = request.selection();
        boolean selected = selection != null && (!selection.selectedKeys().isEmpty() || Boolean.TRUE.equals(selection.allMatchingSelected()));
        CollectionExportScope scope = request.scope() == CollectionExportScope.AUTO
                ? selected ? CollectionExportScope.SELECTED : CollectionExportScope.FILTERED : request.scope();
        Sort requestedSort = ensureDeterministicIdTieBreaker(resolveExportSort(request.sort()));
        ResourceFilterQuery<E> filterQuery = resolveResourceFilterQuery(request.filters(), PageRequest.of(0, 1, requestedSort));
        final Sort sort = ensureDeterministicIdTieBreaker(filterQuery.pageable().getSort());
        Specification<E> spec = scope == CollectionExportScope.ALL ? filterQuery.accessSpecification() : filterQuery.effectiveSpecification();
        if (scope == CollectionExportScope.SELECTED) {
            if (selection == null) throw new IllegalArgumentException("SELECTED requires selection");
            if (!Boolean.TRUE.equals(selection.allMatchingSelected()))
                spec = filterQuery.accessSpecification().and(selectionSpecification(selection.selectedKeys()));
            if (!selection.excludedKeys().isEmpty()) spec = spec.and(Specification.not(selectionSpecification(selection.excludedKeys())));
        }
        int offset = 0, windowSize = maxRows;
        if (scope == CollectionExportScope.CURRENT_PAGE) {
            CollectionExportPage pagination = request.pagination();
            if (pagination == null || pagination.pageSize() == null || pagination.pageSize() < 1)
                throw new IllegalArgumentException("CURRENT_PAGE requires a positive pageSize");
            int page = pagination.pageIndex() != null ? pagination.pageIndex()
                    : pagination.pageNumber() == null ? 0 : pagination.pageNumber() - 1;
            if (page < 0 || pagination.pageSize() > limit) throw new IllegalArgumentException("Invalid export pagination");
            offset = Math.multiplyExact(page, pagination.pageSize());
            windowSize = Math.min(maxRows, pagination.pageSize());
        }
        List<E> entities;
        if (getEntityManager() != null) {
            entities = executeEntityWindow(spec, sort, offset, windowSize + 1);
        } else {
            // Repository-only adapters retain bounded reads, including a non-aligned page limit.
            final int start = offset, size = windowSize + 1;
            Pageable pageable = new org.springframework.data.domain.AbstractPageRequest(0, size) {
                @Override public long getOffset() { return start; }
                @Override public Sort getSort() { return sort; }
                @Override public Pageable next() { throw new UnsupportedOperationException(); }
                @Override public Pageable previous() { throw new UnsupportedOperationException(); }
                @Override public Pageable first() { return this; }
                @Override public Pageable withPage(int pageNumber) { throw new UnsupportedOperationException(); }
            };
            entities = repository.findAll(spec, pageable).getContent();
        }
        assertEntitiesAccessAllowed(entities);
        boolean truncated = entities.size() > windowSize && (scope != CollectionExportScope.CURRENT_PAGE
                || request.pagination().pageSize() > windowSize);
        List<ResponseDTO> rows = toResponses(entities.subList(0, Math.min(windowSize, entities.size())));
        List<CollectionExportField> fields = governedExportFields();
        var effectiveRequest = new CollectionExportRequest<>(request.componentType(), request.componentId(), request.resourcePath(),
                request.format(), scope, request.selection(), request.fields(), request.filters(), request.sort(), request.pagination(),
                request.query(), request.includeHeaders(), request.applyFormatting(), maxRows, request.fileName(),
                request.formatOptions(), request.localization(), request.metadata());
        CollectionExportResult result = collectionExportExecutor.export(effectiveRequest, rows, fields,
                (row, field) -> ResourceExportSupport.requireExportableValue(ResourceKeysetSupport.read(row,
                        field.valuePath() == null || field.valuePath().isBlank() ? field.key() : field.valuePath())),
                Map.of("effectiveScope", scope.value(), "truncated", truncated, "maxRows", maxRows));
        if (!truncated) return result;
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.add("The export reached its row limit; narrow the selection or filters to export the remaining records.");
        return new CollectionExportResult(result.status(), result.format(), result.scope(), result.content(), result.fileName(),
                result.contentType(), result.downloadUrl(), result.jobId(), result.rowCount(), warnings, result.metadata());
    }

    protected Sort resolveExportSort(Object requested) {
        if (requested == null) return getDefaultSort();
        if (requested instanceof Sort sort) return expandResourceSort(sort);
        List<?> entries = requested instanceof Collection<?> list ? new ArrayList<>(list) : List.of(requested);
        List<Sort.Order> orders = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof String text) {
                String[] parts = text.split(",", -1);
                if (parts.length > 2 || parts[0].isBlank()) throw new IllegalArgumentException("Invalid export sort");
                orders.add(new Sort.Order(parts.length == 2 ? Sort.Direction.fromString(parts[1]) : Sort.Direction.ASC, parts[0]));
            } else if (entry instanceof Map<?, ?> map && map.get("property") instanceof String property) {
                orders.add(new Sort.Order(map.get("direction") == null ? Sort.Direction.ASC
                        : Sort.Direction.fromString(map.get("direction").toString()), property));
            } else throw new IllegalArgumentException("Unsupported export sort");
        }
        return expandResourceSort(Sort.by(orders));
    }

    @SuppressWarnings("unchecked")
    protected Specification<E> selectionSpecification(Collection<?> keys) {
        Class<?> idType = ResolvableType.forClass(getClass()).as(AbstractBaseQueryResourceService.class).getGeneric(2).resolve();
        Specification<E> selection = (root, query, cb) -> cb.disjunction();
        for (Object key : keys) {
            if (key == null || idType == null) throw new IllegalArgumentException("Invalid export identity");
            ID id = (ID) (idType.isInstance(key) ? key : CompositeIdSupport.isCompositeIdType(idType)
                    ? CompositeIdSupport.decode(key.toString(), idType)
                    : DefaultConversionService.getSharedInstance().convert(key, idType));
            selection = selection.or(identitySpecification(id));
        }
        return selection;
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
                : buildResourceSpecification(effectiveFilter, pageable);
        return optionSourceQueryExecutor.filterOptions(
                entityManager,
                entityClass,
                Objects.requireNonNull(resolveResourceFilterAccessScope()).specification()
                        .and(specification == null ? null : specification.spec()),
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
                : buildResourceSpecification(effectiveFilter, Pageable.unpaged());
        return optionSourceQueryExecutor.byIdsOptions(
                entityManager,
                entityClass,
                Objects.requireNonNull(resolveResourceFilterAccessScope()).specification()
                        .and(specification == null ? null : specification.spec()),
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
                : buildResourceSpecification(effectiveFilter, Pageable.unpaged());
        return optionSourceQueryExecutor.byIdsOptions(
                entityManager,
                entityClass,
                Objects.requireNonNull(resolveResourceFilterAccessScope()).specification()
                        .and(specification == null ? null : specification.spec()),
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

        List<ResolvedStatsDimension> dimensions = statsEligibility.validateGroupByDimensions(
                request,
                getStatsFieldRegistry(),
                properties.maxBuckets(),
                properties.maxDimensions(),
                properties.maxCells(),
                properties.maxComparisonPeriodDays()
        );
        List<ResolvedStatsMetric> resolvedMetrics = resolveMetrics(request.effectiveMetrics(), "group-by");
        Specification<E> specification = resolveResourceFilterQuery(request.filter(), Pageable.unpaged()).effectiveSpecification();
        ComparisonPeriodWindow window = resolveGroupByWindow(request, dimensions, properties.maxComparisonPeriodDays());
        if (window != null) {
            StatsFieldDescriptor periodDescriptor = resolveGroupByPeriodDescriptor(request, dimensions);
            specification = specification.and(ComparisonPeriodSpecifications.forPeriod(
                    periodDescriptor.keyPropertyPath(),
                    window.from(),
                    window.to(),
                    window.timezone()
            ));
        }
        return statsQueryExecutor.executeGroupBy(
                entityManager,
                entityClass,
                specification,
                dimensions,
                resolvedMetrics,
                request,
                window,
                properties.maxBuckets(),
                properties.maxCells()
        );
    }

    private ComparisonPeriodWindow resolveGroupByWindow(
            GroupByStatsRequest<FilterDTO> request,
            List<ResolvedStatsDimension> dimensions,
            int maxPeriodDays
    ) {
        if (!request.hasTemporalWindow()) {
            return null;
        }
        if (request.periodPreset() != null) {
            ResolvedComparisonPeriod resolved = new ComparisonPeriodResolver(
                    statsClock == null ? Clock.systemUTC() : statsClock
            ).resolve(new ComparisonPeriodRequest(
                    request.periodPreset(),
                    null,
                    null,
                    request.timezone(),
                    ComparisonPeriodMode.PREVIOUS_ALIGNED
            ));
            long days = java.time.temporal.ChronoUnit.DAYS.between(resolved.currentFrom(), resolved.currentTo()) + 1;
            if (days > maxPeriodDays) {
                throw new IllegalArgumentException("Maximum stats period exceeded: " + maxPeriodDays + " days.");
            }
            return new ComparisonPeriodWindow(resolved.currentFrom(), resolved.currentTo(), resolved.timezone());
        }
        java.time.ZoneId zone = java.time.ZoneId.of(request.timezone().trim());
        long days = java.time.temporal.ChronoUnit.DAYS.between(request.from(), request.to()) + 1;
        if (days > maxPeriodDays) {
            throw new IllegalArgumentException("Maximum stats period exceeded: " + maxPeriodDays + " days.");
        }
        return new ComparisonPeriodWindow(request.from(), request.to(), zone.getId());
    }

    private StatsFieldDescriptor resolveGroupByPeriodDescriptor(
            GroupByStatsRequest<FilterDTO> request,
            List<ResolvedStatsDimension> dimensions
    ) {
        if (request.periodField() != null && !request.periodField().isBlank()) {
            return getStatsFieldRegistry().resolve(request.periodField().trim())
                    .orElseThrow(() -> new IllegalArgumentException("Stats period field is not allowed: " + request.periodField()));
        }
        return dimensions.stream()
                .map(ResolvedStatsDimension::descriptor)
                .filter(StatsFieldDescriptor::timeSeriesEligible)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Stats group-by with temporal window requires a temporal dimension or explicit periodField."
                ));
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
        Specification<E> specification = resolveResourceFilterQuery(request.filter(), Pageable.unpaged()).effectiveSpecification();
        return statsQueryExecutor.executeTimeSeries(
                entityManager,
                entityClass,
                specification,
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
        Specification<E> specification = resolveResourceFilterQuery(request.filter(), Pageable.unpaged()).effectiveSpecification();
        return statsQueryExecutor.executeDistribution(
                entityManager,
                entityClass,
                specification,
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
        Specification<E> baseSpec = resolveResourceFilterQuery(request.filter(), Pageable.unpaged()).effectiveSpecification();
        int candidateLimit = properties.maxComparisonCandidates();
        GroupByStatsRequest<FilterDTO> groupRequest = new GroupByStatsRequest<>(
                request.filter(), request.field(), request.metrics().get(0), candidateLimit + 1,
                StatsBucketOrder.KEY_ASC, request.metrics()
        );
        GroupByStatsResponse current = statsQueryExecutor.executeGroupBy(entityManager, entityClass,
                baseSpec.and(ComparisonPeriodSpecifications.forPeriod(periodDescriptor.keyPropertyPath(), period.currentFrom(), period.currentTo(), period.timezone())),
                descriptor, metrics, groupRequest, candidateLimit + 1);
        GroupByStatsResponse previous = statsQueryExecutor.executeGroupBy(entityManager, entityClass,
                baseSpec.and(ComparisonPeriodSpecifications.forPeriod(periodDescriptor.keyPropertyPath(), period.previousFrom(), period.previousTo(), period.timezone())),
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
        if (supportsCursorPagination()) {
            return executeDefaultCursorPagination(filter, sort, after, before, size);
        }
        throw new UnsupportedOperationException("Cursor pagination not implemented");
    }

    protected CursorPage<E> executeDefaultCursorPagination(
            FilterDTO filter,
            Sort sort,
            String after,
            String before,
            int size
    ) {
        if (size < 1 || size == Integer.MAX_VALUE) throw new IllegalArgumentException("Cursor size must be positive and bounded");
        boolean hasAfter = after != null && !after.isBlank();
        boolean hasBefore = before != null && !before.isBlank();
        if (hasAfter && hasBefore) throw new IllegalArgumentException("Use either after or before");
        ResolvedCursorQuery<E> resolved = resolveCursorQuery(filter, sort);
        Specification<E> spec = resolved.specification();
        if (hasAfter || hasBefore) spec = spec.and(ResourceKeysetSupport.boundary(resolved.sort(),
                ResourceKeysetSupport.decode(hasAfter ? after : before, resolved.sort()), hasAfter));
        Sort traversal = hasBefore ? ResourceKeysetSupport.reverse(resolved.sort()) : resolved.sort();
        List<E> window = executeKeysetWindow(spec, traversal, size + 1);
        if (window.isEmpty()) return new CursorPage<>(List.of(), null, null, size);
        boolean more = window.size() > size;
        List<E> content = new ArrayList<>(window.subList(0, Math.min(size, window.size())));
        if (hasBefore) java.util.Collections.reverse(content);
        String first = ResourceKeysetSupport.encode(content.getFirst(), resolved.sort());
        String last = ResourceKeysetSupport.encode(content.getLast(), resolved.sort());
        boolean previous = hasBefore ? more : hasAfter && repository.count(resolved.specification().and(
                ResourceKeysetSupport.boundary(resolved.sort(), ResourceKeysetSupport.decode(first, resolved.sort()), false))) > 0;
        boolean next = hasBefore ? repository.count(resolved.specification().and(
                ResourceKeysetSupport.boundary(resolved.sort(), ResourceKeysetSupport.decode(last, resolved.sort()), true))) > 0 : more;
        return new CursorPage<>(List.copyOf(content), next ? last : null, previous ? first : null, size);
    }

    private List<E> executeKeysetWindow(Specification<E> spec, Sort sort, int size) {
        if (getEntityManager() == null) {
            Specification<E> ordered = (root, query, cb) -> {
                var predicate = spec.toPredicate(root, query, cb);
                if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                    if (query.isDistinct())
                        throw new UnsupportedOperationException("Distinct keyset pagination requires an EntityManager");
                    query.orderBy(ResourceKeysetSupport.orders(cb, root, sort));
                }
                return predicate;
            };
            // Spring Data must preserve the explicit CASE ordering, including null precedence.
            return repository.findAll(ordered, PageRequest.of(0, size, Sort.unsorted())).getContent();
        }
        return executeEntityWindow(spec, sort, 0, size);
    }

    private List<E> executeEntityWindow(Specification<E> spec, Sort sort, int offset, int size) {
        var cb = getEntityManager().getCriteriaBuilder();
        var cq = cb.createTupleQuery();
        var root = cq.from(entityClass);
        var predicate = spec.toPredicate(root, cq, cb);
        if (predicate != null) cq.where(predicate);
        var orders = ResourceKeysetSupport.orders(cb, root, sort);
        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        selections.add(root);
        if (cq.isDistinct()) orders.forEach(order -> selections.add(order.getExpression()));
        cq.multiselect(selections).orderBy(orders);
        var query = getEntityManager().createQuery(cq).setMaxResults(size);
        if (offset > 0) query.setFirstResult(offset);
        return query.getResultList().stream().map(tuple -> unproxyEntity(tuple.get(0, entityClass))).toList();
    }

    protected OptionalLong executeDefaultLocate(FilterDTO filter, Sort sort, ID id) {
        if (id == null) throw getNotFoundException();
        E target = findEntityById(id);
        ResolvedCursorQuery<E> resolved = resolveCursorQuery(filter, sort);
        if (repository.findOne(resolved.specification().and(identitySpecification(id))).isEmpty()) throw getNotFoundException();
        String cursor = ResourceKeysetSupport.encode(target, resolved.sort());
        return OptionalLong.of(repository.count(resolved.specification().and(ResourceKeysetSupport.boundary(
                resolved.sort(), ResourceKeysetSupport.decode(cursor, resolved.sort()), false))));
    }

    /** Identity components in canonical declaration order; override for a host-defined mapping. */
    protected List<String> getIdentityPaths() {
        List<String> paths = new ArrayList<>();
        for (Field field : getAllFields(entityClass)) {
            if (field.isAnnotationPresent(EmbeddedId.class)) {
                CompositeIdSupport.componentNames(field.getType()).forEach(name -> paths.add(field.getName() + "." + name));
            } else if (field.isAnnotationPresent(Id.class)) paths.add(field.getName());
        }
        if (paths.isEmpty()) {
            for (Method method : getAllMethods(entityClass)) {
                if (method.isAnnotationPresent(Id.class) || method.isAnnotationPresent(EmbeddedId.class)) {
                    String name = java.beans.Introspector.decapitalize(method.getName().substring(3));
                    if (method.isAnnotationPresent(EmbeddedId.class)) {
                        CompositeIdSupport.registerCompositeIdType(method.getReturnType());
                        CompositeIdSupport.componentNames(method.getReturnType()).forEach(component -> paths.add(name + "." + component));
                    } else paths.add(name);
                }
            }
        }
        return paths.isEmpty() ? List.of(getIdFieldName()) : List.copyOf(paths);
    }

    protected final Sort expandResourceSort(Sort sort) {
        if (sort == null) return Sort.unsorted();
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            if (order.getProperty().equals("id") || order.getProperty().equals(getIdFieldName())) {
                getIdentityPaths().forEach(path -> orders.add(order.withProperty(path)));
            } else orders.add(order);
        }
        return JpaCriteriaPathSupport.expandCompositeSort(entityClass, Sort.by(orders));
    }

    private Specification<E> identitySpecification(ID id) {
        if (getIdentityPaths().size() == 1) {
            String path = getIdentityPaths().getFirst();
            Object value = id != null && CompositeIdSupport.isCompositeIdType(id.getClass())
                    ? ResourceKeysetSupport.read(id, path.substring(path.lastIndexOf('.') + 1)) : id;
            return (root, query, cb) -> cb.equal(JpaCriteriaPathSupport.resolvePath(root, path, true), value);
        }
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> parts = new ArrayList<>();
            for (String path : getIdentityPaths()) {
                String component = path.substring(path.lastIndexOf('.') + 1);
                parts.add(cb.equal(JpaCriteriaPathSupport.resolvePath(root, path, true), ResourceKeysetSupport.read(id, component)));
            }
            return cb.and(parts.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private ResolvedCursorQuery<E> resolveCursorQuery(FilterDTO filter, Sort sort) {
        Sort effectiveSort = (sort != null && sort.isSorted()) ? sort : getDefaultSort();
        ResourceFilterQuery<E> query = resolveResourceFilterQuery(filter, PageRequest.of(0, 1, effectiveSort));
        Sort resolvedSort = query.pageable() != null && query.pageable().getSort() != null
                ? query.pageable().getSort()
                : effectiveSort;
        Sort deterministicSort = ensureDeterministicIdTieBreaker(resolvedSort);
        return new ResolvedCursorQuery<>(query.effectiveSpecification(), deterministicSort);
    }

    private Sort ensureDeterministicIdTieBreaker(Sort sort) {
        sort = expandResourceSort(sort);
        Sort tieBreaker = Sort.by(getIdentityPaths().stream().map(Sort.Order::asc).toList());
        if (sort == null || !sort.isSorted()) {
            return tieBreaker;
        }
        Set<String> existingProperties = sort.stream()
                .map(Sort.Order::getProperty)
                .collect(Collectors.toSet());
        List<Sort.Order> missingIdOrders = tieBreaker.stream()
                .filter(order -> !existingProperties.contains(order.getProperty()))
                .toList();
        if (missingIdOrders.isEmpty()) {
            return sort;
        }
        List<Sort.Order> combined = new ArrayList<>();
        sort.forEach(combined::add);
        combined.addAll(missingIdOrders);
        return Sort.by(combined);
    }

    private record ResolvedCursorQuery<E>(
            Specification<E> specification,
            Sort sort
    ) {
    }

    protected ID extractId(E entity) {
        return getResourceMapper().extractId(unproxyEntity(entity));
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

        return CompositeIdSupport.stringify(extractId(entity));
    }

    protected EntityNotFoundException getNotFoundException() {
        return new EntityNotFoundException("Registro nao encontrado");
    }

    protected E findEntityById(ID id) {
        E entity = unproxyEntity(repository.findById(id).orElseThrow(this::getNotFoundException));
        assertEntityAccessAllowed(entity);
        return entity;
    }

    protected List<E> findAllEntities() {
        return repository.findAll(Objects.requireNonNull(resolveResourceFilterAccessScope()).specification(), expandResourceSort(getDefaultSort()));
    }

    protected List<E> findEntitiesById(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("ids must not contain null");
        List<E> entities = repository.findAllById(ids).stream().map(this::unproxyEntity).toList();
        Set<ID> found = entities.stream().map(this::extractId).collect(Collectors.toSet());
        if (ids.stream().anyMatch(id -> !found.contains(id))) throw getNotFoundException();
        assertEntitiesAccessAllowed(entities);
        return entities;
    }

    /** Verifies item authorization after primary-key lookup, preserving missing-item 404 semantics. */
    protected void assertEntityAccessAllowed(E entity) { }

    /**
     * Collection authorization boundary. Hosts may preload shared authorization facts in one
     * bounded query; the default preserves the individual guard for every returned entity.
     * This hook never replaces the mandatory SQL filter access scope.
     */
    protected void assertEntitiesAccessAllowed(Collection<E> entities) {
        entities.forEach(this::assertEntityAccessAllowed);
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
        assertEntitiesAccessAllowed(page.getContent());
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

        Pageable effectivePageable = page.getPageable();
        if (effectivePageable.isPaged() && effectivePageable.getPageNumber() != 0) {
            return new PageImpl<>(remaining, effectivePageable, page.getTotalElements());
        }

        List<ID> missing = orderedIds.stream().filter(id -> !ensured.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            List<E> included = findEntitiesByIdWithinAccessScope(missing, query.accessSpecification());
            assertEntitiesAccessAllowed(included);
            included.forEach(entity -> ensured.put(extractId(entity), entity));
        }

        List<E> merged = new ArrayList<>(orderedIds.size() + remaining.size());
        orderedIds.forEach(id -> {
            E entity = ensured.get(id);
            if (entity != null) {
                merged.add(entity);
            }
        });
        merged.addAll(remaining);

        return new PageImpl<>(merged, effectivePageable, page.getTotalElements());
    }

    private GenericSpecification<E> buildResourceSpecification(FilterDTO filter, Pageable pageable) {
        if (filter == null) return new GenericSpecification<>((root, query, cb) -> cb.conjunction(), pageable);
        return getSpecificationsBuilder().buildSpecification(filter, pageable, customFieldDefinitions, customFieldTenant, resolveResourceKey());
    }

    protected final ResourceFilterQuery<E> resolveResourceFilterQuery(FilterDTO filter, Pageable pageable) {
        Pageable sortedPageable = pageable;
        if (pageable.isPaged() && !pageable.getSort().isSorted()) {
            sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), getDefaultSort());
        }

        GenericSpecification<E> functional = buildResourceSpecification(filter, sortedPageable);
        Pageable effectivePageable = functional.pageable();
        if (effectivePageable != null && effectivePageable.getSort().isSorted()) {
            Sort expandedSort = expandResourceSort(effectivePageable.getSort());
            if (expandedSort != effectivePageable.getSort()) {
                effectivePageable = effectivePageable.isPaged()
                        ? PageRequest.of(effectivePageable.getPageNumber(), effectivePageable.getPageSize(), expandedSort)
                        : Pageable.unpaged(expandedSort);
            }
        }
        ResourceFilterAccessScope<E> accessScope = Objects.requireNonNull(
                resolveResourceFilterAccessScope(),
                "resolveResourceFilterAccessScope() must return an explicit scope"
        );
        Specification<E> access = accessScope.specification();
        return new ResourceFilterQuery<>(access, access.and(functional.spec()), effectivePageable);
    }

    private List<E> findEntitiesByIdWithinAccessScope(Collection<ID> ids, Specification<E> accessSpecification) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Specification<E> requestedIds = selectionSpecification(ids);
        return repository.findAll(accessSpecification.and(requestedIds));
    }

    protected record ResourceFilterQuery<E>(
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
