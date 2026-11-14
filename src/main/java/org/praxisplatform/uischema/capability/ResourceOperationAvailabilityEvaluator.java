package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.surface.SurfaceCatalogItem;
import org.praxisplatform.uischema.surface.SurfaceCatalogNotFoundException;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Function;

/**
 * Shared operation policy composition for guards, hypermedia and discovery.
 * Structural capture never invokes host policy under the schema read lock.
 * This evaluates availability only; domain services still authorize source access
 * and enforce field/scope policy before preparing a draft.
 */
public final class ResourceOperationAvailabilityEvaluator {
    private static final ThreadLocal<ActiveOperations> ACTIVE_OPERATIONS = new ThreadLocal<>();

    private static final class ActiveOperations {
        private final ResourceOperationAvailabilityEvaluator owner;
        private final String path;
        private final Map<String, CapabilityOperation> operations;
        private final Map<String, CapabilityOperation> collectionSurfaceBaselines = new LinkedHashMap<>();

        private ActiveOperations(
                ResourceOperationAvailabilityEvaluator owner,
                String path,
                Map<String, CapabilityOperation> operations
        ) {
            this.owner = owner;
            this.path = path;
            this.operations = operations;
        }
    }

    private final CanonicalCapabilityResolver resolver;
    private final OpenApiDocumentService documents;
    private final ResourceOperationAvailabilityProvider provider;
    private final ResourceStateSnapshotProvider states;
    private final SurfaceCatalogService surfaces;

    public ResourceOperationAvailabilityEvaluator(CanonicalCapabilityResolver resolver,
            OpenApiDocumentService documents, ResourceOperationAvailabilityProvider provider,
            ResourceStateSnapshotProvider states, SurfaceCatalogService surfaces) {
        this.resolver = resolver;
        this.documents = documents;
        this.provider = provider;
        this.states = states;
        this.surfaces = surfaces;
    }

