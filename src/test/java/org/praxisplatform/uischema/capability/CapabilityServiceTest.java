package org.praxisplatform.uischema.capability;

import org.junit.jupiter.api.Test;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityServiceTest {

    @Test
    void collectionCapabilitiesAggregateCanonicalOperationsAndCollectionScopedDiscoveries() {
        CapabilityService service = new DefaultCapabilityService(
                new StaticCanonicalCapabilityResolver(Map.of("create", true, "update", true)),
                new SurfaceCatalogService(null, null, null) {
                    @Override
                    public SurfaceCatalogResponse findByResourceKey(String resourceKey) {
                        return new SurfaceCatalogResponse(
                                resourceKey,
                                "/employees",
                                "human-resources",
                                null,
                                List.of(
                                        surface("create", SurfaceScope.COLLECTION),
                                        surface("detail", SurfaceScope.ITEM)
                                )
                        );
                    }
                },
                new ActionCatalogService(null, null, null) {
                    @Override
                    public ActionCatalogResponse findCollectionActions(String resourceKey) {
                        return new ActionCatalogResponse(
                                resourceKey,
                                "/employees",
                                "human-resources",
                                null,
                                List.of(
                                        action("bulk-approve", ActionScope.COLLECTION),
                                        action("approve", ActionScope.ITEM)
                                )
                        );
                    }
                },
                new StaticOpenApiDocumentService("human-resources")
        );

        CapabilitySnapshot snapshot = service.collectionCapabilities("human-resources.employees", "/employees");

        assertEquals("human-resources.employees", snapshot.resourceKey());
        assertEquals("/employees", snapshot.resourcePath());
        assertEquals("human-resources", snapshot.group());
        assertEquals(null, snapshot.resourceId());
        assertEquals(Boolean.TRUE, snapshot.canonicalOperations().get("create"));
        assertEquals(List.of("create"), snapshot.surfaces().stream().map(SurfaceCatalogItem::id).toList());
        assertEquals(List.of("bulk-approve"), snapshot.actions().stream().map(ActionCatalogItem::id).toList());
    }

    @Test
    void itemCapabilitiesAggregateItemScopedDiscoveriesAndPreserveResourceId() {
        CapabilityService service = new DefaultCapabilityService(
                new StaticCanonicalCapabilityResolver(Map.of("byId", true, "update", true)),
                new SurfaceCatalogService(null, null, null) {
                    @Override
                    public SurfaceCatalogResponse findItemSurfaces(String resourceKey, Object resourceId) {
                        return new SurfaceCatalogResponse(
                                resourceKey,
                                "/employees",
                                "human-resources",
                                resourceId,
                                List.of(surface("detail", SurfaceScope.ITEM), surface("edit", SurfaceScope.ITEM))
                        );
                    }
                },
                new ActionCatalogService(null, null, null) {
                    @Override
                    public ActionCatalogResponse findItemActions(String resourceKey, Object resourceId) {
                        return new ActionCatalogResponse(
                                resourceKey,
                                "/employees",
                                "human-resources",
                                resourceId,
                                List.of(action("approve", ActionScope.ITEM))
                        );
                    }
                },
                new StaticOpenApiDocumentService("human-resources")
        );

        CapabilitySnapshot snapshot = service.itemCapabilities("human-resources.employees", "/employees", 42L);

        assertEquals(42L, snapshot.resourceId());
        assertEquals(List.of("detail", "edit"), snapshot.surfaces().stream().map(SurfaceCatalogItem::id).toList());
        assertEquals(List.of("approve"), snapshot.actions().stream().map(ActionCatalogItem::id).toList());
        assertEquals(Boolean.TRUE, snapshot.canonicalOperations().get("update"));
    }

    @Test
    void capabilitiesReturnEmptyCatalogsWhenSurfacesOrActionsAreNotPublished() {
        CapabilityService service = new DefaultCapabilityService(
                new StaticCanonicalCapabilityResolver(Map.of("all", true)),
                new SurfaceCatalogService(null, null, null) {
                    @Override
                    public SurfaceCatalogResponse findByResourceKey(String resourceKey) {
                        throw SurfaceCatalogNotFoundException.unknownResourceKey(resourceKey);
                    }

                    @Override
                    public SurfaceCatalogResponse findItemSurfaces(String resourceKey, Object resourceId) {
                        throw SurfaceCatalogNotFoundException.missingItemSurfaces(resourceKey);
                    }
                },
                new ActionCatalogService(null, null, null) {
                    @Override
                    public ActionCatalogResponse findCollectionActions(String resourceKey) {
                        throw ActionCatalogNotFoundException.missingCollectionActions(resourceKey);
                    }

                    @Override
                    public ActionCatalogResponse findItemActions(String resourceKey, Object resourceId) {
                        throw ActionCatalogNotFoundException.missingItemActions(resourceKey);
                    }
                },
                new StaticOpenApiDocumentService("human-resources")
        );

        CapabilitySnapshot collection = service.collectionCapabilities("human-resources.payroll-view", "/payroll-view");
        CapabilitySnapshot item = service.itemCapabilities("human-resources.payroll-view", "/payroll-view", 99L);

        assertTrue(collection.surfaces().isEmpty());
        assertTrue(collection.actions().isEmpty());
        assertTrue(item.surfaces().isEmpty());
        assertTrue(item.actions().isEmpty());
    }

    private SurfaceCatalogItem surface(String id, SurfaceScope scope) {
        return new SurfaceCatalogItem(
                id,
                "human-resources.employees",
                scope == SurfaceScope.COLLECTION ? SurfaceKind.FORM : SurfaceKind.VIEW,
                scope,
                id,
                "",
                id,
                id,
                scope == SurfaceScope.COLLECTION ? "/employees" : "/employees/{id}",
                scope == SurfaceScope.COLLECTION ? "POST" : "GET",
                "surface-schema-" + id,
                "/schemas/filtered?path=/employees",
                AvailabilityDecision.allowAll(),
                10,
                List.of()
        );
    }

    private ActionCatalogItem action(String id, ActionScope scope) {
        return new ActionCatalogItem(
                id,
                "human-resources.employees",
                scope,
                id,
                "",
                id,
                scope == ActionScope.COLLECTION ? "/employees/actions/" + id : "/employees/{id}/actions/" + id,
                "POST",
                "request-schema-" + id,
                "/schemas/filtered?path=/employees/actions/" + id + "&schemaType=request",
                "response-schema-" + id,
                "/schemas/filtered?path=/employees/actions/" + id + "&schemaType=response",
                AvailabilityDecision.allowAll(),
                10,
                "ok",
                List.of()
        );
    }

    private record StaticOpenApiDocumentService(String group) implements OpenApiDocumentService {

        @Override
        public String resolveGroupFromPath(String path) {
            return group;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode getDocumentForGroup(String groupName) {
            return null;
        }

        @Override
        public String getOrComputeSchemaHash(String schemaId, java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> payloadSupplier) {
            return "unused";
        }

        @Override
        public void clearCaches() {
        }
    }

    private record StaticCanonicalCapabilityResolver(Map<String, Boolean> capabilities) implements CanonicalCapabilityResolver {

        @Override
        public Map<String, Boolean> resolve(String resourcePath) {
            return capabilities;
        }

        @Override
        public Map<String, Boolean> resolve(com.fasterxml.jackson.databind.JsonNode openApiDocument, String resourcePath) {
            return capabilities;
        }
    }
}
