package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;

/** Rejects opaque/mutable Java values masquerading as JSON before defensive copying. */
final class BulkJsonValues {
    private BulkJsonValues() { }

    static void validate(JsonNode value) { validate(value, 0); }

    private static void validate(JsonNode value, int depth) {
        if (depth > 16 || value == null || value.isPojo() || value.isBinary() || value.isMissingNode())
            throw new IllegalArgumentException("Unsupported bulk JSON value or nesting depth");
        if (value.isFloatingPointNumber()) {
            if (value.isDouble() || value.isFloat())
                throw new IllegalArgumentException("Bulk JSON decimals require exact decimal nodes, not binary floating point");
            var decimal = value.decimalValue();
            if (decimal.precision() > 256 || Math.abs((long) decimal.scale()) > 256)
                throw new IllegalArgumentException("Decimal is outside the bulk numeric limit");
        } else if (value.isIntegralNumber() && value.bigIntegerValue().abs().toString().length() > 256) {
            throw new IllegalArgumentException("Integer is outside the bulk numeric limit");
        }
        for (JsonNode child : value) validate(child, depth + 1);
    }
}
