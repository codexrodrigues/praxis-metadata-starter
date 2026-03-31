package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta respostas do catalogo de surfaces sobre o registry annotation-driven.
 *
 * <p>
 * O servico suporta tanto discovery global/documental (`/schemas/surfaces`) quanto discovery
 * contextual item-level (`/{resource}/{id}/surfaces`). Em ambos os casos, a resposta apenas
 * referencia operacoes reais e schemas canonicos; nao define payload inline.
 * </p>
 */
public class SurfaceCatalogService {

    private final SurfaceDefinitionRegistry surfaceDefinitionRegistry;
    private final SurfaceAvailabilityEvaluator availabilityEvaluator;
    private final SurfaceAvailabilityContextResolver contextResolver;

    public SurfaceCatalogService(
            SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            SurfaceAvailabilityEvaluator availabilityEvaluator,
            SurfaceAvailabilityContextResolver contextResolver
    ) {
        this.surfaceDefinitionRegistry = surfaceDefinitionRegistry;
        this.availabilityEvaluator = availabilityEvaluator;
        this.contextResolver = contextResolver;
    }

    public SurfaceCatalogResponse findByResourceKey(String resourceKey) {
        List<SurfaceDefinition> definitions = requireDefinitions(
                sort(surfaceDefinitionRegistry.findByResourceKey(resourceKey)),
                SurfaceCatalogNotFoundException.unknownResourceKey(resourceKey)
        );
        String resolvedResourcePath = singleValue(definitions, SurfaceDefinition::resourcePath);
        String resolvedGroup = singleValue(definitions, SurfaceDefinition::group);
        Map<ContextKey, SurfaceAvailabilityContext> contexts = availabilityContexts(definitions, null);
        return new SurfaceCatalogResponse(
                resourceKey,
                resolvedResourcePath,
                resolvedGroup,
                null,
                definitions.stream().map(def -> toCatalogItem(def, contexts, null)).toList()
        );
    }

    public SurfaceCatalogResponse findByGroup(String group) {
        List<SurfaceDefinition> definitions = requireDefinitions(
                sort(surfaceDefinitionRegistry.findByGroup(group)),
                SurfaceCatalogNotFoundException.unknownGroup(group)
        );
        Map<ContextKey, SurfaceAvailabilityContext> contexts = availabilityContexts(definitions, null);
        return new SurfaceCatalogResponse(
                null,
                null,
                group,
                null,
                definitions.stream().map(def -> toCatalogItem(def, contexts, null)).toList()
        );
    }

    public SurfaceCatalogResponse findItemSurfaces(String resourceKey, Object resourceId) {
        List<SurfaceDefinition> resourceDefinitions = requireDefinitions(
                sort(surfaceDefinitionRegistry.findByResourceKey(resourceKey)),
                SurfaceCatalogNotFoundException.unknownResourceKey(resourceKey)
        );
        List<SurfaceDefinition> definitions = requireDefinitions(resourceDefinitions.stream()
                .filter(definition -> definition.scope() == SurfaceScope.ITEM)
                .toList(), SurfaceCatalogNotFoundException.missingItemSurfaces(resourceKey));
        String resolvedResourcePath = singleValue(definitions, SurfaceDefinition::resourcePath);
        String resolvedGroup = singleValue(definitions, SurfaceDefinition::group);
        Map<ContextKey, SurfaceAvailabilityContext> contexts = availabilityContexts(definitions, resourceId);
        return new SurfaceCatalogResponse(
                resourceKey,
                resolvedResourcePath,
                resolvedGroup,
                resourceId,
                definitions.stream().map(def -> toCatalogItem(def, contexts, resourceId)).toList()
        );
    }

    private SurfaceCatalogItem toCatalogItem(
            SurfaceDefinition definition,
            Map<ContextKey, SurfaceAvailabilityContext> contexts,
            Object requestedResourceId
    ) {
        Object scopedResourceId = definition.scope() == SurfaceScope.ITEM ? requestedResourceId : null;
        SurfaceAvailabilityContext context = contexts.get(new ContextKey(
                definition.resourceKey(),
                definition.resourcePath(),
                scopedResourceId
        ));
        AvailabilityDecision availability = availabilityEvaluator.evaluate(definition, context);
        return new SurfaceCatalogItem(
                definition.id(),
                definition.resourceKey(),
                definition.kind(),
                definition.scope(),
                definition.title(),
                definition.description(),
                definition.intent(),
                definition.operation().operationId(),
                definition.operation().path(),
                definition.operation().method(),
                definition.schema().schemaId(),
                definition.schema().url(),
                availability,
                definition.order(),
                definition.tags()
        );
    }

    private Map<ContextKey, SurfaceAvailabilityContext> availabilityContexts(
            List<SurfaceDefinition> definitions,
            Object requestedResourceId
    ) {
        Map<ContextKey, SurfaceAvailabilityContext> contexts = new LinkedHashMap<>();
        for (SurfaceDefinition definition : definitions) {
            Object scopedResourceId = definition.scope() == SurfaceScope.ITEM ? requestedResourceId : null;
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

    private List<SurfaceDefinition> sort(List<SurfaceDefinition> definitions) {
        return definitions.stream()
                .sorted(Comparator
                        .comparingInt(SurfaceDefinition::order)
                        .thenComparing(SurfaceDefinition::resourceKey, Comparator.nullsLast(String::compareTo))
                        .thenComparing(SurfaceDefinition::id, Comparator.nullsLast(String::compareTo))
                )
                .toList();
    }

    private List<SurfaceDefinition> requireDefinitions(
            List<SurfaceDefinition> definitions,
            RuntimeException notFoundException
    ) {
        if (definitions == null || definitions.isEmpty()) {
            throw notFoundException;
        }
        return definitions;
    }

    private String singleValue(List<SurfaceDefinition> definitions, java.util.function.Function<SurfaceDefinition, String> extractor) {
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
        throw new IllegalStateException("Surface catalog expected a single canonical value but found: " + values);
    }

    private record ContextKey(String resourceKey, String resourcePath, Object resourceId) {
    }
}
