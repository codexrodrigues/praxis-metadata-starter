package org.praxisplatform.uischema.service.base;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.praxisplatform.uischema.annotation.DraftCleared;
import org.praxisplatform.uischema.annotation.ResourceState;
import org.praxisplatform.uischema.bulk.BulkDomainCommandAdapter;
import org.praxisplatform.uischema.bulk.BulkDomainResourceAdapter;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.governance.AccessMode;
import org.praxisplatform.uischema.governance.MutationContext;
import org.praxisplatform.uischema.governance.MutationKind;
import org.praxisplatform.uischema.governance.ResourceGovernanceInterceptor;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Base transacional mutante do novo core resource-oriented.
 *
 * <p>
 * Toda a superficie de leitura vive em {@link AbstractBaseQueryResourceService}. Esta classe sobe
 * apenas quando o recurso precisa de create/update/delete, evitando que recursos read-only herdem
 * semantica de comando desabilitada.
 * </p>
 *
 * <p>
 * O contrato desta base pressupoe que a escrita do recurso continua sendo modelada por operacoes
 * tipadas e por um {@link ResourceMapper} canonico, sem introduzir dispatchers genericos,
 * contratos paralelos ou paths especiais para semantica que deveria permanecer
 * resource-oriented.
 * </p>
 */
public abstract class AbstractBaseResourceService<
        E,
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO,
        CreateDTO,
        UpdateDTO
