package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityEvaluator;
import org.praxisplatform.uischema.capability.ResourceStateSnapshotScope;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta respostas do catalogo de actions sobre o registry annotation-driven.
 *
 * <p>
 * O servico suporta discovery documental por recurso ou grupo e discovery contextual para
 * actions item-level e collection-level. Em todos os casos, o catalogo referencia operacoes HTTP
 * reais e schemas canonicos, sem introduzir payload inline ou dispatcher generico.
 * </p>
 */
public class ActionCatalogService {

    private final ResourceOperationAvailabilityEvaluator operationAvailability;
    private final ActionDefinitionRegistry actionDefinitionRegistry;
    private final ActionAvailabilityEvaluator availabilityEvaluator;
    private final ActionAvailabilityContextResolver contextResolver;
    private final java.util.function.BiFunction<List<ActionDefinition>,
            java.util.function.Function<Map<String, ActionExecutionContract>, ActionCatalogResponse>,
            ActionCatalogResponse> bulkProjector;

    public ActionCatalogService(
            ActionDefinitionRegistry actionDefinitionRegistry,
            ActionAvailabilityEvaluator availabilityEvaluator,
            ActionAvailabilityContextResolver contextResolver
    ) {
        this(actionDefinitionRegistry, availabilityEvaluator, contextResolver,
                (definitions, consumer) -> consumer.apply(Map.of()));
    }

    public ActionCatalogService(ActionDefinitionRegistry actionDefinitionRegistry,
            ActionAvailabilityEvaluator availabilityEvaluator, ActionAvailabilityContextResolver contextResolver,
            java.util.function.BiFunction<List<ActionDefinition>,
                    java.util.function.Function<Map<String, ActionExecutionContract>, ActionCatalogResponse>,
                    ActionCatalogResponse> bulkProjector) {
        this(actionDefinitionRegistry, availabilityEvaluator, contextResolver, bulkProjector, null);
    }

    public ActionCatalogService(ActionDefinitionRegistry actionDefinitionRegistry,
            ActionAvailabilityEvaluator availabilityEvaluator, ActionAvailabilityContextResolver contextResolver,
            java.util.function.BiFunction<List<ActionDefinition>,
                    java.util.function.Function<Map<String, ActionExecutionContract>, ActionCatalogResponse>,
                    ActionCatalogResponse> bulkProjector,
            ResourceOperationAvailabilityEvaluator operationAvailability) {
        this.operationAvailability = operationAvailability;
        this.bulkProjector = java.util.Objects.requireNonNull(bulkProjector, "bulkProjector");
        this.actionDefinitionRegistry = actionDefinitionRegistry;
        this.availabilityEvaluator = availabilityEvaluator;
        this.contextResolver = contextResolver;
    }

    /**
     * Retorna as actions documentais de um recurso.
     */
    public ActionCatalogResponse findByResourceKey(String resourceKey) {
        try (var ignored = ResourceStateSnapshotScope.open()) {
            List<ActionDefinition> definitions = requireDefinitions(
                    sort(actionDefinitionRegistry.findByResourceKey(resourceKey)),
                    ActionCatalogNotFoundException.unknownResourceKey(resourceKey)
            );
            String resolvedResourcePath = singleValue(definitions, ActionDefinition::resourcePath);
            String resolvedGroup = singleValue(definitions, ActionDefinition::group);
            Map<ContextKey, ActionAvailabilityContext> contexts = availabilityContexts(definitions, null);
            return bulkProjector.apply(definitions, executions -> new ActionCatalogResponse(
                    resourceKey,
                    resolvedResourcePath,
                    resolvedGroup,
                    null,
                    definitions.stream().map(def -> toCatalogItem(def, contexts, null, executions)).toList()
            ));
        }
    }

    /**
     * Retorna as actions documentais agregadas por grupo OpenAPI.
     */
    public ActionCatalogResponse findByGroup(String group) {
        try (var ignored = ResourceStateSnapshotScope.open()) {
            List<ActionDefinition> definitions = requireDefinitions(
                    sort(actionDefinitionRegistry.findByGroup(group)),
                    ActionCatalogNotFoundException.unknownGroup(group)
            );
            Map<ContextKey, ActionAvailabilityContext> contexts = availabilityContexts(definitions, null);
            return bulkProjector.apply(definitions, executions -> new ActionCatalogResponse(
                    null,
                    null,
                    group,
                    null,
                    definitions.stream().map(def -> toCatalogItem(def, contexts, null, executions)).toList()
            ));
        }
    }

