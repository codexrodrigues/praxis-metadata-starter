package org.praxisplatform.uischema.action;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.surface.ResourceStateSnapshot;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionCatalogServiceTest {

    @Test
    void resolvesAvailabilityContextOncePerResourceCatalogInsteadOfOncePerAction() {
        AtomicInteger resolverCalls = new AtomicInteger();
        ActionDefinitionRegistry registry = new StaticActionDefinitionRegistry(List.of(
                definition("approve"),
                definition("reject"),
                definition("resubmit")
        ));
        ActionAvailabilityContextResolver contextResolver = (resourceKey, resourcePath, resourceId) -> {
            resolverCalls.incrementAndGet();
            return new ActionAvailabilityContext(
                    resourceKey,
                    resourcePath,
                    resourceId,
                    null,
                    Locale.ROOT,
                    null,
                    Set.of("employee:approve"),
                    ResourceStateSnapshot.of("INACTIVE")
            );
        };
        ActionAvailabilityEvaluator evaluator = (definition, context) ->
                AvailabilityDecision.allow(Map.of("actionId", definition.id()));

        ActionCatalogService service = new ActionCatalogService(registry, evaluator, contextResolver);

        ActionCatalogResponse response = service.findItemActions("example.employees", 42L);

        assertEquals(1, resolverCalls.get());
        assertEquals(3, response.actions().size());
        assertNotNull(response.actions().get(0).availability());
    }

    @Test
    void sortsResourceCatalogByOrderAndId() {
        ActionDefinitionRegistry registry = new MapActionDefinitionRegistry(
                Map.of(
                        "example.employees", List.of(
                                definition("reject", 30, "example.employees", "/employees", "example", ActionScope.ITEM),
                                definition("approve", 10, "example.employees", "/employees", "example", ActionScope.ITEM),
                                definition("resubmit", 20, "example.employees", "/employees", "example", ActionScope.ITEM)
                        )
                ),
                Map.of()
        );
        ActionCatalogService service = new ActionCatalogService(registry, allowAllEvaluator(), contextualResolver());

        ActionCatalogResponse response = service.findByResourceKey("example.employees");

        assertEquals("example.employees", response.resourceKey());
        assertEquals("/employees", response.resourcePath());
        assertEquals("example", response.group());
        assertEquals(List.of("approve", "resubmit", "reject"), response.actions().stream().map(ActionCatalogItem::id).toList());
    }

    @Test
    void rejectsUnknownResourceKeyAndMissingItemActions() {
        ActionDefinitionRegistry emptyRegistry = new MapActionDefinitionRegistry(Map.of(), Map.of());
        ActionCatalogService emptyService = new ActionCatalogService(emptyRegistry, allowAllEvaluator(), contextualResolver());

        assertThrows(ActionCatalogNotFoundException.class, () -> emptyService.findByResourceKey("unknown.resource"));
        assertThrows(ActionCatalogNotFoundException.class, () -> emptyService.findByGroup("unknown-group"));

        ActionDefinitionRegistry collectionOnlyRegistry = new MapActionDefinitionRegistry(
                Map.of(
                        "example.employees", List.of(
                                definition("bulk-approve", 10, "example.employees", "/employees", "example", ActionScope.COLLECTION)
                        )
                ),
                Map.of()
        );
        ActionCatalogService collectionOnlyService = new ActionCatalogService(collectionOnlyRegistry, allowAllEvaluator(), contextualResolver());

        assertThrows(ActionCatalogNotFoundException.class, () -> collectionOnlyService.findItemActions("example.employees", 42L));
    }

    @Test
    void resolvesCollectionActionsWithoutResourceIdAndRejectsMissingCollectionActions() {
        AtomicInteger resolverCalls = new AtomicInteger();
        ActionDefinitionRegistry registry = new MapActionDefinitionRegistry(
                Map.of(
                        "example.employees", List.of(
                                definition("bulk-approve", 5, "example.employees", "/employees", "example", ActionScope.COLLECTION),
                                definition("approve", 10, "example.employees", "/employees", "example", ActionScope.ITEM)
                        )
                ),
                Map.of()
        );
        ActionAvailabilityContextResolver contextResolver = (resourceKey, resourcePath, resourceId) -> {
            resolverCalls.incrementAndGet();
            return new ActionAvailabilityContext(
                    resourceKey,
                    resourcePath,
                    resourceId,
                    null,
                    Locale.ROOT,
                    null,
                    Set.of("employee:bulk-approve"),
                    new ResourceStateSnapshot(null, Map.of())
            );
        };
        ActionCatalogService service = new ActionCatalogService(registry, allowAllEvaluator(), contextResolver);

        ActionCatalogResponse response = service.findCollectionActions("example.employees");

        assertEquals(1, resolverCalls.get());
        assertEquals(List.of("bulk-approve"), response.actions().stream().map(ActionCatalogItem::id).toList());
        assertEquals(ActionScope.COLLECTION, response.actions().get(0).scope());
        assertEquals(null, response.resourceId());

        ActionDefinitionRegistry itemOnlyRegistry = new MapActionDefinitionRegistry(
                Map.of(
                        "example.employees", List.of(
                                definition("approve", 10, "example.employees", "/employees", "example", ActionScope.ITEM)
                        )
                ),
                Map.of()
        );
        ActionCatalogService itemOnlyService = new ActionCatalogService(itemOnlyRegistry, allowAllEvaluator(), contextualResolver());

        assertThrows(ActionCatalogNotFoundException.class, () -> itemOnlyService.findCollectionActions("example.employees"));
    }

    @Test
    void rejectsActionCatalogWithConflictingCanonicalResourcePath() {
        ActionDefinitionRegistry registry = new MapActionDefinitionRegistry(
                Map.of(
                        "example.employees", List.of(
                                definition("approve", 10, "example.employees", "/employees", "example", ActionScope.ITEM),
                                definition("reject", 20, "example.employees", "/people", "example", ActionScope.ITEM)
                        )
                ),
                Map.of()
        );
        ActionCatalogService service = new ActionCatalogService(registry, allowAllEvaluator(), contextualResolver());

        assertThrows(IllegalStateException.class, () -> service.findByResourceKey("example.employees"));
    }

    private ActionAvailabilityEvaluator allowAllEvaluator() {
        return (definition, context) -> AvailabilityDecision.allow(Map.of("actionId", definition.id()));
    }

    private ActionAvailabilityContextResolver contextualResolver() {
        return (resourceKey, resourcePath, resourceId) -> new ActionAvailabilityContext(
                resourceKey,
                resourcePath,
                resourceId,
                null,
                Locale.ROOT,
                null,
                Set.of("employee:approve"),
                ResourceStateSnapshot.of("INACTIVE")
        );
    }

    private ActionDefinition definition(String id) {
        return definition(id, 10, "example.employees", "/employees", "example", ActionScope.ITEM);
    }

    private ActionDefinition definition(
            String id,
            int order,
            String resourceKey,
            String resourcePath,
            String group,
            ActionScope scope
    ) {
        String path = scope == ActionScope.COLLECTION
                ? resourcePath + "/actions/" + id
                : resourcePath + "/{id}/actions/" + id;
        return new ActionDefinition(
                id,
                resourceKey,
                resourcePath,
                group,
                scope,
                id,
                "",
                new CanonicalOperationRef(group, id, path, "POST"),
                new CanonicalSchemaRef("schema-request-" + id, "request", "/schemas/filtered?path=" + path),
                new CanonicalSchemaRef("schema-response-" + id, "response", "/schemas/filtered?path=" + path),
                order,
                "ok",
                List.of(),
                List.of(),
                List.of()
        );
    }

    private record StaticActionDefinitionRegistry(List<ActionDefinition> definitions) implements ActionDefinitionRegistry {

        @Override
        public List<ActionDefinition> findByResourceKey(String resourceKey) {
            return definitions;
        }

        @Override
        public List<ActionDefinition> findByGroup(String group) {
            return definitions;
        }
    }

    private record MapActionDefinitionRegistry(
            Map<String, List<ActionDefinition>> byResource,
            Map<String, List<ActionDefinition>> byGroup
    ) implements ActionDefinitionRegistry {

        @Override
        public List<ActionDefinition> findByResourceKey(String resourceKey) {
            return byResource.getOrDefault(resourceKey, List.of());
        }

        @Override
        public List<ActionDefinition> findByGroup(String group) {
            return byGroup.getOrDefault(group, List.of());
        }
    }
}
