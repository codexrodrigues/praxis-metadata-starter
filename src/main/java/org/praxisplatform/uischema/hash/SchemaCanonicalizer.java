package org.praxisplatform.uischema.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Canonicalizes a JSON tree into a deterministic form for hashing.
 *
 * Rules:
 * - Object keys sorted lexicographically.
 * - Arrays keep original order except arrays named "required" which are sorted case-sensitively.
 * - Numeric nodes normalized via BigDecimal.stripTrailingZeros().
 * - String values preserved as-is (no coercion of numeric-like strings).
 * - Nulls are neither introduced nor removed; input structure is preserved aside from ordering/number normalization.
 */
public final class SchemaCanonicalizer {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    public SchemaCanonicalizer() {}

    public JsonNode canonicalize(JsonNode root) {
        return canonicalize(root, null);
    }

    private JsonNode canonicalize(JsonNode node, String currentFieldName) {
        if (node == null) return null;

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);

            ObjectNode sorted = FACTORY.objectNode();
            for (String fn : fieldNames) {
                JsonNode child = obj.get(fn);
                sorted.set(fn, canonicalize(child, fn));
            }
            return sorted;
        }

        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            ArrayNode out = FACTORY.arrayNode();
            for (JsonNode element : array) {
                out.add(canonicalize(element, null));
            }
            if ("required".equals(currentFieldName)) {
                // Sort case-sensitive lexicographically by their canonical textual representation
                List<JsonNode> items = new ArrayList<>();
                out.forEach(items::add);
                items.sort((a, b) -> a.asText().compareTo(b.asText()));
                ArrayNode sorted = FACTORY.arrayNode();
                for (JsonNode it : items) sorted.add(it);
                return sorted;
            }
            return out;
        }

        if (node.isNumber()) {
            // Normalize numeric representation
            BigDecimal bd = node.decimalValue().stripTrailingZeros();
            return DecimalNode.valueOf(bd);
        }

        // For strings, booleans, null, etc., return as-is to preserve exact content
        return node;
    }
}

