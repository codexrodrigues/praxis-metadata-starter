package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void eachConcreteIdentityBindingAcceptsOnlyItsOwnWireRepresentation() throws Exception {
        assertValid("IntegerUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":7,"expectedVersion":"v"}]},"changes":[{"field":"enabled","operator":"SET","value":false}]}
                """);
        assertValid("LongUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"9007199254740993","expectedVersion":"v"}]},"changes":[{"field":"enabled","operator":"CLEAR"}]}
                """);
        assertValid("StringUniformEvaluationRequest", """
                {"executionMode":"ASYNC","selection":{"mode":"QUERY","filter":{"state":"PENDING"},"excludedIds":["001"]},"changes":[{"field":"comment","operator":"SET","value":""}]}
                """);
        assertValid("UuidUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"123e4567-e89b-12d3-a456-426614174000","expectedVersion":"v"}]},"changes":[{"field":"name","operator":"SET","value":"after"}]}
                """);
        assertValid("IntegerCommandEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":7,"expectedVersion":"v"}]},"parameters":{"reason":"BATCH"}}
                """);
        assertValid("LongCommandEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"nested":{"proposalId":"domain-value","executionMode":"domain-value"}}}
                """);
        assertValid("StringCommandEvaluationRequest", """
                {"executionMode":"ASYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"001","expectedVersion":"v"}]},"parameters":{"reason":"BATCH"}}
                """);
        assertValid("UuidCommandEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"123e4567-e89b-12d3-a456-426614174000","expectedVersion":"v"}]},"parameters":{}}
                """);
        assertValid("IntegerItemEvaluationRequest", """
                {"executionMode":"SYNC","items":[{"id":7,"expectedVersion":"v","changes":[{"field":"name","operator":"CLEAR"}]}]}
                """);
        assertValid("LongItemEvaluationRequest", """
                {"executionMode":"SYNC","items":[{"id":"9007199254740993","expectedVersion":"v","changes":[{"field":"name","operator":"CLEAR"}]}]}
                """);
        assertValid("StringItemEvaluationRequest", """
                {"executionMode":"SYNC","items":[{"id":"001","expectedVersion":"v","changes":[{"field":"name","operator":"CLEAR"}]}]}
                """);
        assertValid("UuidItemEvaluationRequest", """
                {"executionMode":"SYNC","items":[{"id":"123e4567-e89b-12d3-a456-426614174000","expectedVersion":"v","changes":[{"field":"name","operator":"CLEAR"}]}]}
                """);

        assertInvalid("LongUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":9007199254740993,"expectedVersion":"v"}]},"changes":[{"field":"enabled","operator":"CLEAR"}]}
                """);
        assertInvalid("IntegerUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"7","expectedVersion":"v"}]},"changes":[{"field":"enabled","operator":"CLEAR"}]}
                """);
        assertInvalid("UuidUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"123E4567-E89B-12D3-A456-426614174000","expectedVersion":"v"}]},"changes":[{"field":"enabled","operator":"CLEAR"}]}
                """);
    }

    @Test
    void requestEnvelopesRejectAmbiguousChangesUnknownPropertiesAndInvalidLimits() throws Exception {
        assertInvalid("LongUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}],"filter":{}},"changes":[{"field":"enabled","operator":"CLEAR"}]}
                """);
        assertInvalid("LongUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{},"targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"enabled","operator":"CLEAR"}]}
                """);
        assertInvalid("LongUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"   "}]},"changes":[{"field":"enabled","operator":"CLEAR"}]}
                """);
        assertInvalid("LongUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"enabled","operator":"CLEAR","value":null}]}
                """);
        assertInvalid("LongUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"enabled","operator":"SET","value":null}]}
                """);
        assertInvalid("LongUniformEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"1","expectedVersion":"v"}]},"changes":[{"field":"enabled","operator":"CLEAR"}],"unexpected":true}
                """);
        assertInvalid("LongCommandEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"proposalId":"protected"}}
                """);
        assertInvalid("LongCommandEvaluationRequest", """
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{}},"parameters":{"executionMode":"protected"}}
                """);
        assertInvalid("BulkConfirmationRequest", "{\"proposalId\":\"  \"}");

        JsonNode schema = schemaDocument();
        assertEquals(50, schema.path("$defs").path("FieldChanges").path("maxItems").asInt());
        assertEquals(10000, schema.path("$defs").path("LongSelection").path("oneOf").path(0)
                .path("properties").path("targets").path("maxItems").asInt());
        assertEquals(20, schema.path("$defs").path("LongIdentity").path("maxLength").asInt());
    }

    @Test
    void responseDtosRoundTripAndMatchTheirClosedPublicSchemas() throws Exception {
        ResourceCommandMessage diagnostic = new ResourceCommandMessage(
                ResourceCommandErrorCategory.VALIDATION, "RULE_BLOCKED", "A safe message", "reason", Map.of());
        CanonicalOperationRef operation = new CanonicalOperationRef(null, "bulk-update", "/records/bulk/update", "POST");
        BulkProposal proposal = new BulkProposal(
                "proposal-1", operation, BulkMode.UNIFORM_UPDATE, BulkExecutionMode.SYNC,
                ActionCollectionAtomicity.PER_ITEM, BulkProposalStatus.READY,
                Instant.parse("2026-09-13T12:00:00Z"), Instant.parse("2026-09-13T12:15:00Z"),
                new BulkProposalTotals(2, 2, 2, 0), objectMapper.readTree("{\"amount\":12345678901234567890.123400}"),
                List.of(), List.of(new BulkEvidenceReference(BulkEvidenceKind.POLICY, "policy-1", "r1", "sha256:abc")));
        BulkExecution execution = new BulkExecution(
                "execution-1", "proposal-1", operation, BulkMode.UNIFORM_UPDATE, BulkExecutionMode.SYNC,
                ActionCollectionAtomicity.PER_ITEM, BulkExecutionStatus.COMPLETED_WITH_ERRORS,
                Instant.parse("2026-09-13T12:01:00Z"), Instant.parse("2026-09-13T12:02:00Z"),
                Instant.parse("2026-09-13T12:02:00Z"),
                new BulkExecutionTotals(2, 0, 1, 0, 1, 0, 0, 0, 0), List.of(diagnostic));
        BulkItemResult<String> item = new BulkItemResult<>("001", BulkItemStatus.DENIED, List.of(diagnostic));

        assertRoundTripAndSchema("BulkProposal", proposal, BulkProposal.class);
        assertRoundTripAndSchema("BulkExecution", execution, BulkExecution.class);
        assertRoundTripAndSchema("StringBulkItemResult", item, BulkItemResult.class);
        assertEquals(new BigDecimal("12345678901234567890.123400"), proposal.redactedIntent().path("amount").decimalValue());

        ObjectNode operationWithoutGroup = (ObjectNode) objectMapper.readTree("""
                {"operationId":"bulk-update","path":"/records/bulk/update","method":"POST"}
                """);
        assertTrue(schemaFor("CanonicalOperationRef").validate(operationWithoutGroup).isEmpty());

        ObjectNode invalidDiagnostic = (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsBytes(item));
        ((ObjectNode) invalidDiagnostic.path("diagnostics").path(0).path("metadata")).put("untrusted", "value");
        assertFalse(schemaFor("StringBulkItemResult").validate(invalidDiagnostic).isEmpty());

        ObjectNode invalidCategory = (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsBytes(item));
        ((ObjectNode) invalidCategory.path("diagnostics").path(0)).put("category", "UNTRUSTED");
        assertFalse(schemaFor("StringBulkItemResult").validate(invalidCategory).isEmpty());
    }

    private <T> void assertRoundTripAndSchema(String definition, T value, Class<?> type) throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(value);
        assertEquals(value, objectMapper.readValue(json, type));
        assertTrue(schemaFor(definition).validate(objectMapper.readTree(json)).isEmpty());
    }

    private void assertValid(String definition, String json) throws Exception {
        assertTrue(schemaFor(definition).validate(objectMapper.readTree(json)).isEmpty());
    }

    private void assertInvalid(String definition, String json) throws Exception {
        assertFalse(schemaFor(definition).validate(objectMapper.readTree(json)).isEmpty());
    }

    private JsonSchema schemaFor(String definition) throws Exception {
        ObjectNode binding = objectMapper.createObjectNode();
        binding.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        binding.set("$defs", schemaDocument().path("$defs"));
        binding.put("$ref", "#/$defs/" + definition);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(binding);
    }

    private JsonNode schemaDocument() throws Exception {
        return objectMapper.readTree(Path.of("docs/spec/bulk-protocol.schema.json").toFile());
    }
}
