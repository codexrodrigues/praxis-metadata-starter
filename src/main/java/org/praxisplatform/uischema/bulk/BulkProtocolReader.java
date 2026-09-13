package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Isolated, bounded entry point for the bulk wire protocol. It validates original JSON tokens
 * before a host mapper can coerce identifiers. It never changes the application's ObjectMapper.
 * Domain parameter/filter readers must bind and validate their canonical DTOs; authorization,
 * candidate validation, proposal persistence and execution are separate stages.
 */
public final class BulkProtocolReader<WI, ID> {
    private final BulkIdentityCodec<WI, ID> codec;
    private final BulkProtocolLimits limits;
    private final ObjectMapper mapper;

    public BulkProtocolReader(BulkIdentityCodec<WI, ID> codec) {
        this(codec, BulkProtocolLimits.defaults());
    }

    public BulkProtocolReader(BulkIdentityCodec<WI, ID> codec, BulkProtocolLimits limits) {
        this.codec = Objects.requireNonNull(codec, "codec is required");
        this.limits = Objects.requireNonNull(limits, "limits are required");
        var factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(limits.maxDepth()).maxNumberLength(256)
                        .maxStringLength(limits.maxRequestBytes()).build()).build();
        mapper = new ObjectMapper(factory)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    }

    public BulkUniformEvaluationRequest<WI, JsonNode> readUniform(byte[] body) {
        return readUniform(body, JsonNode::deepCopy);
    }

    public <F> BulkUniformEvaluationRequest<WI, F> readUniform(byte[] body, Function<JsonNode, F> filterReader) {
        return readUniformNode(parse(body), filterReader);
    }

    <F> BulkUniformEvaluationRequest<WI, F> readUniformNode(JsonNode root, Function<JsonNode, F> filterReader) {
        fields(root, Set.of("executionMode", "selection", "changes"));
        return new BulkUniformEvaluationRequest<>(executionMode(root), selection(root.get("selection"), filterReader),
                changes(root.get("changes")));
    }

    public <P, F> BulkCommandEvaluationRequest<P, WI, F> readCommand(
            byte[] body, Function<JsonNode, P> parametersReader, Function<JsonNode, F> filterReader) {
        return readCommandNode(parse(body), parametersReader, filterReader);
    }

    <P, F> BulkCommandEvaluationRequest<P, WI, F> readCommandNode(
            JsonNode root, Function<JsonNode, P> parametersReader, Function<JsonNode, F> filterReader) {
        fields(root, Set.of("executionMode", "selection", "parameters"));
        JsonNode parameters = root.get("parameters");
        validateParameterBindings(parameters);
        return new BulkCommandEvaluationRequest<>(executionMode(root), selection(root.get("selection"), filterReader),
                Objects.requireNonNull(parametersReader, "parametersReader is required").apply(parameters.deepCopy()));
    }

    static void validateParameterBindings(JsonNode parameters) {
        object(parameters);
        for (String reserved : List.of("proposalId", "selection", "targets", "items", "executionMode", "atomicity",
                "changes", "filter", "excludedIds", "expectedVersion", "id", "mode")) {
            if (parameters.has(reserved)) throw invalid("Parameters contain a protected binding");
        }
    }

    public BulkItemEvaluationRequest<WI> readItems(byte[] body) {
        return readItemsNode(parse(body));
    }

    BulkItemEvaluationRequest<WI> readItemsNode(JsonNode root) {
        fields(root, Set.of("executionMode", "items"));
        JsonNode items = array(root.get("items"), limits.maxTargets(), false);
        List<BulkItemChange<WI>> result = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            fields(item, Set.of("id", "expectedVersion", "changes"));
            result.add(new BulkItemChange<>(codec.readWire(item.get("id")), text(item.get("expectedVersion")),
                    changes(item.get("changes"))));
        }
        return new BulkItemEvaluationRequest<>(executionMode(root), result);
    }

    public BulkConfirmationRequest readConfirmation(byte[] body) {
        JsonNode root = parse(body);
        fields(root, Set.of("proposalId"));
        return new BulkConfirmationRequest(text(root.get("proposalId")));
    }

    private <F> BulkSelection<WI, F> selection(JsonNode node, Function<JsonNode, F> filterReader) {
        object(node);
        BulkSelectionMode mode = enumValue(BulkSelectionMode.class, node.get("mode"));
        if (mode == BulkSelectionMode.EXPLICIT) {
            fields(node, Set.of("mode", "targets"));
            JsonNode targets = array(node.get("targets"), limits.maxTargets(), false);
            List<BulkTarget<WI>> result = new ArrayList<>(targets.size());
            for (JsonNode target : targets) {
                fields(target, Set.of("id", "expectedVersion"));
                result.add(new BulkTarget<>(codec.readWire(target.get("id")), text(target.get("expectedVersion"))));
            }
            return new BulkSelection<>(mode, result, null, null);
        }
        fields(node, Set.of("mode", "filter", "excludedIds"));
        object(node.get("filter"));
        List<WI> excluded = new ArrayList<>();
        if (node.has("excludedIds")) {
            for (JsonNode id : array(node.get("excludedIds"), limits.maxExclusions(), true)) excluded.add(codec.readWire(id));
        }
        F filter = Objects.requireNonNull(filterReader, "filterReader is required").apply(node.get("filter").deepCopy());
        return new BulkSelection<>(mode, null, filter, excluded);
    }

    private List<BulkFieldChange> changes(JsonNode node) {
        List<BulkFieldChange> result = new ArrayList<>();
        for (JsonNode change : array(node, limits.maxFields(), false)) {
            fields(change, Set.of("field", "operator", "value"));
            BulkChangeOperator operator = enumValue(BulkChangeOperator.class, change.get("operator"));
            if (operator == BulkChangeOperator.CLEAR && change.has("value")) throw invalid("CLEAR cannot contain value");
            result.add(new BulkFieldChange(text(change.get("field")), operator, change.get("value")));
        }
        return BulkContractChecks.changes(result);
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > limits.maxRequestBytes())
            throw invalid("Bulk request size is outside the configured limit");
        try {
            JsonNode root = mapper.readTree(body);
            object(root);
            checkNumbers(root);
            return root;
        } catch (IOException exception) {
            // Jackson exceptions can include the request body: do not expose them as a cause.
            throw invalid("Invalid bulk JSON document");
        }
    }

    private static void checkNumbers(JsonNode node) {
        if (node.isFloatingPointNumber()) {
            var decimal = node.decimalValue();
            if (decimal.precision() > 256 || Math.abs((long) decimal.scale()) > 256)
                throw invalid("Decimal is outside the bulk numeric limit");
        }
        for (JsonNode child : node) checkNumbers(child);
    }

    private static BulkExecutionMode executionMode(JsonNode root) {
        return enumValue(BulkExecutionMode.class, root.get("executionMode"));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node) {
        try { return Enum.valueOf(type, text(node)); }
        catch (IllegalArgumentException exception) { throw invalid("Invalid bulk protocol enum"); }
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) throw invalid("A nonblank string is required");
        return node.textValue();
    }

    private static JsonNode array(JsonNode node, int limit, boolean allowEmpty) {
        if (node == null || !node.isArray() || (!allowEmpty && node.isEmpty()) || node.size() > limit)
            throw invalid("Bulk array is outside the configured limit");
        return node;
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        object(node);
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) throw invalid("Unknown bulk protocol field");
    }

    private static void object(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("A JSON object is required");
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