    /**
     * Retorna apenas as actions item-level disponiveis para uma instancia especifica.
     */
    public ActionCatalogResponse findItemActions(String resourceKey, Object resourceId) {
        try (var ignored = ResourceStateSnapshotScope.open()) {
            List<ActionDefinition> resourceDefinitions = requireDefinitions(
                    sort(actionDefinitionRegistry.findByResourceKey(resourceKey)),
                    ActionCatalogNotFoundException.unknownResourceKey(resourceKey)
            );
            List<ActionDefinition> definitions = requireDefinitions(
                    resourceDefinitions.stream().filter(definition -> definition.scope() == ActionScope.ITEM).toList(),
                    ActionCatalogNotFoundException.missingItemActions(resourceKey)
            );
            String resolvedResourcePath = singleValue(definitions, ActionDefinition::resourcePath);
            String resolvedGroup = singleValue(definitions, ActionDefinition::group);
            Map<ContextKey, ActionAvailabilityContext> contexts = availabilityContexts(definitions, resourceId);
            return bulkProjector.apply(definitions, executions -> new ActionCatalogResponse(
                    resourceKey,
                    resolvedResourcePath,
                    resolvedGroup,
                    resourceId,
                    definitions.stream().map(def -> toCatalogItem(def, contexts, resourceId, executions)).toList()
            ));
        }
    }

    /**
     * Retorna apenas as actions collection-level de um recurso.
     */
    public ActionCatalogResponse findCollectionActions(String resourceKey) {
        try (var ignored = ResourceStateSnapshotScope.open()) {
            List<ActionDefinition> resourceDefinitions = requireDefinitions(
                    sort(actionDefinitionRegistry.findByResourceKey(resourceKey)),
                    ActionCatalogNotFoundException.unknownResourceKey(resourceKey)
            );
            List<ActionDefinition> definitions = requireDefinitions(
                    resourceDefinitions.stream().filter(definition -> definition.scope() == ActionScope.COLLECTION).toList(),
                    ActionCatalogNotFoundException.missingCollectionActions(resourceKey)
            );
            String resolvedResourcePath = singleValue(definitions, ActionDefinition::resourcePath);
            String resolvedGroup = singleValue(definitions, ActionDefinition::group);
            Map<ContextKey, ActionAvailabilityContext> contexts = availabilityContexts(definitions, null);
            return bulkProjector.apply(definitions, executions -> new ActionCatalogResponse(
                    resourceKey,
                    resolvedResourcePath,
                    resolvedGroup,
                    null,
                    definitions.stream().map(def -> toCatalogItem(def, contexts, null, executions)).toList()
            ));
        }
    }

    private ActionCatalogItem toCatalogItem(
            ActionDefinition definition,
            Map<ContextKey, ActionAvailabilityContext> contexts,
            Object requestedResourceId,
            Map<String, ActionExecutionContract> executions
    ) {
        Object scopedResourceId = definition.scope() == ActionScope.ITEM ? requestedResourceId : null;
        ActionAvailabilityContext context = contexts.get(new ContextKey(
                definition.resourceKey(),
                definition.resourcePath(),
                scopedResourceId
        ));
        AvailabilityDecision availability;
        if ("duplicate-draft".equals(definition.id())) {
            availability = operationAvailability == null
                    ? AvailabilityDecision.deny("operation-availability-unconfigured", Map.of())
                    : operationAvailability.withOperations(definition.resourcePath(), operations -> {
                        var decision = operationAvailability.evaluate(operations, definition.resourceKey(),
                                definition.resourcePath(), definition.id(), scopedResourceId);
                        return decision.allowed() ? declaredDuplicateDraftAvailability(definition, context) : decision;
                    });
        } else {
            availability = availabilityEvaluator.evaluate(definition, context);
        }
        return new ActionCatalogItem(
                definition.id(),
                definition.resourceKey(),
                definition.scope(),
                definition.title(),
                definition.description(),
                definition.operation().operationId(),
                definition.operation().path(),
                definition.operation().method(),
                definition.requestSchema() != null ? definition.requestSchema().schemaId() : null,
                definition.requestSchema() != null ? definition.requestSchema().url() : null,
                definition.responseSchema() != null ? definition.responseSchema().schemaId() : null,
                definition.responseSchema() != null ? definition.responseSchema().url() : null,
                emptyToNull(definition.allowedStates()),
                availability,
                definition.order(),
                definition.successMessage(),
                definition.tags(),
                executions.getOrDefault(definition.operation().operationId(), definition.execution().withBulk(null))
        );
    }

