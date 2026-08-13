package org.praxisplatform.uischema.determination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveDeterminationSpecContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void operationExamplesRespectTheClosedReactiveDeterminationSchema() throws Exception {
        JsonNode schemaDocument = mapper.readTree(Path.of("docs/spec/x-ui-operation.schema.json").toFile());
        // x-ui.analytics possui contrato e suite proprios. Esta suite isola o bloco novo e evita
        // resolver o $id publico durante testes offline.
        ((com.fasterxml.jackson.databind.node.ObjectNode) schemaDocument.path("properties"))
                .remove("analytics");
        JsonNode valid = mapper.readTree(Path.of("docs/spec/examples/x-ui-operation.valid.json").toFile());
        JsonNode invalid = mapper.readTree(Path.of("docs/spec/examples/x-ui-operation.invalid.json").toFile());
        JsonSchema schema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaDocument);

        assertTrue(schema.validate(valid).isEmpty());
        assertFalse(schema.validate(invalid).isEmpty());
    }

    @Test
    void capabilityAndProvenanceCannotCarryAuthorableOrContextualExecutionDetails() throws Exception {
        JsonNode schema = mapper.readTree(Path.of("docs/spec/x-ui-operation.schema.json").toFile());
        JsonNode capability = schema.path("$defs").path("reactiveDeterminationCapability");
        JsonNode provenance = schema.path("$defs").path("reactiveDeterminationProvenance");

        assertFalse(capability.path("additionalProperties").asBoolean(true));
        assertEquals(
                Set.of("operationId", "method", "href", "requestSchemaUrl", "responseSchemaUrl"),
                mapper.convertValue(
                        capability.path("required"),
                        mapper.getTypeFactory().constructCollectionType(Set.class, String.class)
                )
        );
        assertFalse(capability.path("properties").has("path"));
        assertFalse(capability.path("properties").has("headers"));
        assertFalse(capability.path("properties").has("callback"));
        assertFalse(provenance.path("properties").has("tenant"));
        assertFalse(provenance.path("properties").has("user"));
        assertFalse(provenance.path("properties").has("decisionId"));
    }
}
