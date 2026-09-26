package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.SpecVersion;

import java.util.List;

/** Internal response proof used while composing governed operation descriptors. */
record CanonicalResponseSchema(
        CanonicalOperationRef operation,
        SpecVersion specVersion,
        JsonNode schema,
        List<Variant> variants) {

    CanonicalResponseSchema {
        schema = schema.deepCopy();
        variants = List.copyOf(variants);
    }

    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }

    record Variant(int status, String mediaType) { }
}
