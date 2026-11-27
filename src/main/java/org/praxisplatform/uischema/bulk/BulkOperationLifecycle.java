package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;

/**
 * Governed lifecycle boundary for one host bulk namespace. Readiness is derived from current MVC,
 * operation, OpenAPI and provider composition, then fenced by the durable operation-control row.
 * This service publishes no HTTP endpoint or capability.
 */
public final class BulkOperationLifecycle {
    private final BulkResourceOperationBindings bindings;
    private final BulkOperationStructuralCompiler compiler;
    private final CanonicalOperationResolver operationResolver;
    private final OpenApiDocumentService documents;
    private final BulkExecutionInfrastructure runtime;
    private final BulkControlPlaneInfrastructure controlPlane;
    private final List<BulkOperationDescriptorProvider> providers;
    private final ThreadLocal<Boolean> lifecycleCacheFence = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<ResponseProjectionFrame> responseProjection = new ThreadLocal<>();

    public BulkOperationLifecycle(BulkResourceOperationBindings bindings,
            CanonicalOperationResolver operationResolver, OpenApiDocumentService documents,
            ActionDefinitionRegistry actionDefinitions, ObjectMapper mapper,
            SchemaReferenceResolver schemaReferences, BulkExecutionInfrastructure runtime,
            BulkControlPlaneInfrastructure controlPlane,
            List<BulkOperationDescriptorProvider> providers) {
        this(bindings, operationResolver, documents, actionDefinitions, mapper, schemaReferences, runtime,
                controlPlane, providers, new org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver(documents));
    }

