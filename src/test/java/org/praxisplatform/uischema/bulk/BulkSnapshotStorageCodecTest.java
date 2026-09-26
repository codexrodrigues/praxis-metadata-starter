package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import static org.assertj.core.api.Assertions.*;

class BulkSnapshotStorageCodecTest {
    static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext("namespace-a", "subject-a", "employees",
            new CanonicalOperationRef("admin", "employee-bulk-edit", "/employees/bulk", "PATCH"),
            "schema-a", ActionCollectionAtomicity.ATOMIC);
    static final BulkOperationControlExpectation CONTROL_EXPECTATION = new BulkOperationControlExpectation(
            1, "sha256:" + "0".repeat(64), "structural-r1");

    static BulkStoredProposal proposal() { return proposal(snapshot(BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.strings(), "\"101\"", "1.0")); }
    static BulkStoredProposal proposal(BulkIntentSnapshot snapshot) {
        return new BulkStoredProposal(UUID.randomUUID(), Instant.parse("2026-09-13T12:00:00Z"),
                Instant.parse("2026-09-13T12:15:00Z"), snapshot, CONTROL_EXPECTATION);
    }
    static <WI, ID> BulkIntentSnapshot snapshot(BulkMode mode, BulkIdentityCodec<WI, ID> codec, String id, String number) {
        return snapshot(CONTEXT, mode, codec, id, number);
    }
    static <WI, ID> BulkIntentSnapshot snapshot(BulkFingerprintContext context, BulkMode mode,
            BulkIdentityCodec<WI, ID> codec, String id, String number) {
        var reader = new BulkProtocolReader<>(codec);
        String selection = "\"selection\":{\"mode\":\"EXPLICIT\",\"targets\":[{\"id\":"+id+",\"expectedVersion\":\"v1\"}]}";
        String changes = "\"changes\":[{\"field\":\"amount\",\"operator\":\"SET\",\"value\":"+number+"}]";
        return switch(mode) {
            case UNIFORM_UPDATE -> BulkIntentSnapshot.uniform(context, codec,
                    reader.readUniform(bytes("{\"executionMode\":\"SYNC\","+selection+","+changes+"}")), JsonNode::deepCopy);
            case DOMAIN_COMMAND -> BulkIntentSnapshot.command(context, codec,
                    reader.<JsonNode, JsonNode>readCommand(bytes("{\"executionMode\":\"SYNC\","+selection+",\"parameters\":{\"amount\":"+number+",\"reason\":\"protected-customer-value\"}}"), JsonNode::deepCopy, JsonNode::deepCopy), JsonNode::deepCopy, JsonNode::deepCopy);
            case PER_ITEM_UPDATE -> BulkIntentSnapshot.items(context, codec,
                    reader.readItems(bytes("{\"executionMode\":\"SYNC\",\"items\":[{\"id\":"+id+",\"expectedVersion\":\"v1\","+changes+"}]}")));
        };
    }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void allModesAndIdentityCodecsRetainIntentAndFingerprint() {
        for (var mode : BulkMode.values()) {
            roundtrip(snapshot(mode, BulkIdentityCodecs.integers(), "42", "1.0"));
            roundtrip(snapshot(mode, BulkIdentityCodecs.longs(), "\"9223372036854775807\"", "123456789012345678901234567890"));
            roundtrip(snapshot(mode, BulkIdentityCodecs.strings(), "\"ação😀\"", "0.12345678901234567890123456789"));
            roundtrip(snapshot(mode, BulkIdentityCodecs.uuids(), "\"123e4567-e89b-12d3-a456-426614174000\"", "1e100"));
        }
    }
    @Test void integerAndDecimalOneStayDistinctAfterPersistence() {
        var integer = snapshot(BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.strings(), "\"1\"", "1");
        var decimal = snapshot(BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.strings(), "\"1\"", "1.0");
        var first = roundtrip(integer); var second = roundtrip(decimal);
        assertThat(first.intent().at("/parameters/amount").isIntegralNumber()).isTrue();
        assertThat(second.intent().at("/parameters/amount").isBigDecimal()).isTrue();
        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
    }
    @Test void programmaticDecimalLimitsSurviveWithoutTransportLexicalCoercion() {
        for (var number : new BigDecimal[]{ new BigDecimal("1e-256"), new BigDecimal("1e256"),
                new BigDecimal("10e256"), new BigDecimal("1"+"0".repeat(255)).scaleByPowerOfTen(256),
                new BigDecimal("9".repeat(256)).scaleByPowerOfTen(256), new BigDecimal("0."+"9".repeat(256)) }) {
            var parameters = JsonNodeFactory.instance.objectNode().put("amount", number);
            var request = new BulkCommandEvaluationRequest<JsonNode,String,JsonNode>(BulkExecutionMode.SYNC,
                    new BulkSelection<>(BulkSelectionMode.EXPLICIT, java.util.List.of(new BulkTarget<>("1", "v")), null, null), parameters);
            roundtrip(BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request, JsonNode::deepCopy, JsonNode::deepCopy));
        }
    }
    @Test void transportDecimalsBeyondDoubleRangeRemainExactInEveryModality() {
        String number = "9".repeat(200)+"e200";
        for (var mode : BulkMode.values()) {
            var restored = roundtrip(snapshot(mode, BulkIdentityCodecs.strings(), "\"1\"", number));
            String pointer = switch (mode) {
                case DOMAIN_COMMAND -> "/parameters/amount";
                case UNIFORM_UPDATE -> "/changes/0/value";
                case PER_ITEM_UPDATE -> "/items/0/changes/0/value";
            };
            assertThat(restored.intent().at(pointer).decimalValue()).isEqualByComparingTo(new BigDecimal(number));
        }
    }
    @Test void corruptUnknownDuplicateAndTrailingContentIsSanitized() {
        var snapshot = proposal().snapshot(); String encoded = new String(BulkSnapshotStorageCodec.encode(snapshot), StandardCharsets.UTF_8);
        for (var corrupt : new String[]{ encoded+" {}", encoded.replace("\"namespaceId\":", "\"namespaceId\":\"secret\",\"namespaceId\":"),
                encoded.replace("\"codecId\":\"string\"", "\"codecId\":\"custom\""), encoded.replace("protected-customer-value", "changed-secret"),
                encoded.replace("\"mode\":\"DOMAIN_COMMAND\"", "\"mode\":\"UNKNOWN\""), "null", "{}" }) {
            assertThatThrownBy(() -> BulkSnapshotStorageCodec.decode(bytes(corrupt), snapshot.fingerprint()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid protected proposal content").hasNoCause();
        }
    }
    @Test void snapshotAccessorsAndStorageDocumentCannotMutateOriginal() {
        var original = proposal().snapshot(); String fingerprint = original.fingerprint();
        ((ObjectNode) original.storageDocument().at("/intent/parameters")).put("reason", "changed");
        assertThat(original.intent().at("/parameters/reason").asText()).isEqualTo("protected-customer-value");
        assertThat(original.fingerprint()).isEqualTo(fingerprint);
        assertThat(original.toString()).doesNotContain("protected-customer-value");
    }
    @Test void escapedJsonCannotExceedStorageByteBudget() {
        var parameters = JsonNodeFactory.instance.objectNode().put("reason", "\u0001".repeat(1500000));
        var request = new BulkCommandEvaluationRequest<JsonNode,String,JsonNode>(BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, java.util.List.of(new BulkTarget<>("1", "v")), null, null), parameters);
        var snapshot = BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request, JsonNode::deepCopy, JsonNode::deepCopy);
        assertThatThrownBy(() -> BulkSnapshotStorageCodec.encode(snapshot)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test void rejectsAsyncQueryAndInvalidValidityWindows() {
        var reader = new BulkProtocolReader<>(BulkIdentityCodecs.strings());
        for (String mode : new String[]{"SYNC", "ASYNC"}) {
            var query = BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), reader.<JsonNode, JsonNode>readCommand(bytes("{\"executionMode\":\""+mode+"\",\"selection\":{\"mode\":\"QUERY\",\"filter\":{}},\"parameters\":{}}"), JsonNode::deepCopy, JsonNode::deepCopy), JsonNode::deepCopy, JsonNode::deepCopy);
            assertThatThrownBy(() -> proposal(query)).isInstanceOf(IllegalArgumentException.class);
        }
        var value = proposal();
        assertThatThrownBy(() -> new BulkStoredProposal(value.id(), value.createdAt(), value.createdAt().plusNanos(500), value.snapshot())).isInstanceOf(IllegalArgumentException.class);
        var truncated = new BulkStoredProposal(value.id(), value.createdAt().plusNanos(1234), value.expiresAt().plusNanos(1234), value.snapshot());
        assertThat(truncated.createdAt().getNano()).isEqualTo(1000);
    }
    private BulkIntentSnapshot roundtrip(BulkIntentSnapshot snapshot) {
        var restored = BulkSnapshotStorageCodec.decode(BulkSnapshotStorageCodec.encode(snapshot), snapshot.fingerprint());
        assertThat(restored.fingerprint()).isEqualTo(snapshot.fingerprint());
        assertThat(restored.context()).isEqualTo(snapshot.context());
        assertThat(restored.mode()).isEqualTo(snapshot.mode());
        assertThat(restored.codecId()).isEqualTo(snapshot.codecId());
        // Jackson differentiates IntNode and BigIntegerNode; fingerprint binds mathematical integers.
        assertThat(BulkCanonicalJson.digest(restored.intent())).isEqualTo(BulkCanonicalJson.digest(snapshot.intent()));
        return restored;
    }
}
