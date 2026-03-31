package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta respostas do catalogo de actions sobre o registry annotation-driven.
 */
public class ActionCatalogService {

    private final ActionDefinitionRegistry actionDefinitionRegistry;
    private final ActionAvailabilityEvaluator availabilityEvaluator;
    private final ActionAvailabilityContextResolver contextResolver;

    public ActionCatalogService(
            ActionDefinitionRegistry actionDefinitionRegistry,
            ActionAvailabilityEvaluator availabilityEvaluator,
            ActionAvailabilityContextResolver contextResolver
    ) {
        this.actionDefinitionRegistry = actionDefinitionRegistry;
        this.availabilityEvaluator = availabilityEvaluator;
        this.contextResolver = contextResolver;
    }

    public ActionCatalogResponse findByResourceKey(String resourceKey) {
        List<ActionDefinition> definitions = requireDefinitions(
                sort(actionDefinitionRegistry.findByResourceKey(resourceKey)),
                ActionCatalogNotFoundException.unknownResourceKey(resourceKey)
        );
        String resolvedResourcePath = singleValue(definitions, ActionDefinition::resourcePath);
        String resolvedGroup = singleValue(definitions, ActionDefinition::group);
        Map<ContextKey, ActionAvailabilityContext> contexts = availabilityContexts(definitions, null);
        return new ActionCatalogResponse(
                resourceKey,
                resolvedResourcePath,
                resolvedGroup,
                null,
                definitions.stream().map(def -> toCatalogItem(def, contexts, null)).toList()
        );
    }

    public ActionCatalogResponse findByGroup(String group) {
        List<ActionDefinition> definitions = requireDefinitions(
                sort(actionDefinitionRegistry.findByGroup(group)),
                ActionCatalogNotFoundException.unknownGroup(group)
        );
        Map<ContextKey, ActionAvailabilityContext> contexts = availabilityContexts(definitions, null);
        return new ActionCatalogResponse(
                null,
                null,
                group,
                null,
                definitions.stream().map(def -> toCatalogItem(def, contexts, null)).toList()
        );
    }

    public ActionCatalogResponse findItemActions(String resourceKey, Object resourceId) {
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
        return new ActionCatalogResponse(
                resourceKey,
                resolvedResourcePath,
                resolvedGroup,
                resourceId,
                definitions.stream().map(def -> toCatalogItem(def, contexts, resourceId)).toList()
        );
    }

    public ActionCatalogResponse findCollectionActions(String resourceKey) {
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
        return new ActionCatalogResponse(
                resourceKey,
                resolvedResourcePath,
                resolvedGroup,
                null,
                definitions.stream().map(def -> toCatalogItem(def, contexts, null)).toList()
        );
    }

    private ActionCatalogItem toCatalogItem(
            ActionDefinition definition,
            Map<ContextKey, ActionAvailabilityContext> contexts,
            Object requestedResourceId
    ) {
        Object scopedResourceId = definition.scope() == ActionScope.ITEM ? requestedResourceId : null;
        ActionAvailabilityContext context = contexts.get(new ContextKey(
                definition.resourceKey(),
                definition.resourcePath(),
                scopedResourceId
        ));
        AvailabilityDecision availability = availabilityEvaluator.evaluate(definition, context);
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
                availability,
                definition.order(),
                definition.successMessage(),
                definition.tags()
        );
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