    public BulkOperationLifecycle(BulkResourceOperationBindings bindings,
            CanonicalOperationResolver operationResolver, OpenApiDocumentService documents,
            ActionDefinitionRegistry actionDefinitions, ObjectMapper mapper,
            SchemaReferenceResolver schemaReferences, BulkExecutionInfrastructure runtime,
            BulkControlPlaneInfrastructure controlPlane, List<BulkOperationDescriptorProvider> providers,
            org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilities) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.operationResolver = Objects.requireNonNull(operationResolver, "operationResolver");
        this.compiler = new BulkOperationStructuralCompiler(bindings, operationResolver, documents,
                actionDefinitions, mapper, schemaReferences, capabilities);
        this.documents = Objects.requireNonNull(documents, "documents");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        if (!runtime.namespace().equals(controlPlane.namespace())
                || !runtime.deploymentId().equals(controlPlane.deploymentId()))
            throw new IllegalArgumentException("Runtime and control plane must bind the same namespace and logical deployment");
        if (!documents.supportsFreshBulkLifecycleComposition()
                || !documents.supportsFreshBulkLifecyclePublicCacheCoherence())
            throw new IllegalStateException("Bulk lifecycle requires fresh OpenAPI composition coherent with this node's public document cache");
        documents.installBulkLifecyclePublicationGuard((generation, digest) -> {
            var current = readPublicationForDocumentGuard();
            if (current == null || !current.published() || current.generation() != generation
                    || !Objects.equals(current.documentDigest(), digest))
                throw unavailable("Local OpenAPI photograph does not match the durable publication");
        });
        documents.installBulkLifecycleInvalidationGuard(() -> {
            if (!lifecycleCacheFence.get()) suspendAllBeforeCacheClear();
        });
    }

    /** Returns the durable lifecycle namespace owned by this service. */
    public String namespace() {
        return runtime.namespace();
    }

    /**
     * Reads the publication-current durable READY generation through the runtime-role fence,
     * without composing OpenAPI or installing a local photograph. The identity must name a
     * valid declared bulk confirmation with an operational descriptor provider bound to this
     * exact runtime infrastructure in this namespace.
     *
     * <p>An empty result means the operation is absent, not READY, or filtered by the durable
     * publication fence. Database, role and catalog failures propagate. This method must start
     * outside any transaction. The result is a momentary hint, not authorization, admission or
     * a promise of readiness; {@link #reconcilePublished(BulkOperationControlIdentity, long)}
     * independently revalidates the generation, descriptor and document digest before installation.</p>
     */
    public OptionalLong readPublishedReadyGeneration(BulkOperationControlIdentity identity) {
        requireIdentity(identity);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable("Published readiness lookup must start outside transactions");
        if (!bindings.diagnostics().isEmpty() || bindings.bulkOperations().stream().noneMatch(binding ->
                binding.confirmationOperationId().equals(identity.confirmationOperationId())))
            throw contractDiagnostic("Published readiness lookup requires a valid declared bulk confirmation",
                    identity.confirmationOperationId());
        BulkOperationDescriptorProvider provider = providerSnapshot().get(identity.confirmationOperationId());
        if (provider == null || provider.executionInfrastructure() != runtime)
            throw contractDiagnostic(
                    "Published readiness lookup requires a provider bound to this runtime infrastructure",
                    identity.confirmationOperationId());
        JdbcBulkOperationControl.Snapshot current = read(identity);
        return current != null && current.ready()
                ? OptionalLong.of(current.generation()) : OptionalLong.empty();
    }

    /**
     * Reads momentary control snapshots for every operational bulk confirmation in this lifecycle's
     * configured namespace using one SQL round-trip inside the runtime-role fence. Preserves
     * {@code UNCOMPOSED}/{@code SUSPENDED} generations alongside publication-current {@code READY}
     * rows without composing OpenAPI or installing a local photograph.
     */
    public List<BulkOperationControlSnapshot> listControlSnapshots() {
        return listControlSnapshots(runtime.namespace());
    }

    /**
     * Reads momentary control snapshots for every operational bulk confirmation in {@code namespace}
     * using one SQL round-trip inside the runtime-role fence. Must start outside any transaction.
     */
    public List<BulkOperationControlSnapshot> listControlSnapshots(String namespace) {
        List<String> operationIds = resolveOperationalIds(namespace,
                "Published readiness lookup must start outside transactions");
        if (operationIds.isEmpty()) {
            return List.of();
        }
        return runtime.withLifecycleRead(connection -> {
            JdbcBulkOpenApiPublication.Snapshot publication =
                    JdbcBulkOpenApiPublication.lockForRead(connection, runtime.namespace(), runtime.deploymentId());
            Map<String, JdbcBulkOperationControl.Snapshot> controls =
                    JdbcBulkOperationControl.lockBatchForAdmission(connection, runtime.namespace(), operationIds);
            List<BulkOperationControlSnapshot> snapshots = new ArrayList<>(operationIds.size());
            for (String operationId : operationIds) {
                JdbcBulkOperationControl.Snapshot control = controls.get(operationId);
                snapshots.add(new BulkOperationControlSnapshot(
                        runtime.namespace(),
                        operationId,
                        control != null ? control.state() : null,
                        control != null ? control.generation() : null,
                        publication.state(),
                        publication.generation(),
                        publication.updatedAt()));
            }
            return List.copyOf(snapshots);
        });
    }

    /**
     * Re-composes the current local contract and returns the durable expectation only when both
     * the descriptor and the database fence still match. The later proposal transaction must
     * re-check this expectation under the database lock; this method alone is not authorization.
     */
    public BulkOperationControlExpectation requireReady(BulkOperationControlIdentity identity) {
        requireIdentity(identity);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable("Readiness composition must start outside operational transactions");
        ResponseProjectionFrame frame = responseProjection.get();
        if (frame != null) return frame.requireReady(identity);
        requireOperationalIdentity(identity);
        Set<String> requiredGroups = requiredOpenApiGroups();
        return documents.withPublishedBulkOpenApiPublication((candidate, publicationGeneration) -> {
            operationResolver.refreshPublishedOpenApiGroupsStrict(requiredGroups);
            BulkOperationalDescriptor descriptor = descriptor(identity, false);
            JdbcBulkOperationControl.Snapshot current = runtime.withLifecycleRead(connection -> {
                requireCapacityBinding(connection, descriptor);
                return JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(),
                        identity.confirmationOperationId());
            });
            if (current == null || !current.ready()
                    || !descriptor.descriptorFingerprint().equals(current.descriptorFingerprint())
                    || !descriptor.structuralRevision().equals(current.structuralRevision()))
                throw unavailable("Bulk operation is not durably READY for the current composed descriptor");
            return descriptor.expectation(current.generation());
        });
    }

    /**
     * Mints an opaque, local admission from the currently published descriptor outside a
     * transaction. The owning storage or execution transaction must still fence its control row.
     */
    public ReadyAdmission requireReady(BulkOperationControlIdentity identity,
            BulkExecutionMode executionMode, BulkSelectionMode selectionMode) {
        requireIdentity(identity);
        Objects.requireNonNull(executionMode, "executionMode");
        Objects.requireNonNull(selectionMode, "selectionMode");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable("Readiness composition must start outside operational transactions");
        requireOperationalIdentity(identity);
        Set<String> requiredGroups = requiredOpenApiGroups();
        return documents.withPublishedBulkOpenApiPublication((candidate, publicationGeneration) -> {
            operationResolver.refreshPublishedOpenApiGroupsStrict(requiredGroups);
            BulkOperationalDescriptor descriptor = descriptor(identity, false);
            var profile = descriptor.profile();
            if (!profile.executionModes().contains(executionMode)
                    || !profile.selectionModes().contains(selectionMode)
                    || selectionMode == BulkSelectionMode.QUERY
                        && (descriptor.structural().mode() != BulkMode.UNIFORM_UPDATE
                            || descriptor.structural().atomicity()
                                != org.praxisplatform.uischema.action.ActionCollectionAtomicity.PER_ITEM))
                throw unavailable("Selection is not published for this operation");
            JdbcBulkOperationControl.Snapshot current = runtime.withLifecycleRead(connection -> {
                requireCapacityBinding(connection, descriptor);
                return JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(),
                        identity.confirmationOperationId());
            });
            if (current == null || !current.ready()
                    || !descriptor.descriptorFingerprint().equals(current.descriptorFingerprint())
                    || !descriptor.structuralRevision().equals(current.structuralRevision()))
                throw unavailable("Bulk operation is not durably READY for the current composed descriptor");
            return new ReadyAdmission(identity, descriptor.expectation(current.generation()),
                    descriptor.structural().mode(), descriptor.structural().atomicity(),
                    executionMode, selectionMode, profile, descriptor.infrastructure(),
                    descriptor.structural().resourceKey(),
                    descriptor.structural().operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION).reference(),
                    descriptor.asynchronousCapacityBinding(), descriptor.codecId(),
                    descriptor.structural().mode() == BulkMode.DOMAIN_COMMAND ? null : descriptor.structural().update().editableFields());
        });
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreType
    public static final class ReadyAdmission {
        private final BulkEditableFields editableFields;
        private final BulkOperationControlIdentity identity;
        private final BulkOperationControlExpectation expectation;
        private final BulkMode mode;
        private final org.praxisplatform.uischema.action.ActionCollectionAtomicity atomicity;
        private final BulkExecutionMode executionMode;
        private final BulkSelectionMode selectionMode;
        private final BulkOperationalProfile profile;
        private final BulkExecutionInfrastructure infrastructure;
        private final String resourceKey;
        private final org.praxisplatform.uischema.openapi.CanonicalOperationRef operationRef;
        private final java.util.Optional<BulkCapacityBinding> capacityBinding;
        private final String codecId;

        private ReadyAdmission(BulkOperationControlIdentity identity,
                BulkOperationControlExpectation expectation, BulkMode mode,
                org.praxisplatform.uischema.action.ActionCollectionAtomicity atomicity,
                BulkExecutionMode executionMode, BulkSelectionMode selectionMode,
                BulkOperationalProfile profile, BulkExecutionInfrastructure infrastructure,
                String resourceKey, org.praxisplatform.uischema.openapi.CanonicalOperationRef operationRef,
                java.util.Optional<BulkCapacityBinding> capacityBinding, String codecId, BulkEditableFields editableFields) {
            this.editableFields = editableFields;
            this.identity = identity;
            this.expectation = expectation;
            this.mode = mode;
            this.atomicity = atomicity;
            this.executionMode = executionMode;
            this.selectionMode = selectionMode;
            this.profile = profile;
            this.infrastructure = infrastructure;
            this.resourceKey = resourceKey;
            this.operationRef = operationRef;
            this.capacityBinding = capacityBinding;
            this.codecId = codecId;
        }
        boolean matchesCodec(BulkIntentSnapshot snapshot) { return codecId.equals(snapshot.codecId()); }
        BulkEditableFields editableFields() { return editableFields; }
        boolean matchesScope(BulkFingerprintContext scope) {
            return identity.namespaceId().equals(scope.namespaceId())
                    && resourceKey.equals(scope.resourceKey()) && operationRef.equals(scope.operationRef())
                    && atomicity == scope.atomicity();
        }
        boolean belongsTo(BulkExecutionInfrastructure expected) { return infrastructure == expected; }
        java.util.Optional<BulkCapacityBinding> capacityBinding() { return capacityBinding; }
        public BulkOperationControlIdentity identity() { return identity; }
        public BulkOperationControlExpectation expectation() { return expectation; }
        public BulkMode mode() { return mode; }
        public org.praxisplatform.uischema.action.ActionCollectionAtomicity atomicity() { return atomicity; }
        public BulkExecutionMode executionMode() { return executionMode; }
        public BulkSelectionMode selectionMode() { return selectionMode; }
        public BulkOperationalProfile profile() { return profile; }
        public int maxTargets() { return profile.maxTargets(); }
    }

    /** Resolves action discovery through the same scoped response fence as capabilities. */
    public Map<String, org.praxisplatform.uischema.action.ActionExecutionContract> projectReadyActions(
            List<org.praxisplatform.uischema.action.ActionDefinition> actions) {
        return projectReadyActions(actions, java.util.function.Function.identity());
    }

    /**
     * Runs the consumer once outside document locks and JDBC transactions. A capability response
     * may reuse its existing frame for actions of that same resource. Standalone action discovery
     * owns its frame; arbitrary nested action scopes remain unsupported. Preparation failure denies
     * scoped readiness; failures after the consumer starts propagate without retry or fallback.
     */
    public <T> T projectReadyActions(List<org.praxisplatform.uischema.action.ActionDefinition> actions,
            java.util.function.Function<Map<String, org.praxisplatform.uischema.action.ActionExecutionContract>, T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable("Response projection must start outside operational transactions");
        var nested = responseProjection.get();
        if (nested != null) {
            if (nested.resourceKey == null || actions == null || actions.stream().filter(Objects::nonNull)
                    .anyMatch(action -> !nested.resourceKey.equals(action.resourceKey())))
                throw unavailable("Nested action projection must belong to its capability response");
            return consumer.apply(nested.actions(actions));
        }
        if (actions == null || actions.isEmpty() || bindings.bulkOperations().isEmpty())
            return consumer.apply(Map.of());
        Set<String> declaredIds = bindings.bulkOperations().stream()
                .filter(binding -> binding.mode() == BulkMode.DOMAIN_COMMAND)
                .filter(binding -> actions.stream().filter(Objects::nonNull).anyMatch(action -> action.operation() != null
                        && binding.confirmationOperationId().equals(action.operation().operationId())))
                .map(BulkOperationBinding::confirmationOperationId).collect(java.util.stream.Collectors.toSet());
        if (declaredIds.isEmpty()) return consumer.apply(Map.of());
        return withProjection(null, declaredIds, frame -> {
            var executions = frame.actions(actions);
            frame.readinessIds = frame.approved.keySet().stream()
                    .filter(identity -> executions.containsKey(identity.confirmationOperationId()))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return consumer.apply(executions);
        });
    }

    /**
     * Captures READY resource mutation contracts and a shared action frame for one resource response. The entire
     * synchronous builder, including host availability, executes outside preparation/cache locks.
     * Final provider, document and durable generation fences run even when a host swallowed denial.
     * No frame or readiness result is retained across responses.
     */
    public <T> T projectReadyCapabilities(String resourceKey,
            java.util.function.Function<Map<String, BulkExecutionContract>, T> consumer) {
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(consumer, "consumer");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable("Response projection must start outside operational transactions");
        if (responseProjection.get() != null) throw unavailable("Nested capability responses are not supported");
        Set<String> declaredIds = bindings.bulkOperations().stream()
                .filter(binding -> resourceKey.equals(binding.resourceKey()))
                .map(BulkOperationBinding::confirmationOperationId).collect(java.util.stream.Collectors.toSet());
        if (declaredIds.isEmpty()) return consumer.apply(Map.of());
        return withProjection(resourceKey, declaredIds, frame -> consumer.apply(frame.updates()));
    }

    private <T> T withProjection(String resourceKey, Set<String> declaredIds,
            java.util.function.Function<ResponseProjectionFrame, T> consumer) {
        ResponseProjectionFrame frame;
        try {
            frame = prepareProjection(resourceKey, declaredIds);
        } catch (RuntimeException unavailableComposition) {
            frame = new ResponseProjectionFrame(resourceKey, Map.of(), List.of(), Map.of(), null);
        }
        responseProjection.set(frame);
        Throwable failure = null;
        try {
            T result = consumer.apply(frame);
            frame.verifyFinal();
            return result;
        } catch (RuntimeException | Error failed) {
            failure = failed;
            throw failed;
        } finally {
            responseProjection.remove();
            try { frame.close(); }
            catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) throw cleanupFailure;
                if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private ResponseProjectionFrame prepareProjection(String resourceKey, Set<String> requestedIds) {
        // Structural updates without providers do not enter runtime I/O or advertise readiness.
        var supplied = providerSnapshot().keySet();
        Set<String> declaredIds = bindings.bulkOperations().stream()
                .filter(binding -> requestedIds.contains(binding.confirmationOperationId()))
                .filter(binding -> binding.mode() == BulkMode.DOMAIN_COMMAND || supplied.contains(binding.confirmationOperationId()))
                .map(BulkOperationBinding::confirmationOperationId).collect(java.util.stream.Collectors.toSet());
        if (declaredIds.isEmpty()) return new ResponseProjectionFrame(resourceKey, Map.of(), List.of(), Map.of(), null);
        Map<BulkOperationControlIdentity, JdbcBulkOperationControl.Snapshot> before = runtime.withLifecycleRead(connection -> {
            Map<BulkOperationControlIdentity, JdbcBulkOperationControl.Snapshot> captured = new LinkedHashMap<>();
            for (var operationId : declaredIds.stream().sorted().toList()) {
                var identity = new BulkOperationControlIdentity(runtime.namespace(), operationId);
                captured.put(identity, JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(), operationId));
            }
            return captured;
        });
        if (before.values().stream().noneMatch(value -> value != null && value.ready()))
            return new ResponseProjectionFrame(resourceKey, Map.of(), List.of(), Map.of(), null);
        Set<String> requiredGroups = requiredOpenApiGroups();
        return documents.withPublishedBulkOpenApiPublication((candidate, publicationGeneration) -> {
            operationResolver.refreshPublishedOpenApiGroupsStrict(requiredGroups);
            List<BulkOperationalDescriptor> descriptors = descriptors(false).stream()
                    .sorted(Comparator.comparing(value -> value.identity().confirmationOperationId())).toList();
            Map<String, BulkExecutionContract> result = new LinkedHashMap<>();
            Map<BulkOperationControlIdentity, JdbcBulkOperationControl.Snapshot> approved = new LinkedHashMap<>();
            runtime.withLifecycleRead(connection -> {
                for (var descriptor : descriptors) {
                    var initial = before.get(descriptor.identity());
                    if (initial == null || !initial.ready()) continue;
                    requireCapacityBinding(connection, descriptor);
                    var current = JdbcBulkOperationControl.lockForAdmission(connection,
                            descriptor.identity().namespaceId(), descriptor.identity().confirmationOperationId());
                    if (!matches(initial, "READY", initial.generation(), descriptor)
                            || !matches(current, "READY", initial.generation(), descriptor)) continue;
                    result.put(descriptor.identity().confirmationOperationId(), BulkExecutionContract.from(descriptor));
                    approved.put(descriptor.identity(), initial);
                }
                return null;
            });
            return new ResponseProjectionFrame(resourceKey, Map.copyOf(result), descriptors, Map.copyOf(approved),
                    documents.captureBulkLifecycleDocumentFence());
        });
    }

    private final class ResponseProjectionFrame implements AutoCloseable {
        private final String resourceKey;
        private final Map<String, BulkExecutionContract> contracts;
        private final List<BulkOperationalDescriptor> descriptors;
        private final Map<BulkOperationControlIdentity, JdbcBulkOperationControl.Snapshot> approved;
        private final OpenApiDocumentService.BulkLifecycleDocumentFence documentFence;
        private RuntimeException rejection;
        private Set<BulkOperationControlIdentity> readinessIds;

        private ResponseProjectionFrame(String resourceKey, Map<String, BulkExecutionContract> contracts,
                List<BulkOperationalDescriptor> descriptors,
                Map<BulkOperationControlIdentity, JdbcBulkOperationControl.Snapshot> approved,
                OpenApiDocumentService.BulkLifecycleDocumentFence documentFence) {
            this.resourceKey = resourceKey;
            this.contracts = contracts;
            this.descriptors = descriptors;
            this.approved = approved;
            this.readinessIds = approved.keySet();
            this.documentFence = documentFence;
        }

        private Map<String, org.praxisplatform.uischema.action.ActionExecutionContract> actions(
                List<org.praxisplatform.uischema.action.ActionDefinition> actions) {
            Map<String, org.praxisplatform.uischema.action.ActionExecutionContract> result = new LinkedHashMap<>();
            for (var descriptor : descriptors) {
                if (descriptor.structural().mode() != BulkMode.DOMAIN_COMMAND) continue;
                var bulk = contracts.get(descriptor.identity().confirmationOperationId());
                if (bulk == null) continue;
                for (var action : actions) {
                    if (action != null && descriptor.structural().action().operation().equals(action.operation())
                            && BulkOperationStructuralDescriptor.Action.from(action).equals(descriptor.structural().action()))
                        result.put(action.operation().operationId(), action.execution().withBulk(bulk));
                }
            }
            return Map.copyOf(result);
        }

        private Map<String, BulkExecutionContract> updates() {
            Map<String, BulkExecutionContract> result = new LinkedHashMap<>();
            for (var contract : contracts.values()) {
                if (contract.mode() == BulkMode.DOMAIN_COMMAND) continue;
                String id = contract.crudCapabilityId();
                if (result.putIfAbsent(id, contract) != null)
                    throw contractDiagnostic(
                            "More than one bulk update maps to the same capability ID",
                            contract.confirmationOperation().operation().operationId());
            }
            return Map.copyOf(result);
        }

        private BulkOperationControlExpectation requireReady(BulkOperationControlIdentity identity) {
            if (documentFence == null || !readinessIds.contains(identity))
                throw unavailable("Bulk operation is not READY in this response projection");
            verify(Set.of(identity), false);
            return descriptors.stream().filter(value -> value.identity().equals(identity)).findFirst().orElseThrow()
                    .expectation(approved.get(identity).generation());
        }

        private void verifyFinal() {
            if (documentFence != null) {
                verify(approved.keySet(), true);
                if (rejection != null) throw rejection;
            }
        }

        private void verify(Set<BulkOperationControlIdentity> identities, boolean finalCheck) {
            if (!finalCheck && rejection != null) throw rejection;
            try {
                documentFence.read(() -> {
                    Map<String, BulkOperationDescriptorProvider> currentProviders = providerSnapshot();
                    if (currentProviders.size() != descriptors.size())
                        throw unavailable("Descriptor provider bindings changed during discovery");
                    for (var descriptor : descriptors) {
                        var provider = currentProviders.remove(descriptor.identity().confirmationOperationId());
                        if (provider == null || !descriptor.equals(
                                BulkOperationalDescriptorComposer.compose(descriptor.structural(), provider)))
                            throw unavailable("Descriptor provider composition changed during discovery");
                    }
                    return runtime.withLifecycleRead(connection -> {
                        for (var descriptor : descriptors) {
                            if (!identities.contains(descriptor.identity())) continue;
                            requireCapacityBinding(connection, descriptor);
                            var current = JdbcBulkOperationControl.lockForAdmission(connection,
                                    descriptor.identity().namespaceId(), descriptor.identity().confirmationOperationId());
                            if (!matches(current, "READY", approved.get(descriptor.identity()).generation(), descriptor))
                                throw unavailable("Bulk operation changed after response projection capture");
                        }
                        return null;
                    });
                });
            } catch (RuntimeException unavailableProjection) {
                // A projection that lost its operational proof must never escape as
                // a JDBC/transaction 500 or publish the partially verified response.
                if (rejection == null) rejection = new BulkDiscoveryUnavailableException();
                throw rejection;
            }
        }
        @Override public void close() { if (documentFence != null) documentFence.close(); }
    }

    /**
     * Publishes one fresh exact OpenAPI/MVC/provider composition using a generation CAS. Only an
     * UNCOMPOSED or SUSPENDED row may become READY. Publication never accepts a caller-supplied
     * fingerprint and a stale generation never retries implicitly.
     */
    public BulkOperationControlExpectation publish(BulkOperationControlIdentity identity, long expectedGeneration) {
        return publish(identity, expectedGeneration, null);
    }

    /**
     * Variant of {@link #publish(BulkOperationControlIdentity, long)} that optionally appends an
     * immutable {@code PUBLISH} entry to the durable V21 lifecycle audit ledger inside the same
     * control-plane commit transaction.
     */
    public BulkOperationControlExpectation publish(
            BulkOperationControlIdentity identity, long expectedGeneration, BulkLifecycleAuditContext audit) {
        requireOperationalIdentity(identity);
        if (expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE)
            throw new IllegalArgumentException("expectedGeneration must be nonnegative and incrementable");
        documents.requireBulkOpenApiServing();
        var initialPublication = runtime.withLifecycleRead(connection -> {
            var before = JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(),
                    identity.confirmationOperationId());
            if (before == null || before.generation() != expectedGeneration
                    || !("UNCOMPOSED".equals(before.state()) || "SUSPENDED".equals(before.state())))
                throw conflict("Bulk operation control is not publishable at the expected generation");
            return JdbcBulkOpenApiPublication.lockForRead(connection, runtime.namespace(), runtime.deploymentId());
        }); // This SHARE transaction ends before any control UPDATE.
        var publication = initialPublication;
        if (publication.published() && documents.hasLocalPublishedBulkOpenApiPublication()) {
            PreparedPublication prepared = documents.withPublishedBulkOpenApiPublication((candidate, generation) -> {
                requireSamePublication(initialPublication, candidate, generation);
                operationResolver.refreshPublishedOpenApiGroupsStrict(requiredOpenApiGroups());
                return new PreparedPublication(candidate, descriptor(identity, false));
            });
            return commitPrepared(identity, expectedGeneration, prepared, publication, false, audit);
        }
        if (!publication.published()) {
            // Initial/uncomposed ledgers must be durably suspended before capture. Direct UPDATE,
            // never a publication SHARE lock converted in the same physical transaction.
            boolean needsGlobalSuspension = !"SUSPENDED".equals(initialPublication.state());
            documents.withBulkLifecycleCompositionLock(() -> {
                if (needsGlobalSuspension) {
                    suspendGlobalPublication(initialPublication);
                }
                withLifecycleCacheFence(() -> { documents.clearCaches(); return null; });
                return null;
            });
            if (needsGlobalSuspension) {
                publication = readPublication();
            }
        }
        var capturedPublication = publication;
        var candidate = documents.prepareBulkOpenApiPublication(Set.copyOf(
                operationResolver.publishedOpenApiGroups(requiredOpenApiGroups())));
        if (capturedPublication.published()) requireSamePublication(capturedPublication, candidate, capturedPublication.generation());
        var descriptor = documents.withPreparedBulkOpenApiPublication(candidate, () -> {
            operationResolver.refreshPublishedOpenApiGroupsStrict(requiredOpenApiGroups());
            return descriptor(identity, false);
        });
        return commitPrepared(identity, expectedGeneration, new PreparedPublication(candidate, descriptor),
                capturedPublication, !capturedPublication.published(), audit);
    }

    private record PreparedPublication(org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate,
            BulkOperationalDescriptor descriptor) {}

    private BulkOperationControlExpectation commitPrepared(BulkOperationControlIdentity identity, long expectedGeneration,
            PreparedPublication prepared, JdbcBulkOpenApiPublication.Snapshot before, boolean newPublication,
            BulkLifecycleAuditContext audit) {
        long publicationGeneration = newPublication ? Math.incrementExact(before.generation()) : before.generation();
        documents.withPreparedBulkOpenApiCommit(prepared.candidate(), () -> {
            var admitted = descriptor(identity, false);
            // Structural descriptors are immutable objects, not identity-comparable values.
            // Their canonical digest and the operational fingerprint bind the complete content.
            if (!prepared.descriptor().identity().equals(admitted.identity())
                    || !prepared.descriptor().structuralRevision().equals(admitted.structuralRevision())
                    || !prepared.descriptor().descriptorFingerprint().equals(admitted.descriptorFingerprint())
                    || prepared.descriptor().infrastructure() != admitted.infrastructure())
                throw conflict("Bulk descriptor changed before publication admission");
            var started = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                controlPlane.withConnection(connection -> {
                    documents.withBulkLifecycleCompositionLock(() -> connection);
                    started.set(true);
                    if (newPublication) {
                        var global = JdbcBulkOpenApiPublication.transition(connection, runtime.namespace(), runtime.deploymentId(),
                                before.generation(), JdbcBulkOpenApiPublication.Target.PUBLISHED, prepared.candidate().digest());
                        if (!global.applied() || global.generation() != publicationGeneration)
                            throw conflict("OpenAPI publication lost its generation race");
                    }
                    var operation = JdbcBulkOperationControl.transition(connection, identity.namespaceId(),
                            identity.confirmationOperationId(), expectedGeneration, JdbcBulkOperationControl.Target.READY,
                            prepared.descriptor().descriptorFingerprint(), prepared.descriptor().structuralRevision(),
                            publicationGeneration, prepared.candidate().digest());
                    // Throw inside the control transaction so a losing operation also rolls back global publication.
                    if (!operation.applied() || operation.generation() != expectedGeneration + 1)
                        throw conflict("Bulk operation publication lost its generation race");
                    if (audit != null) {
                        JdbcBulkLifecycleAudit.record(connection, UUID.randomUUID(), identity.namespaceId(),
                                runtime.deploymentId(), identity.confirmationOperationId(), "PUBLISH", audit,
                                expectedGeneration, operation.generation(), "READY");
                    }
                    return null;
                });
            } catch (RuntimeException uncertain) {
                if (!started.get()) throw uncertain;
                try {
                    runtime.withLifecycleRead(connection -> {
                        var global = JdbcBulkOpenApiPublication.lockForRead(connection, runtime.namespace(), runtime.deploymentId());
                        requireSamePublication(global, prepared.candidate(), publicationGeneration);
                        var operation = JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(), identity.confirmationOperationId());
                        if (!matches(operation, "READY", expectedGeneration + 1, prepared.descriptor()))
                            throw conflict("Publication commit was not reconciled to its exact global and operation identities");
                        return null;
                    });
                } catch (RuntimeException unreconciled) {
                    uncertain.addSuppressed(unreconciled);
                    throw uncertain;
                }
            }
            return null;
        });
        // Successful commit/reconciliation precedes installation. If installation fails, no READY
        // is returned locally; explicit read-only reconciliation may install the exact photograph.
        documents.installBulkOpenApiPublication(prepared.candidate(), publicationGeneration);
        return prepared.descriptor().expectation(expectedGeneration + 1);
    }

    /** Reconciles an already committed publication without control CAS or domain mutation. */
    public BulkOperationControlExpectation reconcilePublished(BulkOperationControlIdentity identity, long readyGeneration) {
        return reconcilePublished(identity, readyGeneration, null);
    }

    /**
     * Reconciles an already committed publication and, when {@code audit} is non-null, appends an
     * immutable {@code RECONCILE} record to the V21 lifecycle audit ledger after installation.
     */
    public BulkOperationControlExpectation reconcilePublished(
            BulkOperationControlIdentity identity, long readyGeneration, BulkLifecycleAuditContext audit) {
        requireOperationalIdentity(identity);
        documents.requireBulkOpenApiServing();
        var publication = readPublication();
        if (!publication.published()) throw conflict("No committed OpenAPI publication can be reconciled");
        // Explicit off-path reconciliation may replace a stale local generation. It never clears
        // caches through the global invalidation hook or trusts presence of the old photograph.
        var candidate = documents.prepareBulkOpenApiPublication(Set.copyOf(
                operationResolver.publishedOpenApiGroups(requiredOpenApiGroups())));
        requireSamePublication(publication, candidate, publication.generation());
        PreparedPublication prepared = documents.withPreparedBulkOpenApiPublication(candidate, () ->
                new PreparedPublication(candidate, descriptor(identity, false)));
        runtime.withLifecycleRead(connection -> {
            var global = JdbcBulkOpenApiPublication.lockForRead(connection, runtime.namespace(), runtime.deploymentId());
            requireSamePublication(global, prepared.candidate(), publication.generation());
            var operation = JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(), identity.confirmationOperationId());
            if (!matches(operation, "READY", readyGeneration, prepared.descriptor()))
                throw conflict("Committed operation does not match reconciliation identity");
            return null;
        });
        documents.installBulkOpenApiPublication(prepared.candidate(), publication.generation());
        if (audit != null) {
            controlPlane.withConnection(connection -> {
                JdbcBulkLifecycleAudit.record(connection, UUID.randomUUID(), identity.namespaceId(),
                        runtime.deploymentId(), identity.confirmationOperationId(), "RECONCILE", audit,
                        readyGeneration, readyGeneration, "READY");
                return null;
            });
        }
        return prepared.descriptor().expectation(readyGeneration);
    }

    /** Reads persistent V21 lifecycle audit entries for the configured namespace, newest first. */
    public List<BulkLifecycleAuditEntry> listAuditEntries(int limit) {
        return listAuditEntries(runtime.namespace(), limit);
    }

    /** Reads persistent V21 lifecycle audit entries for {@code namespace}, newest first (1..500). */
    public List<BulkLifecycleAuditEntry> listAuditEntries(String namespace, int limit) {
        requireNamespace(namespace);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Lifecycle audit limit must be between 1 and 500");
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable("Lifecycle audit lookup must start outside transactions");
        }
        return runtime.withLifecycleRead(connection -> JdbcBulkLifecycleAudit.read(connection, namespace, limit));
    }

    /** Publishes all eligible operations in the configured namespace using one OpenAPI composition. */
    public BulkNamespaceBatchResult publishAll() {
        return publishAll(runtime.namespace(), null, BulkBatchProgressListener.NOOP);
    }

    /** Publishes all eligible operations in {@code namespace} using one OpenAPI composition. */
    public BulkNamespaceBatchResult publishAll(String namespace) {
        return publishAll(namespace, null, BulkBatchProgressListener.NOOP);
    }

    /** Publishes all eligible operations in {@code namespace}, appending {@code PUBLISH_ALL} audit entries. */
    public BulkNamespaceBatchResult publishAll(String namespace, BulkLifecycleAuditContext audit) {
        return publishAll(namespace, audit, BulkBatchProgressListener.NOOP);
    }

    /**
     * Publishes all eligible operations in {@code namespace} sharing a single cold OpenAPI
     * composition and a single descriptor compilation, reporting incremental progress to
     * {@code progressListener}.
     */
    public BulkNamespaceBatchResult publishAll(
            String namespace, BulkLifecycleAuditContext audit, BulkBatchProgressListener progressListener) {
        BulkBatchProgressListener listener = progressListener != null ? progressListener : BulkBatchProgressListener.NOOP;
        documents.requireBulkOpenApiServing();
        List<String> operationIds = resolveOperationalIds(namespace,
                "Namespace publication batch must start outside transactions");
        if (operationIds.isEmpty()) {
            listener.onProgress("COMPLETED", 0, 0, 0, 0, 0);
            return new BulkNamespaceBatchResult(namespace, "PUBLISH_ALL", null,
                    0, 0, 0, 0, 0, List.of(), Instant.now());
        }
        BatchReadSnapshot readSnapshot = runtime.withLifecycleRead(connection -> new BatchReadSnapshot(
                JdbcBulkOpenApiPublication.lockForRead(connection, runtime.namespace(), runtime.deploymentId()),
                JdbcBulkOperationControl.lockBatchForAdmission(connection, runtime.namespace(), operationIds)));
        JdbcBulkOpenApiPublication.Snapshot initialPublication = readSnapshot.publication();
        JdbcBulkOpenApiPublication.Snapshot publication = initialPublication;
        PreparedBatch preparedBatch;
        if (initialPublication.published() && documents.hasLocalPublishedBulkOpenApiPublication()) {
            preparedBatch = documents.withPublishedBulkOpenApiPublication((candidate, generation) -> {
                requireSamePublication(initialPublication, candidate, generation);
                operationResolver.refreshPublishedOpenApiGroupsStrict(requiredOpenApiGroups());
                return new PreparedBatch(candidate, generation, true, indexDescriptors(descriptors(false)));
            });
        } else {
            if (!initialPublication.published()) {
                boolean needsGlobalSuspension = !"SUSPENDED".equals(initialPublication.state());
                documents.withBulkLifecycleCompositionLock(() -> {
                    if (needsGlobalSuspension) {
                        suspendGlobalPublication(initialPublication);
                    }
                    withLifecycleCacheFence(() -> { documents.clearCaches(); return null; });
                    return null;
                });
                if (needsGlobalSuspension) {
                    publication = readPublication();
                }
            }
            JdbcBulkOpenApiPublication.Snapshot capturedPublication = publication;
            var candidate = documents.prepareBulkOpenApiPublication(Set.copyOf(
                    operationResolver.publishedOpenApiGroups(requiredOpenApiGroups())));
            if (capturedPublication.published()) {
                requireSamePublication(capturedPublication, candidate, capturedPublication.generation());
            }
            Map<String, BulkOperationalDescriptor> compiledByOp = documents.withPreparedBulkOpenApiPublication(
                    candidate, () -> {
                        operationResolver.refreshPublishedOpenApiGroupsStrict(requiredOpenApiGroups());
                        return indexDescriptors(descriptors(false));
                    });
            preparedBatch = new PreparedBatch(candidate, capturedPublication.generation(),
                    capturedPublication.published(), compiledByOp);
        }

        int total = operationIds.size();
        listener.onProgress("PREPARED", total, 0, 0, 0, 0);

        BatchExecutionState state = new BatchExecutionState(preparedBatch.published(), preparedBatch.publicationGeneration());
        boolean hasPending = operationIds.stream().anyMatch(opId -> {
            JdbcBulkOperationControl.Snapshot snap = readSnapshot.controls().get(opId);
            return snap != null && ("UNCOMPOSED".equals(snap.state()) || "SUSPENDED".equals(snap.state()));
        });
        if (hasPending) {
            documents.withPreparedBulkOpenApiCommit(preparedBatch.candidate(), () -> {
                executePublishBatchItems(operationIds, readSnapshot.controls(), preparedBatch, audit,
                        listener, total, state);
                return null;
            });
        } else {
            executePublishBatchItems(operationIds, readSnapshot.controls(), preparedBatch, audit,
                    listener, total, state);
        }
        if (state.pubPublished && (state.succeeded > 0 || !documents.hasLocalPublishedBulkOpenApiPublication())) {
            documents.installBulkOpenApiPublication(preparedBatch.candidate(), state.pubGeneration);
        }
        listener.onProgress("COMPLETED", total, state.processed, state.succeeded, state.skipped, state.failed);
        return new BulkNamespaceBatchResult(namespace, "PUBLISH_ALL",
                state.pubPublished ? state.pubGeneration : null,
                total, state.processed, state.succeeded, state.skipped, state.failed,
                List.copyOf(state.items), Instant.now());
    }

    /** Converges all eligible operations in the configured namespace onto READY using one OpenAPI composition. */
    public BulkNamespaceBatchResult convergeAll() {
        return convergeAll(runtime.namespace(), null, BulkBatchProgressListener.NOOP);
    }

    /** Converges all eligible operations in {@code namespace} onto READY using one OpenAPI composition. */
    public BulkNamespaceBatchResult convergeAll(String namespace) {
        return convergeAll(namespace, null, BulkBatchProgressListener.NOOP);
    }

    /** Converges all eligible operations in {@code namespace} onto READY, recording V21 audit entries. */
    public BulkNamespaceBatchResult convergeAll(String namespace, BulkLifecycleAuditContext audit) {
        return convergeAll(namespace, audit, BulkBatchProgressListener.NOOP);
    }

    /**
     * Converges all eligible operations in {@code namespace} onto {@code READY} using at most one
     * cold OpenAPI composition and one descriptor compilation:
     * <ul>
     *   <li>If the installed local snapshot and durable publication match the current release and all
     *       operations are already {@code READY}, skips with zero cold fetches.</li>
     *   <li>If the durable publication matches the composed digest, reconciles in-place (recording
     *       {@code RECONCILE_ALL} audit entries) or publishes only pending operations.</li>
     *   <li>If the durable publication is {@code UNCOMPOSED}, {@code SUSPENDED}, or on a previous
     *       release digest, suspends any stale generation atomically and publishes the new release
     *       within the same batch (recording {@code PUBLISH_ALL} audit entries).</li>
     * </ul>
     */
    public BulkNamespaceBatchResult convergeAll(
            String namespace, BulkLifecycleAuditContext audit, BulkBatchProgressListener progressListener) {
        BulkBatchProgressListener listener = progressListener != null ? progressListener : BulkBatchProgressListener.NOOP;
        documents.requireBulkOpenApiServing();
        List<String> operationIds = resolveOperationalIds(namespace,
                "Namespace convergence batch must start outside transactions");
        if (operationIds.isEmpty()) {
            listener.onProgress("COMPLETED", 0, 0, 0, 0, 0);
            return new BulkNamespaceBatchResult(namespace, "CONVERGE_ALL", null,
                    0, 0, 0, 0, 0, List.of(), Instant.now());
        }

        BatchReadSnapshot initialRead = readBatchSnapshot(operationIds);
        if (initialRead.publication().published() && documents.hasLocalPublishedBulkOpenApiPublication()) {
            try {
                BulkNamespaceBatchResult fastResult = documents.withPublishedBulkOpenApiPublication(
                        (publishedCandidate, generation) -> {
                            requireSamePublication(initialRead.publication(), publishedCandidate, generation);
                            operationResolver.refreshPublishedOpenApiGroupsStrict(requiredOpenApiGroups());
                            Map<String, BulkOperationalDescriptor> compiled = indexDescriptors(descriptors(false));
                            if (allOperationsReadyAndMatching(operationIds, initialRead.controls(), compiled)) {
                                return buildAllAlreadyReadyResult(namespace, operationIds, initialRead, listener);
                            }
                            return null;
                        });
                if (fastResult != null) {
                    return fastResult;
                }
            } catch (BulkPublicationConflictException
                     | org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationUnavailableException drifted) {
                // Installed snapshot drifted or durable ledger transitioned; converge with one cold composition.
            }
        }

        org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate = documents.prepareBulkOpenApiPublication(
                Set.copyOf(operationResolver.publishedOpenApiGroups(requiredOpenApiGroups())));
        Map<String, BulkOperationalDescriptor> compiledByOp = documents.withPreparedBulkOpenApiPublication(
                candidate, () -> {
                    operationResolver.refreshPublishedOpenApiGroupsStrict(requiredOpenApiGroups());
                    return indexDescriptors(descriptors(false));
                });
        int total = operationIds.size();
        listener.onProgress("PREPARED", total, 0, 0, 0, 0);

        BatchReadSnapshot current = readBatchSnapshot(operationIds);
        if (isMatchingPublishedPublication(current.publication(), candidate)
                && !hasDriftedReadyOperation(operationIds, current.controls(), compiledByOp)) {
            if (!hasPendingOperation(operationIds, current.controls())) {
                return reconcilePreparedBatch(namespace, operationIds, candidate, compiledByOp,
                        current, audit, listener, "CONVERGE_ALL");
            }
            return publishPreparedBatch(namespace, operationIds, candidate, compiledByOp,
                    current, true, current.publication().generation(), audit, listener, "CONVERGE_ALL");
        }

        if (!"SUSPENDED".equals(current.publication().state())) {
            JdbcBulkOpenApiPublication.Snapshot beforeSuspend = current.publication();
            try {
                documents.withBulkLifecycleCompositionLock(() -> {
                    suspendGlobalPublication(beforeSuspend);
                    return null;
                });
            } catch (BulkPublicationConflictException raced) {
                // Re-read below in case a peer replica suspended or already published this release digest.
            }
            current = readBatchSnapshot(operationIds);
            if (isMatchingPublishedPublication(current.publication(), candidate)
                    && !hasDriftedReadyOperation(operationIds, current.controls(), compiledByOp)) {
                if (!hasPendingOperation(operationIds, current.controls())) {
                    return reconcilePreparedBatch(namespace, operationIds, candidate, compiledByOp,
                            current, audit, listener, "CONVERGE_ALL");
                }
                return publishPreparedBatch(namespace, operationIds, candidate, compiledByOp,
                        current, true, current.publication().generation(), audit, listener, "CONVERGE_ALL");
            }
        }

        return publishPreparedBatch(namespace, operationIds, candidate, compiledByOp,
                current, false, current.publication().generation(), audit, listener, "CONVERGE_ALL");
    }

    /** Reconciles all eligible READY operations in the configured namespace using one OpenAPI composition. */
    public BulkNamespaceBatchResult reconcileAll() {
        return reconcileAll(runtime.namespace(), null, BulkBatchProgressListener.NOOP);
    }

    /** Reconciles all eligible READY operations in {@code namespace} using one OpenAPI composition. */
    public BulkNamespaceBatchResult reconcileAll(String namespace) {
        return reconcileAll(namespace, null, BulkBatchProgressListener.NOOP);
    }

    /** Reconciles all eligible READY operations in {@code namespace}, appending {@code RECONCILE_ALL} audit entries. */
    public BulkNamespaceBatchResult reconcileAll(String namespace, BulkLifecycleAuditContext audit) {
        return reconcileAll(namespace, audit, BulkBatchProgressListener.NOOP);
    }

    /**
     * Reconciles all committed {@code READY} operations in {@code namespace} on this node using a
     * single cold OpenAPI composition and a single descriptor compilation, then installs the local
     * publication photograph once and reports progress to {@code progressListener}.
     */
    public BulkNamespaceBatchResult reconcileAll(
            String namespace, BulkLifecycleAuditContext audit, BulkBatchProgressListener progressListener) {
        BulkBatchProgressListener listener = progressListener != null ? progressListener : BulkBatchProgressListener.NOOP;
        documents.requireBulkOpenApiServing();
        List<String> operationIds = resolveOperationalIds(namespace,
                "Namespace reconciliation batch must start outside transactions");
        if (operationIds.isEmpty()) {
            listener.onProgress("COMPLETED", 0, 0, 0, 0, 0);
            return new BulkNamespaceBatchResult(namespace, "RECONCILE_ALL", null,
                    0, 0, 0, 0, 0, List.of(), Instant.now());
        }
        JdbcBulkOpenApiPublication.Snapshot publication = readPublication();
        if (!publication.published()) {
            throw conflict("No committed OpenAPI publication can be reconciled");
        }
        var candidate = documents.prepareBulkOpenApiPublication(Set.copyOf(
                operationResolver.publishedOpenApiGroups(requiredOpenApiGroups())));
        requireSamePublication(publication, candidate, publication.generation());
        Map<String, BulkOperationalDescriptor> compiledByOp = documents.withPreparedBulkOpenApiPublication(
                candidate, () -> {
                    operationResolver.refreshPublishedOpenApiGroupsStrict(requiredOpenApiGroups());
                    return indexDescriptors(descriptors(false));
                });
        int total = operationIds.size();
        listener.onProgress("PREPARED", total, 0, 0, 0, 0);

        BatchReadSnapshot verifiedRead = runtime.withLifecycleRead(connection -> {
            JdbcBulkOpenApiPublication.Snapshot global = JdbcBulkOpenApiPublication.lockForRead(
                    connection, runtime.namespace(), runtime.deploymentId());
            requireSamePublication(global, candidate, publication.generation());
            Map<String, JdbcBulkOperationControl.Snapshot> controls = JdbcBulkOperationControl.lockBatchForAdmission(
                    connection, runtime.namespace(), operationIds);
            return new BatchReadSnapshot(global, controls);
        });
        return reconcilePreparedBatch(namespace, operationIds, candidate, compiledByOp,
                verifiedRead, audit, listener, "RECONCILE_ALL");
    }

    private BatchReadSnapshot readBatchSnapshot(List<String> operationIds) {
        return runtime.withLifecycleRead(connection -> new BatchReadSnapshot(
                JdbcBulkOpenApiPublication.lockForRead(connection, runtime.namespace(), runtime.deploymentId()),
                JdbcBulkOperationControl.lockBatchForAdmission(connection, runtime.namespace(), operationIds)));
    }

    private static boolean isMatchingPublishedPublication(
            JdbcBulkOpenApiPublication.Snapshot publication,
            org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate) {
        return publication != null
                && publication.published()
                && Objects.equals(publication.documentDigest(), candidate.digest());
    }

    private static boolean hasDriftedReadyOperation(
            List<String> operationIds,
            Map<String, JdbcBulkOperationControl.Snapshot> controls,
            Map<String, BulkOperationalDescriptor> compiledByOp) {
        for (String operationId : operationIds) {
            JdbcBulkOperationControl.Snapshot control = controls.get(operationId);
            BulkOperationalDescriptor descriptor = compiledByOp.get(operationId);
            if (control != null && "READY".equals(control.state())
                    && (descriptor == null || !matches(control, "READY", control.generation(), descriptor))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPendingOperation(
            List<String> operationIds,
            Map<String, JdbcBulkOperationControl.Snapshot> controls) {
        for (String operationId : operationIds) {
            JdbcBulkOperationControl.Snapshot control = controls.get(operationId);
            if (control != null && ("UNCOMPOSED".equals(control.state()) || "SUSPENDED".equals(control.state()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean allOperationsReadyAndMatching(
            List<String> operationIds,
            Map<String, JdbcBulkOperationControl.Snapshot> controls,
            Map<String, BulkOperationalDescriptor> compiledByOp) {
        for (String operationId : operationIds) {
            JdbcBulkOperationControl.Snapshot control = controls.get(operationId);
            BulkOperationalDescriptor descriptor = compiledByOp.get(operationId);
            if (control == null || !control.ready() || descriptor == null
                    || !matches(control, "READY", control.generation(), descriptor)) {
                return false;
            }
        }
        return true;
    }

    private BulkNamespaceBatchResult buildAllAlreadyReadyResult(
            String namespace,
            List<String> operationIds,
            BatchReadSnapshot initialRead,
            BulkBatchProgressListener listener) {
        int total = operationIds.size();
        listener.onProgress("PREPARED", total, 0, 0, 0, 0);
        List<BulkNamespaceBatchResult.ItemOutcome> items = new ArrayList<>(total);
        int processed = 0;
        for (String operationId : operationIds) {
            JdbcBulkOperationControl.Snapshot control = initialRead.controls().get(operationId);
            items.add(new BulkNamespaceBatchResult.ItemOutcome(
                    operationId,
                    control.generation(),
                    control.generation(),
                    "READY",
                    BulkNamespaceBatchResult.ItemStatus.SKIPPED_ALREADY_READY,
                    "Operation is already READY with matching descriptor"));
            processed++;
            listener.onProgress("PUBLISHING", total, processed, 0, processed, 0);
        }
        listener.onProgress("COMPLETED", total, processed, 0, processed, 0);
        return new BulkNamespaceBatchResult(
                namespace,
                "CONVERGE_ALL",
                initialRead.publication().generation(),
                total,
                processed,
                0,
                processed,
                0,
                List.copyOf(items),
                Instant.now());
    }

    private BulkNamespaceBatchResult publishPreparedBatch(
            String namespace,
            List<String> operationIds,
            org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate,
            Map<String, BulkOperationalDescriptor> compiledByOp,
            BatchReadSnapshot readSnapshot,
            boolean initiallyPublished,
            long initialPublicationGeneration,
            BulkLifecycleAuditContext audit,
            BulkBatchProgressListener listener,
            String resultIntent) {
        int total = operationIds.size();
        PreparedBatch preparedBatch = new PreparedBatch(
                candidate, initialPublicationGeneration, initiallyPublished, compiledByOp);
        BatchExecutionState state = new BatchExecutionState(initiallyPublished, initialPublicationGeneration);
        if (hasPendingOperation(operationIds, readSnapshot.controls())) {
            documents.withPreparedBulkOpenApiCommit(candidate, () -> {
                executePublishBatchItems(operationIds, readSnapshot.controls(), preparedBatch, audit,
                        listener, total, state);
                return null;
            });
        } else {
            executePublishBatchItems(operationIds, readSnapshot.controls(), preparedBatch, audit,
                    listener, total, state);
        }
        if (!initiallyPublished && state.failed > 0) {
            BatchReadSnapshot afterRace = readBatchSnapshot(operationIds);
            if (isMatchingPublishedPublication(afterRace.publication(), candidate)
                    && !hasDriftedReadyOperation(operationIds, afterRace.controls(), compiledByOp)
                    && !hasPendingOperation(operationIds, afterRace.controls())) {
                return reconcilePreparedBatch(namespace, operationIds, candidate, compiledByOp,
                        afterRace, audit, listener, resultIntent);
            }
        }
        if (state.pubPublished && (state.succeeded > 0 || !documents.hasLocalPublishedBulkOpenApiPublication())) {
            documents.installBulkOpenApiPublication(candidate, state.pubGeneration);
        }
        listener.onProgress("COMPLETED", total, state.processed, state.succeeded, state.skipped, state.failed);
        return new BulkNamespaceBatchResult(namespace, resultIntent,
                state.pubPublished ? state.pubGeneration : null,
                total, state.processed, state.succeeded, state.skipped, state.failed,
                List.copyOf(state.items), Instant.now());
    }

    private BulkNamespaceBatchResult reconcilePreparedBatch(
            String namespace,
            List<String> operationIds,
            org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate,
            Map<String, BulkOperationalDescriptor> compiledByOp,
            BatchReadSnapshot verifiedRead,
            BulkLifecycleAuditContext audit,
            BulkBatchProgressListener listener,
            String resultIntent) {
        int total = operationIds.size();
        long publicationGeneration = verifiedRead.publication().generation();
        int processed = 0;
        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        List<BulkNamespaceBatchResult.ItemOutcome> items = new ArrayList<>(total);
        List<ReconciledAuditItem> reconciledAuditItems = new ArrayList<>();
        for (String operationId : operationIds) {
            BulkOperationalDescriptor descriptor = compiledByOp.get(operationId);
            if (descriptor == null) {
                throw contractDiagnostic("Bulk operation identity is not a validated lifecycle binding", operationId);
            }
            JdbcBulkOperationControl.Snapshot control = verifiedRead.controls().get(operationId);
            if (control == null || !control.ready()) {
                Long gen = control != null ? control.generation() : null;
                String st = control != null ? control.state() : "MISSING";
                skipped++;
                items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, gen, gen, st,
                        BulkNamespaceBatchResult.ItemStatus.SKIPPED_NOT_READY,
                        "Operation is not READY and was skipped during reconciliation"));
            } else if (!matches(control, "READY", control.generation(), descriptor)) {
                failed++;
                items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, control.generation(),
                        control.generation(), control.state(), BulkNamespaceBatchResult.ItemStatus.FAILED,
                        "Committed operation does not match compiled descriptor"));
            } else {
                succeeded++;
                items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, control.generation(),
                        control.generation(), "READY", BulkNamespaceBatchResult.ItemStatus.RECONCILED,
                        "Reconciled local OpenAPI photograph"));
                reconciledAuditItems.add(new ReconciledAuditItem(operationId, control.generation()));
            }
            processed++;
            listener.onProgress("RECONCILING", total, processed, succeeded, skipped, failed);
        }
        if (succeeded > 0) {
            documents.installBulkOpenApiPublication(candidate, publicationGeneration);
            if (audit != null) {
                controlPlane.withConnection(connection -> {
                    for (ReconciledAuditItem reconciled : reconciledAuditItems) {
                        JdbcBulkLifecycleAudit.record(connection, UUID.randomUUID(), runtime.namespace(),
                                runtime.deploymentId(), reconciled.operationId(), "RECONCILE_ALL", audit,
                                reconciled.generation(), reconciled.generation(), "READY");
                    }
                    return null;
                });
            }
        }
        listener.onProgress("COMPLETED", total, processed, succeeded, skipped, failed);
        return new BulkNamespaceBatchResult(namespace, resultIntent, publicationGeneration,
                total, processed, succeeded, skipped, failed, List.copyOf(items), Instant.now());
    }

    private void executePublishBatchItems(
            List<String> operationIds,
            Map<String, JdbcBulkOperationControl.Snapshot> controls,
            PreparedBatch preparedBatch,
            BulkLifecycleAuditContext audit,
            BulkBatchProgressListener listener,
            int total,
            BatchExecutionState state) {
        for (String operationId : operationIds) {
            BulkOperationalDescriptor descriptor = preparedBatch.compiledByOp().get(operationId);
            if (descriptor == null) {
                throw contractDiagnostic("Bulk operation identity is not a validated lifecycle binding", operationId);
            }
            JdbcBulkOperationControl.Snapshot control = controls.get(operationId);
            if (control == null) {
                state.failed++;
                state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, null, null, "MISSING",
                        BulkNamespaceBatchResult.ItemStatus.FAILED, "Bulk operation control row is missing"));
            } else if ("READY".equals(control.state())) {
                if (state.pubPublished
                        && matches(control, "READY", control.generation(), descriptor)) {
                    state.skipped++;
                    state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, control.generation(),
                            control.generation(), "READY",
                            BulkNamespaceBatchResult.ItemStatus.SKIPPED_ALREADY_READY,
                            "Operation is already READY with matching descriptor"));
                } else {
                    state.failed++;
                    state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, control.generation(),
                            control.generation(), control.state(),
                            BulkNamespaceBatchResult.ItemStatus.FAILED,
                            "Operation is READY with a stale descriptor or publication state and must be suspended first"));
                }
            } else if ("UNCOMPOSED".equals(control.state()) || "SUSPENDED".equals(control.state())) {
                long expectedOpGen = control.generation();
                if (expectedOpGen < 0 || expectedOpGen == Long.MAX_VALUE) {
                    state.failed++;
                    state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, expectedOpGen, expectedOpGen,
                            control.state(), BulkNamespaceBatchResult.ItemStatus.FAILED,
                            "Operation generation cannot advance"));
                } else {
                    commitBatchPublishItem(operationId, expectedOpGen, control.state(), descriptor,
                            preparedBatch.candidate(), audit, state);
                }
            } else {
                state.failed++;
                state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, control.generation(),
                        control.generation(), control.state(), BulkNamespaceBatchResult.ItemStatus.FAILED,
                        "Unsupported control state for publish: " + control.state()));
            }
            state.processed++;
            listener.onProgress("PUBLISHING", total, state.processed, state.succeeded, state.skipped, state.failed);
        }
    }

    private void commitBatchPublishItem(
            String operationId,
            long expectedOpGen,
            String initialState,
            BulkOperationalDescriptor descriptor,
            org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate,
            BulkLifecycleAuditContext audit,
            BatchExecutionState state) {
        boolean newPublication = !state.pubPublished;
        long targetPubGen = newPublication ? Math.incrementExact(state.pubGeneration) : state.pubGeneration;
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        var rejectedByPolicy = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            controlPlane.withConnection(connection -> {
                documents.withBulkLifecycleCompositionLock(() -> connection);
                started.set(true);
                if (newPublication) {
                    var global = JdbcBulkOpenApiPublication.transition(connection, runtime.namespace(),
                            runtime.deploymentId(), state.pubGeneration,
                            JdbcBulkOpenApiPublication.Target.PUBLISHED, candidate.digest());
                    if (!global.applied() || global.generation() != targetPubGen) {
                        rejectedByPolicy.set(true);
                        throw conflict("OpenAPI publication lost its generation race");
                    }
                }
                var operation = JdbcBulkOperationControl.transition(connection, runtime.namespace(),
                        operationId, expectedOpGen, JdbcBulkOperationControl.Target.READY,
                        descriptor.descriptorFingerprint(), descriptor.structuralRevision(),
                        targetPubGen, candidate.digest());
                if (!operation.applied() || operation.generation() != expectedOpGen + 1) {
                    rejectedByPolicy.set(true);
                    throw conflict("Bulk operation publication lost its generation race");
                }
                if (audit != null) {
                    JdbcBulkLifecycleAudit.record(connection, UUID.randomUUID(), runtime.namespace(),
                            runtime.deploymentId(), operationId, "PUBLISH_ALL", audit,
                            expectedOpGen, operation.generation(), "READY");
                }
                return null;
            });
            state.pubPublished = true;
            state.pubGeneration = targetPubGen;
            state.succeeded++;
            state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, expectedOpGen,
                    expectedOpGen + 1, "READY", BulkNamespaceBatchResult.ItemStatus.PUBLISHED,
                    "Published operational descriptor"));
        } catch (BulkPublicationConflictException conflictEx) {
            if (!started.get() || rejectedByPolicy.get()) {
                if (!state.pubPublished) {
                    try {
                        JdbcBulkOpenApiPublication.Snapshot refreshedPub = readPublication();
                        state.pubPublished = isMatchingPublishedPublication(refreshedPub, candidate);
                        state.pubGeneration = refreshedPub.generation();
                    } catch (RuntimeException refreshFailure) {
                        conflictEx.addSuppressed(refreshFailure);
                    }
                }
                state.failed++;
                state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, expectedOpGen,
                        expectedOpGen, initialState, BulkNamespaceBatchResult.ItemStatus.FAILED,
                        conflictEx.getMessage()));
                return;
            }
            try {
                runtime.withLifecycleRead(connection -> {
                    var global = JdbcBulkOpenApiPublication.lockForRead(connection, runtime.namespace(), runtime.deploymentId());
                    requireSamePublication(global, candidate, targetPubGen);
                    var operation = JdbcBulkOperationControl.lockForAdmission(connection, runtime.namespace(), operationId);
                    if (!matches(operation, "READY", expectedOpGen + 1, descriptor)) {
                        throw conflict("Publication commit was not reconciled to its exact global and operation identities");
                    }
                    return null;
                });
                state.pubPublished = true;
                state.pubGeneration = targetPubGen;
                state.succeeded++;
                state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, expectedOpGen,
                        expectedOpGen + 1, "READY", BulkNamespaceBatchResult.ItemStatus.PUBLISHED,
                        "Published operational descriptor"));
            } catch (RuntimeException unreconciled) {
                state.failed++;
                state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, expectedOpGen,
                        expectedOpGen, initialState, BulkNamespaceBatchResult.ItemStatus.FAILED,
                        conflictEx.getMessage()));
            }
        } catch (RuntimeException uncertain) {
            if (!started.get()) {
                throw uncertain;
            }
            try {
                runtime.withLifecycleRead(connection -> {
                    var global = JdbcBulkOpenApiPublication.lockForRead(connection, runtime.namespace(), runtime.deploymentId());
                    requireSamePublication(global, candidate, targetPubGen);
                    var operation = JdbcBulkOperationControl.lockForAdmission(connection, runtime.namespace(), operationId);
                    if (!matches(operation, "READY", expectedOpGen + 1, descriptor)) {
                        throw conflict("Publication commit was not reconciled to its exact global and operation identities");
                    }
                    return null;
                });
                state.pubPublished = true;
                state.pubGeneration = targetPubGen;
                state.succeeded++;
                state.items.add(new BulkNamespaceBatchResult.ItemOutcome(operationId, expectedOpGen,
                        expectedOpGen + 1, "READY", BulkNamespaceBatchResult.ItemStatus.PUBLISHED,
                        "Published operational descriptor"));
            } catch (RuntimeException unreconciled) {
                uncertain.addSuppressed(unreconciled);
                throw uncertain;
            }
        }
    }

    private static Map<String, BulkOperationalDescriptor> indexDescriptors(
            List<BulkOperationalDescriptor> descriptors) {
        Map<String, BulkOperationalDescriptor> indexed = new LinkedHashMap<>(descriptors.size());
        for (BulkOperationalDescriptor descriptor : descriptors) {
            indexed.put(descriptor.identity().confirmationOperationId(), descriptor);
        }
        return Map.copyOf(indexed);
    }

    private List<String> resolveOperationalIds(String namespace, String activeTxMessage) {
        requireNamespace(namespace);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable(activeTxMessage);
        }
        if (!bindings.diagnostics().isEmpty()) {
            throw contractDiagnostic(
                    "Bulk MVC declarations contain diagnostics: " + String.join("; ", bindings.diagnostics()));
        }
        Map<String, BulkOperationDescriptorProvider> remainingProviders = new LinkedHashMap<>(providerSnapshot());
        Set<String> sortedIds = new java.util.TreeSet<>();
        for (BulkOperationBinding binding : bindings.bulkOperations()) {
            String opId = binding.confirmationOperationId();
            BulkOperationDescriptorProvider provider = remainingProviders.remove(opId);
            if (provider == null && binding.mode() != BulkMode.DOMAIN_COMMAND) {
                continue;
            }
            if (provider == null) {
                throw contractDiagnostic(
                        "A validated confirmation binding has no descriptor provider: " + opId, opId);
            }
            if (provider.executionInfrastructure() != runtime) {
                throw contractDiagnostic(
                        "Published readiness lookup requires a provider bound to this runtime infrastructure",
                        opId);
            }
            sortedIds.add(opId);
        }
        if (!remainingProviders.isEmpty()) {
            String stray = remainingProviders.keySet().iterator().next();
            throw contractDiagnostic(
                    "A descriptor provider has no validated bulk confirmation binding: "
                            + String.join(", ", remainingProviders.keySet()),
                    stray);
        }
        return sortedIds.isEmpty() ? List.of() : List.copyOf(sortedIds);
    }

    private void requireNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (!runtime.namespace().equals(namespace)) {
            throw new IllegalArgumentException(
                    "Bulk operation namespace is outside the configured lifecycle namespace");
        }
    }

    private record PreparedBatch(
            org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate,
            long publicationGeneration,
            boolean published,
            Map<String, BulkOperationalDescriptor> compiledByOp) {
    }

    private record BatchReadSnapshot(
            JdbcBulkOpenApiPublication.Snapshot publication,
            Map<String, JdbcBulkOperationControl.Snapshot> controls) {
    }

    private record ReconciledAuditItem(String operationId, long generation) {
    }

    private static final class BatchExecutionState {
        private boolean pubPublished;
        private long pubGeneration;
        private int processed;
        private int succeeded;
        private int skipped;
        private int failed;
        private final List<BulkNamespaceBatchResult.ItemOutcome> items = new ArrayList<>();

        private BatchExecutionState(boolean pubPublished, long pubGeneration) {
            this.pubPublished = pubPublished;
            this.pubGeneration = pubGeneration;
        }
    }

    /**
     * Startup convenience helper: publishes an {@code UNCOMPOSED}/{@code SUSPENDED} row, reconciles an
     * already matching {@code READY} row, or suspends and re-publishes when a rolling upgrade updated the
     * composed descriptor. Callers must still invoke this only when durable control state is ready.
     */
    public BulkOperationControlExpectation ensureReadyOnStartup(BulkOperationControlIdentity identity) {
        requireOperationalIdentity(identity);
        JdbcBulkOperationControl.Snapshot current = read(identity);
        if (current == null) {
            throw conflict("Bulk operation control row is missing for " + identity.confirmationOperationId());
        }
        if (current.ready()) {
            try {
                return reconcilePublished(identity, current.generation());
            } catch (IllegalStateException unreconcilable) {
                long suspendedGeneration = suspend(identity, current.generation());
                return publish(identity, suspendedGeneration);
            }
        }
        return publish(identity, current.generation());
    }

    /** Returns the control identities configured for every provider registered on this lifecycle. */
    public List<BulkOperationControlIdentity> configuredIdentities() {
        return providers.stream()
                .map(BulkOperationDescriptorProvider::confirmationOperationId)
                .distinct()
                .map(operationId -> new BulkOperationControlIdentity(runtime.namespace(), operationId))
                .toList();
    }

    private JdbcBulkOpenApiPublication.Snapshot readPublicationForDocumentGuard() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            return runtime.withConnection(connection -> JdbcBulkOpenApiPublication.lockForRead(connection,
                    runtime.namespace(), runtime.deploymentId()));
        return runtime.readAttestedPublication();
    }

    private JdbcBulkOpenApiPublication.Snapshot readPublication() {
        return runtime.withLifecycleRead(connection -> JdbcBulkOpenApiPublication.lockForRead(connection,
                runtime.namespace(), runtime.deploymentId()));
    }

    private static void requireSamePublication(JdbcBulkOpenApiPublication.Snapshot snapshot,
            org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate, long generation) {
        if (!snapshot.published() || snapshot.generation() != generation || !Objects.equals(snapshot.documentDigest(), candidate.digest()))
            throw conflict("Captured OpenAPI photograph differs from the durable publication");
    }

    private void suspendGlobalPublication(JdbcBulkOpenApiPublication.Snapshot before) {
        try {
            var transition = controlPlane.withConnection(connection -> JdbcBulkOpenApiPublication.transition(connection,
                    runtime.namespace(), runtime.deploymentId(), before.generation(), JdbcBulkOpenApiPublication.Target.SUSPENDED, null));
            if (!transition.applied() || transition.generation() != before.generation() + 1)
                throw conflict("Global OpenAPI suspension lost its generation race");
        } catch (RuntimeException uncertain) {
            try {
                var after = readPublication();
                if (!"SUSPENDED".equals(after.state()) || after.generation() != before.generation() + 1 || after.documentDigest() != null)
                    throw conflict("Global suspension could not be reconciled");
            } catch (RuntimeException unreconciled) { uncertain.addSuppressed(unreconciled); throw uncertain; }
        }
    }

    /**
     * Durably suspends the target operation and global deployment publication atomically in one
     * PostgreSQL transaction before invalidating local OpenAPI caches. The durable fence is
     * authoritative even if local cache invalidation subsequently fails.
     */
    public long suspend(BulkOperationControlIdentity identity, long expectedGeneration) {
        return suspend(identity, expectedGeneration, null);
    }

    /**
     * Atomically suspends the target operation and global deployment publication and, when
     * {@code audit} is non-null, appends a {@code SUSPEND} entry to the durable V21 audit ledger
     * inside the same database transaction.
     */
    public long suspend(
            BulkOperationControlIdentity identity, long expectedGeneration, BulkLifecycleAuditContext audit) {
        return documents.withBulkLifecycleCompositionLock(() ->
                suspendUnderCompositionLock(identity, expectedGeneration, audit));
    }

    private long suspendUnderCompositionLock(
            BulkOperationControlIdentity identity, long expectedGeneration, BulkLifecycleAuditContext audit) {
        requireIdentity(identity);
        if (expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE) {
            throw new IllegalArgumentException("expectedGeneration must be nonnegative and incrementable");
        }
        JdbcBulkOperationControl.Snapshot before = read(identity);
        if (before == null || before.generation() != expectedGeneration) {
            throw conflict("Bulk operation suspension lost its expected generation");
        }
        JdbcBulkOpenApiPublication.Snapshot beforePublication = readPublication();
        if (beforePublication == null || beforePublication.generation() < 0
                || beforePublication.generation() == Long.MAX_VALUE) {
            throw conflict("Bulk publication generation cannot advance");
        }
        long expectedPublicationGeneration = beforePublication.generation();
        long expectedOperationGeneration = Math.incrementExact(expectedGeneration);
        long expectedNextPublicationGeneration = Math.incrementExact(expectedPublicationGeneration);
        JdbcBulkOperationControl.SuspendTransition transition;
        try {
            transition = controlPlane.withConnection(connection -> {
                JdbcBulkOperationControl.SuspendTransition result = JdbcBulkOperationControl.suspendWithPublication(
                        connection, identity.namespaceId(), runtime.deploymentId(),
                        identity.confirmationOperationId(), expectedGeneration, expectedPublicationGeneration);
                if (!result.applied() || result.operationGeneration() != expectedOperationGeneration
                        || result.publicationGeneration() != expectedNextPublicationGeneration) {
                    throw conflict("Bulk operation suspension lost its generation race");
                }
                if (audit != null) {
                    JdbcBulkLifecycleAudit.record(connection, UUID.randomUUID(), identity.namespaceId(),
                            runtime.deploymentId(), identity.confirmationOperationId(), "SUSPEND", audit,
                            expectedGeneration, result.operationGeneration(), "SUSPENDED");
                }
                return result;
            });
        } catch (RuntimeException uncertain) {
            JdbcBulkOperationControl.Snapshot afterOp = readAfterUncertainCommit(identity, uncertain);
            JdbcBulkOpenApiPublication.Snapshot afterPub = readPublicationAfterUncertainCommit(uncertain);
            if (afterOp != null && "SUSPENDED".equals(afterOp.state())
                    && afterOp.generation() == expectedOperationGeneration
                    && afterOp.descriptorFingerprint() == null && afterOp.structuralRevision() == null
                    && afterPub != null && "SUSPENDED".equals(afterPub.state())
                    && afterPub.generation() == expectedNextPublicationGeneration
                    && afterPub.documentDigest() == null) {
                withLifecycleCacheFence(() -> { documents.clearCaches(); return null; });
                return afterOp.generation();
            }
            throw uncertain;
        }
        withLifecycleCacheFence(() -> { documents.clearCaches(); return null; });
        return transition.operationGeneration();
    }

    private JdbcBulkOpenApiPublication.Snapshot readPublicationAfterUncertainCommit(RuntimeException original) {
        try {
            return readPublication();
        } catch (RuntimeException reconciliationFailure) {
            original.addSuppressed(reconciliationFailure);
            return null;
        }
    }

    /**
     * Called by the canonical documentation-cache invalidation hook. Suspends every currently
     * valid declared bulk identity before the shared cache is cleared. A partial suspension
     * aborts the cache clear; already-suspended rows remain safely closed.
     */
    public void suspendAllBeforeCacheClear() {
        documents.withBulkLifecycleCompositionLock(() -> {
            if (!bindings.diagnostics().isEmpty())
                throw contractDiagnostic("Cannot invalidate OpenAPI caches with malformed bulk declarations");
            suspendGlobalPublication(readPublication());
            return null;
        });
    }

    private Set<String> requiredOpenApiGroups() {
        Set<String> groups = new java.util.TreeSet<>();
        for (BulkOperationBinding binding : bindings.bulkOperations()) {
            var handler = binding.confirmationHandler();
            var mapping = bindings.mappingFor(handler).orElseThrow(() ->
                    contractDiagnostic("A validated confirmation handler has no MVC mapping",
                            binding.confirmationOperationId()));
            groups.add(compilerGroup(handler, mapping));
        }
        if (groups.isEmpty()) throw contractDiagnostic("No published OpenAPI group can be composed for bulk lifecycle");
        return Set.copyOf(groups);
    }

    private String compilerGroup(org.springframework.web.method.HandlerMethod handler,
            org.springframework.web.servlet.mvc.method.RequestMappingInfo mapping) {
        return operationResolver.resolve(handler, mapping).group();
    }

    private BulkOperationalDescriptor descriptor(BulkOperationControlIdentity identity, boolean fresh) {
        requireIdentity(identity);
        return descriptors(fresh).stream().filter(value -> value.identity().equals(identity)).findFirst()
                .orElseThrow(() -> contractDiagnostic(
                        "Bulk operation identity is not a validated lifecycle binding",
                        identity.confirmationOperationId()));
    }

    private List<BulkOperationalDescriptor> descriptors(boolean fresh) {
        if (!bindings.diagnostics().isEmpty())
            throw contractDiagnostic("Bulk MVC declarations contain diagnostics: " + String.join("; ", bindings.diagnostics()));
        // Every declaration is compiled. Commands require providers; valid updates may remain
        // structural only until a concrete host provider supplies the operational profile.
        List<BulkOperationStructuralDescriptor> structures = compiler.compileAll(fresh);
        Map<String, BulkOperationDescriptorProvider> byOperation = providerSnapshot();
        Set<String> updateSlots = new java.util.HashSet<>();
        for (var structural : structures) {
            if (structural.mode() != BulkMode.DOMAIN_COMMAND
                    && !updateSlots.add(structural.resourceKey() + ":"
                            + BulkExecutionContract.crudCapabilityId(structural.mode(), structural.atomicity())))
                throw contractDiagnostic("Bulk update mode and atomicity collide within a resource",
                        structural.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION).reference().operationId());
        }
        List<BulkOperationalDescriptor> descriptors = new ArrayList<>(structures.size());
        for (BulkOperationStructuralDescriptor structural : structures) {
            String operationId = structural.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .reference().operationId();
            BulkOperationDescriptorProvider provider = byOperation.remove(operationId);
            if (provider == null && structural.mode() != BulkMode.DOMAIN_COMMAND) continue;
            if (provider == null)
                throw contractDiagnostic("A validated confirmation binding has no descriptor provider: " + operationId, operationId);
            BulkOperationalDescriptor composed = BulkOperationalDescriptorComposer.compose(structural, provider);
            if (composed.infrastructure() != runtime)
                throw contractDiagnostic("Every descriptor provider must use the lifecycle runtime infrastructure instance", operationId);
            descriptors.add(composed);
        }
        if (!byOperation.isEmpty()) {
            String stray = byOperation.keySet().iterator().next();
            throw contractDiagnostic(
                    "A descriptor provider has no validated bulk confirmation binding: " + String.join(", ", byOperation.keySet()),
                    stray);
        }
        return List.copyOf(descriptors);
    }

    private Map<String, BulkOperationDescriptorProvider> providerSnapshot() {
        Map<String, BulkOperationDescriptorProvider> byOperation = new LinkedHashMap<>();
        for (BulkOperationDescriptorProvider provider : providers) {
            Objects.requireNonNull(provider, "descriptor provider");
            CapturedProvider captured = new CapturedProvider(provider);
            String operationId = captured.confirmationOperationId();
            if (operationId == null || operationId.isBlank() || !operationId.equals(operationId.strip()))
                throw contractDiagnostic("Descriptor provider confirmation operation ID is not canonical", operationId);
            if (byOperation.putIfAbsent(operationId, captured) != null)
                throw contractDiagnostic("Multiple descriptor providers target one confirmation operation: " + operationId, operationId);
        }
        return byOperation;
    }

    private JdbcBulkOperationControl.Snapshot read(BulkOperationControlIdentity identity) {
        return runtime.withLifecycleRead(connection ->
                JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(),
                        identity.confirmationOperationId()));
    }

    private JdbcBulkOperationControl.Snapshot readAfterUncertainCommit(
            BulkOperationControlIdentity identity, RuntimeException original) {
        try {
            return read(identity);
        } catch (RuntimeException reconciliationFailure) {
            original.addSuppressed(reconciliationFailure);
            return null;
        }
    }

    private static boolean matches(JdbcBulkOperationControl.Snapshot snapshot, String state, long generation,
            BulkOperationalDescriptor descriptor) {
        return snapshot != null && state.equals(snapshot.state()) && snapshot.generation() == generation
                && descriptor.descriptorFingerprint().equals(snapshot.descriptorFingerprint())
                && descriptor.structuralRevision().equals(snapshot.structuralRevision());
    }

    private void requireIdentity(BulkOperationControlIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (bindings.declaresUpdateConfirmation(identity.confirmationOperationId())
                && (!bindings.diagnostics().isEmpty() || bindings.bulkOperations().stream().noneMatch(binding ->
                        binding.confirmationOperationId().equals(identity.confirmationOperationId())
                                && binding.mode() != BulkMode.DOMAIN_COMMAND)))
            throw contractDiagnostic("Malformed bulk updates have no operational lifecycle support",
                    identity.confirmationOperationId());
        if (!runtime.namespace().equals(identity.namespaceId()))
            throw new IllegalArgumentException("Bulk operation identity is outside the configured namespace");
    }

    private void requireOperationalIdentity(BulkOperationControlIdentity identity) {
        requireIdentity(identity);
        if (bindings.declaresUpdateConfirmation(identity.confirmationOperationId())
                && !providerSnapshot().containsKey(identity.confirmationOperationId()))
            throw contractDiagnostic(
                    "A structural bulk update has no operational descriptor provider: "
                            + identity.confirmationOperationId(),
                    identity.confirmationOperationId());
    }

    private static BulkLifecycleUnavailableException unavailable(String message) {
        return new BulkLifecycleUnavailableException(message);
    }

    private static BulkPublicationConflictException conflict(String message) {
        return new BulkPublicationConflictException(message);
    }

    private static BulkContractDiagnosticException contractDiagnostic(String message) {
        return new BulkContractDiagnosticException(message);
    }

    private static BulkContractDiagnosticException contractDiagnostic(String message, String operationId) {
        return new BulkContractDiagnosticException(message, operationId);
    }

    private <T> T withLifecycleCacheFence(Supplier<T> action) {
        boolean previous = lifecycleCacheFence.get();
        lifecycleCacheFence.set(true);
        try {
            return action.get();
        } finally {
            if (previous) lifecycleCacheFence.set(true);
            else lifecycleCacheFence.remove();
        }
    }

    private void requireCapacityBinding(java.sql.Connection connection, BulkOperationalDescriptor descriptor)
            throws java.sql.SQLException {
        if (descriptor.asynchronousCapacityBinding().isPresent())
            JdbcBulkCapacityOccupancy.requireBinding(connection, runtime,
                    descriptor.asynchronousCapacityBinding().orElseThrow(), true);
    }

    private static final class CapturedProvider implements BulkOperationDescriptorProvider {
        private final String confirmationOperationId;
        private final String providerId;
        private final String providerRevision;
        private final BulkIdentityCodec<?, ?> codec;
        private final String identitySchemaPointer;
        private final BulkOperationalProfile profile;
        private final BulkExecutionInfrastructure infrastructure;
        private final java.util.Optional<BulkCapacityBinding> capacityBinding;

        private CapturedProvider(BulkOperationDescriptorProvider source) {
            confirmationOperationId = source.confirmationOperationId();
            providerId = source.providerId();
            providerRevision = source.providerRevision();
            codec = source.identityCodec();
            identitySchemaPointer = source.identitySchemaPointer();
            profile = source.profile();
            infrastructure = source.executionInfrastructure();
            capacityBinding = source.asynchronousCapacityBinding();
        }
        public String confirmationOperationId() { return confirmationOperationId; }
        public String providerId() { return providerId; }
        public String providerRevision() { return providerRevision; }
        public BulkIdentityCodec<?, ?> identityCodec() { return codec; }
        public String identitySchemaPointer() { return identitySchemaPointer; }
        public BulkOperationalProfile profile() { return profile; }
        public BulkExecutionInfrastructure executionInfrastructure() { return infrastructure; }
        public java.util.Optional<BulkCapacityBinding> asynchronousCapacityBinding() { return capacityBinding; }
    }
}
