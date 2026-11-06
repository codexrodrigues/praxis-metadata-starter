package org.praxisplatform.uischema.service.base;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.transaction.annotation.Transactional;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.praxisplatform.uischema.customfields.CustomFieldValidator customFieldValidator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.praxisplatform.uischema.customfields.ResourceTenantContextResolver resourceTenantContextResolver;

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

    /** Shared by ordinary, versioned and bulk writes using one captured definition/input snapshot. */
    protected final Set<String> validatePreparedCustomFieldsForUpdate(
            org.praxisplatform.uischema.customfields.CustomFieldValidator.PreparedValidation prepared,
            ID id, E existing, UpdateDTO dto) {
        if (!prepared.requiresDraft()) return prepared.validateForUpdate(existing);
        requireCleanVersionedContext();
        Object draft = prepareUpdateValidationDraft(id, snapshotBeforeMutation(existing), dto);
        requireCleanVersionedContext();
        return prepared.validateForUpdate(existing, draft);
    }

    @Override
    @Transactional
    public BaseResourceCommandService.SavedResult<ID, ResponseDTO> create(CreateDTO dto) {
        var customFields = prepareCustomFields(dto, "request");
        if (!customFields.requiresDraft()) customFields.validateForCreate();
        E entity = getResourceMapper().newEntity(dto);
        if (customFields.requiresDraft()) {
            requireCleanVersionedContext();
            Object draft = prepareCreateValidationDraft(dto, entity);
            requireCleanVersionedContext();
            customFields.validateForCreate(draft);
        }
        beforeCreate(dto, entity);
        validateCreate(ResourceMutationContext.create(), dto, entity);
        E saved = refreshManaged(getRepository().save(entity));
        afterCreate(dto, saved);
        return new BaseResourceCommandService.SavedResult<>(extractId(saved), enrichSingleResponse(getResourceMapper().toResponse(saved)));
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
        Set<String> readOnlyKeys = validatePreparedCustomFieldsForUpdate(customFields, id, existing, dto);
        beforeUpdate(id, existing, dto);
        validateUpdate(ResourceMutationContext.update(), id, existing, dto);
        // Domain hooks own server-side decisions. Protect their result from the request mapper.
        var readOnlyValues = org.praxisplatform.uischema.customfields.CustomFieldSupport.snapshotReadOnlyValues(existing, readOnlyKeys);
        applyResourceUpdate(id, existing, dto);
        org.praxisplatform.uischema.customfields.CustomFieldSupport.restoreReadOnlyValues(existing, readOnlyKeys, readOnlyValues);
        E saved = refreshManaged(getRepository().save(existing));
        afterUpdate(id, saved, dto);
        return enrichSingleResponse(getResourceMapper().toResponse(saved));
    }

    /** Whether versioned writes and deletes acquire a database row lock before domain hooks. */
    protected boolean usePessimisticLockOnVersionedWrite() { return true; }

    private E findEntityForMutation(ID id) {
        if (getEntityManager() == null || !usePessimisticLockOnVersionedWrite()) return findEntityById(id);
        E entity = getEntityManager().find(getEntityClass(), id);
        if (entity == null) throw getNotFoundException();
        assertEntityAccessAllowed(unproxyEntity(entity));
        // A managed entity may predate this transaction's lock; refresh under that lock.
        getEntityManager().refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        entity = unproxyEntity(entity);
        assertEntityAccessAllowed(entity);
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
        Set<String> readOnlyKeys = validatePreparedCustomFieldsForUpdate(customFields, id, existing, dto);
        beforeUpdate(id, existing, dto);
        validateUpdate(context, id, existing, dto);
        var readOnlyValues = org.praxisplatform.uischema.customfields.CustomFieldSupport.snapshotReadOnlyValues(existing, readOnlyKeys);
        applyResourceUpdate(id, existing, dto);
        org.praxisplatform.uischema.customfields.CustomFieldSupport.restoreReadOnlyValues(existing, readOnlyKeys, readOnlyValues);
        E saved = refreshManaged(getRepository().save(existing));
        afterUpdate(id, saved, dto);
        // afterUpdate may change managed state. Certify only the last persisted revision.
        saved = refreshManaged(saved);
        ResourceRepresentationResult<ResponseDTO> result = toResourceRepresentation(saved);
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
        mutator.accept(existing);
        validation.accept(existing);
        E saved = refreshManaged(getRepository().save(existing));
        ResourceRepresentationResult<ResponseDTO> result = toResourceRepresentation(saved);
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
        E previous = org.springframework.beans.BeanUtils.instantiateClass(getEntityClass());
        var copies = new java.util.IdentityHashMap<Object, Object>();
        copies.put(entity, previous);
        ResourceStateCopySupport.copyFields(entity, previous, copies);
        return previous;
    }

    /** Optional operation scope, composed with the ordinary collection scope before target limits. */
    protected ResourceFilterAccessScope<E> resolveBulkResourceFilterAccessScope() {
        return resolveResourceFilterAccessScope();
    }

    /** Bounded collection selection using the same canonical filter and tenant scope as ordinary queries. */
    @Transactional(readOnly = true)
    public List<ID> selectBulkIdentities(FilterDTO filter, Collection<ID> included, Collection<ID> excluded, int maxTargets) {
        if (maxTargets < 1 || maxTargets > 10000) throw new IllegalArgumentException("Invalid bulk target limit");
        var scope = Objects.requireNonNull(resolveResourceFilterAccessScope()).specification()
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
            if (!response.has(property.getName()))
                throw new IllegalStateException("Override toBulkUpdate for update properties absent from response");
            projected.set(property.getName(), response.get(property.getName()).deepCopy());
        }
        return mapper.convertValue(projected, updateType);
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
        return new org.praxisplatform.uischema.bulk.BulkDomainResourceAdapter<>() {
            public FilterDTO readFilter(com.fasterxml.jackson.databind.JsonNode filter) {
                return mapper.convertValue(filter, filterJavaType);
            }
            public List<ID> selectBulkIdentities(FilterDTO filter, Collection<ID> included, Collection<ID> excluded, int max) {
                return AbstractBaseResourceService.this.selectBulkIdentities(filter, included, excluded, max);
            }
            private String version(ID id, E entity) {
                return etags.create(Objects.requireNonNull(scope.get()), resolveResourceKey(), id, requireEntityVersion(entity));
            }
            public String currentVersion(ID id) { return version(id, findEntityById(id)); }
            public void discardEvaluationState() {
                if (getEntityManager() != null && getEntityManager().unwrap(org.hibernate.Session.class).isDirty())
                    getEntityManager().clear();
            }
            public Candidate evaluate(ID id, String expected, List<org.praxisplatform.uischema.bulk.BulkFieldChange> changes,
                                      ResourceMutationContext context,
                                      org.praxisplatform.uischema.bulk.BulkEditableFields fields) {
                context.requireBulk();
                var mode = context.bulkMode();
                requireCleanVersionedContext();
                E existing = findEntityById(id);
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
                validatePreparedCustomFieldsForUpdate(prepareCustomFields(dto, "request"), id, existing, dto);
                beforeUpdate(id, candidate, dto);
                var admissionEvidence = validateUpdate(context, id, candidate, dto);
                // A domain hook may hydrate request values. Validate and capture those exact decisions.
                validateBulkCandidate(dto);
                applyResourceUpdate(id, candidate, dto);
                requireCleanVersionedContext();
                com.fasterxml.jackson.databind.JsonNode facts = current;
                if (admissionEvidence != null) {
                    facts = mapper.createObjectNode().set("resource", current);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) facts).set("admission", admissionEvidence.deepCopy());
                }
                return new Candidate(observed, facts, mapper.valueToTree(dto));
            }
            private void validateBulkCandidate(UpdateDTO dto) {
                var violations = validator.validate(dto);
                if (!violations.isEmpty()) throw new jakarta.validation.ConstraintViolationException(violations);
            }
            public void update(ID id, String expected, com.fasterxml.jackson.databind.JsonNode plan, ResourceMutationContext context) {
                context.requireBulk();
                if (context.stage() != ResourceMutationContext.Stage.CONFIRMATION) throw new IllegalArgumentException("Confirmation context required");
                requireCleanVersionedContext();
                if (getEntityManager() == null) throw new IllegalStateException("JPA bulk mutation requires an EntityManager");
                if (!usePessimisticLockOnVersionedWrite()) {
                    E locked = getEntityManager().find(getEntityClass(), id);
                    if (locked == null) throw getNotFoundException();
                    assertEntityAccessAllowed(unproxyEntity(locked));
                    getEntityManager().refresh(locked, LockModeType.PESSIMISTIC_WRITE);
                    assertEntityAccessAllowed(unproxyEntity(locked));
                }
                var precondition = new ResourceVersionUpdatePrecondition<>(etags, expected,
                        Objects.requireNonNull(scope.get()), resolveResourceKey(), id);
                AbstractBaseResourceService.this.updateWithContext(id, updateMapper.convertValue(plan, updateType), precondition, context);
            }
        };
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
        E entity = findEntityForMutation(id);
        beforeDelete(id, entity);
        validateDelete(ResourceMutationContext.delete(), id, entity);
        getRepository().delete(entity);
        afterDelete(id, entity);
    }

    @Override
    @Transactional
    public void deleteAllById(Collection<ID> ids) {
        if (ids == null || ids.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("ids must not contain null");
        List<ID> distinctIds = new LinkedHashSet<>(ids).stream().toList();
        // Validate every target before running hooks or deleting any target.
        Map<ID, E> locked = new java.util.LinkedHashMap<>();
        distinctIds.stream().sorted(java.util.Comparator.comparing(org.praxisplatform.uischema.id.CompositeIdSupport::stringify))
                .forEach(id -> locked.put(id, findEntityForMutation(id)));
        List<E> entities = distinctIds.stream().map(locked::get).toList();
        beforeDeleteAllById(distinctIds);
        var admissionContext = ResourceMutationContext.deleteBatch();
        for (int index = 0; index < entities.size(); index++) {
            E entity = entities.get(index);
            ID id = distinctIds.get(index);
            beforeDelete(id, entity);
            validateDelete(admissionContext, id, entity);
        }
        // No target is scheduled for deletion until every locked target has been admitted.
        for (int index = 0; index < entities.size(); index++) {
            E entity = entities.get(index);
            ID id = distinctIds.get(index);
            getRepository().delete(entity);
            afterDelete(id, entity);
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

    /**
     * Hook transacional chamado depois do save/refresh e antes da conversao para response DTO.
     */
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

    /**
     * Hook transacional chamado depois da carga da entidade existente e antes do mapper aplicar o update.
     */
    protected void beforeUpdate(ID id, E entity, UpdateDTO dto) {
    }

    /**
     * Hook transacional chamado depois do save/refresh e antes da conversao para response DTO.
     */
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
}
