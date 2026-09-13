package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;

/** Rejects opaque/mutable Java values masquerading as JSON before defensive copying. */
final class BulkJsonValues {
    static final int MAX_DECIMAL_SCALE = 256;
    private BulkJsonValues() { }

    /** Shared exact token reader; each caller supplies its own bounded, strict parser. */
    static JsonNode readDocument(JsonParser parser) throws IOException {
        parser.nextToken();
        JsonNode root = readNode(parser);
        if (parser.nextToken() != null) throw invalid();
        return root;
    }

    // Read exact numeric tokens directly. Databind's tree reader may first probe a huge
    // exponent as double and materialize Infinity even with USE_BIG_DECIMAL_FOR_FLOATS.
    private static JsonNode readNode(JsonParser parser) throws IOException {
        if (parser.currentToken() == null) throw invalid();
        var nodes = JsonNodeFactory.instance;
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                var object = nodes.objectNode();
                while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                    if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.FIELD_NAME) throw invalid();
                    String name = parser.currentName(); parser.nextToken(); object.set(name, readNode(parser));
                }
                yield object;
            }
            case START_ARRAY -> {
                var array = nodes.arrayNode();
                while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) array.add(readNode(parser));
                yield array;
            }
            case VALUE_NUMBER_INT -> nodes.numberNode(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> nodes.numberNode(parser.getDecimalValue());
            case VALUE_STRING -> nodes.textNode(parser.getText());
            case VALUE_TRUE -> nodes.booleanNode(true);
            case VALUE_FALSE -> nodes.booleanNode(false);
            case VALUE_NULL -> nodes.nullNode();
            default -> throw invalid();
        };
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid bulk JSON document");
    }


    static void validate(JsonNode value) { validate(value, 0); }

    private static void validate(JsonNode value, int depth) {
        if (depth > 16 || value == null || value.isPojo() || value.isBinary() || value.isMissingNode())
            throw new IllegalArgumentException("Unsupported bulk JSON value or nesting depth");
        if (value.isFloatingPointNumber()) {
            if (value.isDouble() || value.isFloat())
                throw new IllegalArgumentException("Bulk JSON decimals require exact decimal nodes, not binary floating point");
            var decimal = value.decimalValue();
            if (decimal.precision() > 256 || Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE)
                throw new IllegalArgumentException("Decimal is outside the bulk numeric limit");
        } else if (value.isIntegralNumber() && value.bigIntegerValue().abs().toString().length() > 256) {
            throw new IllegalArgumentException("Integer is outside the bulk numeric limit");
        }
        for (JsonNode child : value) validate(child, depth + 1);
    }
}
