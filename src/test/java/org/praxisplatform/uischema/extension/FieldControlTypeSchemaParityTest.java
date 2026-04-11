package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldControlTypeSchemaParityTest {

    @Test
    void controlTypeSchemaShouldStayAlignedWithJavaEnum() throws Exception {
        Path schemaPath = resolveProjectFile("docs/spec/x-ui-field.schema.json");
        assertTrue(Files.exists(schemaPath), "x-ui field schema should exist");

        JsonNode root = new ObjectMapper().readTree(Files.readString(schemaPath));
        JsonNode controlTypeSchema = root.path("properties").path("controlType");
        JsonNode controlTypeEnum = controlTypeSchema.path("enum");
        assertTrue(controlTypeEnum.isArray(), "controlType enum should be declared as array in schema");

        Set<String> schemaValues = new LinkedHashSet<>();
        controlTypeEnum.forEach(node -> schemaValues.add(node.asText()));

        Set<String> javaValues = new LinkedHashSet<>();
        for (FieldControlType javaControlType : FieldControlType.values()) {
            javaValues.add(javaControlType.getValue());
        }

        assertEquals(
                javaValues,
                schemaValues,
                "docs/spec/x-ui-field.schema.json must stay in sync with FieldControlType"
        );

        assertTrue(
                controlTypeSchema.path("description").asText().contains("paridade dinâmica suportada no Angular"),
                "schema should document that the starter only publishes Angular-supported dynamic control types"
        );
        assertIterableEquals(
                List.of("inline"),
                readArray(controlTypeSchema.path("x-canonical-control-type-prefixes")),
                "schema should expose the canonical control type prefixes for machine consumers"
        );
        assertIterableEquals(
                List.of(),
                readArray(controlTypeSchema.path("x-compatibility-control-types")),
                "schema should expose an empty compatibility list after removing unsupported control types"
        );
    }

    private static List<String> readArray(JsonNode node) {
        assertTrue(node.isArray(), "schema metadata node should be an array");
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static Path resolveProjectFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.exists(direct)) {
                return direct;
            }

            Path nestedModule = current.resolve("praxis-metadata-starter").resolve(relativePath);
            if (Files.exists(nestedModule)) {
                return nestedModule;
            }

            current = current.getParent();
        }

        throw new IllegalStateException("Unable to locate project file: " + relativePath);
    }
}
