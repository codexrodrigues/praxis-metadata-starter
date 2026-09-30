package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.type.TypeFactory;
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
 * action, OpenAPI and provider composition, then fenced by the durable operation-control row.
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

    public BulkOperationLifecycle(BulkResourceOperationBindings bindings,
            CanonicalOperationResolver operationResolver, OpenApiDocumentService documents,
            ActionDefinitionRegistry actionDefinitions, TypeFactory typeFactory,
            SchemaReferenceResolver schemaReferences, BulkExecutionInfrastructure runtime,
            BulkControlPlaneInfrastructure controlPlane,
            List<BulkOperationDescriptorProvider> providers) {
        this(bindings, operationResolver, documents, actionDefinitions, typeFactory, schemaReferences, runtime,
                controlPlane, providers, new org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver(documents));
    }

    public BulkOperationLifecycle(BulkResourceOperationBindings bindings,
            CanonicalOperationResolver operationResolver, OpenApiDocumentService documents,
            ActionDefinitionRegistry actionDefinitions, TypeFactory typeFactory,
            SchemaReferenceResolver schemaReferences, BulkExecutionInfrastructure runtime,
            BulkControlPlaneInfrastructure controlPlane, List<BulkOperationDescriptorProvider> providers,
            org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilities) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.operationResolver = Objects.requireNonNull(operationResolver, "operationResolver");
        this.compiler = new BulkOperationStructuralCompiler(bindings, operationResolver, documents,
                actionDefinitions, typeFactory, schemaReferences, capabilities);
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
        Set<String> requiredGroups = requiredOpenApiGroups();
        List<String> publishedGroups = operationResolver.publishedOpenApiGroups(requiredGroups);
        return documents.withFreshBulkLifecycleDocuments(Set.copyOf(publishedGroups), () -> {
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
     * Resolves all requested action projections in one fresh composition. The same captured
     * descriptor supplies both its fingerprint and its public contract. A second durable read
     * rejects generation changes during composition, including suspend/republish with equal
     * content. Contextual availability and later transactional admission remain independent.
     */
    public Map<String, org.praxisplatform.uischema.action.ActionExecutionContract> projectReadyActions(
            List<org.praxisplatform.uischema.action.ActionDefinition> actions) {
        if (actions == null || actions.isEmpty() || bindings.bulkOperations().isEmpty()) return Map.of();
        Set<String> declaredIds = bindings.bulkOperations().stream().map(BulkOperationBinding::confirmationOperationId)
                .collect(java.util.stream.Collectors.toSet());
        if (actions.stream().filter(Objects::nonNull).noneMatch(action -> action.operation() != null
                && declaredIds.contains(action.operation().operationId()))) return Map.of();
        try {
            Map<BulkOperationControlIdentity, JdbcBulkOperationControl.Snapshot> before = runtime.withLifecycleRead(connection -> {
                Map<BulkOperationControlIdentity, JdbcBulkOperationControl.Snapshot> captured = new LinkedHashMap<>();
                for (var operationId : declaredIds.stream().sorted().toList()) {
                    var identity = new BulkOperationControlIdentity(runtime.namespace(), operationId);
                    captured.put(identity, JdbcBulkOperationControl.lockForAdmission(connection, identity.namespaceId(), operationId));
                }
                return captured;
            });
            Set<String> requiredGroups = requiredOpenApiGroups();
            List<String> publishedGroups = operationResolver.publishedOpenApiGroups(requiredGroups);
            return documents.withFreshBulkLifecycleDocuments(Set.copyOf(publishedGroups), () -> {
                operationResolver.refreshPublishedOpenApiGroupsStrict(requiredGroups);
                List<BulkOperationalDescriptor> descriptors = descriptors(false).stream()
                        .sorted(Comparator.comparing(value -> value.identity().confirmationOperationId())).toList();
                return runtime.withLifecycleRead(connection -> {
                    Map<String, org.praxisplatform.uischema.action.ActionExecutionContract> result = new LinkedHashMap<>();
                    for (var descriptor : descriptors) {
                        var initial = before.get(descriptor.identity());
                        if (initial == null || !initial.ready()) continue;
                        var current = JdbcBulkOperationControl.lockForAdmission(connection,
                                descriptor.identity().namespaceId(), descriptor.identity().confirmationOperationId());
                        if (!matches(initial, "READY", initial.generation(), descriptor)
                                || !matches(current, "READY", initial.generation(), descriptor)) continue;
                        for (var action : actions) {
                            if (action != null && descriptor.structural().action().operation().equals(action.operation())
                                    && BulkOperationStructuralDescriptor.Action.from(action).equals(descriptor.structural().action())) {
                                try {
                                    result.put(action.operation().operationId(), action.execution().withBulk(BulkExecutionContract.from(descriptor)));
                                } catch (IllegalStateException unavailableProjection) {
                                    // Raw transport validity does not imply a renderable filtered UI variant.
                                }
                            }
                        }
                    }
                    return Map.copyOf(result);
                });
            });
        } catch (RuntimeException unavailableComposition) {
            // Discovery remains useful for ordinary actions while bulk fails closed.
            return Map.of();
        }
    }

    /**
     * Publishes one fresh exact OpenAPI/MVC/provider composition using a generation CAS. Only an
     * UNCOMPOSED or SUSPENDED row may become READY. Publication never accepts a caller-supplied
     * fingerprint and a stale generation never retries implicitly.
     */
    public BulkOperationControlExpectation publish(BulkOperationControlIdentity identity, long expectedGeneration) {
        requireIdentity(identity);
        if (expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE)
            throw new IllegalArgumentException("expectedGeneration must be nonnegative and incrementable");
        JdbcBulkOperationControl.Snapshot before = read(identity);
        if (before == null || before.generation() != expectedGeneration
                || !("UNCOMPOSED".equals(before.state()) || "SUSPENDED".equals(before.state())))
            throw unavailable("Bulk operation control is not publishable at the expected generation");

        Set<String> requiredGroups = requiredOpenApiGroups();
        List<String> publishedGroups = operationResolver.publishedOpenApiGroups(requiredGroups);
        return documents.withFreshBulkLifecycleDocuments(Set.copyOf(publishedGroups), () -> {
            operationResolver.refreshPublishedOpenApiGroupsStrict(requiredGroups);
            BulkOperationalDescriptor descriptor = descriptor(identity, false);
            // Re-enter the real publication boundary to check the remaining admission budget
            // immediately before CAS. No deadline check may turn a committed READY into failure.
            return documents.withBulkLifecycleCompositionLock(() -> publishPrepared(identity, expectedGeneration, descriptor));
        });
    }

    private BulkOperationControlExpectation publishPrepared(BulkOperationControlIdentity identity,
            long expectedGeneration, BulkOperationalDescriptor descriptor) {
        JdbcBulkOperationControl.Transition transition;
        var transitionStarted = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            transition = controlPlane.withConnection(connection -> {
                // Acquiring the connection may have consumed the remaining admission budget.
                var admittedConnection = documents.withBulkLifecycleCompositionLock(() -> connection);
                transitionStarted.set(true);
                return JdbcBulkOperationControl.transition(admittedConnection, identity.namespaceId(),
                        identity.confirmationOperationId(), expectedGeneration,
                        JdbcBulkOperationControl.Target.READY, descriptor.descriptorFingerprint(),
                        descriptor.structuralRevision());
            });
        } catch (RuntimeException uncertain) {
            if (!transitionStarted.get()) throw uncertain;
            JdbcBulkOperationControl.Snapshot after = readAfterUncertainCommit(identity, uncertain);
            if (matches(after, "READY", expectedGeneration + 1, descriptor))
                return descriptor.expectation(after.generation());
            throw uncertain;
        }
        if (!transition.applied()) throw unavailable("Bulk operation publication lost its generation race");
        if (transition.generation() != expectedGeneration + 1)
            throw unavailable("Bulk operation publication returned a noncanonical generation");
        return descriptor.expectation(transition.generation());
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
        suspendDeclaredOperations(identity);
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
                            JdbcBulkOperationControl.Target.SUSPENDED, null, null));
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
     * declared confirmation identity before the shared cache is cleared. A partial suspension
     * aborts the cache clear; already-suspended rows remain safely closed.
     */
    public void suspendAllBeforeCacheClear() {
        documents.withBulkLifecycleCompositionLock(() -> {
            suspendDeclaredOperations(null);
            return null;
        });
    }

    private void suspendDeclaredOperations(BulkOperationControlIdentity except) {
        if (!bindings.diagnostics().isEmpty())
            throw unavailable("Cannot invalidate OpenAPI caches with malformed bulk declarations");
        List<BulkOperationControlIdentity> identities = bindings.bulkOperations().stream()
                .map(binding -> new BulkOperationControlIdentity(runtime.namespace(), binding.confirmationOperationId()))
                .sorted(Comparator.comparing(BulkOperationControlIdentity::namespaceId)
                        .thenComparing(BulkOperationControlIdentity::confirmationOperationId))
                .toList();
        for (BulkOperationControlIdentity identity : identities) {
            if (identity.equals(except)) continue;
            JdbcBulkOperationControl.Snapshot current = read(identity);
            if (current == null) continue;
            // Include already-suspended rows: advancing the generation fences a publication
            // that started before invalidation and has not yet reached its CAS.
            long next = suspendDurably(identity, current.generation());
            if (next != current.generation() + 1)
                throw unavailable("Bulk operation cache-invalidation fence did not advance");
        }
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
        List<BulkOperationStructuralDescriptor> structures = compiler.compileAll(fresh);
        Map<String, BulkOperationDescriptorProvider> byOperation = providerSnapshot();
        if (structures.size() != byOperation.size())
            throw unavailable("Every validated bulk confirmation binding must have exactly one descriptor provider");
        List<BulkOperationalDescriptor> descriptors = new ArrayList<>(structures.size());
        for (BulkOperationStructuralDescriptor structural : structures) {
            String operationId = structural.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .reference().operationId();
            BulkOperationDescriptorProvider provider = byOperation.remove(operationId);
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
        if (!runtime.namespace().equals(identity.namespaceId()))
            throw new IllegalArgumentException("Bulk operation identity is outside the configured namespace");
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
