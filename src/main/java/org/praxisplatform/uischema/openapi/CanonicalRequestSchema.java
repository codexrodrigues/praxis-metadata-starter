package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.SpecVersion;

/**
 * Isolated structural request schema read from the operation's OpenAPI document.
 * This is not a filtered UI projection, authorization decision or automatic Java DTO binding.
 */
public final class CanonicalRequestSchema {
    private final CanonicalOperationRef operation;
    private final String mediaType;
    private final SpecVersion specVersion;
    private final JsonNode schema;

    CanonicalRequestSchema(CanonicalOperationRef operation, String mediaType,
            SpecVersion specVersion, JsonNode schema) {
        this.operation = operation;
        this.mediaType = mediaType;
        this.specVersion = specVersion;
        this.schema = schema.deepCopy();
    }

    public CanonicalOperationRef operation() { return operation; }
    public String mediaType() { return mediaType; }
    public SpecVersion specVersion() { return specVersion; }
    public JsonNode schema() { return schema.deepCopy(); }
}
