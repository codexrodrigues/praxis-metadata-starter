package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.action.ActionCatalogNotFoundException;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.exporting.CollectionExportCapability;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.stats.StatsCapability;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.surface.SurfaceCatalogItem;
import org.praxisplatform.uischema.surface.SurfaceCatalogNotFoundException;
import org.praxisplatform.uischema.surface.SurfaceCatalogResponse;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Monta snapshots unificados de capabilities sobre os eixos canonicamente publicados.
 *
 * <p>
 * A implementacao combina tres fontes: operacoes canonicas extraidas do OpenAPI, surfaces
 * publicadas pelo catalogo semantico e actions publicadas pelo catalogo de workflow. A composicao
 * continua defensiva: ausencia de surfaces ou actions nao invalida o snapshot inteiro.
 * </p>
 */
public class DefaultCapabilityService implements CapabilityService {

    private final CanonicalCapabilityResolver canonicalCapabilityResolver;
    private final SurfaceCatalogService surfaceCatalogService;
    private final ActionCatalogService actionCatalogService;
    private final OpenApiDocumentService openApiDocumentService;
    private final ResourceOperationAvailabilityProvider resourceOperationAvailabilityProvider;
    private final ResourceStateSnapshotProvider resourceStateSnapshotProvider;

    public DefaultCapabilityService(
            CanonicalCapabilityResolver canonicalCapabilityResolver,
            SurfaceCatalogService surfaceCatalogService,
            ActionCatalogService actionCatalogService,
            OpenApiDocumentService openApiDocumentService
    ) {
        this(
                canonicalCapabilityResolver,
                surfaceCatalogService,
                actionCatalogService,
                openApiDocumentService,
                new NoOpResourceOperationAvailabilityProvider(),
                new NoOpResourceStateSnapshotProvider()
        );
    }

    public DefaultCapabilityService(
            CanonicalCapabilityResolver canonicalCapabilityResolver,
            SurfaceCatalogService surfaceCatalogService,
            ActionCatalogService actionCatalogService,
            OpenApiDocumentService openApiDocumentService,
            ResourceOperationAvailabilityProvider resourceOperationAvailabilityProvider,
            ResourceStateSnapshotProvider resourceStateSnapshotProvider
    ) {
        this.canonicalCapabilityResolver = canonicalCapabilityResolver;
        this.surfaceCatalogService = surfaceCatalogService;
        this.actionCatalogService = actionCatalogService;
        this.openApiDocumentService = openApiDocumentService;
        this.resourceOperationAvailabilityProvider = resourceOperationAvailabilityProvider == null
                ? new NoOpResourceOperationAvailabilityProvider()
                : resourceOperationAvailabilityProvider;
        this.resourceStateSnapshotProvider = resourceStateSnapshotProvider == null
                ? new NoOpResourceStateSnapshotProvider()
                : resourceStateSnapshotProvider;
    }

    @Override
    public CapabilitySnapshot collectionCapabilities(String resourceKey, String resourcePath) {
        return collectionCapabilities(resourceKey, resourcePath, false);
    }

