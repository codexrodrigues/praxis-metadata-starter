package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.determination.ReactiveDeterminationDefinitionRegistry;
import org.praxisplatform.uischema.determination.ReactiveDeterminationMetadataCompiler;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.ApiResourceIdentityResolver;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.FilteredSchemaProjection;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;

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
    private final ConcurrentMap<String, CachedDocumentHash> documentHashCache = new ConcurrentHashMap<>();

    private record CachedDocumentHash(Object token, String hash) { }

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
        List<String> sortedIds = registry.schemaOperationIds().stream().sorted().toList();
        if (sortedIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> ownersByOpId = new HashMap<>();
        Map<String, CanonicalOperationRef> matchingOps = resolveMatchingOperations(sortedIds, resourceKey, group, ownersByOpId);
        List<CompiledForm> result = new ArrayList<>(matchingOps.size());
        Map<String, String> callLocalDocumentHashes = new HashMap<>();
        for (Map.Entry<String, CanonicalOperationRef> entry : matchingOps.entrySet()) {
            String id = entry.getKey();
            CanonicalOperationRef operation = entry.getValue();
            String owner = ownersByOpId.get(id);
            JsonNode document = documents.getDocumentForGroup(operation.group());
            if (document == null) {
                throw new IllegalStateException("Missing form OpenAPI document: " + id);
            }
            String documentHash = resolveDocumentHash(operation.group(), document, callLocalDocumentHashes);
            String path = documents.resolveDocumentPath(document.path("paths"), operation.path(), operation.method());
            JsonNode published = document.path("paths").path(path).path(operation.method().toLowerCase(java.util.Locale.ROOT));
            if (!id.equals(published.path("operationId").asText())) {
                throw new IllegalStateException("Determination form identity differs from published operation: " + id);
            }
            CanonicalOperationRef selected = new CanonicalOperationRef(operation.group(), id, path, operation.method());
            JsonNode form = projection.select(document, path, operation.method(), "request").schema();
            List<Map<String, Object>> compiled = compiler.compile(selected, "request", document, form);
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
    }

    private Map<String, CanonicalOperationRef> resolveMatchingOperations(
            List<String> sortedIds,
            String resourceKey,
            String group,
            Map<String, String> ownersByOpId
    ) {
        if (operations instanceof OpenApiCanonicalOperationResolver openApiResolver) {
            Set<String> seenIds = new HashSet<>();
            Map<String, CanonicalOperationRef> selected = openApiResolver.resolveByOperationIdsForScope(
                    sortedIds,
                    candidate -> {
                        seenIds.add(candidate.operationId());
                        String owner = identities.resolveResourceKey(candidate.path(), candidate.path(), candidate.method())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Determination form has no explicit resource identity: " + candidate.operationId()));
                        ownersByOpId.put(candidate.operationId(), owner);
                        return (resourceKey == null || resourceKey.equals(owner))
                                && (group == null || group.equals(candidate.group()));
                    });
            for (String id : sortedIds) {
                if (!seenIds.contains(id)) {
                    throw new IllegalStateException("Unresolved determination form operation: " + id);
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

    private String resolveDocumentHash(String group, JsonNode document, Map<String, String> callLocalHashes) {
        String local = callLocalHashes.get(group);
        if (local != null) {
            return local;
        }
        Object token = documents.groupDocumentCacheToken(group);
        if (token != null) {
            CachedDocumentHash cached = documentHashCache.get(group);
            if (cached != null && Objects.equals(cached.token(), token)) {
                callLocalHashes.put(group, cached.hash());
                return cached.hash();
            }
            String hash = SchemaHashUtil.sha256Hex(canonicalizer.canonicalize(document));
            documentHashCache.put(group, new CachedDocumentHash(token, hash));
            callLocalHashes.put(group, hash);
            return hash;
        }
        String hash = SchemaHashUtil.sha256Hex(canonicalizer.canonicalize(document));
        callLocalHashes.put(group, hash);
        return hash;
    }

    /** Captured form evidence contributes to the catalog fingerprint; scope and compiled metadata remain intact. */
    public record CompiledForm(String resourceKey, CanonicalOperationRef operation, CanonicalSchemaRef schema,
            JsonNode formSchema, List<Map<String, Object>> determinations, Map<String, JsonNode> fields,
            String sourceDocumentHash) { }
}
