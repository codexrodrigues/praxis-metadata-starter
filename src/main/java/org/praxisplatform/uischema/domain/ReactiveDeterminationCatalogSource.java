package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.determination.ReactiveDeterminationDefinitionRegistry;
import org.praxisplatform.uischema.determination.ReactiveDeterminationMetadataCompiler;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.openapi.BatchCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.ApiResourceIdentityResolver;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.FilteredSchemaProjection;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;

/** Resolves exact form scopes and consumes the same compiler and schema selector as /schemas/filtered. */
public final class ReactiveDeterminationCatalogSource {
    private final ReactiveDeterminationDefinitionRegistry registry;
    private final ReactiveDeterminationMetadataCompiler compiler;
    private final CanonicalOperationResolver operations;
    private final OpenApiDocumentService documents;
    private final ApiResourceIdentityResolver identities;
    private final SchemaReferenceResolver schemas;
    private final CanonicalCapabilityResolver capabilities;
    private final FilteredSchemaProjection projection;
    private final SchemaCanonicalizer canonicalizer = new SchemaCanonicalizer();
    private final ConcurrentMap<String, GroupHashSlots> documentHashCache = new ConcurrentHashMap<>();

    private record CachedDocumentHash(Object token, String hash) { }

    private record GroupHashSlots(CachedDocumentHash primary, CachedDocumentHash secondary) {
        String findPrimaryHash(Object token) {
            return primary != null && Objects.equals(primary.token(), token) ? primary.hash() : null;
        }

        boolean matchesSecondary(Object token) {
            return secondary != null && Objects.equals(secondary.token(), token);
        }
    }

    public ReactiveDeterminationCatalogSource(ReactiveDeterminationDefinitionRegistry registry,
            ReactiveDeterminationMetadataCompiler compiler, CanonicalOperationResolver operations,
            OpenApiDocumentService documents, ApiResourceIdentityResolver identities,
            SchemaReferenceResolver schemas, CanonicalCapabilityResolver capabilities, ObjectMapper mapper) {
        this.registry = registry;
        this.compiler = compiler;
        this.operations = operations;
        this.documents = documents;
        this.identities = identities;
        this.schemas = schemas;
        this.capabilities = capabilities;
        this.projection = new FilteredSchemaProjection(mapper);
    }

    /** No URL-prefix discovery, interpretation of raw definitions, or fallback to another operation. */
    public List<CompiledForm> find(String resourceKey, String group) {
        return find(resourceKey, group, false);
    }