    /** Captures structural operations; availability is evaluated after releasing the lock. */
    public <T> T withOperations(String path, Function<Map<String, CapabilityOperation>, T> consumer) {
        ActiveOperations existing = ACTIVE_OPERATIONS.get();
        if (existing != null && existing.owner == this && java.util.Objects.equals(existing.path, path)) {
            return consumer.apply(existing.operations);
        }
        if (documents.hasLocalPublishedBulkOpenApiPublication()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Discovery projection must start outside operational transactions");
        class Capture implements AutoCloseable {
            OpenApiDocumentService.BulkLifecycleDocumentFence fence;
            @Override public void close() { if (fence != null) fence.close(); }
        }
        try (var capture = new Capture(); var scope = ResourceStateSnapshotScope.open()) {
            var operations = documents.withSchemaCacheReadLock(() -> {
                boolean published = documents.hasLocalPublishedBulkOpenApiPublication();
                if (published && org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive())
                    throw new IllegalStateException("Discovery projection must start outside operational transactions");
                var resolved = published
                        ? resolver.resolveOperations(documents.getDocumentForGroup(documents.resolveGroupFromPath(path)), path)
                        : resolver.resolveOperations(path);
                var detached = Map.copyOf(resolved);
                capture.fence = published ? documents.captureBulkLifecycleDocumentFence() : null;
                return detached;
            });
            ACTIVE_OPERATIONS.set(new ActiveOperations(this, path, operations));
            T result;
            try {
                result = consumer.apply(operations);
            } finally {
                if (existing != null) {
                    ACTIVE_OPERATIONS.set(existing);
                } else {
                    ACTIVE_OPERATIONS.remove();
                }
            }
            if (capture.fence != null) capture.fence.verify();
            return result;
        }
    }

    /** Uses the same structural metadata and scopes for direct guards and snapshots. */
    public AvailabilityDecision evaluate(Map<String, CapabilityOperation> operations, String key,
            String path, String id, Object resourceId) {
        return evaluateProjection(operations, key, path, id, resourceId, false);
    }

    /** Evaluates an existing snapshot without rebuilding its already materialized surface baselines. */
    AvailabilityDecision evaluateProjection(Map<String, CapabilityOperation> operations, String key,
            String path, String id, Object resourceId, boolean surfaceBaselinesResolved) {
        if (!"duplicate-draft".equals(id)) return evaluateSingle(operations.get(id), key, path, id,
                resourceId, false, surfaceBaselinesResolved);
        var duplicate = operations.get(id);
        if (duplicate == null || !duplicate.supported()) return deny("operation-not-supported");
        // Collection authorization precedes any lookup of source state.
        if (!evaluateSingle(operations.get("create"), key, path, "create", null, true, surfaceBaselinesResolved).allowed())
            return deny("duplicate-draft-unavailable");
        if (resourceId == null) return deny("resource-context-required");
        if (!evaluateSingle(operations.get("view"), key, path, "view", resourceId, true, surfaceBaselinesResolved).allowed())
            return deny("duplicate-draft-unavailable");
        if (!evaluateSingle(duplicate, key, path, id, resourceId, true, surfaceBaselinesResolved).allowed())
            return deny("duplicate-draft-unavailable");
        return AvailabilityDecision.allowAll();
    }

    AvailabilityDecision evaluateUnknownItem(String key, String path, String id, Object resourceId) {
        try {
            var state = resourceId == null ? null
                    : ResourceStateSnapshotScope.resolve(states, key, resourceId).orElse(null);
            var snapshotDecision = DefaultResourceAvailabilityService.INSTANCE.evaluateOperationSnapshot(id, state, Map.of());
            var decision = provider.evaluate(ResourceOperationAvailabilityContext.item(key, path, id, resourceId, state));
            return combine(snapshotDecision, decision == null ? AvailabilityDecision.allowAll() : decision);
        } catch (RuntimeException ex) {
            return AvailabilityDecision.deny("availability-provider-error", Map.of("operationId", id));
        }
    }

    private AvailabilityDecision evaluateSingle(CapabilityOperation operation, String key, String path,
            String id, Object resourceId, boolean required, boolean surfaceBaselinesResolved) {
        if (required && (operation == null || !operation.supported())) return deny("operation-not-supported");
        String scope = operation != null ? operation.scope() : resourceId == null ? "COLLECTION" : "ITEM";
        boolean item = "ITEM".equalsIgnoreCase(scope);
        try {
            ResourceStateSnapshot state = item && resourceId != null
                    ? ResourceStateSnapshotScope.resolve(states, key, resourceId).orElse(null) : null;
            if (!surfaceBaselinesResolved) operation = withSurfaceAvailability(operation, key, id, resourceId);
            AvailabilityDecision snapshotDecision = DefaultResourceAvailabilityService.INSTANCE.evaluateOperationSnapshot(
                    id, state, Map.of());
            AvailabilityDecision decision = provider.evaluate(new ResourceOperationAvailabilityContext(
                    key, path, id, scope, item ? resourceId : null, state,
                    operation == null ? Map.of() : metadata(operation)));
            if (decision == null) decision = required ? deny("availability-provider-error") : AvailabilityDecision.allowAll();
            return combine(combine(operation == null ? null : operation.availability(), snapshotDecision), decision);
        } catch (RuntimeException ex) {
            return AvailabilityDecision.deny("availability-provider-error", Map.of("operationId", id));
        }
    }

    // Surfaces already contribute create/view availability to capability snapshots.
    // Read that same baseline for direct guards and action prerequisites; never call capabilities.
    private CapabilityOperation withSurfaceAvailability(CapabilityOperation operation,
            String key, String id, Object resourceId) {
        if (operation == null || !operation.supported() || surfaces == null
                || !("create".equals(id) || "view".equals(id))) return operation;
        boolean collectionBaseline = "create".equals(id) || resourceId == null;
        ActiveOperations active = ACTIVE_OPERATIONS.get();
        String cacheKey = key + ":" + id;
        if (collectionBaseline && active != null && active.owner == this
                && active.collectionSurfaceBaselines.containsKey(cacheKey)) {
            return active.collectionSurfaceBaselines.get(cacheKey);
        }
        try {
            var candidates = SurfaceCatalogService.withAvailabilityOnly(() -> collectionBaseline
                    ? surfaces.findByResourceKey(key).surfaces()
                    : surfaces.findItemSurfaces(key, resourceId).surfaces());
            var selected = selectSurface(candidates, id);
            CapabilityOperation enriched = selected == null
                    ? operation
                    : new CapabilityOperation(operation.id(), operation.supported(), operation.scope(),
                            selected.method() != null && !selected.method().isBlank()
                                    ? selected.method() : operation.preferredMethod(), operation.preferredRel(),
                            combine(operation.availability(), selected.availability()), operation.formats(),
                            operation.scopes(), operation.maxRows(), operation.async(), operation.bulk());
            if (collectionBaseline && active != null && active.owner == this) {
                active.collectionSurfaceBaselines.put(cacheKey, enriched);
            }
            return enriched;
        } catch (SurfaceCatalogNotFoundException ex) {
            if (collectionBaseline && active != null && active.owner == this) {
                active.collectionSurfaceBaselines.put(cacheKey, operation);
            }
            return operation;
        }
    }

    static SurfaceCatalogItem selectSurface(List<SurfaceCatalogItem> candidates, String id) {
        var eligible = candidates.stream().filter(surface -> "create".equals(id)
                ? surface.scope() == SurfaceScope.COLLECTION
                    && (surface.kind() == SurfaceKind.FORM || surface.kind() == SurfaceKind.PARTIAL_FORM)
                : surface.scope() == SurfaceScope.ITEM
                    && (surface.kind() == SurfaceKind.VIEW || surface.kind() == SurfaceKind.READ_PROJECTION
                        || surface.kind() == SurfaceKind.CHART)).toList();
        return eligible.stream().filter(surface -> "create".equals(id)
                ? "create".equalsIgnoreCase(surface.id())
                : "detail".equalsIgnoreCase(surface.id()) || "view".equalsIgnoreCase(surface.id()))
                .findFirst().orElse(eligible.isEmpty() ? null : eligible.getFirst());
    }

    private Map<String, Object> metadata(CapabilityOperation operation) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (operation.preferredMethod() != null && !operation.preferredMethod().isBlank())
            values.put("preferredMethod", operation.preferredMethod());
        if (operation.preferredRel() != null && !operation.preferredRel().isBlank())
            values.put("preferredRel", operation.preferredRel());
        values.put("supported", operation.supported());
        return Map.copyOf(values);
    }

    private static AvailabilityDecision combine(AvailabilityDecision first, AvailabilityDecision second) {
        first = first == null ? AvailabilityDecision.allowAll() : first;
        second = second == null ? AvailabilityDecision.allowAll() : second;
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (first.metadata() != null) metadata.putAll(first.metadata());
        if (second.metadata() != null) metadata.putAll(second.metadata());
        if (!first.allowed()) return AvailabilityDecision.deny(first.reason(), metadata);
        if (!second.allowed()) return AvailabilityDecision.deny(second.reason(), metadata);
        return AvailabilityDecision.allow(metadata);
    }

    private static AvailabilityDecision deny(String reason) {
        return AvailabilityDecision.deny(reason, Map.of());
    }
}
