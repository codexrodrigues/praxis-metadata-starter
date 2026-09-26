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
}
