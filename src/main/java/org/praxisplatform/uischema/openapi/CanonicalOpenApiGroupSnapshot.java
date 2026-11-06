package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable defensive copy of one exact OpenAPI group document for a bounded composition read.
 *
 * <p>The cache is deliberately not the snapshot. Readers using this value observe the same
 * captured document even if the cache is cleared or refreshed during their work. The snapshot
 * does not prove freshness, handler identity, authorization, readiness or execution eligibility;
 * callers that publish readiness must separately fence invalidation and validate the real MVC
 * handlers and durable control state.</p>
 */
public final class CanonicalOpenApiGroupSnapshot {
    private static final org.praxisplatform.uischema.schema.FilteredSchemaProjection PROJECTOR =
            new org.praxisplatform.uischema.schema.FilteredSchemaProjection(new com.fasterxml.jackson.databind.ObjectMapper());
    private final String group;
    private final JsonNode document;
    private final OpenApiDocumentService documents;
    private final Object publicationToken;
    private final OperationIndexCache indexCache;
    private final java.util.Map<CanonicalOperationRef, String> resolvedPaths = new java.util.HashMap<>();
    private final java.util.Map<org.praxisplatform.uischema.capability.CanonicalCapabilityResolver,
            java.util.Map<String, java.util.Map<String, Boolean>>> projectionCapabilities = new java.util.IdentityHashMap<>();
    private final java.util.Map<CanonicalOperationRef, CanonicalRequestSchema> requests = new java.util.HashMap<>();
    private final java.util.Map<CanonicalOperationRef, CanonicalResponseSchema> responses = new java.util.HashMap<>();
    private final java.util.Set<CanonicalOperationRef> bodyless = new java.util.HashSet<>();
    private final java.util.Map<org.praxisplatform.uischema.schema.SchemaReferenceResolver,
            java.util.Map<org.praxisplatform.uischema.capability.CanonicalCapabilityResolver,
                    java.util.Map<ProjectionKey, org.praxisplatform.uischema.schema.FilteredSchemaProjection.Resolved>>>
            projections = new java.util.IdentityHashMap<>();
    private record ProjectionKey(CanonicalOperationRef operation, String schemaType, String idField, Boolean readOnly) { }

    private CanonicalOpenApiGroupSnapshot(OpenApiDocumentService documents, String group, JsonNode document,
            Object publicationToken, OperationIndexCache indexCache) {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("An exact OpenAPI group is required");
        if (document == null || !document.isObject()) throw new IllegalStateException("Exact OpenAPI group document is unavailable");
        this.documents = documents;
        this.group = group;
        this.document = document.deepCopy();
        this.publicationToken = publicationToken;
        this.indexCache = indexCache;
    }

    /** Captures one defensive copy of the exactly named OpenAPI group, with no base-document fallback. */
    public static CanonicalOpenApiGroupSnapshot capture(OpenApiDocumentService documents, String group) {
        return capture(documents, group, false, new OperationIndexCache());
    }

    /** Captures one exact group after asking the source to bypass its process-local cache. */
    public static CanonicalOpenApiGroupSnapshot captureFresh(OpenApiDocumentService documents, String group) {
        return capture(documents, group, true, new OperationIndexCache());
    }

    private static CanonicalOpenApiGroupSnapshot capture(OpenApiDocumentService documents, String group,
            boolean fresh, OperationIndexCache indexCache) {
        if (documents == null) throw new IllegalArgumentException("OpenApiDocumentService is required");
        Object token = publicationIdentity(documents, group);
        JsonNode document = fresh ? documents.refreshDocumentForGroupStrict(group)
                : documents.getDocumentForGroupStrict(group);
        return new CanonicalOpenApiGroupSnapshot(documents, group, document,
                token != null && token.equals(publicationIdentity(documents, group)) ? token : null, indexCache);
    }

    /**
     * A bounded composition read across exact groups. It shares structural indexing only;
     * it neither establishes publication freshness nor replaces admission guards.
     * Create a new scope for each composition, never retain it as application state.
     */
    public static final class CaptureScope {
        private final OpenApiDocumentService documents;
        private final OperationIndexCache indexCache = new OperationIndexCache();
        private final java.util.Map<String, CanonicalOpenApiGroupSnapshot> snapshots = new java.util.HashMap<>();
        private final boolean fresh;

        public CaptureScope(OpenApiDocumentService documents, boolean fresh) {
            this.documents = java.util.Objects.requireNonNull(documents);
            this.fresh = fresh;
        }

        /** Captures each exact group once in this composition. */
        public synchronized CanonicalOpenApiGroupSnapshot capture(String group) {
            return snapshots.computeIfAbsent(group,
                    key -> CanonicalOpenApiGroupSnapshot.capture(documents, key, fresh, indexCache));
        }

