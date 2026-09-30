package org.praxisplatform.uischema.action;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

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
    void allFourEntrypointsEvaluateOriginalDefinitionsInsideOneResponseScope() {
        var item = definition("approve", 1, "example.employees", "/employees", "example", ActionScope.ITEM);
        var collection = definition("collect", 2, "example.employees", "/employees", "example", ActionScope.COLLECTION);
        var definitions = List.of(item, collection);
        var active = new java.util.concurrent.atomic.AtomicBoolean();
        var scopes = new AtomicInteger();
        var contexts = new AtomicInteger();
        var registry = new MapActionDefinitionRegistry(Map.of("example.employees", definitions), Map.of("example", definitions));
        var service = new ActionCatalogService(registry, (definition, context) -> {
            org.junit.jupiter.api.Assertions.assertTrue(active.get());
            org.junit.jupiter.api.Assertions.assertTrue(definition == item || definition == collection);
            return AvailabilityDecision.deny("missing-authority", Map.of());
        }, (resource, path, id) -> {
            org.junit.jupiter.api.Assertions.assertFalse(active.get(), "context resolution precedes composition");
            contexts.incrementAndGet();
            return contextualResolver().resolve(resource, path, id);
        }, (actions, consumer) -> {
            scopes.incrementAndGet();
            active.set(true);
            try { return consumer.apply(Map.of()); }
            finally { active.set(false); }
        });
        for (var response : List.of(service.findByResourceKey("example.employees"), service.findByGroup("example"),
                service.findItemActions("example.employees", 42L), service.findCollectionActions("example.employees"))) {
            for (var action : response.actions()) {
                org.junit.jupiter.api.Assertions.assertFalse(action.availability().allowed());
                assertEquals("missing-authority", action.availability().reason());
                org.junit.jupiter.api.Assertions.assertNull(action.execution().bulk());
            }
        }
        assertEquals(4, scopes.get());
        assertEquals(4, contexts.get());
        org.junit.jupiter.api.Assertions.assertFalse(active.get());
    }

    @Test
    void resolvesLifecycleOnceForTheWholeCatalogAndKeepsNonBulkExecutionJsonUnchanged() {
        var definitions = List.of(definition("approve"), definition("reject"));
        var registry = new StaticActionDefinitionRegistry(definitions);
        AtomicInteger projectionCalls = new AtomicInteger();
        var service = new ActionCatalogService(registry, allowAllEvaluator(), contextualResolver(), (actions, consumer) -> {
            projectionCalls.incrementAndGet();
            return consumer.apply(Map.of());
        });
        var response = service.findByResourceKey("example.employees");
        assertEquals(1, projectionCalls.get(), "bulk composition is resolved once for the whole catalog");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var item : response.actions()) {
            com.fasterxml.jackson.databind.JsonNode json = mapper.valueToTree(item.execution());
            org.junit.jupiter.api.Assertions.assertFalse(json.has("bulk"));
            assertEquals(5, json.size(), "ordinary actions retain the five existing execution policies");
            assertEquals(mapper.valueToTree(definitions.getFirst().execution()), json);
        }
    }

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
    void materializesDeclaredAllowedStatesOnActionCatalogItem() {
        ActionDefinitionRegistry registry = new MapActionDefinitionRegistry(
                Map.of(
                        "example.employees", List.of(
                                definition(
                                        "approve",
                                        10,
                                        "example.employees",
                                        "/employees",
                                        "example",
                                        ActionScope.ITEM,
                                        List.of("INACTIVE")
                                )
                        )
                ),
                Map.of()
        );
        ActionCatalogService service = new ActionCatalogService(registry, allowAllEvaluator(), contextualResolver());

        ActionCatalogResponse response = service.findByResourceKey("example.employees");

        assertEquals(List.of("INACTIVE"), response.actions().get(0).allowedStates());
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
        return definition(id, order, resourceKey, resourcePath, group, scope, List.of());
    }

    private ActionDefinition definition(
            String id,
            int order,
            String resourceKey,
            String resourcePath,
            String group,
            ActionScope scope,
            List<String> allowedStates
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
                allowedStates,
                List.of(),
                ActionExecutionContract.defaults(scope)
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
