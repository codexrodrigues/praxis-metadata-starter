package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;

/** Bulk-specific typed framing. Not RFC 8785 and not a replacement for the schema hash. */
final class BulkCanonicalJson {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final MessageDigest digest;
    private int size;

    private BulkCanonicalJson() {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    static JsonNode normalize(JsonNode node) {
        preflight(node);
        return ordered(node);
    }

    static void preflight(JsonNode node) {
        BulkJsonValues.validate(node);
        digest(node); // Bound the typed frame before allocating a defensive tree copy.
    }

    private static JsonNode ordered(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) { unicode(name); result.set(name, ordered(node.get(name))); }
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> result.add(ordered(child)));
            return result;
        }
        if (node.isTextual()) unicode(node.textValue());
        if (node.isFloatingPointNumber())
            return JsonNodeFactory.instance.numberNode(node.decimalValue().stripTrailingZeros());
        return node.deepCopy();
    }

    static String digest(JsonNode node) {
        var encoder = new BulkCanonicalJson();
        encoder.string("praxis.bulk.intent/1");
        encoder.write(node, 0);
        return "sha256:" + HexFormat.of().formatHex(encoder.digest.digest());
    }

    private void write(JsonNode node, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Bulk fingerprint depth exceeded");
        if (node.isObject()) {
            marker('O'); number(node.size());
            var names = new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) { string(name); write(node.get(name), depth + 1); }
        } else if (node.isArray()) {
            marker('A'); number(node.size());
            for (JsonNode child : node) write(child, depth + 1);
        } else if (node.isTextual()) { marker('S'); string(node.textValue()); }
        else if (node.isIntegralNumber()) { marker('I'); string(node.bigIntegerValue().toString()); }
        else if (node.isBigDecimal()) { marker('D'); string(node.decimalValue().stripTrailingZeros().toString()); }
        else if (node.isBoolean()) marker(node.booleanValue() ? 'T' : 'F');
        else if (node.isNull()) marker('N');
        else throw new IllegalArgumentException("Unsupported bulk fingerprint node");
    }

    private void marker(char marker) { bytes(new byte[]{(byte) marker}); }
    private void number(int number) { bytes(ByteBuffer.allocate(4).putInt(number).array()); }
    private void string(String value) {
        unicode(value);
        if (value.length() > MAX_BYTES) throw new IllegalArgumentException("Bulk fingerprint size exceeded");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); number(bytes.length); bytes(bytes);
    }
    private void bytes(byte[] bytes) {
        if (bytes.length > MAX_BYTES - size) throw new IllegalArgumentException("Bulk fingerprint size exceeded");
        size += bytes.length; digest.update(bytes);
    }
    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Malformed Unicode in bulk intent");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Malformed Unicode in bulk intent");
        }
    }
}