        /**
         * Resolves a whole structural composition from captured MVC maps and validates the
         * live registries before returning its result. No intermediate admission is minted.
         */
        public <T> T compose(java.util.function.Supplier<T> reader) {
            synchronized (indexCache) {
                if (indexCache.composing) throw new IllegalStateException("Nested MVC compositions are not supported");
                indexCache.mappingValidations.values().forEach(Runnable::run);
                indexCache.composing = true;
                try {
                    T result = reader.get();
                    indexCache.mappingValidations.values().forEach(Runnable::run);
                    return result;
                } finally {
                    indexCache.composing = false;
                }
            }
        }
    }

    /** The exact group name captured by this snapshot. */
    public String group() { return group; }

    /** Internal reader access; public consumers receive only validated schema contracts. */
    JsonNode document() { return document.deepCopy(); }

    /**
     * Reuse structural identity evidence only inside this bounded snapshot and its exact
     * immutable publication. This is not READY authority: callers still own live guards,
     * MVC validation, provider composition and durable admission. Unpublished/mutable
     * sources are deliberately rebuilt on every read.
     */
    OpenApiCanonicalOperationResolver.OperationIndex operationIndex(OpenApiDocumentService source,
            java.util.Set<String> groups,
            java.util.function.Supplier<OpenApiCanonicalOperationResolver.OperationIndex> build) {
        boolean published = source == documents && publicationToken != null;
        if (published && !publicationToken.equals(publicationIdentity(source, group)))
            throw new IllegalStateException("Captured OpenAPI publication is no longer current");
        if (!published) return build.get();
        synchronized (indexCache) {
            var tokens = groupTokens(source, groups);
            Object publication = ((PublicationIdentity) publicationToken).publication();
            if (tokens != null && indexCache.index != null && indexCache.publication == publication
                    && tokens.equals(indexCache.groupTokens)) return indexCache.index;
            var index = build.get();
            if (!publicationToken.equals(publicationIdentity(source, group))
                    || !java.util.Objects.equals(tokens, groupTokens(source, groups)))
                throw new IllegalStateException("Published OpenAPI photograph changed during identity indexing");
            if (tokens != null) {
                indexCache.publication = publication;
                indexCache.groupTokens = tokens;
                indexCache.index = index;
            }
            return index;
        }
    }

    private static java.util.Map<String, Object> groupTokens(OpenApiDocumentService source,
            java.util.Set<String> groups) {
        var tokens = new java.util.HashMap<String, Object>();
        for (String group : groups) {
            Object token = source.groupDocumentCacheToken(group);
            if (token == null) return null;
            tokens.put(group, token);
        }
        return java.util.Map.copyOf(tokens);
    }

    private static final class OperationIndexCache {
        private Object publication;
        private java.util.Map<String, Object> groupTokens;
        private OpenApiCanonicalOperationResolver.OperationIndex index;
        private final java.util.Map<OpenApiCanonicalOperationResolver, OpenApiCanonicalOperationResolver.MappingIndex>
                mappings = new java.util.IdentityHashMap<>();
        private final java.util.Map<OpenApiCanonicalOperationResolver, Runnable> mappingValidations = new java.util.IdentityHashMap<>();
        private boolean composing;
    }

    /** Reuse deterministic MVC indexing only in this capture scope; validate the live registry on every read. */
    OpenApiCanonicalOperationResolver.MappingIndex mappingIndex(OpenApiCanonicalOperationResolver resolver,
            java.util.function.Supplier<OpenApiCanonicalOperationResolver.MappingIndex> build,
            java.util.function.Consumer<OpenApiCanonicalOperationResolver.MappingIndex> validate) {
        synchronized (indexCache) {
            var mappings = indexCache.mappings.computeIfAbsent(resolver, ignored -> build.get());
            indexCache.mappingValidations.putIfAbsent(resolver, () -> validate.accept(mappings));
            validateMappings(mappings, validate);
            return mappings;
        }
    }

    void validateMappings(OpenApiCanonicalOperationResolver.MappingIndex mappings,
            java.util.function.Consumer<OpenApiCanonicalOperationResolver.MappingIndex> validate) {
        synchronized (indexCache) {
            if (!indexCache.composing) validate.accept(mappings);
        }
    }

    private record PublicationIdentity(Object publication, Object group) {
        @Override public boolean equals(Object other) {
            return other instanceof PublicationIdentity identity
                    && publication == identity.publication && group.equals(identity.group);
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(publication) + group.hashCode(); }
    }

    private static Object publicationIdentity(OpenApiDocumentService source, String group) {
        Object publication = source.localPublishedBulkOpenApiPublicationToken();
        Object groupToken = source.groupDocumentCacheToken(group);
        return publication == null || groupToken == null ? null : new PublicationIdentity(publication, groupToken);
    }

