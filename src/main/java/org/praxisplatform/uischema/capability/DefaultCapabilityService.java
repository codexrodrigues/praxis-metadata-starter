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
import org.praxisplatform.uischema.surface.SurfaceScope;

import java.util.List;
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
        Map<String, Boolean> canonicalOperations = canonicalCapabilityResolver.resolve(resourcePath);
        String group = openApiDocumentService.resolveGroupFromPath(resourcePath);
        return new CapabilitySnapshot(
                resourceKey,
                resourcePath,
                group,
                null,
                canonicalOperations,
                collectionSurfaces(resourceKey),
                collectionActions(resourceKey)
        );
    }

    @Override
    public CapabilitySnapshot itemCapabilities(String resourceKey, String resourcePath, Object resourceId) {
        Map<String, Boolean> canonicalOperations = canonicalCapabilityResolver.resolve(resourcePath);
        String group = openApiDocumentService.resolveGroupFromPath(resourcePath);
        return new CapabilitySnapshot(
                resourceKey,
                resourcePath,
                group,
                resourceId,
                canonicalOperations,
                itemSurfaces(resourceKey, resourceId),
                itemActions(resourceKey, resourceId)
        );
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
}
