package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BulkIntentFingerprintTest {

    @Test
    void fixesVersionedFramingAgainstIndependentReferenceVectors() {
        // Independently encoded using Python struct big-endian framing and hashlib SHA-256.
        assertEquals("sha256:5fa11beb16d7e35ac8777906ea440f31d63485e6814ec6d924a27019bd3ea92d",
                uniform(context(), "SYNC", "101", "v1", "title", "first").fingerprint());
        ObjectNode typed = JsonNodeFactory.instance.objectNode();
        typed.putArray("z").add(true).addNull().add("ação😀");
        typed.put("a", new java.math.BigDecimal("1.2300"));
        assertEquals("sha256:604ab683753835600db2da25112c6a5baa40f8ef6bbbf8298fbb475e2072f86e",
                BulkCanonicalJson.digest(typed));
    }

    @Test
    void bindsEachOperationCoordinateIndependently() {
        var baseline = uniform(context(), "SYNC", "101", "v1", "title", "first").fingerprint();
        for (var op : List.of(
                new CanonicalOperationRef("other", operation().operationId(), operation().path(), operation().method()),
                new CanonicalOperationRef(operation().group(), "other", operation().path(), operation().method()),
                new CanonicalOperationRef(operation().group(), operation().operationId(), "/other", operation().method()),
                new CanonicalOperationRef(operation().group(), operation().operationId(), operation().path(), "POST"))) {
            assertNotEquals(baseline, uniform(context("namespace-a", "subject-a", "employees", op,
                    "schema-a", ActionCollectionAtomicity.ATOMIC), "SYNC", "101", "v1", "title", "first").fingerprint());
        }
    }

    @Test
    void rejectsOversizedProgrammaticCollectionsBeforeCanonicalization() {
        var changes = List.of(BulkFieldChange.clear("x"));
        var targets = java.util.stream.IntStream.range(0, 10001).mapToObj(i -> new BulkTarget<>(Integer.toString(i), "v")).toList();
        var request = new BulkUniformEvaluationRequest<String, JsonNode>(BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, targets, null, null), changes);
        assertThrows(IllegalArgumentException.class,
                () -> BulkIntentSnapshot.uniform(context(), BulkIdentityCodecs.strings(), request, JsonNode::deepCopy));
        var tooManyChanges = java.util.stream.IntStream.range(0, 51).mapToObj(i -> BulkFieldChange.clear("field" + i)).toList();
        var items = new BulkItemEvaluationRequest<>(BulkExecutionMode.SYNC,
                List.of(new BulkItemChange<>("1", "v", tooManyChanges)));
        assertThrows(IllegalArgumentException.class, () -> BulkIntentSnapshot.items(context(), BulkIdentityCodecs.strings(), items));
    }

    @Test
    void bindsEveryTrustedContextDimensionAndProtocolDimension() {
        BulkIntentSnapshot baseline = uniform(context(), "SYNC", "101", "v1", "title", "first");

        assertNotEquals(baseline.fingerprint(), uniform(context("namespace-b", "subject-a", "employees",
                operation(), "schema-a", ActionCollectionAtomicity.ATOMIC), "SYNC", "101", "v1", "title", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context("namespace-a", "subject-b", "employees",
                operation(), "schema-a", ActionCollectionAtomicity.ATOMIC), "SYNC", "101", "v1", "title", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context("namespace-a", "subject-a", "contracts",
                operation(), "schema-a", ActionCollectionAtomicity.ATOMIC), "SYNC", "101", "v1", "title", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context("namespace-a", "subject-a", "employees",
                new CanonicalOperationRef("supervisor", "employee-bulk-edit", "/employees/bulk", "PATCH"), "schema-a",
                ActionCollectionAtomicity.ATOMIC), "SYNC", "101", "v1", "title", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context("namespace-a", "subject-a", "employees",
                operation(), "schema-b", ActionCollectionAtomicity.ATOMIC), "SYNC", "101", "v1", "title", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context("namespace-a", "subject-a", "employees",
                operation(), "schema-a", ActionCollectionAtomicity.PER_ITEM), "SYNC", "101", "v1", "title", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context(), "ASYNC", "101", "v1", "title", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context(), "SYNC", "101", "v2", "title", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context(), "SYNC", "102", "v1", "title", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context(), "SYNC", "101", "v1", "description", "first").fingerprint());
        assertNotEquals(baseline.fingerprint(), uniform(context(), "SYNC", "101", "v1", "title", "second").fingerprint());

        BulkIntentSnapshot integerCodec = BulkIntentSnapshot.uniform(context(), BulkIdentityCodecs.integers(),
                new BulkUniformEvaluationRequest<>(BulkExecutionMode.SYNC,
                        new BulkSelection<>(BulkSelectionMode.EXPLICIT, List.of(new BulkTarget<>(101, "v1")), null, null),
                        List.of(BulkFieldChange.set("title", JsonNodeFactory.instance.textNode("first")))), ignored -> null);
        assertNotEquals(baseline.fingerprint(), integerCodec.fingerprint());

        BulkIntentSnapshot items = BulkIntentSnapshot.items(context(), BulkIdentityCodecs.strings(),
                new BulkItemEvaluationRequest<>(BulkExecutionMode.SYNC,
                        List.of(new BulkItemChange<>("101", "v1",
                                List.of(BulkFieldChange.set("title", JsonNodeFactory.instance.textNode("first")))))));
        assertNotEquals(baseline.fingerprint(), items.fingerprint());
    }

    @Test
    void canonicalizesObjectKeysAndSetLikeIntentCollections() {
        BulkIntentSnapshot first = uniformFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[
                  {"id":"200","expectedVersion":"v2"},{"id":"100","expectedVersion":"v1"}]},"changes":[
                  {"field":"zeta","operator":"SET","value":{"b":2,"a":1}},
                  {"field":"alpha","operator":"SET","value":{"second":2,"first":1}}]}
                """);
        BulkIntentSnapshot reordered = uniformFromJson("""
                {"changes":[
                  {"value":{"first":1,"second":2},"operator":"SET","field":"alpha"},
                  {"value":{"a":1,"b":2},"field":"zeta","operator":"SET"}],
                  "selection":{"targets":[{"expectedVersion":"v1","id":"100"},{"expectedVersion":"v2","id":"200"}],"mode":"EXPLICIT"},
                  "executionMode":"SYNC"}
                """);

        assertEquals(first.fingerprint(), reordered.fingerprint());

        BulkIntentSnapshot queryA = commandFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{"b":2,"a":1},"excludedIds":["9","2"]},
                 "parameters":{"request":{"z":3,"a":1}}}
                """);
        BulkIntentSnapshot queryB = commandFromJson("""
                {"parameters":{"request":{"a":1,"z":3}},"selection":{"excludedIds":["2","9"],"filter":{"a":1,"b":2},"mode":"QUERY"},
                 "executionMode":"SYNC"}
        """);
        assertEquals(queryA.fingerprint(), queryB.fingerprint());

        assertNotEquals(queryA.fingerprint(), commandFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{"a":2,"b":2},"excludedIds":["2","9"]},
                 "parameters":{"request":{"a":1,"z":3}}}
                """).fingerprint());
        assertNotEquals(queryA.fingerprint(), commandFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{"a":1,"b":2},"excludedIds":["2","9"]},
                 "parameters":{"request":{"a":2,"z":3}}}
                """).fingerprint());
    }

    @Test
    void preservesDomainArrayAndPerItemOrder() {
        BulkIntentSnapshot firstParameters = commandFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"steps":["one","two"]}}
                """);
        BulkIntentSnapshot reorderedParameters = commandFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"steps":["two","one"]}}
                """);
        assertNotEquals(firstParameters.fingerprint(), reorderedParameters.fingerprint());

        BulkIntentSnapshot firstItems = itemsFromJson("""
                {"executionMode":"SYNC","items":[
                  {"id":"101","expectedVersion":"v1","changes":[{"field":"title","operator":"SET","value":"first"}]},
                  {"id":"102","expectedVersion":"v2","changes":[{"field":"title","operator":"SET","value":"second"}]}]}
                """);
        BulkIntentSnapshot reorderedItems = itemsFromJson("""
                {"executionMode":"SYNC","items":[
                  {"id":"102","expectedVersion":"v2","changes":[{"field":"title","operator":"SET","value":"second"}]},
                  {"id":"101","expectedVersion":"v1","changes":[{"field":"title","operator":"SET","value":"first"}]}]}
                """);
        assertNotEquals(firstItems.fingerprint(), reorderedItems.fingerprint());
    }

    @Test
    void preservesJsonTypesWhileNormalizingExactDecimalRepresentations() {
        BulkIntentSnapshot decimalOne = commandFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"amount":1.0}}
                """);
        BulkIntentSnapshot decimalOneWithZeroes = commandFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"amount":1.00}}
                """);
        BulkIntentSnapshot integerOne = commandFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"amount":1}}
                """);
        BulkIntentSnapshot stringOne = commandFromJson("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"amount":"1"}}
                """);

        assertEquals(decimalOne.fingerprint(), decimalOneWithZeroes.fingerprint());
        assertNotEquals(decimalOne.fingerprint(), integerOne.fingerprint());
        assertNotEquals(decimalOne.fingerprint(), stringOne.fingerprint());
    }

    @Test
    void copiesEveryInputAndNeverExposesMutableSnapshotState() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        ObjectNode nested = parameters.putObject("nested");
        nested.put("value", "before");
        BulkCommandEvaluationRequest<JsonNode, String, JsonNode> request = new BulkCommandEvaluationRequest<>(
                BulkExecutionMode.SYNC, new BulkSelection<>(BulkSelectionMode.QUERY, null,
                JsonNodeFactory.instance.objectNode(), List.of()), parameters);

        BulkIntentSnapshot snapshot = BulkIntentSnapshot.command(context(), BulkIdentityCodecs.strings(), request,
                JsonNode::deepCopy, JsonNode::deepCopy);
        String fingerprint = snapshot.fingerprint();
        nested.put("value", "after");

        assertEquals("before", snapshot.intent().path("parameters").path("nested").path("value").asText());
        JsonNode exposed = snapshot.intent();
        ((ObjectNode) exposed.path("parameters").path("nested")).put("value", "mutated");
        assertEquals("before", snapshot.intent().path("parameters").path("nested").path("value").asText());
        assertEquals(fingerprint, snapshot.fingerprint());
    }

    @Test
    void rejectsUnsafeOrUnboundedValuesBeforeFingerprinting() {
        assertThrows(IllegalArgumentException.class, () -> BulkIntentSnapshot.command(context(), BulkIdentityCodecs.strings(),
                command(JsonNodeFactory.instance.numberNode(1.0d)), JsonNode::deepCopy, JsonNode::deepCopy));
        assertThrows(IllegalArgumentException.class, () -> BulkIntentSnapshot.command(context(), BulkIdentityCodecs.strings(),
                command(com.fasterxml.jackson.databind.node.MissingNode.getInstance()), JsonNode::deepCopy, JsonNode::deepCopy));

        ObjectNode cyclic = JsonNodeFactory.instance.objectNode();
        cyclic.set("self", cyclic);
        assertThrows(IllegalArgumentException.class, () -> BulkIntentSnapshot.command(context(), BulkIdentityCodecs.strings(),
                command(cyclic), value -> value, JsonNode::deepCopy));

        ObjectNode protectedParameter = JsonNodeFactory.instance.objectNode();
        protectedParameter.put("executionMode", "ASYNC");
        assertThrows(IllegalArgumentException.class, () -> BulkIntentSnapshot.command(context(), BulkIdentityCodecs.strings(),
                command(protectedParameter), JsonNode::deepCopy, JsonNode::deepCopy));

        BulkUniformEvaluationRequest<String, JsonNode> nonCanonicalLong = new BulkUniformEvaluationRequest<>(BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, List.of(new BulkTarget<>("001", "v1")), null, null),
                List.of(BulkFieldChange.set("title", JsonNodeFactory.instance.textNode("first"))));
        assertThrows(IllegalArgumentException.class, () -> BulkIntentSnapshot.uniform(context(), BulkIdentityCodecs.longs(),
                nonCanonicalLong, JsonNode::deepCopy));

        ObjectNode malformedUnicode = JsonNodeFactory.instance.objectNode();
        malformedUnicode.put("text", "\ud800");
        assertThrows(IllegalArgumentException.class, () -> BulkIntentSnapshot.command(context(), BulkIdentityCodecs.strings(),
                command(malformedUnicode), JsonNode::deepCopy, JsonNode::deepCopy));

        ObjectNode tooLarge = JsonNodeFactory.instance.objectNode();
        tooLarge.put("text", "x".repeat(8 * 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> BulkIntentSnapshot.command(context(), BulkIdentityCodecs.strings(),
                command(tooLarge), JsonNode::deepCopy, JsonNode::deepCopy));
    }

    private static BulkIntentSnapshot uniform(BulkFingerprintContext context, String executionMode, String id,
                                               String version, String field, String value) {
        return uniformFromJson(context, """
                {"executionMode":"%s","selection":{"mode":"EXPLICIT","targets":[{"id":"%s","expectedVersion":"%s"}]},
                 "changes":[{"field":"%s","operator":"SET","value":"%s"}]}
                """.formatted(executionMode, id, version, field, value));
    }

    private static BulkIntentSnapshot uniformFromJson(String json) {
        return uniformFromJson(context(), json);
    }

    private static BulkIntentSnapshot uniformFromJson(BulkFingerprintContext context, String json) {
        BulkUniformEvaluationRequest<String, JsonNode> request = reader().readUniform(bytes(json), JsonNode::deepCopy);
        return BulkIntentSnapshot.uniform(context, BulkIdentityCodecs.strings(), request, JsonNode::deepCopy);
    }

    private static BulkIntentSnapshot commandFromJson(String json) {
        BulkCommandEvaluationRequest<JsonNode, String, JsonNode> request = reader().readCommand(bytes(json),
                JsonNode::deepCopy, JsonNode::deepCopy);
        return BulkIntentSnapshot.command(context(), BulkIdentityCodecs.strings(), request, JsonNode::deepCopy,
                JsonNode::deepCopy);
    }

    private static BulkIntentSnapshot itemsFromJson(String json) {
        return BulkIntentSnapshot.items(context(), BulkIdentityCodecs.strings(), reader().readItems(bytes(json)));
    }

    private static BulkCommandEvaluationRequest<JsonNode, String, JsonNode> command(JsonNode parameters) {
        return new BulkCommandEvaluationRequest<>(BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.QUERY, null, JsonNodeFactory.instance.objectNode(), List.of()), parameters);
    }

    private static BulkProtocolReader<String, String> reader() {
        return new BulkProtocolReader<>(BulkIdentityCodecs.strings());
    }

    private static BulkFingerprintContext context() {
        return context("namespace-a", "subject-a", "employees", operation(), "schema-a", ActionCollectionAtomicity.ATOMIC);
    }

    private static BulkFingerprintContext context(String namespace, String subject, String resource,
                                                  CanonicalOperationRef operation, String schema,
                                                  ActionCollectionAtomicity atomicity) {
        return new BulkFingerprintContext(namespace, subject, resource, operation, schema, atomicity);
    }

    private static CanonicalOperationRef operation() {
        return new CanonicalOperationRef("admin", "employee-bulk-edit", "/employees/bulk", "PATCH");
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