    /** Evaluates only declared action rules, never a catalog or a capability snapshot. */
    public AvailabilityDecision evaluateDeclaredDuplicateDraftAvailability(String key, String path, Object id) {
        try {
            if (operationAvailability == null || actionDefinitionRegistry == null) return AvailabilityDecision.deny("duplicate-draft-unavailable", Map.of());
            var definitions = actionDefinitionRegistry.findByResourceKey(key).stream()
                    .filter(def -> "duplicate-draft".equals(def.id()) && path.equals(def.resourcePath())
                            && def.scope() == ActionScope.ITEM).toList();
            if (definitions.size() != 1) return AvailabilityDecision.deny("duplicate-draft-unavailable", Map.of());
            return declaredDuplicateDraftAvailability(definitions.getFirst(), contextResolver.resolve(key, path, id));
        } catch (RuntimeException ex) {
            return AvailabilityDecision.deny("duplicate-draft-unavailable", Map.of());
        }
    }

    private AvailabilityDecision declaredDuplicateDraftAvailability(ActionDefinition definition,
            ActionAvailabilityContext context) {
        try {
            var decision = availabilityEvaluator.evaluate(definition, context);
            return decision != null && decision.allowed() ? AvailabilityDecision.allowAll()
                    : AvailabilityDecision.deny("duplicate-draft-unavailable", Map.of());
        } catch (RuntimeException ex) {
            return AvailabilityDecision.deny("duplicate-draft-unavailable", Map.of());
        }
    }

    private List<String> emptyToNull(List<String> values) {
        return values == null || values.isEmpty() ? null : List.copyOf(values);
    }

    private Map<ContextKey, ActionAvailabilityContext> availabilityContexts(
            List<ActionDefinition> definitions,
            Object requestedResourceId
    ) {
        Map<ContextKey, ActionAvailabilityContext> contexts = new LinkedHashMap<>();
        for (ActionDefinition definition : definitions) {
            Object scopedResourceId = definition.scope() == ActionScope.ITEM ? requestedResourceId : null;
            ContextKey key = new ContextKey(definition.resourceKey(), definition.resourcePath(), scopedResourceId);
            contexts.computeIfAbsent(
                    key,
                    ignored -> contextResolver.resolve(
                            definition.resourceKey(),
                            definition.resourcePath(),
                            scopedResourceId
                    )
            );
        }
        return contexts;
    }

    private List<ActionDefinition> sort(List<ActionDefinition> definitions) {
        return definitions.stream()
                .sorted(Comparator
                        .comparingInt(ActionDefinition::order)
                        .thenComparing(ActionDefinition::resourceKey, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ActionDefinition::id, Comparator.nullsLast(String::compareTo))
                )
                .toList();
    }

    private List<ActionDefinition> requireDefinitions(
            List<ActionDefinition> definitions,
            RuntimeException notFoundException
    ) {
        if (definitions == null || definitions.isEmpty()) {
            throw notFoundException;
        }
        return definitions;
    }

    private String singleValue(List<ActionDefinition> definitions, java.util.function.Function<ActionDefinition, String> extractor) {
        List<String> values = definitions.stream()
                .map(extractor)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        throw new IllegalStateException("Action catalog expected a single canonical value but found: " + values);
    }

    private record ContextKey(String resourceKey, String resourcePath, Object resourceId) {
    }
}
