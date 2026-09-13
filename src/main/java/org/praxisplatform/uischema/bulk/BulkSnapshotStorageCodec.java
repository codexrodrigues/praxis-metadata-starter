package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

/** Internal storage encoding. JSON decimals retain a point or exponent: 1.0 must not become integer 1. */
final class BulkSnapshotStorageCodec {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(MAX_BYTES).maxNumberLength(520).build()).build());

    private BulkSnapshotStorageCodec() { }

    static byte[] encode(BulkIntentSnapshot snapshot) { return json(snapshot.storageDocument()); }

    static BulkIntentSnapshot decode(byte[] payload, String fingerprint) {
        if (payload == null || payload.length == 0 || payload.length > MAX_BYTES)
            throw invalid();
        try {
            JsonNode root = readDocument(payload);
            exact(root, Set.of("namespaceId", "subjectId", "resourceKey", "schemaRevision", "atomicity",
                    "codecId", "mode", "operationRef", "intent"));
            JsonNode op = root.get("operationRef");
            exact(op, Set.of("group", "operationId", "path", "method"));
            String group = op.get("group").isNull() ? null : text(op, "group");
            var context = new BulkFingerprintContext(text(root, "namespaceId"), text(root, "subjectId"),
                    text(root, "resourceKey"), new CanonicalOperationRef(group, text(op, "operationId"),
                    text(op, "path"), text(op, "method")), text(root, "schemaRevision"),
                    ActionCollectionAtomicity.valueOf(text(root, "atomicity")));
            var restored = restore(context, codec(text(root, "codecId")),
                    BulkMode.valueOf(text(root, "mode")), root.get("intent"));
            if (!restored.fingerprint().equals(fingerprint)) throw invalid();
            return restored;
        } catch (RuntimeException error) {
            // Neither Jackson nor driver details may expose protected business values.
            throw invalid();
        }
    }

    static JsonNode readDocument(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_BYTES) throw invalid();
        try (JsonParser parser = MAPPER.getFactory().createParser(payload)) {
            return BulkJsonValues.readDocument(parser);
        } catch (IOException | RuntimeException error) { throw invalid(); }
    }

    static BulkIdentityCodec<?, ?> codec(String id) {
        return switch (id) {
            case "integer" -> BulkIdentityCodecs.integers();
            case "long" -> BulkIdentityCodecs.longs();
            case "string" -> BulkIdentityCodecs.strings();
            case "uuid" -> BulkIdentityCodecs.uuids();
            default -> throw invalid();
        };
    }

    private static <WI, ID> BulkIntentSnapshot restore(BulkFingerprintContext context,
            BulkIdentityCodec<WI, ID> codec, BulkMode mode, JsonNode intent) {
        var reader = new BulkProtocolReader<>(codec);
        BulkCanonicalJson.preflight(intent);
        return switch (mode) {
            case UNIFORM_UPDATE -> BulkIntentSnapshot.uniform(context, codec, reader.<JsonNode>readUniformNode(intent, JsonNode::deepCopy), JsonNode::deepCopy);
            case DOMAIN_COMMAND -> BulkIntentSnapshot.command(context, codec,
                    reader.<JsonNode, JsonNode>readCommandNode(intent, JsonNode::deepCopy, JsonNode::deepCopy), JsonNode::deepCopy, JsonNode::deepCopy);
            case PER_ITEM_UPDATE -> BulkIntentSnapshot.items(context, codec, reader.readItemsNode(intent));
        };
    }

    static byte[] json(JsonNode value) {
        try {
            var output = new BoundedOutput();
            try (JsonGenerator generator = MAPPER.getFactory().createGenerator(output)) { write(generator, value); }
            if (output.size() > MAX_BYTES) throw invalid();
            return output.toByteArray();
        } catch (IOException error) { throw invalid(); }
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            if (count >= MAX_BYTES) throw invalid();
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > MAX_BYTES - count) throw invalid();
            super.write(bytes, offset, length);
        }
    }

    private static void write(JsonGenerator generator, JsonNode node) throws IOException {
        if (node.isObject()) {
            generator.writeStartObject();
            var fields = node.fields();
            while (fields.hasNext()) { var field = fields.next(); generator.writeFieldName(field.getKey()); write(generator, field.getValue()); }
            generator.writeEndObject();
        } else if (node.isArray()) {
            generator.writeStartArray(); for (var value : node) write(generator, value); generator.writeEndArray();
        } else if (node.isBigDecimal()) {
            String decimal = node.decimalValue().toString();
            generator.writeNumber(decimal.contains(".") || decimal.contains("E") ? decimal : decimal + "e0");
        } else { generator.writeTree(node); }
    }

    private static void exact(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject() || node.size() != names.size()) throw invalid();
        node.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) throw invalid(); });
    }
    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid protected proposal content"); }
}
