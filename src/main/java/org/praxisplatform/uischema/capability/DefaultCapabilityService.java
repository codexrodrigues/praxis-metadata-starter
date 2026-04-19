package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.action.ActionCatalogNotFoundException;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
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

    public DefaultCapabilityService(
            CanonicalCapabilityResolver canonicalCapabilityResolver,
            SurfaceCatalogService surfaceCatalogService,
            ActionCatalogService actionCatalogService,
            OpenApiDocumentService openApiDocumentService
    ) {
        this.canonicalCapabilityResolver = canonicalCapabilityResolver;
        this.surfaceCatalogService = surfaceCatalogService;
        this.actionCatalogService = actionCatalogService;
        this.openApiDocumentService = openApiDocumentService;
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
        Map<String, Boolean> canonicalOperations = new LinkedHashMap<>(canonicalCapabilityResolver.resolve(resourcePath));
        canonicalOperations.put("export", collectionExportSupported);
        List<SurfaceCatalogItem> collectionSurfaces = collectionSurfaces(resourceKey);
        List<ActionCatalogItem> collectionActions = collectionActions(resourceKey);
        String group = openApiDocumentService.resolveGroupFromPath(resourcePath);
        return new CapabilitySnapshot(
                resourceKey,
                resourcePath,
                group,
                null,
                Map.copyOf(canonicalOperations),
                resolveOperations(resourcePath, collectionSurfaces, collectionActions),
                collectionSurfaces,
                collectionActions
        );
    }

    @Override
    public CapabilitySnapshot itemCapabilities(String resourceKey, String resourcePath, Object resourceId) {
        Map<String, Boolean> canonicalOperations = canonicalCapabilityResolver.resolve(resourcePath);
        List<SurfaceCatalogItem> itemSurfaces = itemSurfaces(resourceKey, resourceId);
        List<ActionCatalogItem> itemActions = itemActions(resourceKey, resourceId);
        String group = openApiDocumentService.resolveGroupFromPath(resourcePath);
        return new CapabilitySnapshot(
                resourceKey,
                resourcePath,
                group,
                resourceId,
                canonicalOperations,
                resolveOperations(resourcePath, itemSurfaces, itemActions),
                itemSurfaces,
                itemActions
        );
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
        return kind == SurfaceKind.VIEW || kind == SurfaceKind.READ_PROJECTION;
    }
}
