package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkProtocolContractTest {

    @Test
    void readsUniformUpdateWithExactWireValuesAndExplicitSelection() {
        BulkUniformEvaluationRequest<String, JsonNode> request = reader().readUniform(bytes("""
                {
                  "executionMode": "SYNC",
                  "selection": {
                    "mode": "EXPLICIT",
                    "targets": [{"id": "101", "expectedVersion": "v1"}]
                  },
                  "changes": [
                    {"field": "enabled", "operator": "SET", "value": false},
                    {"field": "priority", "operator": "SET", "value": 0},
                    {"field": "comment", "operator": "SET", "value": ""},
                    {"field": "tags", "operator": "SET", "value": []},
                    {"field": "description", "operator": "CLEAR"}
                  ]
                }
                """));

        assertEquals(BulkExecutionMode.SYNC, request.executionMode());
        assertEquals(BulkSelectionMode.EXPLICIT, request.selection().mode());
        assertEquals("101", request.selection().targets().get(0).id());
        assertEquals("v1", request.selection().targets().get(0).expectedVersion());
        assertFalse(request.changes().get(0).value().asBoolean());
        assertEquals(0, request.changes().get(1).value().asInt());
        assertEquals("", request.changes().get(2).value().asText());
        assertTrue(request.changes().get(3).value().isArray());
        assertEquals(BulkChangeOperator.CLEAR, request.changes().get(4).operator());
        assertNull(request.changes().get(4).value());
    }

    @Test
    void readsCommandAndQuerySelectionThroughTypedDomainCallbacks() {
        BulkCommandEvaluationRequest<CommandParameters, String, FilterParameters> request = reader().readCommand(
                bytes("""
                        {
                          "executionMode": "ASYNC",
                          "selection": {
                            "mode": "QUERY",
                            "filter": {"status": "PENDING"},
                            "excludedIds": ["101"]
                          },
                          "parameters": {"reasonCode": "REBALANCE"}
                        }
                        """),
                parameters -> new CommandParameters(parameters.path("reasonCode").asText()),
                filter -> new FilterParameters(filter.path("status").asText())
        );

        assertEquals(BulkExecutionMode.ASYNC, request.executionMode());
        assertEquals(new CommandParameters("REBALANCE"), request.parameters());
        assertEquals(BulkSelectionMode.QUERY, request.selection().mode());
        assertEquals(new FilterParameters("PENDING"), request.selection().filter());
        assertEquals(java.util.List.of("101"), request.selection().excludedIds());
    }

    @Test
    void readsPerItemDeltasAsTheirOwnModalRequest() {
        BulkItemEvaluationRequest<String> request = reader().readItems(bytes("""
                {
                  "executionMode": "SYNC",
                  "items": [
                    {
                      "id": "101",
                      "expectedVersion": "v1",
                      "changes": [{"field": "title", "operator": "SET", "value": "new title"}]
                    },
                    {
                      "id": "102",
                      "expectedVersion": "v2",
                      "changes": [{"field": "description", "operator": "CLEAR"}]
                    }
                  ]
                }
                """));

        assertEquals(BulkExecutionMode.SYNC, request.executionMode());
        assertEquals(2, request.items().size());
        assertEquals("101", request.items().get(0).id());
        assertEquals("new title", request.items().get(0).changes().get(0).value().asText());
        assertEquals(BulkChangeOperator.CLEAR, request.items().get(1).changes().get(0).operator());
    }

    @Test
    void confirmationAcceptsOnlyNonblankProposalId() {
        assertEquals("proposal-123", reader().readConfirmation(bytes("""
                {"proposalId":"proposal-123"}
                """)).proposalId());

        assertRejected(() -> reader().readConfirmation(bytes("{}")));
        assertRejected(() -> reader().readConfirmation(bytes("{\"proposalId\":null}")));
        assertRejected(() -> reader().readConfirmation(bytes("{\"proposalId\":\" \"}")));
        assertRejected(() -> reader().readConfirmation(bytes("{\"proposalId\":\"p\",\"targets\":[]}")));
        assertRejected(() -> reader().readConfirmation(bytes("{\"proposalId\":\"p\",\"proposalId\":\"other\"}")));
        assertRejected(() -> reader().readConfirmation(bytes("{\"proposalId\":\"p\"} {}")));
    }

    @Test
    void rejectsUnknownAndDuplicatePropertiesAtEveryTransportLevel() {
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true}],"extra":true}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","mode":"QUERY","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}],"unexpected":true},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","id":"2","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v","unexpected":true}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","field":"y","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true,"unexpected":true}]}
                """)));
        assertRejected(() -> reader().readCommand(
                bytes("""
                        {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"reason":"A","reason":"B"}}
                        """),
                JsonNode::deepCopy,
                JsonNode::deepCopy
        ));
        assertRejected(() -> reader().readItems(bytes("""
                {"executionMode":"SYNC","items":[{"id":"1","expectedVersion":"v","changes":[{"field":"x","operator":"SET","value":true}],"extra":true}]}
                """)));
    }

    @Test
    void rejectsMissingNullEmptyAndWrongTypedTransportValues() {
        assertRejected(() -> reader().readUniform(bytes("{}")));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":null,"selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":""}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":1,"expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"01","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"9223372036854775808","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET"}]}
                """)));
    }

    @Test
    void distinguishesClearFromAnExplicitNullSetValue() {
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"CLEAR","value":null}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":null}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"CLEAR","value":false}]}
                """)));
    }

    @Test
    void selectionFormsAreExclusiveAndIdentitiesAreUnique() {
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}],"filter":{}},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{},"targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v1"},{"id":"1","expectedVersion":"v2"}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{},"excludedIds":["1","1"]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> reader().readItems(bytes("""
                {"executionMode":"SYNC","items":[
                  {"id":"1","expectedVersion":"v1","changes":[{"field":"x","operator":"SET","value":true}]},
                  {"id":"1","expectedVersion":"v2","changes":[{"field":"x","operator":"SET","value":false}]}
                ]}
                """)));
    }

    @Test
    void commandParametersCannotContainTransportBindingsAndReaderIsNotCalled() {
        for (String protectedBinding : java.util.List.of(
                "proposalId", "selection", "targets", "items", "executionMode", "atomicity",
                "changes", "filter", "excludedIds", "expectedVersion", "id", "mode")) {
            AtomicBoolean parametersReaderCalled = new AtomicBoolean();
            String json = """
                    {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{%s:true}}
                    """.formatted(quote(protectedBinding));

            assertRejected(() -> reader().readCommand(
                    bytes(json),
                    node -> {
                        parametersReaderCalled.set(true);
                        return node;
                    },
                    JsonNode::deepCopy
            ));
            assertFalse(parametersReaderCalled.get(), "parameter reader ran for " + protectedBinding);
        }
    }

    @Test
    void rejectsRequestByteDepthCollectionAndNumericBounds() {
        BulkProtocolReader<String, Long> byteLimited = reader(new BulkProtocolLimits(32, 16, 50, 10_000, 10_000));
        assertRejected(() -> byteLimited.readConfirmation(bytes("{\"proposalId\":\"more-than-thirty-two-bytes\"}")));

        BulkProtocolReader<String, Long> depthLimited = reader(new BulkProtocolLimits(1_024, 3, 50, 10_000, 10_000));
        assertRejected(() -> depthLimited.readCommand(
                bytes("""
                        {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{"nested":{"status":"PENDING"}}},"parameters":{"reason":"x"}}
                        """),
                JsonNode::deepCopy,
                JsonNode::deepCopy
        ));

        BulkProtocolReader<String, Long> countLimited = reader(new BulkProtocolLimits(1_024, 16, 1, 1, 1));
        assertRejected(() -> countLimited.readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"},{"id":"2","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> countLimited.readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{},"excludedIds":["1","2"]},"changes":[{"field":"x","operator":"SET","value":true}]}
                """)));
        assertRejected(() -> countLimited.readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":true},{"field":"y","operator":"SET","value":false}]}
                """)));

        String hugeNumber = "9".repeat(257);
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":%s}]}
                """.formatted(hugeNumber))));
        assertRejected(() -> reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"SET","value":1e257}]}
                """)));
    }

    @Test
    void completeRequestsRoundTripWithoutLosingWireIdentityOrIntent() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var uniform = reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"9007199254740993","expectedVersion":"v"}]},"changes":[{"field":"x","operator":"CLEAR"},{"field":"y","operator":"SET","value":false}]}
                """));
        assertEquals(uniform, reader().readUniform(mapper.writeValueAsBytes(uniform)));
        var command = reader().readCommand(bytes("""
                {"executionMode":"ASYNC","selection":{"mode":"QUERY","filter":{"status":"PENDING"}},"parameters":{"reason":"BATCH"}}
                """), JsonNode::deepCopy, JsonNode::deepCopy);
        assertEquals(command, reader().readCommand(mapper.writeValueAsBytes(command), JsonNode::deepCopy, JsonNode::deepCopy));
        var items = reader().readItems(bytes("""
                {"executionMode":"SYNC","items":[{"id":"1","expectedVersion":"v","changes":[{"field":"x","operator":"SET","value":[]}]}]}
                """));
        assertEquals(items, reader().readItems(mapper.writeValueAsBytes(items)));
        var confirmation = new BulkConfirmationRequest("proposal");
        assertEquals(confirmation, reader().readConfirmation(mapper.writeValueAsBytes(confirmation)));
    }

    @Test
    void decimalsRemainExactAndParserErrorsDoNotEchoPayload() {
        var request = reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"changes":[{"field":"amount","operator":"SET","value":12345678901234567890.1234567890123456789}]}
                """));
        assertEquals(new java.math.BigDecimal("12345678901234567890.1234567890123456789"), request.changes().getFirst().value().decimalValue());
        var error = assertThrows(IllegalArgumentException.class,
                () -> reader().readConfirmation(bytes("{\"proposalId\":\"secret-value\", trailing}")));
        assertFalse(error.getMessage().contains("secret"));
        assertNull(error.getCause());
    }

    @Test
    void decimalScaleIsValidatedBeforeTrailingZeroNormalization() {
        for (String invalid : java.util.List.of("0e-257", "0e257", "1.000e-257")) {
            assertRejected(() -> reader().readUniform(bytes("""
                    {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"changes":[{"field":"x","operator":"SET","value":%s}]}
                    """.formatted(invalid))));
        }
        var request = reader().readUniform(bytes("""
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"changes":[{"field":"x","operator":"SET","value":0e-256}]}
                """));
        assertEquals(256, request.changes().getFirst().value().decimalValue().scale());
    }

    private static BulkProtocolReader<String, Long> reader() {
        return reader(BulkProtocolLimits.defaults());
    }

    private static BulkProtocolReader<String, Long> reader(BulkProtocolLimits limits) {
        return new BulkProtocolReader<>(BulkIdentityCodecs.longs(), limits);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return '"' + value + '"';
    }

    private static void assertRejected(ThrowingRunnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    private record CommandParameters(String reasonCode) {
    }

    private record FilterParameters(String status) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