> extends AbstractBaseQueryResourceService<E, ResponseDTO, ID, FilterDTO>
        implements BaseResourceService<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO> {

    private static final com.fasterxml.jackson.databind.ObjectMapper FALLBACK_OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .registerModule(new org.praxisplatform.uischema.id.CompositeIdJacksonModule());

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper resourceObjectMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private jakarta.validation.Validator resourceValidator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.praxisplatform.uischema.customfields.CustomFieldValidator customFieldValidator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.praxisplatform.uischema.customfields.ResourceTenantContextResolver resourceTenantContextResolver;

    private static final Object BULK_CONFIRMATION_TX_KEY = new Object();

    private final ThreadLocal<E> prevalidatedBulkConfirmationCandidate = new ThreadLocal<>();

    private static Object currentBulkConfirmationTransactionToken() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }
        Object token = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(BULK_CONFIRMATION_TX_KEY);
        if (token == null) {
            final Object created = new Object();
            org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(BULK_CONFIRMATION_TX_KEY, created);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(BULK_CONFIRMATION_TX_KEY);
                        }
                    });
            token = created;
        }
        return token;
    }

    /**
     * Returns the detached candidate already validated in the current bulk confirmation
     * transaction when {@code evaluate(CONFIRMATION)} immediately precedes {@code update(CONFIRMATION)},
     * or {@code null} when a write must execute full domain validation on its own.
     */
    protected final E prevalidatedBulkConfirmationCandidate() {
        return prevalidatedBulkConfirmationCandidate.get();
    }

    /**
     * Standalone validation without a persisted entity root. Resource writes use
     * {@link #prepareCustomFields(Object, String)} and an explicit create/update context.
     */
    protected Set<String> validateCustomFields(Object dto, String schemaType) {
        return prepareCustomFields(dto, schemaType).validateWithoutEntity();
    }

    /**
     * Captures definitions and primitive validation once, before the trusted state is loaded.
     * Overrides must preserve the preparation returned by super, including read-only keys.
     */
    protected org.praxisplatform.uischema.customfields.CustomFieldValidator.PreparedValidation prepareCustomFields(Object dto, String schemaType) {
        if (customFieldValidator != null && dto != null
                && org.praxisplatform.uischema.customfields.CustomFieldSupport.property(dto.getClass()).isPresent()) {
            return customFieldValidator.prepare(dto, resolveResourceKey(),
                    resourceTenantContextResolver == null ? null : resourceTenantContextResolver.resolveTenant().orElse(null), schemaType);
        }
        return org.praxisplatform.uischema.customfields.CustomFieldValidator.PreparedValidation.empty();
    }

    private final ThreadLocal<Boolean> partialIntentExecution = new ThreadLocal<>();
    private final ThreadLocal<List<String>> partialIntentModifiedFields = new ThreadLocal<>();

    protected final boolean isPartialIntentExecution() {
        return Boolean.TRUE.equals(partialIntentExecution.get());
    }

    protected AbstractBaseResourceService(
            BaseCrudRepository<E, ID> repository,
            GenericSpecificationsBuilder<E> specificationsBuilder,
            Class<E> entityClass
    ) {
        super(repository, specificationsBuilder, entityClass);
    }

    protected AbstractBaseResourceService(BaseCrudRepository<E, ID> repository, Class<E> entityClass) {
        super(repository, entityClass);
    }

    protected AbstractBaseResourceService(
            BaseCrudRepository<E, ID> repository,
            Class<E> entityClass,
            String... identityPaths
    ) {
        super(repository, entityClass, identityPaths);
    }

    protected AbstractBaseResourceService(
            BaseCrudRepository<E, ID> repository,
            GenericSpecificationsBuilder<E> specificationsBuilder,
            Class<E> entityClass,
            String... identityPaths
    ) {
        super(repository, specificationsBuilder, entityClass, identityPaths);
    }

    /**
     * Fornece o mapper canonico responsavel por traduzir DTOs de escrita e resposta.
     */
    @Override
    protected abstract ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID> getResourceMapper();

    /**
     * Prepares trusted draft facts needed by custom-field constraints before business hooks.
     * The default retains the request-shaped draft. Hosts may hydrate the new, unsaved candidate
     * and return an explicit DTO projection. This hook must not persist or perform business effects.
     */
    protected Object prepareCreateValidationDraft(CreateDTO dto, E candidate) { return dto; }

    /**
     * Prepares trusted update facts after access/version checks and before business hooks.
     * The candidate is detached from the authorized persisted entity; it may be hydrated or mapped
     * without mutating that entity. Return a DTO projection, not a JPA association graph.
     */
    protected Object prepareUpdateValidationDraft(ID id, E candidate, UpdateDTO dto) {
        getResourceMapper().applyUpdate(candidate, dto);
        ResponseDTO response = getResourceMapper().toResponse(candidate);
        return response == null ? dto : response;
    }

    /** Extra custom field keys whose existing JSONB state must be preserved across entity mapping on update. */
    protected Set<String> preservedCustomFieldKeysOnUpdate(E existing, UpdateDTO dto) {
        return Set.of();
    }

    /** Shared by ordinary, versioned and bulk writes using one captured definition/input snapshot. */
    protected final Set<String> validatePreparedCustomFieldsForUpdate(
            org.praxisplatform.uischema.customfields.CustomFieldValidator.PreparedValidation prepared,
            ID id, E existing, UpdateDTO dto) {
        Set<String> readOnlyKeys;
        if (!prepared.requiresDraft()) {
            readOnlyKeys = prepared.validateForUpdate(existing);
        } else {
            requireCleanVersionedContext();
            Object draft = prepareUpdateValidationDraft(id, snapshotBeforeMutation(existing), dto);
            requireCleanVersionedContext();
            readOnlyKeys = prepared.validateForUpdate(existing, draft);
        }
        Set<String> extraPreserved = preservedCustomFieldKeysOnUpdate(existing, dto);
        if (extraPreserved == null || extraPreserved.isEmpty()) {
            return readOnlyKeys;
        }
        Set<String> combined = new java.util.LinkedHashSet<>(readOnlyKeys);
        combined.addAll(extraPreserved);
        return Set.copyOf(combined);
    }

    @Override
    @Transactional
    public BaseResourceCommandService.SavedResult<ID, ResponseDTO> create(CreateDTO dto) {
        var customFields = prepareCustomFields(dto, "request");
        if (!customFields.requiresDraft()) customFields.validateForCreate();
        E entity = getResourceMapper().newEntity(dto);
        if (coalesceNullCustomFieldsContainer()) {
            org.praxisplatform.uischema.customfields.CustomFieldSupport.ensureInitializedContainer(entity);
        }
        if (customFields.requiresDraft()) {
            requireCleanVersionedContext();
            Object draft = prepareCreateValidationDraft(dto, entity);
            requireCleanVersionedContext();
            customFields.validateForCreate(draft);
        }
        beforeCreate(dto, entity);
        enforceCreateInitialState(dto, entity);
        validateCreate(ResourceMutationContext.create(), dto, entity);
        if (coalesceNullCustomFieldsContainer()) {
            org.praxisplatform.uischema.customfields.CustomFieldSupport.ensureInitializedContainer(entity);
        }
        MutationContext governanceCtx = new MutationContext(
                MutationKind.CREATE,
                false,
                List.of(),
                dto,
                null
        );
        invokeBeforeMutation(governanceCtx, entity, null);
        E saved = refreshManaged(getRepository().save(entity));
        afterCreate(dto, saved);
        invokeAfterMutationBeforeCommit(governanceCtx, saved);
        ResponseDTO response = applySingleResponseGovernance(
                saved,
                enrichSingleResponse(getResourceMapper().toResponse(saved)),
                "create"
        );
        return new BaseResourceCommandService.SavedResult<>(extractId(saved), response);
    }

    /**
     * Updates an ordinary resource. Versioned services must use their three-argument update:
     * this concrete method takes precedence over the versioned interface's default method,
     * so it rejects a missing precondition before loading an entity or running any hook.
     */
    @Override
    @Transactional
    public ResponseDTO update(ID id, UpdateDTO dto) {
        if (this instanceof VersionedCreateUpdateResourceService<?, ?, ?, ?, ?>) {
            throw ResourceVersionPreconditionException.required();
        }
        var customFields = prepareCustomFields(dto, "request");
        E existing = findEntityById(id);
        enforceEntityAccessAllowed(existing, AccessMode.WRITE);
        enforcePutMutableState(existing);
        Set<String> readOnlyKeys = validatePreparedCustomFieldsForUpdate(customFields, id, existing, dto);
        E previousSnapshot = snapshotBeforeMutationIfObserved(existing);
        beforeUpdate(id, existing, dto);
        validateUpdate(ResourceMutationContext.update(), id, existing, dto);
        // Domain hooks own server-side decisions. Protect their result from the request mapper.
        var readOnlyValues = org.praxisplatform.uischema.customfields.CustomFieldSupport.snapshotReadOnlyValues(existing, readOnlyKeys);
        applyResourceUpdate(id, existing, dto);
        org.praxisplatform.uischema.customfields.CustomFieldSupport.restoreReadOnlyValues(existing, readOnlyKeys, readOnlyValues);
        if (coalesceNullCustomFieldsContainer()) {
            org.praxisplatform.uischema.customfields.CustomFieldSupport.ensureInitializedContainer(existing);
        }
        MutationContext governanceCtx = buildUpdateGovernanceContext(existing, ResourceMutationContext.update(), dto);
        invokeBeforeMutation(governanceCtx, existing, previousSnapshot);
        E saved = refreshManaged(getRepository().save(existing));
        afterUpdate(id, saved, dto);
        invokeAfterMutationBeforeCommit(governanceCtx, saved);
        return applySingleResponseGovernance(
                saved,
                enrichSingleResponse(getResourceMapper().toResponse(saved)),
                "update"
        );
    }

    /** Whether versioned writes and deletes acquire a database row lock before domain hooks. */
    protected boolean usePessimisticLockOnVersionedWrite() { return true; }

    private E findEntityForMutation(ID id) {
        return findEntityForMutation(id, AccessMode.WRITE);
    }

    private E findEntityForMutation(ID id, AccessMode mode) {
        if (getEntityManager() == null || !usePessimisticLockOnVersionedWrite()) {
            E entity = findEntityById(id);
            enforceEntityAccessAllowed(entity, mode);
            return entity;
        }
        E entity = getEntityManager().find(getEntityClass(), id);
        if (entity == null) throw getNotFoundException();
        enforceEntityAccessAllowed(unproxyEntity(entity), mode);
        // A managed entity may predate this transaction's lock; refresh under that lock.
        getEntityManager().refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        entity = unproxyEntity(entity);
        enforceEntityAccessAllowed(entity, mode);
        return entity;
    }

    @Transactional
    public ResourceRepresentationResult<ResponseDTO> update(ID id, UpdateDTO dto,
            ResourceVersionUpdatePrecondition<ID> precondition) {
        return updateWithContext(id, dto, precondition, ResourceMutationContext.update());
    }

    private ResourceRepresentationResult<ResponseDTO> updateWithContext(ID id, UpdateDTO dto,
            ResourceVersionUpdatePrecondition<ID> precondition, ResourceMutationContext context) {
        requirePrecondition(precondition);
        requireCleanVersionedContext();
        var customFields = prepareCustomFields(dto, "request");
        E existing = findEntityForMutation(id);
        precondition.requireMatch(id, requireEntityVersion(existing));
        if (!isPartialIntentExecution() && context.bulkMode() != BulkMode.DOMAIN_COMMAND) {
            enforcePutMutableState(existing);
        }
        Set<String> readOnlyKeys = validatePreparedCustomFieldsForUpdate(customFields, id, existing, dto);
        E previousSnapshot = snapshotBeforeMutationIfObserved(existing);
        beforeUpdate(id, existing, dto);
        validateUpdate(context, id, existing, dto);
        var readOnlyValues = org.praxisplatform.uischema.customfields.CustomFieldSupport.snapshotReadOnlyValues(existing, readOnlyKeys);
        applyResourceUpdate(id, existing, dto);
        org.praxisplatform.uischema.customfields.CustomFieldSupport.restoreReadOnlyValues(existing, readOnlyKeys, readOnlyValues);
        if (coalesceNullCustomFieldsContainer()) {
            org.praxisplatform.uischema.customfields.CustomFieldSupport.ensureInitializedContainer(existing);
        }
        MutationContext governanceCtx = buildUpdateGovernanceContext(existing, context, dto);
        invokeBeforeMutation(governanceCtx, existing, previousSnapshot);
        E saved = refreshManaged(getRepository().save(existing));
        afterUpdate(id, saved, dto);
        // afterUpdate may change managed state. Certify only the last persisted revision.
        if (isPersistenceContextDirty(saved)) {
            saved = refreshManaged(saved);
        }
        invokeAfterMutationBeforeCommit(governanceCtx, saved);
        String surfaceOrOperation = isPartialIntentExecution() ? "patch" : (context.bulkMode() != null ? "bulkUpdate" : "update");
        ResourceRepresentationResult<ResponseDTO> result = toResourceRepresentation(saved, surfaceOrOperation);
        result.requirePersistedVersion();
        return result;
    }

    /** Call from a public transactional domain method; validates the revision under the row lock. */
    protected final ResourceRepresentationResult<ResponseDTO> executeVersionedMutation(ID id,
            ResourceVersionUpdatePrecondition<ID> precondition, Consumer<E> mutator) {
        requirePrecondition(precondition);
        requireCleanVersionedContext();
        Objects.requireNonNull(mutator, "mutator");
        E existing = findEntityForMutation(id);
        precondition.requireMatch(id, requireEntityVersion(existing));
        var validation = prepareDomainMutationValidation(existing);
        E previousSnapshot = snapshotBeforeMutationIfObserved(existing);
        mutator.accept(existing);
        validation.accept(existing);
        MutationContext governanceCtx = new MutationContext(
                MutationKind.DOMAIN_COMMAND,
                false,
                List.of(),
                null,
                resolveMutationStateSnapshotIfObserved(existing)
        );
        invokeBeforeMutation(governanceCtx, existing, previousSnapshot);
        E saved = refreshManaged(getRepository().save(existing));
        invokeAfterMutationBeforeCommit(governanceCtx, saved);
        ResourceRepresentationResult<ResponseDTO> result = toResourceRepresentation(saved, "command");
        result.requirePersistedVersion();
        return result;
    }

    private Consumer<E> prepareDomainMutationValidation(E existing) {
        if (customFieldValidator == null) return entity -> { };
        ResponseDTO projection = getResourceMapper().toResponse(existing);
        boolean projected = projection != null && org.praxisplatform.uischema.customfields.CustomFieldSupport.property(projection.getClass()).isPresent();
        boolean persisted = org.praxisplatform.uischema.customfields.CustomFieldSupport.property(existing.getClass()).isPresent();
        if (!projected && !persisted) {
            Class<?> updateType = org.springframework.core.ResolvableType.forClass(getClass()).as(AbstractBaseResourceService.class).getGeneric(5).resolve();
            if (updateType != null && org.praxisplatform.uischema.customfields.CustomFieldSupport.property(updateType).isPresent())
                throw new IllegalStateException("Domain commands require trusted custom-field state");
            return entity -> { };
        }
        var validation = customFieldValidator.prepareDomainMutation(projected ? projection : existing, existing,
                resolveResourceKey(), resourceTenantContextResolver == null ? null : resourceTenantContextResolver.resolveTenant().orElse(null), "request");
        return entity -> validation.validate(projected ? getResourceMapper().toResponse(entity) : entity, entity);
    }

    /** Detached previous/current form for commands whose decisions compare persisted state. */
    protected final ResourceRepresentationResult<ResponseDTO> executeVersionedMutation(ID id,
            ResourceVersionUpdatePrecondition<ID> precondition, BiConsumer<E, E> mutator) {
        return executeVersionedMutation(id, precondition, current -> mutator.accept(snapshotBeforeMutation(current), current));
    }

    /** Override when the domain needs a deep snapshot of mutable associations or value objects. */
    protected E snapshotBeforeMutation(E entity) {
        return snapshotScalarAndEmbeddedState(entity);
    }

    /**
     * Captures a detached shallow-entity snapshot containing only scalar fields, embedded value objects,
     * collections/maps and initialized {@code @ElementCollection} values while skipping JPA entity
     * relationships ({@code @ManyToOne}, {@code @OneToMany}, {@code @OneToOne}, {@code @ManyToMany})
     * so lazy Hibernate proxies never trigger unwanted initialization or fail detached validation.
     */
    protected final E snapshotScalarAndEmbeddedState(E entity) {
        if (entity == null) {
            return null;
        }
        E source = unproxyEntity(entity);
        E previous = BeanUtils.instantiateClass(getEntityClass());
        var copies = new java.util.IdentityHashMap<Object, Object>();
        copies.put(source, previous);
        for (Class<?> type = previous.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (field.isAnnotationPresent(ManyToOne.class)
                        || field.isAnnotationPresent(OneToOne.class)
                        || field.isAnnotationPresent(OneToMany.class)
                        || field.isAnnotationPresent(ManyToMany.class)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(source);
                    if (fieldValue == null || !org.hibernate.Hibernate.isInitialized(fieldValue)) {
                        continue;
                    }
                    if (field.isAnnotationPresent(ElementCollection.class)) {
                        field.set(previous, ResourceStateCopySupport.copy(fieldValue, copies));
                    } else if (field.isAnnotationPresent(Embedded.class)
                            || field.isAnnotationPresent(EmbeddedId.class)
                            || fieldValue.getClass().isAnnotationPresent(Embeddable.class)) {
                        field.set(previous, ResourceStateCopySupport.copy(fieldValue, copies));
                    } else {
                        field.set(previous, ResourceStateCopySupport.copy(fieldValue, copies));
                    }
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Cannot snapshot scalar/embedded entity state", ex);
                }
            }
        }
        return previous;
    }

    /** Optional operation scope, composed with the ordinary collection scope before target limits. */
    protected ResourceFilterAccessScope<E> resolveBulkResourceFilterAccessScope() {
        return resolveEffectiveFilterAccessScope();
    }

    /** Bounded collection selection using the same canonical filter and tenant scope as ordinary queries. */
    @Transactional(readOnly = true)
    public List<ID> selectBulkIdentities(FilterDTO filter, Collection<ID> included, Collection<ID> excluded, int maxTargets) {
        if (maxTargets < 1 || maxTargets > 10000) throw new IllegalArgumentException("Invalid bulk target limit");
        var scope = resolveEffectiveFilterAccessScope().specification()
                .and(Objects.requireNonNull(resolveBulkResourceFilterAccessScope()).specification());
        var spec = included == null
                ? scope.and(resolveResourceFilterQuery(filter, org.springframework.data.domain.Pageable.unpaged()).effectiveSpecification())
                : scope.and(selectionSpecification(included));
        if (excluded != null && !excluded.isEmpty())
            spec = spec.and(org.springframework.data.jpa.domain.Specification.not(selectionSpecification(excluded)));
        var rows = getRepository().findAll(spec, org.springframework.data.domain.PageRequest.of(0, maxTargets + 1,
                expandResourceSort(getDefaultSort()))).getContent();
        if (rows.size() > maxTargets) throw new IllegalArgumentException("Bulk target limit exceeded");
        return rows.stream().map(this::extractId).toList();
    }

    /**
     * Projects a persisted resource into its full update DTO before applying the sparse changes.
     * Override when response and update have different semantic shapes. No entity serialization occurs.
     */
    protected UpdateDTO toBulkUpdate(E entity, com.fasterxml.jackson.databind.ObjectMapper mapper,
                                      com.fasterxml.jackson.databind.JavaType updateType) {
        var response = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(getResourceMapper().toResponse(entity));
        var projected = mapper.createObjectNode();
        for (var property : mapper.getDeserializationConfig().introspect(updateType).findProperties()) {
            if (!property.couldDeserialize()) continue;
            if (!response.has(property.getName())) {
                if (isReadOnlyOrPreviewUpdateProperty(updateType.getRawClass(), property.getName())) {
                    continue;
                }
                throw new IllegalStateException("Override toBulkUpdate for update properties absent from response");
            }
            projected.set(property.getName(), response.get(property.getName()).deepCopy());
        }
        try {
            return mapper.readerFor(updateType)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue((com.fasterxml.jackson.databind.JsonNode) projected);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Failed to project response into " + updateType.getRawClass().getSimpleName(), ex);
        }
    }

    private static boolean isReadOnlyOrPreviewUpdateProperty(Class<?> updateClass, String propertyName) {
        for (Class<?> current = updateClass; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(propertyName);
                var jsonProp = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                if (jsonProp != null && jsonProp.access() == com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) {
                    return true;
                }
                var ui = field.getAnnotation(org.praxisplatform.uischema.extension.annotation.UISchema.class);
                if (ui != null && ui.readOnly()) {
                    return true;
                }
                var schema = field.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
                if (schema != null && schema.accessMode() == io.swagger.v3.oas.annotations.media.Schema.AccessMode.READ_ONLY) {
                    return true;
                }
                return false;
            } catch (NoSuchFieldException ignored) {
                // continue walking hierarchy
            }
        }
        return false;
    }

    /**
     * Applies a versioned partial update ({@link org.praxisplatform.uischema.dto.PartialUpdateInput})
     * by projecting the persisted entity into {@code UpdateDTO}, copying only the supplied patch
     * properties, and delegating to {@link #update(Object, Object, ResourceVersionUpdatePrecondition)}.
     */
    @Transactional
    protected ResourceRepresentationResult<ResponseDTO> applyVersionedPatch(
            ID id,
            org.praxisplatform.uischema.dto.PartialUpdateInput patch,
            ResourceVersionUpdatePrecondition<ID> precondition) {
        if (precondition == null) {
            throw org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException.required();
        }
        var type = org.springframework.core.ResolvableType.forClass(org.springframework.util.ClassUtils.getUserClass(this))
                .as(AbstractBaseResourceService.class).getGeneric(5);
        if (type.resolve() == null || type.resolve() == Object.class) {
            throw new IllegalStateException("Update DTO must be concrete to applyVersionedPatch");
        }
        var mapper = resourceObjectMapper != null ? resourceObjectMapper : FALLBACK_OBJECT_MAPPER;
        var updateType = mapper.constructType(org.springframework.core.GenericTypeResolver.resolveType(
                type.getType(), org.springframework.core.ResolvableType.forClass(org.springframework.util.ClassUtils.getUserClass(this)).resolve()));
        E entity = findEntityById(id);
        enforceEntityAccessAllowed(entity, AccessMode.WRITE);
        UpdateDTO dto = toBulkUpdate(entity, mapper, updateType);
        ResourceStateCopySupport.copySuppliedPatchProperties(patch, dto, mapper);
        if (resourceValidator != null) {
            var violations = resourceValidator.validate(dto);
            if (!violations.isEmpty()) {
                throw new jakarta.validation.ConstraintViolationException(violations);
            }
        }
        partialIntentExecution.set(Boolean.TRUE);
        partialIntentModifiedFields.set(patch != null ? List.copyOf(patch.suppliedPropertyNames()) : List.of());
        try {
            return update(id, dto, precondition);
        } finally {
            partialIntentExecution.remove();
            partialIntentModifiedFields.remove();
        }
    }

    /**
     * Resolves the canonical {@link org.praxisplatform.uischema.bulk.BulkIdentityCodec} for the
     * generic {@code ID} type of this service ({@code Integer}, {@code Long}, {@code String},
     * {@code UUID}, or composite {@code @Embeddable} / {@code @CompositeResourceId}).
     */
    @SuppressWarnings("unchecked")
    public org.praxisplatform.uischema.bulk.BulkIdentityCodec<?, ID> resolveBulkIdentityCodec() {
        var idResolvable = org.springframework.core.ResolvableType.forClass(org.springframework.util.ClassUtils.getUserClass(this))
                .as(AbstractBaseResourceService.class).getGeneric(2);
        Class<?> idClass = idResolvable.resolve();
        if (idClass == null || idClass == Object.class) {
            throw new IllegalStateException("Resource ID type must be concrete to resolveBulkIdentityCodec");
        }
        if (idClass == Integer.class) {
            return (org.praxisplatform.uischema.bulk.BulkIdentityCodec<?, ID>) org.praxisplatform.uischema.bulk.BulkIdentityCodecs.integers();
        }
        if (idClass == Long.class) {
            return (org.praxisplatform.uischema.bulk.BulkIdentityCodec<?, ID>) org.praxisplatform.uischema.bulk.BulkIdentityCodecs.longs();
        }
        if (idClass == String.class) {
            return (org.praxisplatform.uischema.bulk.BulkIdentityCodec<?, ID>) org.praxisplatform.uischema.bulk.BulkIdentityCodecs.strings();
        }
        if (idClass == java.util.UUID.class) {
            return (org.praxisplatform.uischema.bulk.BulkIdentityCodec<?, ID>) org.praxisplatform.uischema.bulk.BulkIdentityCodecs.uuids();
        }
        return org.praxisplatform.uischema.bulk.BulkIdentityCodecs.composite((Class<ID>) idClass);
    }

    /** Creates a domain adapter without exposing repositories or protected lifecycle hooks to the host. */
    public org.praxisplatform.uischema.bulk.BulkDomainResourceAdapter<ID, FilterDTO> bulkDomainAdapter(
            com.fasterxml.jackson.databind.ObjectMapper mapper, jakarta.validation.Validator validator,
            org.praxisplatform.uischema.concurrency.ResourceVersionEtagService etags,
            java.util.function.Supplier<org.praxisplatform.uischema.concurrency.ResourceVersionScope> scope) {
        var type = org.springframework.core.ResolvableType.forClass(org.springframework.util.ClassUtils.getUserClass(this))
                .as(AbstractBaseResourceService.class).getGeneric(5);
        if (type.resolve() == null || type.resolve() == Object.class)
            throw new IllegalStateException("Bulk update DTO must be concrete");
        var updateType = mapper.constructType(org.springframework.core.GenericTypeResolver.resolveType(type.getType(), org.springframework.util.ClassUtils.getUserClass(this)));
        var filterType = org.springframework.core.ResolvableType.forClass(org.springframework.util.ClassUtils.getUserClass(this))
                .as(AbstractBaseResourceService.class).getGeneric(3);
        var filterJavaType = mapper.constructType(org.springframework.core.GenericTypeResolver.resolveType(filterType.getType(), org.springframework.util.ClassUtils.getUserClass(this)));
        var projectionMapper = BulkUpdateMapper.projectionCopy(mapper);
        var updateMapper = BulkUpdateMapper.strictCopy(mapper);
        record PreparedConfirmation<ID, E, UpdateDTO>(
                Object transactionToken,
                ID id,
                String expectedVersion,
                com.fasterxml.jackson.databind.JsonNode plan,
                ResourceMutationContext context,
                E lockedEntity,
                E validatedCandidate,
                UpdateDTO dto,
                Set<String> readOnlyKeys
        ) { }
        record CertifiedVersion<ID>(
                Object transactionToken,
                ID id,
                String version
        ) { }
        return new org.praxisplatform.uischema.bulk.BulkDomainResourceAdapter<>() {
            private final ThreadLocal<PreparedConfirmation<ID, E, UpdateDTO>> preparedConfirmation = new ThreadLocal<>();
            private final ThreadLocal<CertifiedVersion<ID>> certifiedVersion = new ThreadLocal<>();

            public FilterDTO readFilter(com.fasterxml.jackson.databind.JsonNode filter) {
                return mapper.convertValue(filter, filterJavaType);
            }
            public List<ID> selectBulkIdentities(FilterDTO filter, Collection<ID> included, Collection<ID> excluded, int max) {
                return AbstractBaseResourceService.this.selectBulkIdentities(filter, included, excluded, max);
            }
            private String version(ID id, E entity) {
                return etags.create(Objects.requireNonNull(scope.get()), resolveResourceKey(), id, requireEntityVersion(entity));
            }
            public String currentVersion(ID id) {
                CertifiedVersion<ID> certified = certifiedVersion.get();
                certifiedVersion.remove();
                Object txToken = certified == null ? null : currentBulkConfirmationTransactionToken();
                if (certified != null && txToken != null
                        && txToken.equals(certified.transactionToken())
                        && Objects.equals(id, certified.id())) {
                    return certified.version();
                }
                return version(id, findEntityById(id));
            }
            public void discardEvaluationState() {
                if (getEntityManager() == null) {
                    preparedConfirmation.remove();
                    certifiedVersion.remove();
                    return;
                }
                PreparedConfirmation<ID, E, UpdateDTO> prepared = preparedConfirmation.get();
                Object txToken = prepared == null ? null : currentBulkConfirmationTransactionToken();
                if (prepared != null && txToken != null && txToken.equals(prepared.transactionToken())
                        && getEntityManager().isJoinedToTransaction()
                        && !getEntityManager().unwrap(org.hibernate.Session.class).isDirty()) {
                    return;
                }
                preparedConfirmation.remove();
                certifiedVersion.remove();
                getEntityManager().clear();
            }
            public Candidate evaluate(ID id, String expected, List<org.praxisplatform.uischema.bulk.BulkFieldChange> changes,
                                      ResourceMutationContext context,
                                      org.praxisplatform.uischema.bulk.BulkEditableFields fields) {
                preparedConfirmation.remove();
                certifiedVersion.remove();
                context.requireBulk();
                var mode = context.bulkMode();
                requireCleanVersionedContext();
                Object confirmationTxToken = context.stage() == ResourceMutationContext.Stage.CONFIRMATION
                        ? currentBulkConfirmationTransactionToken() : null;
                E existing;
                if (confirmationTxToken != null && getEntityManager() != null) {
                    var persistenceContext = getEntityManager()
                            .unwrap(org.hibernate.engine.spi.SharedSessionContractImplementor.class)
                            .getPersistenceContext();
                    if (persistenceContext.getNumberOfManagedEntities() == 0) {
                        E entity = getEntityManager().find(getEntityClass(), id, LockModeType.PESSIMISTIC_WRITE);
                        if (entity == null) throw getNotFoundException();
                        entity = unproxyEntity(entity);
                        enforceEntityAccessAllowed(entity, AccessMode.WRITE);
                        existing = entity;
                    } else {
                        E entity = getEntityManager().find(getEntityClass(), id);
                        if (entity == null) throw getNotFoundException();
                        enforceEntityAccessAllowed(unproxyEntity(entity), AccessMode.WRITE);
                        getEntityManager().refresh(entity, LockModeType.PESSIMISTIC_WRITE);
                        entity = unproxyEntity(entity);
                        enforceEntityAccessAllowed(entity, AccessMode.WRITE);
                        existing = entity;
                    }
                } else {
                    existing = findEntityById(id);
                    enforceEntityAccessAllowed(existing, AccessMode.WRITE);
                }
                String observed = version(id, existing);
                if (!observed.equals(expected)) throw org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException.stale();
                E candidate = snapshotBeforeMutation(existing);
                var current = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(toBulkUpdate(candidate, projectionMapper, updateType));
                var plan = org.praxisplatform.uischema.bulk.BulkFieldChanges.applyTo(current, changes,
                        fields.writableFields(mode), fields.clearableFields(mode));
                UpdateDTO dto;
                try { dto = updateMapper.convertValue(plan, updateType); }
                catch (IllegalArgumentException invalid) {
                    if (invalid.getCause() instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException)
                        throw new org.praxisplatform.uischema.bulk.BulkFieldChangeValidationException("Bulk field value has an invalid type",invalid);
                    throw invalid;
                }
                validateBulkCandidate(dto);
                Set<String> readOnlyKeys = validatePreparedCustomFieldsForUpdate(prepareCustomFields(dto, "request"), id, existing, dto);
                beforeUpdate(id, candidate, dto);
                var admissionEvidence = validateUpdate(context, id, candidate, dto);
                // A domain hook may hydrate request values. Validate and capture those exact decisions.
                validateBulkCandidate(dto);
                applyResourceUpdate(id, candidate, dto);
                if (coalesceNullCustomFieldsContainer()) {
                    org.praxisplatform.uischema.customfields.CustomFieldSupport.ensureInitializedContainer(candidate);
                }
                requireCleanVersionedContext();
                com.fasterxml.jackson.databind.JsonNode facts = current;
                if (admissionEvidence != null) {
                    facts = mapper.createObjectNode().set("resource", current);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) facts).set("admission", admissionEvidence.deepCopy());
                }
                com.fasterxml.jackson.databind.JsonNode planNode = mapper.valueToTree(dto);
                if (context.stage() == ResourceMutationContext.Stage.CONFIRMATION) {
                    Object txToken = currentBulkConfirmationTransactionToken();
                    if (txToken != null) {
                        preparedConfirmation.set(new PreparedConfirmation<>(
                                txToken, id, observed, planNode, context, existing, candidate, dto, readOnlyKeys));
                    }
                }
                return new Candidate(observed, facts, planNode);
            }
            private void validateBulkCandidate(UpdateDTO dto) {
                var violations = validator.validate(dto);
                if (!violations.isEmpty()) throw new jakarta.validation.ConstraintViolationException(violations);
            }
            public void update(ID id, String expected, com.fasterxml.jackson.databind.JsonNode plan, ResourceMutationContext context) {
                PreparedConfirmation<ID, E, UpdateDTO> prepared = preparedConfirmation.get();
                preparedConfirmation.remove();
                certifiedVersion.remove();
                context.requireBulk();
                if (context.stage() != ResourceMutationContext.Stage.CONFIRMATION) throw new IllegalArgumentException("Confirmation context required");
                requireCleanVersionedContext();
                if (getEntityManager() == null) throw new IllegalStateException("JPA bulk mutation requires an EntityManager");
                var precondition = new ResourceVersionUpdatePrecondition<>(etags, expected,
                        Objects.requireNonNull(scope.get()), resolveResourceKey(), id);
                Object txToken = currentBulkConfirmationTransactionToken();
                if (prepared != null
                        && txToken != null
                        && txToken.equals(prepared.transactionToken())
                        && Objects.equals(id, prepared.id())
                        && Objects.equals(expected, prepared.expectedVersion())
                        && sameBulkPlan(plan, prepared.plan())
                        && Objects.equals(context, prepared.context())) {
                    E existing;
                    if (prepared.lockedEntity() != null
                            && getEntityManager().contains(prepared.lockedEntity())
                            && getEntityManager().getLockMode(prepared.lockedEntity()) == LockModeType.PESSIMISTIC_WRITE) {
                        existing = unproxyEntity(prepared.lockedEntity());
                        enforceEntityAccessAllowed(existing, AccessMode.WRITE);
                    } else {
                        if (!usePessimisticLockOnVersionedWrite()) {
                            E locked = getEntityManager().find(getEntityClass(), id);
                            if (locked == null) throw getNotFoundException();
                            enforceEntityAccessAllowed(unproxyEntity(locked), AccessMode.WRITE);
                            getEntityManager().refresh(locked, LockModeType.PESSIMISTIC_WRITE);
                            enforceEntityAccessAllowed(unproxyEntity(locked), AccessMode.WRITE);
                        }
                        existing = findEntityForMutation(id);
                    }
                    precondition.requireMatch(id, requireEntityVersion(existing));
                    UpdateDTO dto = prepared.dto();
                    Set<String> readOnlyKeys = prepared.readOnlyKeys();
                    E previousSnapshot = snapshotBeforeMutationIfObserved(existing);
                    MutationContext governanceCtx = buildUpdateGovernanceContext(existing, context, dto);
                    prevalidatedBulkConfirmationCandidate.set(prepared.validatedCandidate());
                    try {
                        beforeUpdate(id, existing, dto);
                        validateUpdate(context, id, existing, dto);
                        var readOnlyValues = org.praxisplatform.uischema.customfields.CustomFieldSupport.snapshotReadOnlyValues(existing, readOnlyKeys);
                        applyResourceUpdate(id, existing, dto);
                        org.praxisplatform.uischema.customfields.CustomFieldSupport.restoreReadOnlyValues(existing, readOnlyKeys, readOnlyValues);
                        if (coalesceNullCustomFieldsContainer()) {
                            org.praxisplatform.uischema.customfields.CustomFieldSupport.ensureInitializedContainer(existing);
                        }
                    } finally {
                        prevalidatedBulkConfirmationCandidate.remove();
                    }
                    invokeBeforeMutation(governanceCtx, existing, previousSnapshot);
                    E saved = flushManagedForBulkUpdate(getRepository().save(existing));
                    afterUpdate(id, saved, dto);
                    if (isPersistenceContextDirty(saved)) {
                        saved = flushManagedForBulkUpdate(saved);
                    }
                    invokeAfterMutationBeforeCommit(governanceCtx, saved);
                    long persistedVersion = requireEntityVersion(saved);
                    certifiedVersion.set(new CertifiedVersion<>(txToken, id,
                            etags.create(Objects.requireNonNull(scope.get()), resolveResourceKey(), id, persistedVersion)));
                    return;
                }
                if (!usePessimisticLockOnVersionedWrite()) {
                    E locked = getEntityManager().find(getEntityClass(), id);
                    if (locked == null) throw getNotFoundException();
                    enforceEntityAccessAllowed(unproxyEntity(locked), AccessMode.WRITE);
                    getEntityManager().refresh(locked, LockModeType.PESSIMISTIC_WRITE);
                    enforceEntityAccessAllowed(unproxyEntity(locked), AccessMode.WRITE);
                }
                ResourceRepresentationResult<ResponseDTO> result = AbstractBaseResourceService.this.updateWithContext(
                        id, updateMapper.convertValue(plan, updateType), precondition, context);
                if (txToken != null) {
                    certifiedVersion.set(new CertifiedVersion<>(txToken, id,
                            etags.create(Objects.requireNonNull(scope.get()), resolveResourceKey(), id, result.requirePersistedVersion())));
                }
            }
        };
    }

    private static boolean sameBulkPlan(
            com.fasterxml.jackson.databind.JsonNode left,
            com.fasterxml.jackson.databind.JsonNode right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) return false;
            var fields = left.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!right.has(entry.getKey())
                        || !sameBulkPlan(entry.getValue(), right.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int i = 0; i < left.size(); i++) {
                if (!sameBulkPlan(left.get(i), right.get(i))) return false;
            }
            return true;
        }
        if (left.isIntegralNumber() && right.isIntegralNumber()) {
            return left.bigIntegerValue().equals(right.bigIntegerValue());
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        return left.equals(right);
    }

    private void requireCleanVersionedContext() {
        if (getEntityManager() == null) return;
        if (!getEntityManager().isJoinedToTransaction()) throw new IllegalStateException("Versioned mutation requires an active write transaction");
        if (getEntityManager().unwrap(org.hibernate.Session.class).isDirty())
            throw new IllegalStateException("Versioned mutation requires a clean persistence context before governance");
    }

    private void requirePrecondition(ResourceVersionUpdatePrecondition<ID> precondition) {
        if (precondition == null) throw ResourceVersionPreconditionException.required();
    }

    private long requireEntityVersion(E entity) {
        return getEntityResourceVersion(entity).orElseThrow(() -> new IllegalStateException("Versioned mutation requires a persisted @Version"));
    }

    @Override
    @Transactional
    public void deleteById(ID id) {
        E entity = findEntityForMutation(id, AccessMode.DELETE);
        E previousSnapshot = snapshotBeforeMutationIfObserved(entity);
        MutationContext governanceCtx = new MutationContext(
                MutationKind.DELETE,
                false,
                List.of(),
                null,
                resolveMutationStateSnapshotIfObserved(entity)
        );
        beforeDelete(id, entity);
        validateDelete(ResourceMutationContext.delete(), id, entity);
        invokeBeforeMutation(governanceCtx, entity, previousSnapshot);
        getRepository().delete(entity);
        afterDelete(id, entity);
        invokeAfterMutationBeforeCommit(governanceCtx, entity);
    }

    @Override
    @Transactional
    public void deleteAllById(Collection<ID> ids) {
        if (ids == null || ids.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("ids must not contain null");
        List<ID> distinctIds = new LinkedHashSet<>(ids).stream().toList();
        // Validate every target before running hooks or deleting any target.
        Map<ID, E> locked = new java.util.LinkedHashMap<>();
        distinctIds.stream().sorted(java.util.Comparator.comparing(org.praxisplatform.uischema.id.CompositeIdSupport::stringify))
                .forEach(id -> locked.put(id, findEntityForMutation(id, AccessMode.DELETE)));
        List<E> entities = distinctIds.stream().map(locked::get).toList();
        boolean observeGovernance = !getResourceGovernanceInterceptors().isEmpty();
        List<E> snapshots = observeGovernance ? entities.stream().map(this::snapshotBeforeMutation).toList() : List.of();
        List<MutationContext> contexts = observeGovernance
                ? entities.stream().map(entity -> new MutationContext(
                        MutationKind.DELETE,
                        false,
                        List.of(),
                        null,
                        resolveMutationStateSnapshotIfObserved(entity)
                )).toList()
                : List.of();
        beforeDeleteAllById(distinctIds);
        var admissionContext = ResourceMutationContext.deleteBatch();
        for (int index = 0; index < entities.size(); index++) {
            E entity = entities.get(index);
            ID id = distinctIds.get(index);
            beforeDelete(id, entity);
            validateDelete(admissionContext, id, entity);
            if (observeGovernance) {
                invokeBeforeMutation(contexts.get(index), entity, snapshots.get(index));
            }
        }
        // No target is scheduled for deletion until every locked target has been admitted.
        for (int index = 0; index < entities.size(); index++) {
            E entity = entities.get(index);
            ID id = distinctIds.get(index);
            getRepository().delete(entity);
            afterDelete(id, entity);
            if (observeGovernance) {
                invokeAfterMutationBeforeCommit(contexts.get(index), entity);
            }
        }
        afterDeleteAllById(distinctIds);
    }

    /**
     * Read-only domain admission after hydration/access hooks and before the first save.
     */
    protected void validateCreate(ResourceMutationContext context, CreateDTO dto, E entity) { }

    /**
     * Read-only domain admission after hydration/access hooks and before applying the request.
     * Return bounded private dependency evidence from that exact admission, or null when none
     * is required. Bulk compares it again at confirmation; it is never a public projection.
     */
    protected com.fasterxml.jackson.databind.JsonNode validateUpdate(ResourceMutationContext context, ID id, E current, UpdateDTO dto) { return null; }

    /** Read-only domain admission under the native deletion lock; collection origin is explicit. */
    protected void validateDelete(ResourceMutationContext context, ID id, E current) { }

    /** Structural preparation and hydration before create admission. */
    protected void beforeCreate(CreateDTO dto, E entity) {
    }

    /** Opt-in hook allowing hosts with non-null customFields JSONB columns to initialize null containers to {@code {}}. */
    protected boolean coalesceNullCustomFieldsContainer() {
        return false;
    }

    /** Hook transacional chamado depois do save/refresh e antes da conversao para response DTO. */
    protected void afterCreate(CreateDTO dto, E entity) {
    }

    /**
     * Applies and validates the candidate before persistence. The same hook runs on the detached
     * bulk candidate and on ordinary/versioned writes; it must not persist side effects.
     * Hosts may hydrate server-owned fields here before invoking their deterministic rules.
     */
    protected void applyResourceUpdate(ID id, E entity, UpdateDTO dto) {
        getResourceMapper().applyUpdate(entity, dto);
    }

    /** Hook transacional chamado depois da carga da entidade existente e antes do mapper aplicar o update. */
    protected void beforeUpdate(ID id, E entity, UpdateDTO dto) {
    }

    /** Hook transacional chamado depois do save/refresh e antes da conversao para response DTO. */
    protected void afterUpdate(ID id, E entity, UpdateDTO dto) {
    }

    /**
     * Hook transacional chamado antes da exclusao individual, quando a entidade existe.
     */
    protected void beforeDelete(ID id, E entity) {
    }

    /**
     * Hook transacional chamado depois da exclusao individual, quando a entidade existe.
     */
    protected void afterDelete(ID id, E entity) {
    }

    /**
     * Hook transacional chamado antes da exclusao em lote por IDs.
     */
    protected void beforeDeleteAllById(Collection<ID> ids) {
    }

    /**
     * Hook transacional chamado depois da exclusao em lote por IDs.
     */
    protected void afterDeleteAllById(Collection<ID> ids) {
    }

    /**
     * Resolve uma referencia JPA para entidade relacionada sem executar busca completa.
     */
    protected <RelatedEntity, RelatedID> RelatedEntity relatedReference(
            Class<RelatedEntity> relatedEntityClass,
            RelatedID id
    ) {
        if (id == null) {
            return null;
        }
        return requireEntityManagerForRelationships().getReference(relatedEntityClass, id);
    }

    /**
     * Resolve referencias JPA para uma colecao de IDs relacionados, preservando a ordem recebida.
     */
    protected <RelatedEntity, RelatedID> List<RelatedEntity> relatedReferences(
            Class<RelatedEntity> relatedEntityClass,
            Collection<RelatedID> ids
    ) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .map(id -> relatedReference(relatedEntityClass, id))
                .toList();
    }

    /**
     * Resolve referencias JPA para uma colecao de IDs relacionados, preservando ordem e removendo duplicatas.
     */
    protected <RelatedEntity, RelatedID> Set<RelatedEntity> relatedReferenceSet(
            Class<RelatedEntity> relatedEntityClass,
            Collection<RelatedID> ids
    ) {
        return new LinkedHashSet<>(relatedReferences(relatedEntityClass, ids));
    }

    /**
     * Substitui uma colecao relacional mutavel por referencias ja resolvidas.
     */
    protected <RelatedEntity> void replaceRelationshipCollection(
            Collection<RelatedEntity> target,
            Collection<? extends RelatedEntity> relatedEntities
    ) {
        if (target == null) {
            throw new IllegalArgumentException("target relationship collection must not be null");
        }
        target.clear();
        if (relatedEntities != null && !relatedEntities.isEmpty()) {
            target.addAll(relatedEntities);
        }
    }

    private EntityManager requireEntityManagerForRelationships() {
        if (getEntityManager() == null) {
            throw new IllegalStateException("EntityManager is required to resolve related entity references");
        }
        return getEntityManager();
    }

    private E refreshManaged(E entity) {
        if (getEntityManager() == null) {
            return entity;
        }
        getEntityManager().flush();
        E managed = getEntityManager().contains(entity) ? entity : getEntityManager().merge(entity);
        getEntityManager().refresh(managed);
        return managed;
    }

    private E flushManagedForBulkUpdate(E entity) {
        if (getEntityManager() == null) {
            return entity;
        }
        E managed = getEntityManager().contains(entity) ? entity : getEntityManager().merge(entity);
        getEntityManager().flush();
        return managed;
    }

    private boolean isPersistenceContextDirty(E entity) {
        if (getEntityManager() == null) {
            return false;
        }
        if (!getEntityManager().contains(entity)) {
            return true;
        }
        org.hibernate.Session session = getEntityManager().unwrap(org.hibernate.Session.class);
        return session != null && session.isDirty();
    }

    /**
     * Generates a non-persistent creation draft from an existing authorized entity, clearing all
     * fields annotated with {@link DraftCleared}. Does not persist or mutate source state.
     */
    @Transactional(readOnly = true)
    public CreateDTO duplicateDraftFromEntity(ID id) {
        Class<?> createType = org.springframework.core.ResolvableType.forClass(org.springframework.util.ClassUtils.getUserClass(this))
                .as(AbstractBaseResourceService.class).getGeneric(4).resolve();
        if (createType == null || createType == Object.class) {
            throw new IllegalStateException("Create DTO must be concrete to duplicateDraftFromEntity");
        }
        @SuppressWarnings("unchecked")
        Class<CreateDTO> typed = (Class<CreateDTO>) createType;
        return duplicateDraftFromEntity(id, typed);
    }

    /**
     * Projects an existing authorized entity into an arbitrary draft DTO class, clearing every
     * property annotated with {@link DraftCleared}.
     */
    @Transactional(readOnly = true)
    public <D> D duplicateDraftFromEntity(ID id, Class<D> draftType) {
        Objects.requireNonNull(draftType, "draftType");
        E entity = findEntityById(id);
        ResponseDTO response = enrichSingleResponse(getResourceMapper().toResponse(entity));
        var mapper = resourceObjectMapper != null ? resourceObjectMapper : FALLBACK_OBJECT_MAPPER;
        var tree = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(response);
        Set<String> clearedKeys = new java.util.LinkedHashSet<>();
        collectDraftClearedPropertyKeys(draftType, mapper, clearedKeys);
        Class<?> responseType = getResponseType();
        if (responseType != null && responseType != draftType) {
            collectDraftClearedPropertyKeys(responseType, mapper, clearedKeys);
        }
        clearedKeys.forEach(tree::remove);
        D draft;
        try {
            draft = mapper.readerFor(draftType)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue((com.fasterxml.jackson.databind.JsonNode) tree);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Failed to build duplicate draft for " + draftType.getSimpleName(), ex);
        }
        if (draft != null && !draftType.isRecord()) {
            clearDraftFieldsReflectively(draft);
        }
        return draft;
    }

    private static void collectDraftClearedPropertyKeys(
            Class<?> dtoType,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            Set<String> clearedKeys
    ) {
        Set<String> internalNames = new java.util.LinkedHashSet<>();
        for (Class<?> c = dtoType; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(field, DraftCleared.class)) {
                    internalNames.add(field.getName());
                    clearedKeys.add(field.getName());
                }
            }
            for (java.lang.reflect.Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                        && org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(method, DraftCleared.class)) {
                    String name = method.getName();
                    if (name.startsWith("get") && name.length() > 3) {
                        String prop = java.beans.Introspector.decapitalize(name.substring(3));
                        internalNames.add(prop);
                        clearedKeys.add(prop);
                    } else if (name.startsWith("is") && name.length() > 2) {
                        String prop = java.beans.Introspector.decapitalize(name.substring(2));
                        internalNames.add(prop);
                        clearedKeys.add(prop);
                    } else {
                        internalNames.add(name);
                        clearedKeys.add(name);
                    }
                }
            }
            if (c.isRecord()) {
                for (var component : c.getRecordComponents()) {
                    if (org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(component, DraftCleared.class)) {
                        internalNames.add(component.getName());
                        clearedKeys.add(component.getName());
                    }
                }
            }
        }
        if (internalNames.isEmpty()) {
            return;
        }
        var beanDesc = mapper.getDeserializationConfig().introspect(mapper.constructType(dtoType));
        for (var prop : beanDesc.findProperties()) {
            if (internalNames.contains(prop.getInternalName()) || internalNames.contains(prop.getName())) {
                clearedKeys.add(prop.getName());
                var fullName = prop.getFullName();
                if (fullName != null && !fullName.isEmpty()) {
                    clearedKeys.add(fullName.getSimpleName());
                }
            }
        }
    }

    private static void clearDraftFieldsReflectively(Object draft) {
        for (Class<?> c = draft.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || field.getType().isPrimitive()) {
                    continue;
                }
                boolean marked = org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(field, DraftCleared.class);
                if (!marked) {
                    String getterName = "get" + Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
                    try {
                        marked = org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                                c.getDeclaredMethod(getterName), DraftCleared.class);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                if (marked) {
                    try {
                        field.setAccessible(true);
                        field.set(draft, null);
                    } catch (IllegalAccessException ex) {
                        throw new IllegalStateException("Cannot clear @DraftCleared field " + field.getName(), ex);
                    }
                }
            }
        }
    }

    /**
     * Resolves a foreign-key entity strictly within the referenced resource's mandatory tenant and
     * entity access scope. Fails with HTTP 400 when missing or inaccessible so cross-tenant existence is never leaked.
     */
    protected final <RelatedEntity, RelatedID> RelatedEntity requireScopedReference(
            AbstractBaseQueryResourceService<RelatedEntity, ?, RelatedID, ?> targetService,
            RelatedID referenceId
    ) {
        return requireScopedReference(targetService, referenceId, null);
    }

    protected final <RelatedEntity, RelatedID> RelatedEntity requireScopedReference(
            AbstractBaseQueryResourceService<RelatedEntity, ?, RelatedID, ?> targetService,
            RelatedID referenceId,
            String fieldName
    ) {
        Objects.requireNonNull(targetService, "targetService");
        String label = fieldName != null && !fieldName.isBlank() ? fieldName.trim() : "reference";
        if (referenceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Referenced identifier for '" + label + "' must not be null");
        }
        var scopeSpec = targetService.resolveEffectiveFilterAccessScope().specification();
        var idSpec = targetService.selectionSpecification(List.of(referenceId));
        RelatedEntity resolved = targetService.getRepository().findOne(scopeSpec.and(idSpec))
                .map(targetService::unproxyEntity)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Referenced resource '" + label + "' not found or outside current access scope"));
        targetService.enforceEntityAccessAllowed(resolved, AccessMode.READ);
        return resolved;
    }

    /** Evaluates one candidate in detached mode for {@code BulkMode.DOMAIN_COMMAND} proposals. */
    protected com.fasterxml.jackson.databind.JsonNode evaluateBulkDomainCommandCandidate(
            ID id,
            E candidate,
            com.fasterxml.jackson.databind.JsonNode parameters
    ) {
        return parameters == null ? (resourceObjectMapper != null ? resourceObjectMapper : FALLBACK_OBJECT_MAPPER).createObjectNode() : parameters;
    }

    /** Executes one locked item mutation for {@code BulkMode.DOMAIN_COMMAND} confirmation. */
    protected void executeBulkDomainCommandItem(
            ID id,
            E existing,
            com.fasterxml.jackson.databind.JsonNode evaluatedPlan
    ) {
        throw new UnsupportedOperationException(
                "Override executeBulkDomainCommandItem(...) to execute BulkMode.DOMAIN_COMMAND items"
        );
    }

    /** Creates a {@link BulkDomainCommandAdapter} bound to this service's lifecycle, ETag verification and governance hooks. */
    public BulkDomainCommandAdapter<ID> bulkDomainCommandAdapter(
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            org.praxisplatform.uischema.concurrency.ResourceVersionEtagService etags,
            Supplier<org.praxisplatform.uischema.concurrency.ResourceVersionScope> scope
    ) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(etags, "etags");
        Objects.requireNonNull(scope, "scope");
        return new BulkDomainCommandAdapter<>() {
            @Override
            public BulkDomainResourceAdapter.Candidate evaluate(
                    ID id,
                    String expectedVersion,
                    com.fasterxml.jackson.databind.JsonNode parameters
            ) {
                E existing = findEntityById(id);
                enforceEntityAccessAllowed(existing, AccessMode.WRITE);
                String observed = etags.create(Objects.requireNonNull(scope.get()), resolveResourceKey(), id, requireEntityVersion(existing));
                if (!observed.equals(expectedVersion)) {
                    throw ResourceVersionPreconditionException.stale();
                }
                E candidate = snapshotBeforeMutation(existing);
                com.fasterxml.jackson.databind.JsonNode facts = mapper.valueToTree(getResourceMapper().toResponse(candidate));
                com.fasterxml.jackson.databind.JsonNode plan = evaluateBulkDomainCommandCandidate(id, candidate, parameters);
                if (plan == null) {
                    plan = mapper.createObjectNode();
                }
                return new BulkDomainResourceAdapter.Candidate(observed, facts, plan);
            }

            @Override
            public void execute(
                    ID id,
                    String expectedVersion,
                    com.fasterxml.jackson.databind.JsonNode evaluatedPlan
            ) {
                requireCleanVersionedContext();
                var precondition = new ResourceVersionUpdatePrecondition<>(etags, expectedVersion,
                        Objects.requireNonNull(scope.get()), resolveResourceKey(), id);
                E existing = findEntityForMutation(id, AccessMode.WRITE);
                precondition.requireMatch(id, requireEntityVersion(existing));
                E previousSnapshot = snapshotBeforeMutationIfObserved(existing);
                MutationContext governanceCtx = new MutationContext(
                        MutationKind.DOMAIN_COMMAND,
                        false,
                        List.of(),
                        evaluatedPlan,
                        resolveMutationStateSnapshotIfObserved(existing)
                );
                executeBulkDomainCommandItem(id, existing, evaluatedPlan);
                invokeBeforeMutation(governanceCtx, existing, previousSnapshot);
                E saved = refreshManaged(getRepository().save(existing));
                invokeAfterMutationBeforeCommit(governanceCtx, saved);
            }
        };
    }

    private E snapshotBeforeMutationIfObserved(E entity) {
        return getResourceGovernanceInterceptors().isEmpty() ? null : snapshotBeforeMutation(entity);
    }

    private org.praxisplatform.uischema.capability.ResourceStateSnapshot resolveMutationStateSnapshotIfObserved(E entity) {
        if (getResourceGovernanceInterceptors().isEmpty() || entity == null) {
            return null;
        }
        return toResourceStateSnapshot(entity).orElse(null);
    }

    private MutationContext buildUpdateGovernanceContext(
            E existing,
            ResourceMutationContext context,
            UpdateDTO dto
    ) {
        MutationKind kind = isPartialIntentExecution()
                ? MutationKind.PARTIAL_INTENT
                : context.bulkMode() != null ? MutationKind.BULK_UPDATE : MutationKind.UPDATE;
        List<String> modified = isPartialIntentExecution() && partialIntentModifiedFields.get() != null
                ? partialIntentModifiedFields.get()
                : List.of();
        return new MutationContext(
                kind,
                isPartialIntentExecution(),
                modified,
                dto,
                resolveMutationStateSnapshotIfObserved(existing)
        );
    }

    private void invokeBeforeMutation(MutationContext context, E candidate, E previous) {
        for (ResourceGovernanceInterceptor interceptor : getResourceGovernanceInterceptors()) {
            interceptor.beforeMutation(this, context, candidate, previous);
        }
    }

    private void invokeAfterMutationBeforeCommit(MutationContext context, E persisted) {
        for (ResourceGovernanceInterceptor interceptor : getResourceGovernanceInterceptors()) {
            interceptor.afterMutationBeforeCommit(this, context, persisted);
        }
    }

    private void enforceCreateInitialState(CreateDTO dto, E entity) {
        ResourceStateEntry entry = findResourceStateEntry(entity, dto);
        if (entry == null || entry.annotation().initialStates().length == 0) {
            return;
        }
        Set<String> allowed = normalizeStates(entry.annotation().initialStates());
        if (allowed.isEmpty()) {
            return;
        }
        if (entry.value() == null || !allowed.contains(entry.value())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Initial resource state '" + entry.value() + "' is not allowed for creation; allowed initial states: " + allowed
            );
        }
    }

    private void enforcePutMutableState(E existing) {
        ResourceStateEntry entry = findResourceStateEntry(existing);
        if (entry == null || entry.annotation().putMutableStates().length == 0) {
            return;
        }
        Set<String> allowed = normalizeStates(entry.annotation().putMutableStates());
        if (allowed.isEmpty()) {
            return;
        }
        if (entry.value() == null || !allowed.contains(entry.value())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Full PUT update is not permitted in state '" + entry.value() + "'; allowed states: " + allowed
            );
        }
    }

    private static Set<String> normalizeStates(String[] states) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String state : states) {
            if (state != null && !state.isBlank()) {
                result.add(state.trim());
            }
        }
        return result;
    }

    private record ResourceStateEntry(ResourceState annotation, String value) {
    }

    private ResourceStateEntry findResourceStateEntry(Object... targets) {
        if (targets == null) {
            return null;
        }
        for (Object target : targets) {
            if (target == null) {
                continue;
            }
            Class<?> clazz = target.getClass();
            for (java.lang.reflect.Field field : getAllFields(clazz)) {
                ResourceState ann = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(field, ResourceState.class);
                if (ann != null) {
                    field.setAccessible(true);
                    try {
                        return new ResourceStateEntry(ann, stringifyStateValue(field.get(target)));
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }
            for (java.lang.reflect.Method method : getAllMethods(clazz)) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                ResourceState ann = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(method, ResourceState.class);
                if (ann != null) {
                    method.setAccessible(true);
                    try {
                        return new ResourceStateEntry(ann, stringifyStateValue(method.invoke(target)));
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
            if (clazz.isRecord()) {
                for (var component : clazz.getRecordComponents()) {
                    ResourceState ann = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(component, ResourceState.class);
                    if (ann != null) {
                        var accessor = component.getAccessor();
                        accessor.setAccessible(true);
                        try {
                            return new ResourceStateEntry(ann, stringifyStateValue(accessor.invoke(target)));
                        } catch (ReflectiveOperationException ignored) {
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String stringifyStateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
