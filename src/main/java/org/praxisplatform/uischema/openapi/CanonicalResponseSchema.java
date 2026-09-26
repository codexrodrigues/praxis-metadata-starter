package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.SpecVersion;

import java.util.List;

/**
 * Immutable response contract parsed from one exact canonical OpenAPI group snapshot.
 * Instances are created only by the strict reader so callers cannot bypass its validation.
 */
public final class CanonicalResponseSchema {
    private final CanonicalOperationRef operation;
    private final SpecVersion specVersion;
    private final JsonNode schema;
    private final List<Variant> variants;

    CanonicalResponseSchema(CanonicalOperationRef operation, SpecVersion specVersion,
            JsonNode schema, List<Variant> variants) {
        this.operation = operation;
        this.specVersion = specVersion;
        this.schema = schema.deepCopy();
        this.variants = List.copyOf(variants);
    }

    public CanonicalOperationRef operation() { return operation; }
    public SpecVersion specVersion() { return specVersion; }
    public JsonNode schema() { return schema.deepCopy(); }
    public List<Variant> variants() { return variants; }

    /** One explicitly declared successful HTTP status and JSON media type. */
    public record Variant(int status, String mediaType) { }
}
