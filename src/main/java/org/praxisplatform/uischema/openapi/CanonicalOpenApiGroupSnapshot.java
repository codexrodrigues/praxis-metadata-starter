package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Internal immutable copy of one exact OpenAPI group document used during one descriptor compile.
 *
 * <p>The service cache is deliberately not the snapshot: cache eviction or refresh during a
 * compile must not let operation, request and response validation observe different documents.</p>
 */
final class CanonicalOpenApiGroupSnapshot {
    private final String group;
    private final JsonNode document;

    private CanonicalOpenApiGroupSnapshot(String group, JsonNode document) {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("An exact OpenAPI group is required");
        if (document == null || !document.isObject()) throw new IllegalStateException("Exact OpenAPI group document is unavailable");
        this.group = group;
        this.document = document.deepCopy();
    }

    static CanonicalOpenApiGroupSnapshot capture(OpenApiDocumentService documents, String group) {
        if (documents == null) throw new IllegalArgumentException("OpenAPI document service is required");
        return new CanonicalOpenApiGroupSnapshot(group, documents.getDocumentForGroupStrict(group));
    }

    String group() { return group; }

    /** Returns a defensive copy; callers cannot mutate the captured source. */
    JsonNode document() { return document.deepCopy(); }
}
