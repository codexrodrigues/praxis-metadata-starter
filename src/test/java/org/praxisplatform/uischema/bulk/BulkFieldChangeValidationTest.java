package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkFieldChangeValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void setPreservesFalseZeroEmptyStringAndEmptyArray() {
        ObjectNode candidate = BulkFieldChanges.applyTo(
                mapper.createObjectNode(),
                List.of(
                        BulkFieldChange.set("enabled", mapper.getNodeFactory().booleanNode(false)),
                        BulkFieldChange.set("priority", mapper.getNodeFactory().numberNode(0)),
                        BulkFieldChange.set("comment", mapper.getNodeFactory().textNode("")),
                        BulkFieldChange.set("tags", mapper.createArrayNode())
                ),
                Set.of("enabled", "priority", "comment", "tags"),
                Set.of()
        );

        assertFalse(candidate.path("enabled").asBoolean());
        assertEquals(0, candidate.path("priority").asInt());
        assertEquals("", candidate.path("comment").asText());
        assertTrue(candidate.path("tags").isArray());
        assertEquals(0, candidate.path("tags").size());
    }

    @Test
    void setRejectsMissingAndNullJsonValuesWhileClearRequiresJavaNull() {
        assertThrows(IllegalArgumentException.class, () -> BulkFieldChange.set("status", null));
        assertThrows(IllegalArgumentException.class, () -> BulkFieldChange.set("status", NullNode.getInstance()));
        assertThrows(IllegalArgumentException.class, () -> BulkFieldChange.set("status", MissingNode.getInstance()));
        assertThrows(IllegalArgumentException.class,
                () -> new BulkFieldChange("status", BulkChangeOperator.CLEAR, NullNode.getInstance()));
        assertThrows(IllegalArgumentException.class,
                () -> new BulkFieldChange("status", BulkChangeOperator.CLEAR, MissingNode.getInstance()));

        BulkFieldChange clear = BulkFieldChange.clear("status");

        assertEquals(BulkChangeOperator.CLEAR, clear.operator());
        assertNull(clear.value());
    }

    @Test
    void changeDefensivelyCopiesIncomingAndOutgoingJson() {
        ObjectNode incoming = mapper.createObjectNode();
        incoming.putObject("metadata").put("active", true);
        BulkFieldChange change = BulkFieldChange.set("profile", incoming);

        ((ObjectNode) incoming.path("metadata")).put("active", false);
        ObjectNode firstRead = (ObjectNode) change.value();
        ((ObjectNode) firstRead.path("metadata")).put("active", false);
        ObjectNode secondRead = (ObjectNode) change.value();

        assertNotSame(incoming, firstRead);
        assertTrue(secondRead.path("metadata").path("active").asBoolean());
    }

    @Test
    void validateRejectsEmptyUnknownReadOnlyAndDuplicateFields() {
        Set<String> writable = Set.of("title", "description");
        Set<String> clearable = Set.of("description");

        assertThrows(IllegalArgumentException.class, () -> BulkFieldChanges.validate(null, writable, clearable));
        assertThrows(IllegalArgumentException.class, () -> BulkFieldChanges.validate(List.of(), writable, clearable));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChanges.validate(List.of(BulkFieldChange.set("id", mapper.getNodeFactory().numberNode(7))), writable, clearable));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChanges.validate(List.of(BulkFieldChange.set("version", mapper.getNodeFactory().numberNode(3))), writable, clearable));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChanges.validate(List.of(BulkFieldChange.set("workflowState", mapper.getNodeFactory().textNode("APPROVED"))), writable, clearable));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChanges.validate(
                        List.of(BulkFieldChange.set("title", mapper.getNodeFactory().textNode("A")),
                                BulkFieldChange.clear("title")),
                        writable,
                        clearable
                ));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChanges.validate(List.of(BulkFieldChange.clear("title")), writable, clearable));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChanges.validate(
                        List.of(BulkFieldChange.set("title", mapper.getNodeFactory().textNode("A"))),
                        writable,
                        Set.of("unknown")
                ));
    }

    @Test
    void applyReturnsCandidateWithSetClearAndOmissionPreserved() throws Exception {
        ObjectNode current = (ObjectNode) mapper.readTree("""
                {"title":"before","retained":"still-here","nested":{"keep":"original"}}
                """);
        ArrayNode tags = mapper.createArrayNode().add("one");

        ObjectNode candidate = BulkFieldChanges.applyTo(
                current,
                List.of(
                        BulkFieldChange.set("title", mapper.getNodeFactory().textNode("after")),
                        BulkFieldChange.set("tags", tags),
                        BulkFieldChange.clear("description")
                ),
                Set.of("title", "tags", "description"),
                Set.of("description")
        );

        assertEquals("before", current.path("title").asText());
        assertFalse(current.has("description"));
        assertEquals("still-here", candidate.path("retained").asText());
        assertEquals("after", candidate.path("title").asText());
        assertEquals("one", candidate.path("tags").get(0).asText());
        assertTrue(candidate.path("description").isNull());

        ((ObjectNode) candidate.path("nested")).put("keep", "candidate-only");
        assertEquals("original", current.path("nested").path("keep").asText());
    }

    @Test
    void dottedFieldIsAppliedAsAnAllowlistedLiteralRootKey() throws Exception {
        ObjectNode current = (ObjectNode) mapper.readTree("""
                {"perfil":{"nome":"original"}}
                """);

        ObjectNode candidate = BulkFieldChanges.applyTo(
                current,
                List.of(BulkFieldChange.set("perfil.nome", mapper.getNodeFactory().textNode("literal"))),
                Set.of("perfil.nome"),
                Set.of()
        );

        assertEquals("original", candidate.path("perfil").path("nome").asText());
        assertEquals("literal", candidate.path("perfil.nome").asText());
    }

    @Test
    void applyUsesDefensiveCopiesForSetValues() {
        ArrayNode tags = mapper.createArrayNode().add("one");
        BulkFieldChange change = BulkFieldChange.set("tags", tags);
        ObjectNode current = mapper.createObjectNode();

        ObjectNode candidate = BulkFieldChanges.applyTo(current, List.of(change), Set.of("tags"), Set.of());
        ((ArrayNode) candidate.path("tags")).add("two");

        JsonNode stored = change.value();
        assertEquals(1, stored.size());
        assertEquals(0, current.size());
    }

    @Test
    void programmaticChangesRejectOpaqueNonFiniteAndCyclicValues() {
        ObjectNode opaque = mapper.createObjectNode().putPOJO("data", new StringBuilder("secret"));
        assertThrows(IllegalArgumentException.class, () -> BulkFieldChange.set("x", opaque));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChange.set("x", mapper.getNodeFactory().numberNode(Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChange.set("x", mapper.getNodeFactory().numberNode(9007199254740993d)));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChange.set("x", mapper.getNodeFactory().numberNode(0.1f)));
        var exact = new java.math.BigDecimal("9007199254740993.123456789");
        assertEquals(exact, BulkFieldChange.set("x", mapper.getNodeFactory().numberNode(exact)).value().decimalValue());
        ObjectNode cyclic = mapper.createObjectNode();
        cyclic.set("self", cyclic);
        assertThrows(IllegalArgumentException.class, () -> BulkFieldChange.set("x", cyclic));
        assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChanges.applyTo(opaque, List.of(BulkFieldChange.clear("x")), Set.of("x"), Set.of("x")));
    }

    @Test
    void diagnosticsDoNotEchoBusinessPayloadOrUntrustedFieldNames() {
        BulkFieldChange change = BulkFieldChange.set("secret-field", mapper.getNodeFactory().textNode("secret-value"));
        assertFalse(change.toString().contains("secret"));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> BulkFieldChanges.validate(List.of(change), Set.of("allowed"), Set.of()));
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test
    void clearSerializationOmitsValue() throws Exception {
        JsonNode serialized = mapper.readTree(mapper.writeValueAsString(BulkFieldChange.clear("description")));

        assertEquals("description", serialized.path("field").asText());
        assertEquals("CLEAR", serialized.path("operator").asText());
        assertFalse(serialized.has("value"));
    }
}
