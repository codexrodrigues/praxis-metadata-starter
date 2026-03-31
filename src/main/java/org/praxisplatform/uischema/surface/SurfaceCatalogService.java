package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

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
        List<SurfaceDefinition> definitions = sort(surfaceDefinitionRegistry.findByResourceKey(resourceKey));
        String resolvedResourcePath = singleValue(definitions, SurfaceDefinition::resourcePath);
        String resolvedGroup = singleValue(definitions, SurfaceDefinition::group);
        return new SurfaceCatalogResponse(
                resourceKey,
                resolvedResourcePath,
                resolvedGroup,
                null,
                definitions.stream().map(def -> toCatalogItem(def, null)).toList()
        );
    }

    public SurfaceCatalogResponse findByGroup(String group) {
        List<SurfaceDefinition> definitions = sort(surfaceDefinitionRegistry.findByGroup(group));
        return new SurfaceCatalogResponse(
                null,
                null,
                group,
                null,
                definitions.stream().map(def -> toCatalogItem(def, null)).toList()
        );
    }

    public SurfaceCatalogResponse findItemSurfaces(String resourceKey, Object resourceId) {
        List<SurfaceDefinition> definitions = sort(surfaceDefinitionRegistry.findByResourceKey(resourceKey).stream()
                .filter(definition -> definition.scope() == SurfaceScope.ITEM)
                .toList());
        String resolvedResourcePath = singleValue(definitions, SurfaceDefinition::resourcePath);
        String resolvedGroup = singleValue(definitions, SurfaceDefinition::group);
        return new SurfaceCatalogResponse(
                resourceKey,
                resolvedResourcePath,
                resolvedGroup,
                resourceId,
                definitions.stream().map(def -> toCatalogItem(def, resourceId)).toList()
        );
    }

    private SurfaceCatalogItem toCatalogItem(SurfaceDefinition definition, Object resourceId) {
        SurfaceAvailabilityContext context = contextResolver.resolve(definition, resourceId);
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

    private List<SurfaceDefinition> sort(List<SurfaceDefinition> definitions) {
        return definitions.stream()
                .sorted(Comparator
                        .comparingInt(SurfaceDefinition::order)
                        .thenComparing(SurfaceDefinition::resourceKey, Comparator.nullsLast(String::compareTo))
                        .thenComparing(SurfaceDefinition::id, Comparator.nullsLast(String::compareTo))
                )
                .toList();
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
}