    /** Reads a strict request contract from this copy without fetching the document again. */
    public synchronized CanonicalRequestSchema requireRequestSchema(CanonicalOperationRef operation) {
        return requests.computeIfAbsent(operation, key -> OpenApiRequestSchemaReader.read(group, document, key, resolvePath(key)));
    }

    /** Reads a strict response contract from this copy without fetching the document again. */
    public synchronized CanonicalResponseSchema requireResponseSchema(CanonicalOperationRef operation) {
        return responses.computeIfAbsent(operation, key -> OpenApiResponseSchemaReader.read(group, document, key, resolvePath(key)));
    }

    private synchronized String resolvePath(CanonicalOperationRef operation) {
        OpenApiRequestSchemaReader.validateOperationReference(operation);
        if (!group.equals(operation.group())) throw new IllegalStateException("Operation belongs to another group");
        // Path interpretation is structural to this exact photograph. Extension code sees
        // a detached tree once; request/response/bodyless readers share its interpretation.
        return resolvedPaths.computeIfAbsent(operation, key -> documents.resolveDocumentPath(
                document.path("paths").deepCopy(), key.path(), key.method().toLowerCase(java.util.Locale.ROOT)));
    }

    private synchronized java.util.Map<String, Boolean> projectionCapabilities(
            org.praxisplatform.uischema.capability.CanonicalCapabilityResolver resolver, String basePath) {
        // These are baseline OpenAPI capabilities, not contextual availability or admission.
        // Capture each provider/resource once in this group, copying both input and output.
        return projectionCapabilities.computeIfAbsent(java.util.Objects.requireNonNull(resolver),
                ignored -> new java.util.HashMap<>()).computeIfAbsent(basePath, path ->
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                        resolver.resolve(document.deepCopy(), path))));
    }

    /** Resolves the filtered UI projection on this same immutable group copy, without a cache read. */
    public synchronized org.praxisplatform.uischema.schema.FilteredSchemaProjection.Resolved resolveFilteredProjection(
            CanonicalOperationRef operation, String schemaType,
            org.praxisplatform.uischema.schema.SchemaReferenceResolver references,
            org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilities,
            String idField, Boolean readOnly) {
        if (!group.equals(operation.group())) throw new IllegalArgumentException("Operation belongs to another group");
        OpenApiRequestSchemaReader.validateOperationReference(operation);
        var key = new ProjectionKey(operation, schemaType, idField, readOnly);
        return projections.computeIfAbsent(java.util.Objects.requireNonNull(references), ignored -> new java.util.IdentityHashMap<>())
                .computeIfAbsent(java.util.Objects.requireNonNull(capabilities), ignored -> new java.util.HashMap<>())
                .computeIfAbsent(key, ignored -> resolveProjection(operation, schemaType, references, capabilities, idField, readOnly));
    }

    private org.praxisplatform.uischema.schema.FilteredSchemaProjection.Resolved resolveProjection(
            CanonicalOperationRef operation, String schemaType,
            org.praxisplatform.uischema.schema.SchemaReferenceResolver references,
            org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilities,
            String idField, Boolean readOnly) {
        if (!operation.operationId().equals(document.path("paths").path(operation.path())
                .path(operation.method().toLowerCase(java.util.Locale.ROOT)).path("operationId").asText()))
            throw new IllegalStateException("Filtered projection operation differs from the captured group identity");
        var caps = projectionCapabilities(capabilities, PROJECTOR.deriveBasePathFrom(operation.path()));
        var projection = PROJECTOR.resolveWithCapabilities(document, operation, schemaType, references, caps, idField, readOnly);
        var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) projection.evidence();
        var reader = new OpenApiRequestSchemaReader(document, OpenApiRequestSchemaReader.version(document.path("openapi")));
        // Resolve the selected UI component independently: its nested references may differ
        // from the raw transport schema. Missing/cyclic refs cannot become public links.
        evidence.set("resolvedSchema", "response".equals(schemaType)
                ? reader.resolveResponseSchema(evidence.path("schema")) : reader.resolveSchema(evidence.path("schema")));
        return new org.praxisplatform.uischema.schema.FilteredSchemaProjection.Resolved(projection.reference(), evidence);
    }

    /**
     * Verifies that the exact operation in this group snapshot does not publish a request body.
     * This is stronger than checking the MVC handler: OpenAPI customizers must not add a body to
     * protocol operations whose handler contract is explicitly bodyless.
     */
    public synchronized void requireNoRequestBody(CanonicalOperationRef operation) {
        if (bodyless.contains(operation)) return;
        OpenApiRequestSchemaReader.requireNoRequestBody(group, document, operation, resolvePath(operation));
        bodyless.add(operation);
    }
}