    /**
     * Resolves and compiles reactive determination forms for the requested scope inside one
     * {@link OpenApiDocumentService#withSchemaCacheReadLock(java.util.function.Supplier)} pass.
     *
     * @param resourceKey optional semantic resource filter
     * @param group       optional OpenAPI group filter
     * @param strict      when {@code true}, reads exact group documents without ungrouped fallback
     */
    public List<CompiledForm> find(String resourceKey, String group, boolean strict) {
        List<String> sortedIds = registry.schemaOperationIds().stream().sorted().toList();
        if (sortedIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> ownersByOpId = new HashMap<>();
        Map<String, CanonicalOperationRef> matchingOps = resolveMatchingOperations(sortedIds, resourceKey, group, ownersByOpId);
        if (matchingOps.isEmpty()) {
            return List.of();
        }
        List<CompiledForm> compiledForms = documents.withSchemaCacheReadLock(() -> {
            List<CompiledForm> result = new ArrayList<>(matchingOps.size());
            Map<String, DocumentSnapshot> snapshotsByGroup = new HashMap<>();
            for (Map.Entry<String, CanonicalOperationRef> entry : matchingOps.entrySet()) {
                String id = entry.getKey();
                CanonicalOperationRef operation = entry.getValue();
                String owner = ownersByOpId.get(id);
                DocumentSnapshot documentSnapshot = readDocumentAndHash(
                        operation.group(), id, strict, snapshotsByGroup);
                JsonNode document = documentSnapshot.document();
                String documentHash = documentSnapshot.documentHash();
                String path = documents.resolveDocumentPath(document.path("paths"), operation.path(), operation.method());
                JsonNode published = document.path("paths").path(path).path(operation.method().toLowerCase(java.util.Locale.ROOT));
                if (!id.equals(published.path("operationId").asText())) {
                    throw new IllegalStateException("Determination form identity differs from published operation: " + id);
                }
                CanonicalOperationRef selected = new CanonicalOperationRef(operation.group(), id, path, operation.method());
                JsonNode form = projection.select(document, path, operation.method(), "request").schema();
                List<Map<String, Object>> compiled = compiler.compile(selected, "request", document, form, strict);
                CanonicalSchemaRef ref = projection.resolve(document, selected, "request", schemas, capabilities, null, null).reference();
                Map<String, JsonNode> fields = new java.util.TreeMap<>();
                for (Map<String, Object> determination : compiled) {
                    for (String side : List.of("inputs", "outputs")) {
                        for (Object item : (List<?>) determination.get(side)) {
                            String pointer = (String) ((Map<?, ?>) item).get("fieldPath");
                            fields.put(pointer, compiler.resolveFieldSchema(form, document, pointer));
                        }
                    }
                }
                result.add(new CompiledForm(owner, selected, ref, form, compiled, Map.copyOf(fields), documentHash));
            }
            return List.copyOf(result);
        });
        if (compiledForms == null) {
            throw new IllegalStateException("OpenAPI document fence did not complete form determination compilation");
        }
        return compiledForms;
    }

    private Map<String, CanonicalOperationRef> resolveMatchingOperations(
            List<String> sortedIds,
            String resourceKey,
            String group,
            Map<String, String> ownersByOpId
    ) {
        if (operations instanceof BatchCanonicalOperationResolver batchResolver) {
            Map<String, BatchCanonicalOperationResolver.ResolvedMvcOperation> resolved =
                    batchResolver.resolveMvcOperationsByIds(sortedIds);
            boolean directIdentityResolver = supportsDirectHandlerIdentity(identities);
            Map<String, CanonicalOperationRef> selected = new LinkedHashMap<>();
            for (String id : sortedIds) {
                BatchCanonicalOperationResolver.ResolvedMvcOperation match = resolved.get(id);
                if (match == null) {
                    throw new IllegalStateException("Unresolved determination form operation: " + id);
                }
                CanonicalOperationRef candidate = match.operation();
                String owner = (directIdentityResolver
                        ? identities.resolveResourceKey(match.handler(), candidate.path())
                        : identities.resolveResourceKey(candidate.path(), candidate.path(), candidate.method()))
                        .orElseThrow(() -> new IllegalStateException(
                                "Determination form has no explicit resource identity: " + id));
                if ((resourceKey == null || resourceKey.equals(owner))
                        && (group == null || group.equals(candidate.group()))) {
                    ownersByOpId.put(id, owner);
                    selected.put(id, candidate);
                }
            }
            return selected;
        }
        Map<String, CanonicalOperationRef> selected = new LinkedHashMap<>();
        for (String id : sortedIds) {
            CanonicalOperationRef operation = operations.resolveByOperationId(id)
                    .orElseThrow(() -> new IllegalStateException("Unresolved determination form operation: " + id));
            String owner = identities.resolveResourceKey(operation.path(), operation.path(), operation.method())
                    .orElseThrow(() -> new IllegalStateException("Determination form has no explicit resource identity: " + id));
            if (resourceKey != null && !resourceKey.equals(owner) || group != null && !group.equals(operation.group())) {
                continue;
            }
            ownersByOpId.put(id, owner);
            selected.put(id, operation);
        }
        return selected;
    }

    private static boolean supportsDirectHandlerIdentity(ApiResourceIdentityResolver identities) {
        return identities != null
                && (identities.getClass() == ApiResourceIdentityResolver.class
                || (AopUtils.isAopProxy(identities)
                && AopProxyUtils.ultimateTargetClass(identities) == ApiResourceIdentityResolver.class));
    }

    private record DocumentSnapshot(JsonNode document, String documentHash) { }

    private DocumentSnapshot readDocumentAndHash(
            String group,
            String operationId,
            boolean strict,
            Map<String, DocumentSnapshot> snapshotsByGroup
    ) {
        DocumentSnapshot existing = snapshotsByGroup.get(group);
        if (existing != null) {
            return existing;
        }
        JsonNode document = strict
                ? documents.getDocumentForGroupStrict(group)
                : documents.getDocumentForGroup(group);
        if (document == null) {
            throw new IllegalStateException("Missing form OpenAPI document: " + operationId);
        }
        String hash = resolveDocumentHash(group, document);
        DocumentSnapshot snapshot = new DocumentSnapshot(document, hash);
        snapshotsByGroup.put(group, snapshot);
        return snapshot;
    }

    private String resolveDocumentHash(String group, JsonNode document) {
        Object token = documents.groupDocumentCacheToken(group);
        if (token != null) {
            GroupHashSlots cached = documentHashCache.get(group);
            if (cached != null) {
                String primaryHash = cached.findPrimaryHash(token);
                if (primaryHash != null) {
                    return primaryHash;
                }
                if (cached.matchesSecondary(token)) {
                    documentHashCache.replace(group, cached, new GroupHashSlots(cached.secondary(), cached.primary()));
                    return cached.secondary().hash();
                }
            }
            String hash = SchemaHashUtil.sha256Hex(canonicalizer.canonicalize(document));
            documentHashCache.compute(group, (ignored, existing) -> {
                CachedDocumentHash updated = new CachedDocumentHash(token, hash);
                if (existing == null || Objects.equals(existing.primary().token(), token)) {
                    return new GroupHashSlots(updated, existing == null ? null : existing.secondary());
                }
                return new GroupHashSlots(updated, existing.primary());
            });
            return hash;
        }
        return SchemaHashUtil.sha256Hex(canonicalizer.canonicalize(document));
    }

    /** Captured form evidence contributes to the catalog fingerprint; scope and compiled metadata remain intact. */
    public record CompiledForm(String resourceKey, CanonicalOperationRef operation, CanonicalSchemaRef schema,
            JsonNode formSchema, List<Map<String, Object>> determinations, Map<String, JsonNode> fields,
            String sourceDocumentHash) { }
}
