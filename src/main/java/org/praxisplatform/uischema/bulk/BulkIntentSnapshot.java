package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Protected, immutable evaluated-input snapshot. This is not a public proposal or a receipt.
 * Domain encoders must serialize already validated typed values without coercing decimals.
 * A fingerprint binds trusted context and intent; it does not authorize or persist an operation.
 */
@JsonIgnoreType
public final class BulkIntentSnapshot {
    private final BulkFingerprintContext context;
    private final String codecId;
    private final BulkMode mode;
    private final JsonNode intent;
    private final String fingerprint;

    private BulkIntentSnapshot(BulkFingerprintContext context, String codecId, BulkMode mode, ObjectNode intent) {
        Objects.requireNonNull(context, "context is required");
        BulkContractChecks.text(codecId, "codecId");
        this.context = context;
        this.codecId = codecId;
        this.mode = mode;
        this.intent = BulkCanonicalJson.normalize(intent);
        this.fingerprint = BulkCanonicalJson.digest(storageDocument());
    }

    JsonNode storageDocument() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("namespaceId", context.namespaceId()); root.put("subjectId", context.subjectId());
        root.put("resourceKey", context.resourceKey()); root.put("schemaRevision", context.schemaRevision());
        root.put("atomicity", context.atomicity().name()); root.put("codecId", codecId); root.put("mode", mode.name());
        var operation = root.putObject("operationRef");
        operation.put("group", context.operationRef().group()); operation.put("operationId", context.operationRef().operationId());
        operation.put("path", context.operationRef().path()); operation.put("method", context.operationRef().method());
        root.set("intent", this.intent.deepCopy());
        return root;
    }

    public BulkFingerprintContext context() { return context; }
    public String codecId() { return codecId; }
    public BulkMode mode() { return mode; }

    public JsonNode intent() { return intent.deepCopy(); }
    public String fingerprint() { return fingerprint; }
    @Override public String toString() { return "BulkIntentSnapshot[protected]"; }

    public static <WI, ID, F> BulkIntentSnapshot uniform(BulkFingerprintContext context,
            BulkIdentityCodec<WI, ID> codec, BulkUniformEvaluationRequest<WI, F> request,
            Function<F, JsonNode> filterEncoder) {
        Objects.requireNonNull(request, "request is required");
        var body = body(request.executionMode());
        body.set("selection", selection(codec, request.selection(), filterEncoder));
        body.set("changes", changes(request.changes()));
        return new BulkIntentSnapshot(context, codec.codecId(), BulkMode.UNIFORM_UPDATE, body);
    }

    public static <P, WI, ID, F> BulkIntentSnapshot command(BulkFingerprintContext context,
            BulkIdentityCodec<WI, ID> codec, BulkCommandEvaluationRequest<P, WI, F> request,
            Function<P, JsonNode> parametersEncoder, Function<F, JsonNode> filterEncoder) {
        Objects.requireNonNull(request, "request is required");
        var body = body(request.executionMode());
        body.set("selection", selection(codec, request.selection(), filterEncoder));
        JsonNode parameters = domain(request.parameters(), parametersEncoder);
        BulkProtocolReader.validateParameterBindings(parameters);
        body.set("parameters", parameters);
        return new BulkIntentSnapshot(context, codec.codecId(), BulkMode.DOMAIN_COMMAND, body);
    }

    public static <WI, ID> BulkIntentSnapshot items(BulkFingerprintContext context,
            BulkIdentityCodec<WI, ID> codec, BulkItemEvaluationRequest<WI> request) {
        Objects.requireNonNull(request, "request is required");
        bounded(request.items().size(), 10000);
        var body = body(request.executionMode()); var items = body.putArray("items");
        for (var item : request.items()) {
            var node = items.addObject(); node.set("id", wire(codec, item.id()));
            node.put("expectedVersion", item.expectedVersion()); node.set("changes", changes(item.changes()));
        }
        return new BulkIntentSnapshot(context, codec.codecId(), BulkMode.PER_ITEM_UPDATE, body);
    }

    private static ObjectNode body(BulkExecutionMode executionMode) {
        var body = JsonNodeFactory.instance.objectNode(); body.put("executionMode", executionMode.name()); return body;
    }

    private static <WI, ID, F> ObjectNode selection(BulkIdentityCodec<WI, ID> codec,
            BulkSelection<WI, F> selection, Function<F, JsonNode> filterEncoder) {
        var result = JsonNodeFactory.instance.objectNode(); result.put("mode", selection.mode().name());
        if (selection.mode() == BulkSelectionMode.EXPLICIT) {
            bounded(selection.targets().size(), 10000);
            var targets = new ArrayList<ObjectNode>();
            for (var target : selection.targets()) {
                var node = JsonNodeFactory.instance.objectNode(); node.set("id", wire(codec, target.id()));
                node.put("expectedVersion", target.expectedVersion()); targets.add(node);
            }
            targets.sort(Comparator.comparing(node -> node.get("id").asText()));
            var array = result.putArray("targets"); targets.forEach(array::add);
        } else {
            bounded(selection.excludedIds().size(), 10000);
            result.set("filter", domain(selection.filter(), filterEncoder));
            var excluded = new ArrayList<JsonNode>();
            for (WI id : selection.excludedIds()) excluded.add(wire(codec, id));
            excluded.sort(Comparator.comparing(JsonNode::asText));
            var array = result.putArray("excludedIds"); excluded.forEach(array::add);
        }
        return result;
    }

    private static JsonNode changes(List<BulkFieldChange> changes) {
        bounded(changes.size(), 50);
        var result = JsonNodeFactory.instance.arrayNode();
        changes.stream().sorted(Comparator.comparing(BulkFieldChange::field)).forEach(change -> {
            var node = result.addObject().put("field", change.field()).put("operator", change.operator().name());
            if (change.operator() == BulkChangeOperator.SET) node.set("value", change.value());
        });
        return result;
    }

    private static <T> JsonNode domain(T value, Function<T, JsonNode> encoder) {
        JsonNode node = Objects.requireNonNull(encoder, "domain encoder is required").apply(value);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("A typed domain object is required");
        BulkCanonicalJson.preflight(node);
        return node.deepCopy();
    }

    private static <WI, ID> JsonNode wire(BulkIdentityCodec<WI, ID> codec, WI id) {
        Objects.requireNonNull(codec, "codec is required");
        JsonNode wire;
        if (id instanceof String text) wire = JsonNodeFactory.instance.textNode(text);
        else if (id instanceof Integer number) wire = JsonNodeFactory.instance.numberNode(number);
        else throw new IllegalArgumentException("Bulk wire identity must be String or Integer");
        if (!Objects.equals(codec.readWire(wire), id)) throw new IllegalArgumentException("Noncanonical bulk wire identity");
        return wire;
    }

    private static void bounded(int count, int max) {
        if (count > max) throw new IllegalArgumentException("Bulk snapshot collection limit exceeded");
    }
}
