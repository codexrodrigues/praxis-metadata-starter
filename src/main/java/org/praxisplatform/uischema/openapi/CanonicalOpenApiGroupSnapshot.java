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
    private final String group;
    private final JsonNode document;
    private final OpenApiDocumentService documents;

    private CanonicalOpenApiGroupSnapshot(OpenApiDocumentService documents, String group, JsonNode document) {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("An exact OpenAPI group is required");
        if (document == null || !document.isObject()) throw new IllegalStateException("Exact OpenAPI group document is unavailable");
        this.documents = documents;
        this.group = group;
        this.document = document.deepCopy();
    }

    /** Captures one defensive copy of the exactly named OpenAPI group, with no base-document fallback. */
    public static CanonicalOpenApiGroupSnapshot capture(OpenApiDocumentService documents, String group) {
        if (documents == null) throw new IllegalArgumentException("OpenAPI document service is required");
        return new CanonicalOpenApiGroupSnapshot(documents, group, documents.getDocumentForGroupStrict(group));
    }

    /** Captures one exact group after asking the source to bypass its process-local cache. */
    public static CanonicalOpenApiGroupSnapshot captureFresh(OpenApiDocumentService documents, String group) {
        if (documents == null) throw new IllegalArgumentException("OpenApiDocumentService is required");
        return new CanonicalOpenApiGroupSnapshot(documents, group, documents.refreshDocumentForGroupStrict(group));
    }

    /** The exact group name captured by this snapshot. */
    public String group() { return group; }

    /** Internal reader access; public consumers receive only validated schema contracts. */
    JsonNode document() { return document.deepCopy(); }

    /** Reads a strict request contract from this copy without fetching the document again. */
    public CanonicalRequestSchema requireRequestSchema(CanonicalOperationRef operation) {
        return OpenApiRequestSchemaReader.read(documents, this, operation);
    }

    /** Reads a strict response contract from this copy without fetching the document again. */
    public CanonicalResponseSchema requireResponseSchema(CanonicalOperationRef operation) {
        return OpenApiResponseSchemaReader.read(documents, this, operation);
    }

    /** Resolves the filtered UI projection on this same immutable group copy, without a cache read. */
    public org.praxisplatform.uischema.schema.FilteredSchemaProjection.Resolved resolveFilteredProjection(
            CanonicalOperationRef operation, String schemaType,
            org.praxisplatform.uischema.schema.SchemaReferenceResolver references,
            org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilities,
            String idField, Boolean readOnly) {
        if (!group.equals(operation.group())) throw new IllegalArgumentException("Operation belongs to another group");
        OpenApiRequestSchemaReader.validateOperationReference(operation);
        if (!operation.operationId().equals(document.path("paths").path(operation.path())
                .path(operation.method().toLowerCase(java.util.Locale.ROOT)).path("operationId").asText()))
            throw new IllegalStateException("Filtered projection operation differs from the captured group identity");
        var projection = new org.praxisplatform.uischema.schema.FilteredSchemaProjection(new com.fasterxml.jackson.databind.ObjectMapper())
                .resolve(document.deepCopy(), operation, schemaType, references, capabilities, idField, readOnly);
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
    public void requireNoRequestBody(CanonicalOperationRef operation) {
        OpenApiRequestSchemaReader.requireNoRequestBody(documents, this, operation);
    }
}
