package org.praxisplatform.uischema.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Stable hashes without disclosing payloads, principals or idempotency keys in storage keys. */
public final class ResourceCommandFingerprint {
    private ResourceCommandFingerprint() { }

    public static String of(ObjectMapper mapper, Object value) {
        return hash(canonical(mapper.valueToTree(value)));
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            SortedMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            return fields.entrySet().stream().map(entry -> '"' + entry.getKey().replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\":" + canonical(entry.getValue())).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(child -> values.add(canonical(child)));
            return String.join(",", values).transform(value -> "[" + value + "]");
        }
        return node.toString();
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
