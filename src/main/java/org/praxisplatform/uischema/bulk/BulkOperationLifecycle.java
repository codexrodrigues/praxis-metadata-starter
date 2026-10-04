package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
            if (!current.published() || current.generation() != generation || !Objects.equals(current.documentDigest(), digest))
                throw unavailable("Local OpenAPI photograph does not match the durable publication");
        });
        documents.installBulkLifecycleInvalidationGuard(() -> {
            if (!lifecycleCacheFence.get()) suspendAllBeforeCacheClear();
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
            JdbcBulkOperationControl.Snapshot current = runtime.withLifecycleRead(connection ->
                    JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(),
                            identity.confirmationOperationId()));
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
            JdbcBulkOperationControl.Snapshot current = runtime.withLifecycleRead(connection ->
                    JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(),
                            identity.confirmationOperationId()));
            if (current == null || !current.ready()
                    || !descriptor.descriptorFingerprint().equals(current.descriptorFingerprint())
                    || !descriptor.structuralRevision().equals(current.structuralRevision()))
                throw unavailable("Bulk operation is not durably READY for the current composed descriptor");
            return new ReadyAdmission(identity, descriptor.expectation(current.generation()),
                    descriptor.structural().mode(), descriptor.structural().atomicity(),
                    executionMode, selectionMode, profile);
        });
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreType
    public static final class ReadyAdmission {
        private final BulkOperationControlIdentity identity;
        private final BulkOperationControlExpectation expectation;
        private final BulkMode mode;
        private final org.praxisplatform.uischema.action.ActionCollectionAtomicity atomicity;
        private final BulkExecutionMode executionMode;
        private final BulkSelectionMode selectionMode;
        private final BulkOperationalProfile profile;

        private ReadyAdmission(BulkOperationControlIdentity identity,
                BulkOperationControlExpectation expectation, BulkMode mode,
                org.praxisplatform.uischema.action.ActionCollectionAtomicity atomicity,
                BulkExecutionMode executionMode, BulkSelectionMode selectionMode,
                BulkOperationalProfile profile) {
            this.identity = identity;
            this.expectation = expectation;
            this.mode = mode;
            this.atomicity = atomicity;
            this.executionMode = executionMode;
            this.selectionMode = selectionMode;
            this.profile = profile;
        }
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
     * Captures READY CRUD contracts and a shared action frame for one resource response. The entire
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
                    throw unavailable("More than one bulk update maps to the same capability ID");
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
                            var current = JdbcBulkOperationControl.lockForAdmission(connection,
                                    descriptor.identity().namespaceId(), descriptor.identity().confirmationOperationId());
                            if (!matches(current, "READY", approved.get(descriptor.identity()).generation(), descriptor))
                                throw unavailable("Bulk operation changed after response projection capture");
                        }
                        return null;
                    });
                });
            } catch (RuntimeException unavailableProjection) {
                if (rejection == null) rejection = unavailableProjection;
                else if (rejection != unavailableProjection) rejection.addSuppressed(unavailableProjection);
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
        requireOperationalIdentity(identity);
        if (expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE)
            throw new IllegalArgumentException("expectedGeneration must be nonnegative and incrementable");
        documents.requireBulkOpenApiServing();
        var before = read(identity);
        if (before == null || before.generation() != expectedGeneration
                || !("UNCOMPOSED".equals(before.state()) || "SUSPENDED".equals(before.state())))
            throw unavailable("Bulk operation control is not publishable at the expected generation");
        var initialPublication = readPublication(); // This SHARE transaction ends before any control UPDATE.
        var publication = initialPublication;
        if (publication.published() && documents.hasLocalPublishedBulkOpenApiPublication()) {
            PreparedPublication prepared = documents.withPublishedBulkOpenApiPublication((candidate, generation) -> {
                requireSamePublication(initialPublication, candidate, generation);
                operationResolver.refreshPublishedOpenApiGroupsStrict(requiredOpenApiGroups());
                return new PreparedPublication(candidate, descriptor(identity, false));
            });
            return commitPrepared(identity, expectedGeneration, prepared, publication, false);
        }
        if (!publication.published()) {
            // Initial/uncomposed ledgers must be durably suspended before capture. Direct UPDATE,
            // never a publication SHARE lock converted in the same physical transaction.
            documents.withBulkLifecycleCompositionLock(() -> {
                suspendGlobalPublication(initialPublication);
                withLifecycleCacheFence(() -> { documents.clearCaches(); return null; });
                return null;
            });
            publication = readPublication();
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
                capturedPublication, !capturedPublication.published());
    }

    private record PreparedPublication(org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate,
            BulkOperationalDescriptor descriptor) {}

    private BulkOperationControlExpectation commitPrepared(BulkOperationControlIdentity identity, long expectedGeneration,
            PreparedPublication prepared, JdbcBulkOpenApiPublication.Snapshot before, boolean newPublication) {
        long publicationGeneration = newPublication ? Math.incrementExact(before.generation()) : before.generation();
        documents.withPreparedBulkOpenApiCommit(prepared.candidate(), () -> {
            var admitted = descriptor(identity, false);
            // Structural descriptors are immutable objects, not identity-comparable values.
            // Their canonical digest and the operational fingerprint bind the complete content.
            if (!prepared.descriptor().identity().equals(admitted.identity())
                    || !prepared.descriptor().structuralRevision().equals(admitted.structuralRevision())
                    || !prepared.descriptor().descriptorFingerprint().equals(admitted.descriptorFingerprint())
                    || prepared.descriptor().infrastructure() != admitted.infrastructure())
                throw unavailable("Bulk descriptor changed before publication admission");
            var started = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                controlPlane.withConnection(connection -> {
                    documents.withBulkLifecycleCompositionLock(() -> connection);
                    started.set(true);
                    if (newPublication) {
                        var global = JdbcBulkOpenApiPublication.transition(connection, runtime.namespace(), runtime.deploymentId(),
                                before.generation(), JdbcBulkOpenApiPublication.Target.PUBLISHED, prepared.candidate().digest());
                        if (!global.applied() || global.generation() != publicationGeneration)
                            throw unavailable("OpenAPI publication lost its generation race");
                    }
                    var operation = JdbcBulkOperationControl.transition(connection, identity.namespaceId(),
                            identity.confirmationOperationId(), expectedGeneration, JdbcBulkOperationControl.Target.READY,
                            prepared.descriptor().descriptorFingerprint(), prepared.descriptor().structuralRevision(),
                            publicationGeneration, prepared.candidate().digest());
                    // Throw inside the control transaction so a losing operation also rolls back global publication.
                    if (!operation.applied() || operation.generation() != expectedGeneration + 1)
                        throw unavailable("Bulk operation publication lost its generation race");
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
                            throw unavailable("Publication commit was not reconciled to its exact global and operation identities");
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
        requireOperationalIdentity(identity);
        documents.requireBulkOpenApiServing();
        var publication = readPublication();
        if (!publication.published()) throw unavailable("No committed OpenAPI publication can be reconciled");
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
                throw unavailable("Committed operation does not match reconciliation identity");
            return null;
        });
        documents.installBulkOpenApiPublication(prepared.candidate(), publication.generation());
        return prepared.descriptor().expectation(readyGeneration);
    }

    private JdbcBulkOpenApiPublication.Snapshot readPublicationForDocumentGuard() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            return runtime.withConnection(connection -> JdbcBulkOpenApiPublication.lockForRead(connection,
                    runtime.namespace(), runtime.deploymentId()));
        return readPublication();
    }

    private JdbcBulkOpenApiPublication.Snapshot readPublication() {
        return runtime.withLifecycleRead(connection -> JdbcBulkOpenApiPublication.lockForRead(connection,
                runtime.namespace(), runtime.deploymentId()));
    }

    private static void requireSamePublication(JdbcBulkOpenApiPublication.Snapshot snapshot,
            org.praxisplatform.uischema.openapi.OpenApiPublicationCandidate candidate, long generation) {
        if (!snapshot.published() || snapshot.generation() != generation || !Objects.equals(snapshot.documentDigest(), candidate.digest()))
            throw unavailable("Captured OpenAPI photograph differs from the durable publication");
    }

    private void suspendGlobalPublication(JdbcBulkOpenApiPublication.Snapshot before) {
        try {
            var transition = controlPlane.withConnection(connection -> JdbcBulkOpenApiPublication.transition(connection,
                    runtime.namespace(), runtime.deploymentId(), before.generation(), JdbcBulkOpenApiPublication.Target.SUSPENDED, null));
            if (!transition.applied() || transition.generation() != before.generation() + 1)
                throw unavailable("Global OpenAPI suspension lost its generation race");
        } catch (RuntimeException uncertain) {
            try {
                var after = readPublication();
                if (!"SUSPENDED".equals(after.state()) || after.generation() != before.generation() + 1 || after.documentDigest() != null)
                    throw unavailable("Global suspension could not be reconciled");
            } catch (RuntimeException unreconciled) { uncertain.addSuppressed(unreconciled); throw uncertain; }
        }
    }

    /**
     * Durably suspends one operation before invalidating local OpenAPI caches. The durable fence
     * is authoritative even if local cache invalidation subsequently fails.
     */
    public long suspend(BulkOperationControlIdentity identity, long expectedGeneration) {
        return documents.withBulkLifecycleCompositionLock(() -> suspendUnderCompositionLock(identity, expectedGeneration));
    }

    private long suspendUnderCompositionLock(BulkOperationControlIdentity identity, long expectedGeneration) {
        requireIdentity(identity);
        JdbcBulkOperationControl.Snapshot before = read(identity);
        if (before == null || before.generation() != expectedGeneration)
            throw unavailable("Bulk operation suspension lost its expected generation");
        long generation = suspendDurably(identity, expectedGeneration);
        suspendGlobalPublication(readPublication());
        withLifecycleCacheFence(() -> { documents.clearCaches(); return null; });
        return generation;
    }

    private long suspendDurably(BulkOperationControlIdentity identity, long expectedGeneration) {
        requireIdentity(identity);
        if (expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE)
            throw new IllegalArgumentException("expectedGeneration must be nonnegative and incrementable");
        JdbcBulkOperationControl.Transition transition;
        try {
            transition = controlPlane.withConnection(connection ->
                    JdbcBulkOperationControl.transition(connection, identity.namespaceId(),
                            identity.confirmationOperationId(), expectedGeneration,
                            JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null));
        } catch (RuntimeException uncertain) {
            JdbcBulkOperationControl.Snapshot after = readAfterUncertainCommit(identity, uncertain);
            if (after != null && "SUSPENDED".equals(after.state())
                    && after.generation() == expectedGeneration + 1
                    && after.descriptorFingerprint() == null && after.structuralRevision() == null) {
                return after.generation();
            }
            throw uncertain;
        }
        if (!transition.applied()) throw unavailable("Bulk operation suspension lost its generation race");
        if (transition.generation() != expectedGeneration + 1)
            throw unavailable("Bulk operation suspension returned a noncanonical generation");
        return transition.generation();
    }

    /**
     * Called by the canonical documentation-cache invalidation hook. Suspends every currently
     * valid declared bulk identity before the shared cache is cleared. A partial suspension
     * aborts the cache clear; already-suspended rows remain safely closed.
     */
    public void suspendAllBeforeCacheClear() {
        documents.withBulkLifecycleCompositionLock(() -> {
            if (!bindings.diagnostics().isEmpty())
                throw unavailable("Cannot invalidate OpenAPI caches with malformed bulk declarations");
            suspendGlobalPublication(readPublication());
            return null;
        });
    }

    private Set<String> requiredOpenApiGroups() {
        Set<String> groups = new java.util.TreeSet<>();
        for (BulkOperationBinding binding : bindings.bulkOperations()) {
            var handler = binding.confirmationHandler();
            var mapping = bindings.mappingFor(handler).orElseThrow(() ->
                    unavailable("A validated confirmation handler has no MVC mapping"));
            groups.add(compilerGroup(handler, mapping));
        }
        if (groups.isEmpty()) throw unavailable("No published OpenAPI group can be composed for bulk lifecycle");
        return Set.copyOf(groups);
    }

    private String compilerGroup(org.springframework.web.method.HandlerMethod handler,
            org.springframework.web.servlet.mvc.method.RequestMappingInfo mapping) {
        return operationResolver.resolve(handler, mapping).group();
    }

    private BulkOperationalDescriptor descriptor(BulkOperationControlIdentity identity, boolean fresh) {
        requireIdentity(identity);
        return descriptors(fresh).stream().filter(value -> value.identity().equals(identity)).findFirst()
                .orElseThrow(() -> unavailable("Bulk operation identity is not a validated lifecycle binding"));
    }

    private List<BulkOperationalDescriptor> descriptors(boolean fresh) {
        if (!bindings.diagnostics().isEmpty())
            throw unavailable("Bulk MVC declarations contain diagnostics: " + String.join("; ", bindings.diagnostics()));
        // Every declaration is compiled. Commands require providers; valid updates may remain
        // structural only until a concrete host provider supplies the operational profile.
        List<BulkOperationStructuralDescriptor> structures = compiler.compileAll(fresh);
        Map<String, BulkOperationDescriptorProvider> byOperation = providerSnapshot();
        Set<String> updateSlots = new java.util.HashSet<>();
        for (var structural : structures) {
            if (structural.mode() != BulkMode.DOMAIN_COMMAND
                    && !updateSlots.add(structural.resourceKey() + ":"
                            + BulkExecutionContract.crudCapabilityId(structural.mode(), structural.atomicity())))
                throw unavailable("Bulk update mode and atomicity collide within a resource");
        }
        List<BulkOperationalDescriptor> descriptors = new ArrayList<>(structures.size());
        for (BulkOperationStructuralDescriptor structural : structures) {
            String operationId = structural.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .reference().operationId();
            BulkOperationDescriptorProvider provider = byOperation.remove(operationId);
            if (provider == null && structural.mode() != BulkMode.DOMAIN_COMMAND) continue;
            if (provider == null) throw unavailable("A validated confirmation binding has no descriptor provider");
            BulkOperationalDescriptor composed = BulkOperationalDescriptorComposer.compose(structural, provider);
            if (composed.infrastructure() != runtime)
                throw unavailable("Every descriptor provider must use the lifecycle runtime infrastructure instance");
            descriptors.add(composed);
        }
        if (!byOperation.isEmpty()) throw unavailable("A descriptor provider has no validated bulk confirmation binding");
        return List.copyOf(descriptors);
    }

    private Map<String, BulkOperationDescriptorProvider> providerSnapshot() {
        Map<String, BulkOperationDescriptorProvider> byOperation = new LinkedHashMap<>();
        for (BulkOperationDescriptorProvider provider : providers) {
            Objects.requireNonNull(provider, "descriptor provider");
            CapturedProvider captured = new CapturedProvider(provider);
            String operationId = captured.confirmationOperationId();
            if (operationId == null || operationId.isBlank() || !operationId.equals(operationId.strip()))
                throw unavailable("Descriptor provider confirmation operation ID is not canonical");
            if (byOperation.putIfAbsent(operationId, captured) != null)
                throw unavailable("Multiple descriptor providers target one confirmation operation");
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
            throw unavailable("Malformed bulk updates have no operational lifecycle support");
        if (!runtime.namespace().equals(identity.namespaceId()))
            throw new IllegalArgumentException("Bulk operation identity is outside the configured namespace");
    }

    private void requireOperationalIdentity(BulkOperationControlIdentity identity) {
        requireIdentity(identity);
        if (bindings.declaresUpdateConfirmation(identity.confirmationOperationId())
                && !providerSnapshot().containsKey(identity.confirmationOperationId()))
            throw unavailable("A structural bulk update has no operational descriptor provider");
    }

    private static IllegalStateException unavailable(String message) { return new IllegalStateException(message); }

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

    private static final class CapturedProvider implements BulkOperationDescriptorProvider {
        private final String confirmationOperationId;
        private final String providerId;
        private final String providerRevision;
        private final BulkIdentityCodec<?, ?> codec;
        private final String identitySchemaPointer;
        private final BulkOperationalProfile profile;
        private final BulkExecutionInfrastructure infrastructure;

        private CapturedProvider(BulkOperationDescriptorProvider source) {
            confirmationOperationId = source.confirmationOperationId();
            providerId = source.providerId();
            providerRevision = source.providerRevision();
            codec = source.identityCodec();
            identitySchemaPointer = source.identitySchemaPointer();
            profile = source.profile();
            infrastructure = source.executionInfrastructure();
        }
        public String confirmationOperationId() { return confirmationOperationId; }
        public String providerId() { return providerId; }
        public String providerRevision() { return providerRevision; }
        public BulkIdentityCodec<?, ?> identityCodec() { return codec; }
        public String identitySchemaPointer() { return identitySchemaPointer; }
        public BulkOperationalProfile profile() { return profile; }
        public BulkExecutionInfrastructure executionInfrastructure() { return infrastructure; }
    }
}
