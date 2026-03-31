package org.praxisplatform.uischema.surface;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SurfaceCatalogServiceTest {

    @Test
    void resolvesAvailabilityContextOncePerResourceCatalogInsteadOfOncePerSurface() {
        AtomicInteger resolverCalls = new AtomicInteger();
        SurfaceDefinitionRegistry registry = new StaticSurfaceDefinitionRegistry(List.of(
                definition("detail"),
                definition("edit"),
                definition("profile")
        ));
        SurfaceAvailabilityContextResolver contextResolver = (resourceKey, resourcePath, resourceId) -> {
            resolverCalls.incrementAndGet();
            return new SurfaceAvailabilityContext(
                    resourceKey,
                    resourcePath,
                    resourceId,
                    null,
                    Locale.ROOT,
                    null,
                    java.util.Set.of(),
                    ResourceStateSnapshot.of("ACTIVE")
            );
        };
        SurfaceAvailabilityEvaluator evaluator = (definition, context) ->
                AvailabilityDecision.allow(java.util.Map.of("surfaceId", definition.id()));

        SurfaceCatalogService service = new SurfaceCatalogService(registry, evaluator, contextResolver);

        SurfaceCatalogResponse response = service.findItemSurfaces("example.employees", 42L);

        assertEquals(1, resolverCalls.get());
        assertEquals(3, response.surfaces().size());
        assertNotNull(response.surfaces().get(0).availability());
    }

    @Test
    void sortsResourceCatalogByOrderAndId() {
        SurfaceDefinitionRegistry registry = new MapSurfaceDefinitionRegistry(
                Map.of(
                        "example.employees", List.of(
                                definition("edit", 30, "example.employees", "/employees", "example", SurfaceScope.ITEM),
                                definition("create", 10, "example.employees", "/employees", "example", SurfaceScope.COLLECTION),
                                definition("detail", 20, "example.employees", "/employees", "example", SurfaceScope.ITEM)
                        )
                ),
                Map.of()
        );
        SurfaceCatalogService service = new SurfaceCatalogService(registry, allowAllEvaluator(), contextualResolver());

        SurfaceCatalogResponse response = service.findByResourceKey("example.employees");

        assertEquals("example.employees", response.resourceKey());
        assertEquals("/employees", response.resourcePath());
        assertEquals("example", response.group());
        assertEquals(List.of("create", "detail", "edit"), response.surfaces().stream().map(SurfaceCatalogItem::id).toList());
    }

    @Test
    void resolvesAvailabilityContextOncePerDistinctResourceInGroupCatalog() {
        AtomicInteger resolverCalls = new AtomicInteger();
        SurfaceDefinitionRegistry registry = new MapSurfaceDefinitionRegistry(
                Map.of(),
                Map.of(
                        "example", List.of(
                                definition("list", 10, "example.departments", "/departments", "example", SurfaceScope.COLLECTION),
                                definition("detail", 20, "example.departments", "/departments", "example", SurfaceScope.ITEM),
                                definition("list", 10, "example.employees", "/employees", "example", SurfaceScope.COLLECTION),
                                definition("detail", 20, "example.employees", "/employees", "example", SurfaceScope.ITEM)
                        )
                )
        );
        SurfaceAvailabilityContextResolver contextResolver = (resourceKey, resourcePath, resourceId) -> {
            resolverCalls.incrementAndGet();
            return new SurfaceAvailabilityContext(resourceKey, resourcePath, resourceId, null, Locale.ROOT, null, java.util.Set.of(), null);
        };
        SurfaceCatalogService service = new SurfaceCatalogService(registry, allowAllEvaluator(), contextResolver);

        SurfaceCatalogResponse response = service.findByGroup("example");

        assertEquals(2, resolverCalls.get());
        assertEquals(4, response.surfaces().size());
    }

    @Test
    void rejectsUnknownResourceKeyAndMissingItemSurfaces() {
        SurfaceDefinitionRegistry emptyRegistry = new MapSurfaceDefinitionRegistry(Map.of(), Map.of());
        SurfaceCatalogService emptyService = new SurfaceCatalogService(emptyRegistry, allowAllEvaluator(), contextualResolver());

        assertThrows(SurfaceCatalogNotFoundException.class, () -> emptyService.findByResourceKey("unknown.resource"));
        assertThrows(SurfaceCatalogNotFoundException.class, () -> emptyService.findByGroup("unknown-group"));

        SurfaceDefinitionRegistry collectionOnlyRegistry = new MapSurfaceDefinitionRegistry(
                Map.of(
                        "example.employees", List.of(
                                definition("create", 10, "example.employees", "/employees", "example", SurfaceScope.COLLECTION)
                        )
                ),
                Map.of()
        );
        SurfaceCatalogService collectionOnlyService = new SurfaceCatalogService(collectionOnlyRegistry, allowAllEvaluator(), contextualResolver());

        assertThrows(SurfaceCatalogNotFoundException.class, () -> collectionOnlyService.findItemSurfaces("example.employees", 42L));
    }

    @Test
    void rejectsResourceCatalogWithConflictingCanonicalResourcePath() {
        SurfaceDefinitionRegistry registry = new MapSurfaceDefinitionRegistry(
                Map.of(
                        "example.employees", List.of(
                                definition("detail", 10, "example.employees", "/employees", "example", SurfaceScope.ITEM),
                                definition("edit", 20, "example.employees", "/people", "example", SurfaceScope.ITEM)
                        )
                ),
                Map.of()
        );
        SurfaceCatalogService service = new SurfaceCatalogService(registry, allowAllEvaluator(), contextualResolver());

        assertThrows(IllegalStateException.class, () -> service.findByResourceKey("example.employees"));
    }

    private SurfaceAvailabilityEvaluator allowAllEvaluator() {
        return (definition, context) -> AvailabilityDecision.allow(java.util.Map.of("surfaceId", definition.id()));
    }

    private SurfaceAvailabilityContextResolver contextualResolver() {
        return (resourceKey, resourcePath, resourceId) -> new SurfaceAvailabilityContext(
                resourceKey,
                resourcePath,
                resourceId,
                null,
                Locale.ROOT,
                null,
                java.util.Set.of(),
                ResourceStateSnapshot.of("ACTIVE")
        );
    }

    private SurfaceDefinition definition(String id) {
        return definition(id, 10, "example.employees", "/employees", "example", SurfaceScope.ITEM);
    }

    private SurfaceDefinition definition(
            String id,
            int order,
            String resourceKey,
            String resourcePath,
            String group,
            SurfaceScope scope
    ) {
        return new SurfaceDefinition(
                id,
                resourceKey,
                resourcePath,
                group,
                scope == SurfaceScope.COLLECTION ? SurfaceKind.FORM : SurfaceKind.PARTIAL_FORM,
                scope,
                id,
                "",
                id,
                "request",
                new CanonicalOperationRef(group, id, resourcePath + (scope == SurfaceScope.COLLECTION ? "" : "/{id}/" + id), scope == SurfaceScope.COLLECTION ? "POST" : "PATCH"),
                new CanonicalSchemaRef("schema-id-" + id, "request", "/schemas/filtered?path=" + resourcePath),
                order,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private record StaticSurfaceDefinitionRegistry(List<SurfaceDefinition> definitions) implements SurfaceDefinitionRegistry {

        @Override
        public List<SurfaceDefinition> findByResourceKey(String resourceKey) {
            return definitions;
        }

        @Override
        public List<SurfaceDefinition> findByGroup(String group) {
            return definitions;
        }
    }

    private record MapSurfaceDefinitionRegistry(
            Map<String, List<SurfaceDefinition>> byResource,
            Map<String, List<SurfaceDefinition>> byGroup
    ) implements SurfaceDefinitionRegistry {

        @Override
        public List<SurfaceDefinition> findByResourceKey(String resourceKey) {
            return byResource.getOrDefault(resourceKey, List.of());
        }

        @Override
        public List<SurfaceDefinition> findByGroup(String group) {
            return byGroup.getOrDefault(group, List.of());
        }
    }
}