    @Override
    public CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported
    ) {
        return collectionCapabilities(resourceKey, resourcePath, collectionExportSupported, null);
    }

    @Override
    public CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            CollectionExportCapability collectionExportCapability
    ) {
        return collectionCapabilities(resourceKey, resourcePath, false, collectionExportCapability);
    }

    @Override
    public CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported,
            CollectionExportCapability collectionExportCapability
    ) {
        return collectionCapabilities(
                resourceKey,
                resourcePath,
                collectionExportSupported,
                collectionExportCapability,
                StatsFieldRegistry.empty()
        );
    }

    @Override
    public CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported,
            CollectionExportCapability collectionExportCapability,
            StatsFieldRegistry statsFieldRegistry
    ) {
        return collectionCapabilities(
                resourceKey,
                resourcePath,
                collectionExportSupported,
                collectionExportCapability,
                statsFieldRegistry,
                StatsSupportMode.AUTO,
                StatsSupportMode.AUTO,
                StatsSupportMode.AUTO
        );
    }

    @Override
    public CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported,
            CollectionExportCapability collectionExportCapability,
            StatsFieldRegistry statsFieldRegistry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode
    ) {
        Map<String, Boolean> canonicalOperations = new LinkedHashMap<>(canonicalCapabilityResolver.resolve(resourcePath));
        canonicalOperations.put("export", collectionExportSupported);
        applyStatsSupport(
                canonicalOperations,
                statsFieldRegistry,
                groupByStatsSupportMode,
                timeSeriesStatsSupportMode,
                distributionStatsSupportMode
        );
        List<SurfaceCatalogItem> collectionSurfaces = collectionSurfaces(resourceKey);
        List<ActionCatalogItem> collectionActions = collectionActions(resourceKey);
        String group = openApiDocumentService.resolveGroupFromPath(resourcePath);
        CapabilitySnapshot snapshot = new CapabilitySnapshot(
                resourceKey,
                resourcePath,
                group,
                null,
                Map.copyOf(canonicalOperations),
                resolveCollectionOperations(
                        resourcePath,
                        collectionSurfaces,
                        collectionActions,
                        collectionExportSupported,
                        collectionExportCapability
                ),
                collectionSurfaces,
                collectionActions,
                StatsCapability.from(
                        statsFieldRegistry,
                        groupByStatsSupportMode,
                        timeSeriesStatsSupportMode,
                        distributionStatsSupportMode
                )
        );
        return snapshot.withOperations(applyCollectionAvailability(snapshot.operations(), resourceKey, resourcePath));
    }

    @Override
    public CapabilitySnapshot itemCapabilities(String resourceKey, String resourcePath, Object resourceId) {
        return itemCapabilities(resourceKey, resourcePath, resourceId, StatsFieldRegistry.empty());
    }

    @Override
    public CapabilitySnapshot itemCapabilities(
            String resourceKey,
            String resourcePath,
            Object resourceId,
            StatsFieldRegistry statsFieldRegistry
    ) {
        return itemCapabilities(
                resourceKey,
                resourcePath,
                resourceId,
                statsFieldRegistry,
                StatsSupportMode.AUTO,
                StatsSupportMode.AUTO,
                StatsSupportMode.AUTO
        );
    }

    @Override
    public CapabilitySnapshot itemCapabilities(
            String resourceKey,
            String resourcePath,
            Object resourceId,
            StatsFieldRegistry statsFieldRegistry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode
    ) {
        Map<String, Boolean> canonicalOperations = new LinkedHashMap<>(canonicalCapabilityResolver.resolve(resourcePath));
        applyStatsSupport(
                canonicalOperations,
                statsFieldRegistry,
                groupByStatsSupportMode,
                timeSeriesStatsSupportMode,
                distributionStatsSupportMode
        );
        List<SurfaceCatalogItem> itemSurfaces = itemSurfaces(resourceKey, resourceId);
        List<ActionCatalogItem> itemActions = itemActions(resourceKey, resourceId);
        String group = openApiDocumentService.resolveGroupFromPath(resourcePath);
        ResourceStateSnapshot stateSnapshot = resourceStateSnapshotProvider.resolve(resourceKey, resourceId).orElse(null);
        CapabilitySnapshot snapshot = new CapabilitySnapshot(
                resourceKey,
                resourcePath,
                group,
                resourceId,
                canonicalOperations,
                resolveOperations(resourcePath, itemSurfaces, itemActions),
                itemSurfaces,
                itemActions,
                StatsCapability.from(
                        statsFieldRegistry,
                        groupByStatsSupportMode,
                        timeSeriesStatsSupportMode,
                        distributionStatsSupportMode
                )
        );
        return snapshot.withOperations(applyItemAvailability(snapshot.operations(), resourceKey, resourcePath, resourceId, stateSnapshot));
    }

    private void applyStatsSupport(
            Map<String, Boolean> canonicalOperations,
            StatsFieldRegistry statsFieldRegistry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode
    ) {
        StatsFieldRegistry registry = statsFieldRegistry == null ? StatsFieldRegistry.empty() : statsFieldRegistry;
        boolean supportsGroupBy = groupByStatsSupportMode != StatsSupportMode.DISABLED
                && registry.descriptors().stream().anyMatch(StatsFieldDescriptorPredicates::groupBy);
        boolean supportsTimeSeries = timeSeriesStatsSupportMode != StatsSupportMode.DISABLED
                && registry.descriptors().stream().anyMatch(StatsFieldDescriptorPredicates::timeSeries);
        boolean supportsDistribution = distributionStatsSupportMode != StatsSupportMode.DISABLED
                && registry.descriptors().stream().anyMatch(StatsFieldDescriptorPredicates::distribution);
        canonicalOperations.computeIfPresent("statsGroupBy", (key, present) -> present && supportsGroupBy);
        canonicalOperations.computeIfPresent("statsTimeSeries", (key, present) -> present && supportsTimeSeries);
        canonicalOperations.computeIfPresent("statsDistribution", (key, present) -> present && supportsDistribution);
    }

    private static final class StatsFieldDescriptorPredicates {
        private StatsFieldDescriptorPredicates() {
        }

        static boolean groupBy(StatsFieldDescriptor descriptor) {
            return descriptor.groupByEligible();
        }

        static boolean timeSeries(StatsFieldDescriptor descriptor) {
            return descriptor.timeSeriesEligible();
        }

        static boolean distribution(StatsFieldDescriptor descriptor) {
            return descriptor.distributionTermsEligible() || descriptor.distributionHistogramEligible();
        }
    }

    @Override
    public AvailabilityDecision collectionOperationAvailability(
            String resourceKey,
            String resourcePath,
            String operationId
    ) {
        return evaluateAvailability(ResourceOperationAvailabilityContext.collection(resourceKey, resourcePath, operationId));
    }

    @Override
    public AvailabilityDecision itemOperationAvailability(
            String resourceKey,
            String resourcePath,
            String operationId,
            Object resourceId
    ) {
        ResourceStateSnapshot stateSnapshot = resourceStateSnapshotProvider.resolve(resourceKey, resourceId).orElse(null);
        return evaluateAvailability(ResourceOperationAvailabilityContext.item(
                resourceKey,
                resourcePath,
                operationId,
                resourceId,
                stateSnapshot
        ));
    }

    private Map<String, CapabilityOperation> applyCollectionAvailability(
            Map<String, CapabilityOperation> operations,
            String resourceKey,
            String resourcePath
    ) {
        Map<String, CapabilityOperation> resolved = new LinkedHashMap<>();
        operations.forEach((id, operation) -> {
            AvailabilityDecision hostDecision = evaluateAvailability(
                    ResourceOperationAvailabilityContext.collection(resourceKey, resourcePath, id)
            );
            resolved.put(id, operation.withAvailability(combineAvailability(operation.availability(), hostDecision)));
        });
        return Map.copyOf(resolved);
    }

    private Map<String, CapabilityOperation> applyItemAvailability(
            Map<String, CapabilityOperation> operations,
            String resourceKey,
            String resourcePath,
            Object resourceId,
            ResourceStateSnapshot stateSnapshot
    ) {
        Map<String, CapabilityOperation> resolved = new LinkedHashMap<>();
        operations.forEach((id, operation) -> {
            AvailabilityDecision hostDecision = evaluateAvailability(
                    ResourceOperationAvailabilityContext.item(resourceKey, resourcePath, id, resourceId, stateSnapshot)
            );
            resolved.put(id, operation.withAvailability(combineAvailability(operation.availability(), hostDecision)));
        });
        return Map.copyOf(resolved);
    }

    private AvailabilityDecision combineAvailability(
            AvailabilityDecision baseline,
            AvailabilityDecision hostDecision
    ) {
        AvailabilityDecision first = baseline == null ? AvailabilityDecision.allowAll() : baseline;
        AvailabilityDecision second = hostDecision == null ? AvailabilityDecision.allowAll() : hostDecision;
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (first.metadata() != null) {
            metadata.putAll(first.metadata());
        }
        if (second.metadata() != null) {
            metadata.putAll(second.metadata());
        }
        if (!first.allowed()) {
            return AvailabilityDecision.deny(first.reason(), metadata);
        }
        if (!second.allowed()) {
            return AvailabilityDecision.deny(second.reason(), metadata);
        }
        return AvailabilityDecision.allow(metadata);
    }

    private AvailabilityDecision evaluateAvailability(ResourceOperationAvailabilityContext context) {
        try {
            AvailabilityDecision decision = resourceOperationAvailabilityProvider.evaluate(context);
            return decision == null ? AvailabilityDecision.allowAll() : decision;
        } catch (RuntimeException ex) {
            return AvailabilityDecision.deny(
                    "availability-provider-error",
                    Map.of("operationId", context.operationId())
            );
        }
    }

    private Map<String, CapabilityOperation> resolveCollectionOperations(
            String resourcePath,
            List<SurfaceCatalogItem> surfaces,
            List<ActionCatalogItem> actions,
            boolean collectionExportSupported,
            CollectionExportCapability collectionExportCapability
    ) {
        Map<String, CapabilityOperation> operations = new LinkedHashMap<>(
                resolveOperations(resourcePath, surfaces, actions)
        );
        operations.put("export", exportOperation(collectionExportSupported, collectionExportCapability));
        return Map.copyOf(operations);
    }

    private Map<String, CapabilityOperation> resolveOperations(
            String resourcePath,
            List<SurfaceCatalogItem> surfaces,
            List<ActionCatalogItem> actions
    ) {
        Map<String, CapabilityOperation> operations = new LinkedHashMap<>(
                canonicalCapabilityResolver.resolveCrudOperations(resourcePath)
        );

        operations.computeIfPresent("create", (id, operation) ->
                enrichFromSurface(operation, findCreateSurface(surfaces))
        );
        operations.computeIfPresent("view", (id, operation) ->
                enrichFromSurface(operation, findViewSurface(surfaces))
        );
        operations.computeIfPresent("edit", (id, operation) ->
                enrichFromSurface(operation, findEditSurface(surfaces))
        );
        operations.computeIfPresent("delete", (id, operation) ->
                enrichDeleteOperation(operation, surfaces, actions)
        );
        return Map.copyOf(operations);
    }

    private CapabilityOperation exportOperation(
            boolean supported,
            CollectionExportCapability collectionExportCapability
    ) {
        if (!supported || collectionExportCapability == null) {
            return new CapabilityOperation(
                    "export",
                    supported,
                    "COLLECTION",
                    supported ? "POST" : null,
                    "export",
                    AvailabilityDecision.allowAll()
            );
        }
        return new CapabilityOperation(
                "export",
                supported,
                "COLLECTION",
                supported ? "POST" : null,
                "export",
                AvailabilityDecision.allowAll(),
                collectionExportCapability.formats(),
                collectionExportCapability.scopes(),
                collectionExportCapability.maxRows(),
                collectionExportCapability.async()
        );
    }

    private CapabilityOperation enrichFromSurface(
            CapabilityOperation operation,
            SurfaceCatalogItem surface
    ) {
        if (operation == null || surface == null) {
            return operation;
        }
        return new CapabilityOperation(
                operation.id(),
                operation.supported(),
                operation.scope(),
                surface.method() != null && !surface.method().isBlank()
                        ? surface.method()
                        : operation.preferredMethod(),
                operation.preferredRel(),
                surface.availability()
        );
    }

    private CapabilityOperation enrichDeleteOperation(
            CapabilityOperation operation,
            List<SurfaceCatalogItem> surfaces,
            List<ActionCatalogItem> actions
    ) {
        if (operation == null) {
            return null;
        }

        SurfaceCatalogItem writableItemSurface = findEditSurface(surfaces);
        if (writableItemSurface != null && writableItemSurface.availability() != null) {
            return operation.withAvailability(writableItemSurface.availability());
        }

        return operation;
    }

    private List<SurfaceCatalogItem> collectionSurfaces(String resourceKey) {
        try {
            SurfaceCatalogResponse response = surfaceCatalogService.findByResourceKey(resourceKey);
            return response.surfaces().stream()
                    .filter(surface -> surface.scope() == SurfaceScope.COLLECTION)
                    .toList();
        } catch (SurfaceCatalogNotFoundException ex) {
            return List.of();
        }
    }

    private List<SurfaceCatalogItem> itemSurfaces(String resourceKey, Object resourceId) {
        try {
            return surfaceCatalogService.findItemSurfaces(resourceKey, resourceId).surfaces();
        } catch (SurfaceCatalogNotFoundException ex) {
            return List.of();
        }
    }

    private List<ActionCatalogItem> collectionActions(String resourceKey) {
        try {
            ActionCatalogResponse response = actionCatalogService.findCollectionActions(resourceKey);
            return response.actions().stream()
                    .filter(action -> action.scope() == ActionScope.COLLECTION)
                    .toList();
        } catch (ActionCatalogNotFoundException ex) {
            return List.of();
        }
    }

    private List<ActionCatalogItem> itemActions(String resourceKey, Object resourceId) {
        try {
            return actionCatalogService.findItemActions(resourceKey, resourceId).actions();
        } catch (ActionCatalogNotFoundException ex) {
            return List.of();
        }
    }

    private SurfaceCatalogItem findCreateSurface(List<SurfaceCatalogItem> surfaces) {
        return surfaces.stream()
                .filter(surface -> surface.scope() == SurfaceScope.COLLECTION)
                .filter(surface -> isWritable(surface.kind()))
                .filter(surface -> "create".equalsIgnoreCase(surface.id()))
                .findFirst()
                .orElseGet(() -> surfaces.stream()
                        .filter(surface -> surface.scope() == SurfaceScope.COLLECTION)
                        .filter(surface -> isWritable(surface.kind()))
                        .findFirst()
                        .orElse(null));
    }

    private SurfaceCatalogItem findViewSurface(List<SurfaceCatalogItem> surfaces) {
        return surfaces.stream()
                .filter(surface -> surface.scope() == SurfaceScope.ITEM)
                .filter(surface -> isReadable(surface.kind()))
                .filter(surface -> "detail".equalsIgnoreCase(surface.id()) || "view".equalsIgnoreCase(surface.id()))
                .findFirst()
                .orElseGet(() -> surfaces.stream()
                        .filter(surface -> surface.scope() == SurfaceScope.ITEM)
                        .filter(surface -> isReadable(surface.kind()))
                        .findFirst()
                        .orElse(null));
    }

    private SurfaceCatalogItem findEditSurface(List<SurfaceCatalogItem> surfaces) {
        return surfaces.stream()
                .filter(surface -> surface.scope() == SurfaceScope.ITEM)
                .filter(surface -> isWritable(surface.kind()))
                .filter(surface -> "edit".equalsIgnoreCase(surface.id()) || "update".equalsIgnoreCase(surface.id()))
                .findFirst()
                .orElseGet(() -> surfaces.stream()
                        .filter(surface -> surface.scope() == SurfaceScope.ITEM)
                        .filter(surface -> isWritable(surface.kind()))
                        .findFirst()
                        .orElse(null));
    }

    private boolean isWritable(SurfaceKind kind) {
        return kind == SurfaceKind.FORM || kind == SurfaceKind.PARTIAL_FORM;
    }

    private boolean isReadable(SurfaceKind kind) {
        return kind == SurfaceKind.VIEW || kind == SurfaceKind.READ_PROJECTION || kind == SurfaceKind.CHART;
    }
}
