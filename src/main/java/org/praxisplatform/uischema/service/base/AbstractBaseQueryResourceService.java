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
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceState;
import org.praxisplatform.uischema.capability.DefaultResourceAvailabilityService;
import org.praxisplatform.uischema.capability.ResourceStructuralCapabilities;
import org.praxisplatform.uischema.governance.AccessMode;
import org.praxisplatform.uischema.governance.ResourceGovernanceInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        StatsFieldRegistry registry = resolveEffectiveStatsFieldRegistry();
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
                                properties.maxCells(),
                                properties.maxTimeSeriesWindowDays(),
                                properties.defaultMinBucketCount()
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

    @Autowired(required = false)
    private ObjectProvider<ScopedBatchReferenceHydrator> scopedBatchReferenceHydratorProvider;

    @Autowired(required = false)
    private ObjectProvider<ResourceGovernanceInterceptor> resourceGovernanceInterceptorProvider;

    private volatile List<ResourceGovernanceInterceptor> manualGovernanceInterceptors = List.of();

    @Autowired(required = false)
    private ObjectProvider<ActionDefinitionRegistry> actionDefinitionRegistryProvider;

    @Value("${praxis.export.include-table-hidden-by-default:true}")
    private boolean includeTableHiddenExportFieldsByDefault = true;

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
    private final List<String> configuredIdentityPaths;
    private final boolean defaultCursorPagination;

    protected AbstractBaseQueryResourceService(
            BaseCrudRepository<E, ID> repository,
            GenericSpecificationsBuilder<E> specificationsBuilder,
            Class<E> entityClass
    ) {
        this(repository, specificationsBuilder, entityClass, false);
    }

    protected AbstractBaseQueryResourceService(BaseCrudRepository<E, ID> repository, Class<E> entityClass) {
        this(repository, new GenericSpecificationsBuilder<>(), entityClass);
    }

    protected AbstractBaseQueryResourceService(
            BaseCrudRepository<E, ID> repository,
            Class<E> entityClass,
            String... identityPaths
    ) {
        this(repository, new GenericSpecificationsBuilder<>(), entityClass, true, identityPaths);
    }

    protected AbstractBaseQueryResourceService(
            BaseCrudRepository<E, ID> repository,
            GenericSpecificationsBuilder<E> specificationsBuilder,
            Class<E> entityClass,
            String... identityPaths
    ) {
        this(repository, specificationsBuilder, entityClass, true, identityPaths);
    }

    private AbstractBaseQueryResourceService(
            BaseCrudRepository<E, ID> repository,
            GenericSpecificationsBuilder<E> specificationsBuilder,
            Class<E> entityClass,
            boolean cursorPaginationEnabled,
            String... identityPaths
    ) {
        this.repository = repository;
        this.specificationsBuilder = specificationsBuilder;
        this.entityClass = entityClass;
        List<String> normalizedPaths = identityPaths == null
                ? List.of()
                : Arrays.stream(identityPaths)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .toList();
        this.configuredIdentityPaths = normalizedPaths.isEmpty() ? List.of() : List.copyOf(normalizedPaths);
        this.defaultCursorPagination = cursorPaginationEnabled && !this.configuredIdentityPaths.isEmpty();
        CompositeIdSupport.registerEntityCompositeIdTypes(entityClass);
    }

    public void setResourceGovernanceInterceptors(List<ResourceGovernanceInterceptor> interceptors) {
        if (interceptors == null || interceptors.isEmpty()) {
            this.manualGovernanceInterceptors = List.of();
            return;
        }
        List<ResourceGovernanceInterceptor> copy = new ArrayList<>();
        for (ResourceGovernanceInterceptor interceptor : interceptors) {
            if (interceptor != null) {
                copy.add(interceptor);
            }
        }
        AnnotationAwareOrderComparator.sort(copy);
        this.manualGovernanceInterceptors = List.copyOf(copy);
    }

    public void setIncludeTableHiddenExportFieldsByDefault(boolean includeTableHiddenExportFieldsByDefault) {
        this.includeTableHiddenExportFieldsByDefault = includeTableHiddenExportFieldsByDefault;
    }

    protected final List<ResourceGovernanceInterceptor> getResourceGovernanceInterceptors() {
        List<ResourceGovernanceInterceptor> combined = new ArrayList<>();
        if (resourceGovernanceInterceptorProvider != null) {
            resourceGovernanceInterceptorProvider.orderedStream().forEach(combined::add);
        }
        if (!manualGovernanceInterceptors.isEmpty()) {
            for (ResourceGovernanceInterceptor manual : manualGovernanceInterceptors) {
                if (!combined.contains(manual)) {
                    combined.add(manual);
                }
            }
            AnnotationAwareOrderComparator.sort(combined);
        }
        return combined.isEmpty() ? List.of() : List.copyOf(combined);
    }

    @Override
    public boolean supportsCursorPagination() {
        return defaultCursorPagination;
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
        record SortCandidate(String property, int priority, boolean ascending) {}
        Map<String, SortCandidate> byProperty = new LinkedHashMap<>();

        for (Field field : getAllFields(getEntityClass())) {
            DefaultSortColumn annotation = field.getAnnotation(DefaultSortColumn.class);
            if (annotation != null) {
                byProperty.putIfAbsent(field.getName(),
                        new SortCandidate(field.getName(), annotation.priority(), annotation.ascending()));
            }
        }

        Class<?> current = getEntityClass();
        while (current != null && current != Object.class && current != Record.class) {
            for (var method : current.getDeclaredMethods()) {
                DefaultSortColumn annotation = method.getAnnotation(DefaultSortColumn.class);
                if (annotation == null || Modifier.isStatic(method.getModifiers())
                        || method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                String propName = resolveDefaultSortPropertyName(current, method);
                if (propName != null) {
                    byProperty.putIfAbsent(propName,
                            new SortCandidate(propName, annotation.priority(), annotation.ascending()));
                }
            }
            if (current.isRecord()) {
                for (var component : current.getRecordComponents()) {
                    DefaultSortColumn annotation = component.getAnnotation(DefaultSortColumn.class);
                    if (annotation != null) {
                        byProperty.putIfAbsent(component.getName(),
                                new SortCandidate(component.getName(), annotation.priority(), annotation.ascending()));
                    }
                }
            }
            current = current.getSuperclass();
        }

        if (byProperty.isEmpty()) {
            if (!configuredIdentityPaths.isEmpty()) {
                List<Sort.Order> identityOrders = configuredIdentityPaths.stream()
                        .map(Sort.Order::asc)
                        .toList();
                return JpaCriteriaPathSupport.expandCompositeSort(getEntityClass(), Sort.by(identityOrders));
            }
            return Sort.unsorted();
        }

        List<Sort.Order> orders = byProperty.values().stream()
                .sorted(Comparator.comparingInt(SortCandidate::priority))
                .map(candidate -> new Sort.Order(
                        candidate.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                        candidate.property()
                ))
                .toList();

        return JpaCriteriaPathSupport.expandCompositeSort(getEntityClass(), Sort.by(orders));
    }

    private static String resolveDefaultSortPropertyName(Class<?> owner, java.lang.reflect.Method method) {
        if (method.getParameterCount() != 0 || method.getReturnType() == void.class) {
            return null;
        }
        String name = method.getName();
        if (owner.isRecord()) {
            return name;
        }
        if (name.startsWith("get") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return name;
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.empty();
    }

    @Override
    public StatsSupportMode getGroupByStatsSupportMode() {
        StatsFieldRegistry registry = resolveEffectiveStatsFieldRegistry();
        return registry.descriptors().isEmpty()
                ? StatsSupportMode.DISABLED : StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getTimeSeriesStatsSupportMode() {
        StatsFieldRegistry registry = resolveEffectiveStatsFieldRegistry();
        return registry.descriptors().isEmpty()
                ? StatsSupportMode.DISABLED : StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getDistributionStatsSupportMode() {
        StatsFieldRegistry registry = resolveEffectiveStatsFieldRegistry();
        return registry.descriptors().isEmpty()
                ? StatsSupportMode.DISABLED : StatsSupportMode.AUTO;
    }

    public StatsSupportMode getComparisonStatsSupportMode() {
        StatsFieldRegistry registry = resolveEffectiveStatsFieldRegistry();
        return registry.descriptors().isEmpty()
                ? StatsSupportMode.DISABLED : StatsSupportMode.AUTO;
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return StatsFieldRegistry.empty();
    }

    protected final StatsFieldRegistry resolveEffectiveStatsFieldRegistry() {
        StatsFieldRegistry registry = getStatsFieldRegistry();
        if (registry == null) {
            registry = StatsFieldRegistry.empty();
        }
        for (ResourceGovernanceInterceptor interceptor : getResourceGovernanceInterceptors()) {
            StatsFieldRegistry filtered = interceptor.filterStatsRegistry(this, registry);
            registry = filtered != null ? filtered : StatsFieldRegistry.empty();
        }
        return registry;
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

    private static boolean isGeneratedProxyOrMockClass(Class<?> candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate != org.springframework.util.ClassUtils.getUserClass(candidate)) {
            return true;
        }
        String name = candidate.getName();
        return name.contains("$MockitoMock$") || name.contains("$ByteBuddy$");
    }

    private static final ClassValue<Boolean> OVERRIDES_FIND_BY_ID = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = org.springframework.util.ClassUtils.getUserClass(type);
                    current != null && current != AbstractBaseQueryResourceService.class;
                    current = current.getSuperclass()) {
                if (isGeneratedProxyOrMockClass(current)) {
                    continue;
                }
                for (Method method : current.getDeclaredMethods()) {
                    if ("findById".equals(method.getName())
                            && method.getParameterCount() == 1
                            && !method.isBridge()
                            && !method.isSynthetic()) {
                        return true;
                    }
                }
            }
            return false;
        }
    };

    private static final ClassValue<Boolean> OVERRIDES_FIND_RESOURCE_STATE_SNAPSHOT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = org.springframework.util.ClassUtils.getUserClass(type);
                    current != null && current != AbstractBaseQueryResourceService.class;
                    current = current.getSuperclass()) {
                if (isGeneratedProxyOrMockClass(current)) {
                    continue;
                }
                for (Method method : current.getDeclaredMethods()) {
                    if ("findResourceStateSnapshot".equals(method.getName())
                            && method.getParameterCount() == 1
                            && !method.isBridge()
                            && !method.isSynthetic()) {
                        return true;
                    }
                }
            }
            return false;
        }
    };

    /**
     * Indicates whether the snapshot computed during {@link #findById(Object)} or
     * {@link #assertItemAccessible(Object)} is semantically equivalent to calling
     * {@link #findResourceStateSnapshot(Object)}, allowing {@link ResourceServiceDelegatingStateSnapshotProvider}
     * to reuse it safely within the active {@link ResourceStateSnapshotScope} without invoking
     * {@code findResourceStateSnapshot} again through AOP proxies.
     *
     * <p>Subclasses that override {@link #findResourceStateSnapshot(Object)} only to map absent/denied
     * errors around {@code super.findResourceStateSnapshot(id)} may opt back in by returning {@code true}.</p>
     */
    protected boolean supportsSeededStateSnapshotReuse() {
        return !OVERRIDES_FIND_RESOURCE_STATE_SNAPSHOT.get(getClass());
    }

    private void seedStateSnapshotInActiveScope(ID id, Optional<ResourceStateSnapshot> snapshot) {
        if (!org.praxisplatform.uischema.capability.ResourceStateSnapshotScope.isOpen() || id == null) {
            return;
        }
        org.praxisplatform.uischema.capability.ResourceStateSnapshotScope.seedServiceSnapshot(this, id, snapshot);
        if (supportsSeededStateSnapshotReuse()) {
            String resourceKey = getResourceKey();
            if (org.springframework.util.StringUtils.hasText(resourceKey)) {
                org.praxisplatform.uischema.capability.ResourceStateSnapshotScope.seedReusableResourceSnapshot(
                        resourceKey, id, snapshot);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void assertItemAccessible(ID id) {
        if (OVERRIDES_FIND_BY_ID.get(getClass())) {
            findById(id);
            return;
        }
        E entity = findEntityById(id);
        seedStateSnapshotInActiveScope(id, resolveEffectiveStateSnapshot(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ResourceRepresentationResult<ResponseDTO> findById(ID id) {
        E entity = findEntityById(id);
        seedStateSnapshotInActiveScope(id, resolveEffectiveStateSnapshot(entity));
        return toResourceRepresentation(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> readContextualSurfaceSummary(
            ID id,
            org.praxisplatform.uischema.surface.ContextualSurfaceSummaryProjector.ProjectionPlan plan,
            java.util.Set<String> principalAuthorities) {
        return BaseResourceQueryService.super.readContextualSurfaceSummary(id, plan, principalAuthorities);
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

    /**
     * Called once for each materialized response batch, inside its read/write transaction.
     * By default, delegates to {@link #hydrateScopedResourceReferences(List)} so null/blank
     * {@code @ResourceReference} display fields are batch-resolved within the caller's access scope.
     */
    protected void enrichResponses(List<ResponseDTO> responses) {
        hydrateScopedResourceReferences(responses);
    }

    /**
     * Batch-hydrates any null/blank {@code @ResourceReference} display fields across {@code responses}
     * using at most 1 scoped query per referenced {@code resourceKey} when a {@link ScopedBatchReferenceHydrator}
     * bean is available in the application context.
     */
    protected final List<ResponseDTO> hydrateScopedResourceReferences(List<ResponseDTO> responses) {
        if (responses == null || responses.isEmpty() || scopedBatchReferenceHydratorProvider == null) {
            return responses;
        }
        ScopedBatchReferenceHydrator hydrator = scopedBatchReferenceHydratorProvider.getIfAvailable();
        return hydrator != null ? hydrator.hydrate(responses) : responses;
    }

    protected final ResponseDTO enrichSingleResponse(ResponseDTO response) {
        if (response != null) enrichResponses(List.of(response));
        return response;
    }

    /** Resolves a selected proxy before field-based mapping, authorization or state snapshots. */
    protected final E unproxyEntity(E entity) {
        return entity == null ? null : entityClass.cast(org.hibernate.Hibernate.unproxy(entity));
    }

    private List<ResponseDTO> toResponses(Collection<E> entities) {
        return toResponses(entities, "list");
    }

    private List<ResponseDTO> toResponses(Collection<E> entities, String surfaceOrOperation) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<E> unproxied = entities.stream().map(this::unproxyEntity).toList();
        List<ResponseDTO> responses = unproxied.stream()
                .map(getResourceMapper()::toResponse)
                .collect(Collectors.toCollection(ArrayList::new));
        enrichResponses(responses);
        return applyResponsesGovernance(unproxied, responses, surfaceOrOperation);
    }

    protected final List<ResponseDTO> applyResponsesGovernance(
            List<E> entities,
            List<ResponseDTO> responses,
            String surfaceOrOperation
    ) {
        if (responses == null || responses.isEmpty()) {
            return responses == null ? List.of() : responses;
        }
        List<ResourceGovernanceInterceptor> interceptors = getResourceGovernanceInterceptors();
        if (interceptors.isEmpty()) {
            return responses;
        }
        List<ResponseDTO> current = new ArrayList<>(responses);
        int pairCount = entities != null ? Math.min(entities.size(), current.size()) : 0;
        for (ResourceGovernanceInterceptor interceptor : interceptors) {
            for (int i = 0; i < pairCount; i++) {
                current.set(i, interceptor.decorateResponse(this, entities.get(i), current.get(i), surfaceOrOperation));
            }
            List<ResponseDTO> batch = interceptor.decorateResponses(this, List.copyOf(current), surfaceOrOperation);
            current = batch == null ? List.of() : new ArrayList<>(batch);
            pairCount = entities != null ? Math.min(entities.size(), current.size()) : 0;
        }
        return List.copyOf(current);
    }

    protected final ResponseDTO applySingleResponseGovernance(
            E entity,
            ResponseDTO response,
            String surfaceOrOperation
    ) {
        if (response == null) {
            return null;
        }
        List<ResponseDTO> decorated = applyResponsesGovernance(
                entity == null ? List.of() : List.of(entity),
                List.of(response),
                surfaceOrOperation
        );
        return decorated.isEmpty() ? null : decorated.getFirst();
    }

    protected Optional<ResourceStateSnapshot> toResourceStateSnapshot(E entity) { return Optional.empty(); }

    /** Public transactional bridge for the default state-snapshot provider. */
    @Override
    @Transactional(readOnly = true)
    public Optional<ResourceStateSnapshot> findResourceStateSnapshot(ID id) {
        return org.praxisplatform.uischema.capability.ResourceStateSnapshotScope.findSeededServiceSnapshot(this, id)
                .orElseGet(() -> {
                    try {
                        E entity = findEntityById(id);
                        Optional<ResourceStateSnapshot> snapshot = resolveEffectiveStateSnapshot(entity);
                        seedStateSnapshotInActiveScope(id, snapshot);
                        return snapshot;
                    } catch (EntityNotFoundException ex) {
                        return Optional.empty();
                    } catch (ResponseStatusException ex) {
                        int status = ex.getStatusCode().value();
                        if (status == 403 || status == 404) {
                            return Optional.empty();
                        }
                        throw ex;
                    } catch (RuntimeException ex) {
                        if (isAccessDeniedException(ex)) {
                            return Optional.empty();
                        }
                        throw ex;
                    }
                });
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
        return toResourceRepresentation(entity, "findById");
    }

    protected final ResourceRepresentationResult<ResponseDTO> toResourceRepresentation(
            E entity,
            String surfaceOrOperation
    ) {
        entity = unproxyEntity(entity);
        ResponseDTO response = enrichSingleResponse(getResourceMapper().toResponse(entity));
        response = applySingleResponseGovernance(entity, response, surfaceOrOperation);
        ResourceRepresentationResult<ResponseDTO> result = new ResourceRepresentationResult<>(
                response, getEntityResourceVersion(entity));
        if (this instanceof VersionedCreateUpdateResourceService<?, ?, ?, ?, ?>) {
            result.requirePersistedVersion();
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponseDTO> findAll() {
        return toResponses(findAllEntities(), "findAll");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponseDTO> findAllById(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<ID, E> byId = findEntitiesById(ids).stream()
                .collect(Collectors.toMap(this::extractId, Function.identity(), (left, right) -> left));
        return toResponses(ids.stream().map(byId::get).filter(Objects::nonNull).toList(), "findAllById");
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseDTO> filter(FilterDTO filter, Pageable pageable, Collection<ID> includeIds) {
        Page<E> page = filterEntitiesWithIncludeIds(filter, pageable, includeIds);
        return new PageImpl<>(toResponses(page.getContent(), "filter"), page.getPageable(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPage<ResponseDTO> filterByCursor(FilterDTO filter, Sort sort, String after, String before, int size) {
        CursorPage<E> page = filterEntitiesByCursor(filter, sort, after, before, size);
        enforceEntitiesAccessAllowed(page.content(), AccessMode.READ);
        return new CursorPage<>(
                toResponses(page.content(), "cursor"),
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

    protected List<CollectionExportField> allExportableFields() { return ResourceExportSupport.allExportableFields(getResponseType()); }

    protected List<CollectionExportField> exportFields() {
        return includeTableHiddenExportFieldsByDefault ? allExportableFields() : defaultExportFields();
    }

    /** Reapplies DTO governance even to host-authored field allowlists. */
    protected final List<CollectionExportField> governedExportFields() {
        List<CollectionExportField> fields = exportFields().stream()
                .filter(Objects::nonNull)
                .filter(field -> field.exportable() != Boolean.FALSE)
                .filter(field -> ResourceExportSupport.exportable(getResponseType(),
                        field.valuePath() == null || field.valuePath().isBlank() ? field.key() : field.valuePath()))
                .toList();
        for (ResourceGovernanceInterceptor interceptor : getResourceGovernanceInterceptors()) {
            List<CollectionExportField> filtered = interceptor.filterExportFields(this, fields);
            fields = filtered == null ? List.of() : List.copyOf(filtered);
        }
        return fields;
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
        for (ResourceGovernanceInterceptor interceptor : getResourceGovernanceInterceptors()) {
            interceptor.authorizeExport(this, request);
        }
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
        enforceEntitiesAccessAllowed(entities, AccessMode.EXPORT);
        boolean truncated = entities.size() > windowSize && (scope != CollectionExportScope.CURRENT_PAGE
                || request.pagination().pageSize() > windowSize);
        List<ResponseDTO> rows = toResponses(entities.subList(0, Math.min(windowSize, entities.size())), "export");
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
                resolveEffectiveFilterAccessScope().specification()
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
                resolveEffectiveFilterAccessScope().specification()
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
                resolveEffectiveFilterAccessScope().specification()
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
                resolveEffectiveStatsFieldRegistry(),
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
                properties.maxCells(),
                properties.defaultMinBucketCount()
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
            return resolveEffectiveStatsFieldRegistry().resolve(request.periodField().trim())
                    .orElseThrow(() -> new IllegalArgumentException("Stats period field is not allowed: " + request.periodField()));
        }
        return dimensions.stream()
                .map(ResolvedStatsDimension::descriptor)
                .filter(descriptor -> descriptor.timeSeriesEligible() && !descriptor.isTemporalInterval())
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
                resolveEffectiveStatsFieldRegistry(),
                properties.maxSeriesPoints(),
                properties.maxTimeSeriesWindowDays()
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

        StatsFieldRegistry effectiveRegistry = resolveEffectiveStatsFieldRegistry();
        StatsFieldDescriptor descriptor = statsEligibility.validateDistribution(
                request,
                effectiveRegistry,
                properties.maxBuckets()
        );
        StatsFieldDescriptor metricDescriptor = request.mode() == org.praxisplatform.uischema.stats.DistributionMode.TERMS
                ? statsEligibility.resolveMetricField(request.metric(), effectiveRegistry, "distribution")
                : null;
        Specification<E> specification = resolveResourceFilterQuery(request.filter(), Pageable.unpaged()).effectiveSpecification();
        return statsQueryExecutor.executeDistribution(
                entityManager,
                entityClass,
                specification,
                descriptor,
                metricDescriptor,
                request,
                properties.maxBuckets(),
                properties.defaultMinBucketCount()
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
        StatsFieldRegistry effectiveRegistry = resolveEffectiveStatsFieldRegistry();
        StatsFieldDescriptor descriptor = statsEligibility.validateComparison(request, effectiveRegistry, properties.maxBuckets());
        StatsFieldDescriptor periodDescriptor = effectiveRegistry.resolve(request.periodField()).orElseThrow();
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
        List<org.praxisplatform.uischema.stats.service.ResolvedStatsDimension> dimensions = List.of(
                new org.praxisplatform.uischema.stats.service.ResolvedStatsDimension(
                        new org.praxisplatform.uischema.stats.dto.StatsDimensionRequest(
                                descriptor.field(),
                                org.praxisplatform.uischema.stats.StatsDimensionExtraction.NONE,
                                descriptor.field()
                        ),
                        descriptor
                )
        );
        int candidateCellLimit = Math.max(properties.maxCells(), candidateLimit + 1);
        GroupByStatsResponse current = statsQueryExecutor.executeGroupBy(entityManager, entityClass,
                baseSpec.and(ComparisonPeriodSpecifications.forPeriod(periodDescriptor.keyPropertyPath(), period.currentFrom(), period.currentTo(), period.timezone())),
                dimensions, metrics, groupRequest, null, candidateLimit + 1, candidateCellLimit, properties.defaultMinBucketCount());
        GroupByStatsResponse previous = statsQueryExecutor.executeGroupBy(entityManager, entityClass,
                baseSpec.and(ComparisonPeriodSpecifications.forPeriod(periodDescriptor.keyPropertyPath(), period.previousFrom(), period.previousTo(), period.timezone())),
                dimensions, metrics, groupRequest, null, candidateLimit + 1, candidateCellLimit, properties.defaultMinBucketCount());
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
        if (!configuredIdentityPaths.isEmpty()) {
            return configuredIdentityPaths;
        }
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

        String identityTitle = resolveIdentityTitle(entity);
        if (identityTitle != null) {
            return identityTitle;
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
        enforceEntityAccessAllowed(entity, AccessMode.READ);
        return entity;
    }

    protected List<E> findAllEntities() {
        return repository.findAll(resolveEffectiveFilterAccessScope().specification(), expandResourceSort(getDefaultSort()));
    }

    protected List<E> findEntitiesById(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("ids must not contain null");
        List<E> entities = repository.findAllById(ids).stream().map(this::unproxyEntity).toList();
        Set<ID> found = entities.stream().map(this::extractId).collect(Collectors.toSet());
        if (ids.stream().anyMatch(id -> !found.contains(id))) throw getNotFoundException();
        enforceEntitiesAccessAllowed(entities, AccessMode.READ);
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

    protected final void enforceEntityAccessAllowed(E entity, AccessMode mode) {
        assertEntityAccessAllowed(entity);
        AccessMode effectiveMode = mode != null ? mode : AccessMode.READ;
        for (ResourceGovernanceInterceptor interceptor : getResourceGovernanceInterceptors()) {
            interceptor.verifyEntityAccess(this, entity, effectiveMode);
        }
    }

    protected final void enforceEntitiesAccessAllowed(Collection<E> entities, AccessMode mode) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        assertEntitiesAccessAllowed(entities);
        AccessMode effectiveMode = mode != null ? mode : AccessMode.READ;
        for (ResourceGovernanceInterceptor interceptor : getResourceGovernanceInterceptors()) {
            interceptor.verifyEntitiesAccess(this, entities, effectiveMode);
        }
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

    protected final ResourceFilterAccessScope<E> resolveEffectiveFilterAccessScope() {
        ResourceFilterAccessScope<E> scope = Objects.requireNonNull(
                resolveResourceFilterAccessScope(),
                "resolveResourceFilterAccessScope() must return an explicit scope"
        );
        for (ResourceGovernanceInterceptor interceptor : getResourceGovernanceInterceptors()) {
            ResourceFilterAccessScope<E> customized = interceptor.customizeFilterAccessScope(this, scope);
            if (customized != null) {
                scope = customized;
            }
        }
        return scope;
    }

    protected Page<E> filterEntities(FilterDTO filterDTO, Pageable pageable) {
        ResourceFilterQuery<E> query = resolveResourceFilterQuery(filterDTO, pageable);
        return repository.findAll(query.effectiveSpecification(), query.pageable());
    }

    protected Page<E> filterEntitiesWithIncludeIds(FilterDTO filter, Pageable pageable, Collection<ID> includeIds) {
        ResourceFilterQuery<E> query = resolveResourceFilterQuery(filter, pageable);
        Page<E> page = repository.findAll(query.effectiveSpecification(), query.pageable());
        enforceEntitiesAccessAllowed(page.getContent(), AccessMode.READ);
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
            enforceEntitiesAccessAllowed(included, AccessMode.READ);
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
        ResourceFilterAccessScope<E> accessScope = resolveEffectiveFilterAccessScope();
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

    /**
     * Resolves human-readable display titles for a batch of entity IDs strictly within the
     * current tenant/access scope ({@link #resolveResourceFilterAccessScope()}).
     *
     * <p>Executes a single bounded query via {@link #findEntitiesByIdWithinAccessScope} and never
     * leaks titles of records outside the caller's authorized scope.</p>
     */
    @Transactional(readOnly = true)
    public Map<ID, String> resolveScopedTitlesByIds(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Set<ID> distinctIds = ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        ResourceFilterAccessScope<E> accessScope = resolveEffectiveFilterAccessScope();
        List<E> entities = findEntitiesByIdWithinAccessScope(distinctIds, accessScope.specification());
        enforceEntitiesAccessAllowed(entities, AccessMode.READ);
        Map<ID, String> titles = new LinkedHashMap<>();
        for (E entity : entities) {
            E unproxied = unproxyEntity(entity);
            ID id = extractId(unproxied);
            String title = computeOptionLabel(unproxied);
            if (id != null && title != null) {
                titles.put(id, title);
            }
        }
        return Map.copyOf(titles);
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
        return optionSourceEligibility.resolveEffectiveDescriptor(descriptor, resolveEffectiveStatsFieldRegistry());
    }

    protected List<ResolvedStatsMetric> resolveMetrics(List<StatsMetricRequest> metrics, String operationName) {
        StatsFieldRegistry effectiveRegistry = resolveEffectiveStatsFieldRegistry();
        return metrics.stream()
                .map(metric -> new ResolvedStatsMetric(
                        metric,
                        statsEligibility.resolveMetricField(metric, effectiveRegistry, operationName)
                ))
                .toList();
    }

    private Optional<ResourceStateSnapshot> resolveEffectiveStateSnapshot(E entity) {
        if (entity == null) {
            return Optional.empty();
        }
        Optional<ResourceStateSnapshot> custom = toResourceStateSnapshot(entity);
        ResourceStateSnapshot base = custom.orElse(null);
        if (base == null) {
            String autoState = readResourceStateValue(entity);
            if (autoState == null) {
                return Optional.empty();
            }
            base = ResourceStateSnapshot.of(autoState);
        }
        if (actionDefinitionRegistryProvider == null) {
            return Optional.of(base);
        }
        ActionDefinitionRegistry registry = actionDefinitionRegistryProvider.getIfAvailable();
        if (registry == null) {
            return Optional.of(base);
        }
        List<ActionDefinition> actions = registry.findByResourceKey(resolveResourceKey());
        if (actions == null || actions.isEmpty()) {
            return Optional.of(base);
        }
        Map<String, Object> enrichedMetadata = new LinkedHashMap<>(base.metadata());
        String currentState = base.state() != null ? base.state().trim() : null;
        for (ActionDefinition action : actions) {
            if (action == null || action.id() == null || action.id().isBlank()) {
                continue;
            }
            String actionId = action.id().trim();
            if ((action.scope() == null || action.scope() == ActionScope.ITEM)
                    && action.allowedStates() != null && !action.allowedStates().isEmpty()
                    && currentState != null && !currentState.isEmpty()) {
                boolean allowed = action.allowedStates().contains(currentState);
                enrichedMetadata.putIfAbsent(
                        DefaultResourceAvailabilityService.ACTION_PREFIX + "." + actionId + "."
                                + DefaultResourceAvailabilityService.KEY_ALLOWED,
                        allowed
                );
                if (!allowed) {
                    String reason = action.disallowedStateReason() != null && !action.disallowedStateReason().isBlank()
                            ? action.disallowedStateReason().trim()
                            : "resource-state-blocked";
                    enrichedMetadata.putIfAbsent(
                            DefaultResourceAvailabilityService.ACTION_PREFIX + "." + actionId + "."
                                    + DefaultResourceAvailabilityService.KEY_REASON,
                            reason
                    );
                }
            }
            if (action.legalBasisRef() != null && !action.legalBasisRef().isBlank()) {
                enrichedMetadata.putIfAbsent(
                        DefaultResourceAvailabilityService.ACTION_PREFIX + "." + actionId + "."
                                + DefaultResourceAvailabilityService.KEY_LEGAL_BASIS,
                        action.legalBasisRef().trim()
                );
            }
        }
        return Optional.of(new ResourceStateSnapshot(base.state(), enrichedMetadata));
    }

    private String readResourceStateValue(Object target) {
        if (target == null) {
            return null;
        }
        Class<?> clazz = target.getClass();
        for (Field field : getAllFields(clazz)) {
            if (AnnotatedElementUtils.hasAnnotation(field, ResourceState.class)) {
                field.setAccessible(true);
                try {
                    String text = extractResourceStateText(field.get(target));
                    if (text != null) {
                        return text;
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        for (Method method : getAllMethods(clazz)) {
            if (method.getParameterCount() == 0 && AnnotatedElementUtils.hasAnnotation(method, ResourceState.class)) {
                method.setAccessible(true);
                try {
                    String text = extractResourceStateText(method.invoke(target));
                    if (text != null) {
                        return text;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    private String extractResourceStateText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private boolean isAccessDeniedException(Throwable ex) {
        for (Class<?> current = ex == null ? null : ex.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            String name = current.getName();
            if ("org.springframework.security.access.AccessDeniedException".equals(name)
                    || "org.springframework.security.authorization.AuthorizationDeniedException".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private String resolveIdentityTitle(E entity) {
        if (entity == null) {
            return null;
        }
        for (Class<?> sourceType : List.of(getEntityClass(), getClass(), getResponseType())) {
            if (sourceType == null) {
                continue;
            }
            ResourceIdentity identity = findResourceIdentityAnnotation(sourceType);
            if (identity == null) {
                continue;
            }
            String fieldPath = !identity.titleField().isBlank()
                    ? identity.titleField().trim()
                    : !identity.displayLabelField().isBlank() ? identity.displayLabelField().trim() : null;
            if (fieldPath == null) {
                continue;
            }
            String text = readIdentityPathSilently(entity, fieldPath);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private ResourceIdentity findResourceIdentityAnnotation(Class<?> type) {
        ResourceIdentity direct = AnnotatedElementUtils.findMergedAnnotation(type, ResourceIdentity.class);
        if (direct != null) {
            return direct;
        }
        ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(type, ApiResource.class);
        return apiResource != null ? apiResource.identity() : null;
    }

    private String readIdentityPathSilently(E entity, String path) {
        try {
            return toNonBlankString(ResourceKeysetSupport.read(entity, path));
        } catch (RuntimeException ex) {
            return null;
        }
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
