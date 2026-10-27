package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.capability.CapabilityOperation;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>
 * O servico tambem compartilha o contexto de availability por requisicao e recurso para evitar
 * resolucoes redundantes quando varias surfaces dependem da mesma fotografia contextual.
 * </p>
 */
public class SurfaceCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(SurfaceCatalogService.class);

    private final SurfaceDefinitionRegistry surfaceDefinitionRegistry;
    private final SurfaceAvailabilityEvaluator availabilityEvaluator;
    private final SurfaceAvailabilityContextResolver contextResolver;
    private final CanonicalCapabilityResolver canonicalCapabilityResolver;
    private final OpenApiDocumentService documents;

    public SurfaceCatalogService(
            SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            SurfaceAvailabilityEvaluator availabilityEvaluator,
            SurfaceAvailabilityContextResolver contextResolver
    ) {
        this(surfaceDefinitionRegistry, availabilityEvaluator, contextResolver, null);
    }

    public SurfaceCatalogService(
            SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            SurfaceAvailabilityEvaluator availabilityEvaluator,
            SurfaceAvailabilityContextResolver contextResolver,
            CanonicalCapabilityResolver canonicalCapabilityResolver
    ) {
        this(surfaceDefinitionRegistry, availabilityEvaluator, contextResolver, canonicalCapabilityResolver, null);
    }

    public SurfaceCatalogService(
            SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            SurfaceAvailabilityEvaluator availabilityEvaluator,
            SurfaceAvailabilityContextResolver contextResolver,
            CanonicalCapabilityResolver canonicalCapabilityResolver,
            OpenApiDocumentService documents
    ) {
        this.surfaceDefinitionRegistry = surfaceDefinitionRegistry;
        this.availabilityEvaluator = availabilityEvaluator;
        this.contextResolver = contextResolver;
        this.canonicalCapabilityResolver = canonicalCapabilityResolver;
        this.documents = documents;
    }

    /**
     * Retorna as surfaces documentais e collection-level de um recurso.
     */
    public SurfaceCatalogResponse findByResourceKey(String resourceKey) {
        List<SurfaceDefinition> definitions = requireDefinitions(
                sort(surfaceDefinitionRegistry.findByResourceKey(resourceKey)),
                SurfaceCatalogNotFoundException.unknownResourceKey(resourceKey)
        );
        String resolvedResourcePath = singleValue(definitions, SurfaceDefinition::resourcePath);
        String resolvedGroup = singleValue(definitions, SurfaceDefinition::group);
        return withRelatedProjection(definitions, projection -> {
            Map<ContextKey, SurfaceAvailabilityContext> contexts = availabilityContexts(definitions, null);
            return new SurfaceCatalogResponse(
                    resourceKey,
                    resolvedResourcePath,
                    resolvedGroup,
                    null,
                    definitions.stream().map(def -> toCatalogItem(def, contexts, null, projection)).toList()
            );
        });
    }

    /**
     * Retorna as surfaces documentais agregadas por grupo OpenAPI.
     */
    public SurfaceCatalogResponse findByGroup(String group) {
        List<SurfaceDefinition> definitions = requireDefinitions(
                sort(surfaceDefinitionRegistry.findByGroup(group)),
                SurfaceCatalogNotFoundException.unknownGroup(group)
        );
        return withRelatedProjection(definitions, projection -> {
            Map<ContextKey, SurfaceAvailabilityContext> contexts = availabilityContexts(definitions, null);
            return new SurfaceCatalogResponse(
                    null,
                    null,
                    group,
                    null,
                    definitions.stream().map(def -> toCatalogItem(def, contexts, null, projection)).toList()
            );
        });
    }

    /**
     * Retorna apenas as surfaces item-level para um recurso e instancia especificos.
     */
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
        return withRelatedProjection(definitions, projection -> {
            Map<ContextKey, SurfaceAvailabilityContext> contexts = availabilityContexts(definitions, resourceId);
            return new SurfaceCatalogResponse(
                    resourceKey,
                    resolvedResourcePath,
                    resolvedGroup,
                    resourceId,
                    definitions.stream().map(def -> toCatalogItem(def, contexts, resourceId, projection)).toList()
            );
        });
    }

    private SurfaceCatalogItem toCatalogItem(
            SurfaceDefinition definition,
            Map<ContextKey, SurfaceAvailabilityContext> contexts,
            Object requestedResourceId,
            RelatedProjection projection
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
                definition.icon(),
                definition.operation().operationId(),
                definition.operation().path(),
                definition.operation().method(),
                definition.schema().schemaId(),
                definition.schema().url(),
                definition.responseCardinality(),
                availability,
                definition.order(),
                definition.tags(),
                projection == null ? resolveRelatedResource(definition) : projection.related().get(definition)
        );
    }

    private RelatedResourceSurface resolveRelatedResource(SurfaceDefinition definition) {
        return resolveRelatedResource(definition, null);
    }

    private RelatedResourceSurface resolveRelatedResource(SurfaceDefinition definition,
            Map<String, ChildCapabilities> children) {
        RelatedResourceSurface related = definition.relatedResource();
        if (related == null || !related.present() || canonicalCapabilityResolver == null) {
            return related;
        }

        ChildCapabilities childCapabilities = children != null
                ? children.get(related.childResourcePath())
                : resolveDirectChildCapabilities(related.childResourcePath());
        List<RelatedResourceChildOperation> supportedOperations = related.childOperations().stream()
                .filter(operation -> isChildOperationSupported(childCapabilities, operation))
                .toList();
        List<RelatedResourceChildOperation> removedOperations = related.childOperations().stream()
                .filter(operation -> !supportedOperations.contains(operation))
                .toList();

        if (!removedOperations.isEmpty()) {
            logger.warn(
                    "Related resource surface {}.{} declared unsupported child operations {} for childResourcePath {}. "
                            + "Only operations backed by canonical child capabilities are published.",
                    definition.resourceKey(),
                    definition.id(),
                    removedOperations,
                    related.childResourcePath()
            );
        }

        return new RelatedResourceSurface(
                related.parentResourceKey(),
                related.parentIdPathVariable(),
                related.childResourceKey(),
                related.childResourcePath(),
                related.childParentField(),
                related.selectable(),
                related.selectionKeyField(),
                supportedOperations
        );
    }

    // A bounded structural phase owns the photograph; contextual resolvers never run under it.
    private <T> T withRelatedProjection(List<SurfaceDefinition> definitions,
            java.util.function.Function<RelatedProjection, T> consumer) {
        if (canonicalCapabilityResolver == null || definitions.stream().noneMatch(definition ->
                definition.relatedResource() != null && definition.relatedResource().present())) return consumer.apply(null);
        if (documents == null) return consumer.apply(null);
        if (documents.hasLocalPublishedBulkOpenApiPublication()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Discovery projection must start outside operational transactions");
        class Owner implements AutoCloseable {
            RelatedProjection projection;
            @Override public void close() {
                if (projection != null && projection.fence() != null) projection.fence().close();
            }
        }
        try (var owner = new Owner()) {
            RelatedProjection projection = documents.withSchemaCacheReadLock(() -> {
                boolean published = documents.hasLocalPublishedBulkOpenApiPublication();
                if (published && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    throw new IllegalStateException("Discovery projection must start outside operational transactions");
                Map<String, ChildCapabilities> children = new LinkedHashMap<>();
                Map<SurfaceDefinition, RelatedResourceSurface> related = new LinkedHashMap<>();
                for (var definition : definitions) {
                    var child = definition.relatedResource();
                    if (child == null) continue;
                    if (child.present() && canonicalCapabilityResolver != null) {
                        children.computeIfAbsent(
                                child.childResourcePath(),
                                path -> published
                                        ? resolveChildCapabilities(path)
                                        : resolveDirectChildCapabilities(path)
                        );
                    }
                    related.put(definition, resolveRelatedResource(definition, children));
                }
                owner.projection = new RelatedProjection(Map.copyOf(related),
                        published ? documents.captureBulkLifecycleDocumentFence() : null);
                return owner.projection;
            });
            T result = consumer.apply(projection);
            if (projection.fence() != null) projection.fence().read(() -> null);
            return result;
        }
    }

    private ChildCapabilities resolveChildCapabilities(String path) {
        try {
            var document = documents.getDocumentForGroup(documents.resolveGroupFromPath(path));
            return new ChildCapabilities(Map.copyOf(canonicalCapabilityResolver.resolve(document, path)),
                    Map.copyOf(canonicalCapabilityResolver.resolveCrudOperations(document, path)));
        } catch (GovernedOpenApiPublicationUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException ex) {
            logger.warn("Related child structural resolution failed; child operations will not be published.");
            return new ChildCapabilities(Map.of(), Map.of());
        }
    }

    private ChildCapabilities resolveDirectChildCapabilities(String childResourcePath) {
        if (!StringUtils.hasText(childResourcePath) || canonicalCapabilityResolver == null) {
            return new ChildCapabilities(Map.of(), Map.of());
        }
        try {
            Map<String, Boolean> capabilities = canonicalCapabilityResolver.resolve(childResourcePath);
            Map<String, CapabilityOperation> crudOperations = canonicalCapabilityResolver.resolveCrudOperations(childResourcePath);
            return new ChildCapabilities(
                    capabilities == null ? Map.of() : Map.copyOf(capabilities),
                    crudOperations == null ? Map.of() : Map.copyOf(crudOperations)
            );
        } catch (GovernedOpenApiPublicationUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException ex) {
            logger.warn(
                    "Failed to resolve child capabilities for related childResourcePath {}. "
                            + "The child operations will not be published.",
                    childResourcePath,
                    ex
            );
            return new ChildCapabilities(Map.of(), Map.of());
        }
    }

    private boolean isChildOperationSupported(ChildCapabilities child, RelatedResourceChildOperation operation) {
        if (child == null || operation == null) return false;
        return switch (operation) {
            case LIST -> Boolean.TRUE.equals(child.indicators().get("all")) || Boolean.TRUE.equals(child.indicators().get("filter"));
            case FILTER -> Boolean.TRUE.equals(child.indicators().get("filter"));
            case CREATE -> supported(child.operations(), "create");
            case UPDATE -> supported(child.operations(), "edit");
            case DELETE -> supported(child.operations(), "delete");
            case DUPLICATE_DRAFT -> supported(child.operations(), "duplicate-draft");
        };
    }

    private record ChildCapabilities(Map<String, Boolean> indicators, Map<String, CapabilityOperation> operations) {}
    private record RelatedProjection(Map<SurfaceDefinition, RelatedResourceSurface> related,
            OpenApiDocumentService.BulkLifecycleDocumentFence fence) {}

    private boolean supported(Map<String, CapabilityOperation> operations, String operationId) {
        CapabilityOperation operation = operations == null ? null : operations.get(operationId);
        return operation != null && operation.supported();
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
