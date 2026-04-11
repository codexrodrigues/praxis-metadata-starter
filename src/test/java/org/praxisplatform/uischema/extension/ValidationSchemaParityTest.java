package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationSchemaParityTest {

    @Test
    void fieldSchemaShouldExposeCanonicalValidationProperties() throws Exception {
        Path schemaPath = resolveProjectFile("docs/spec/x-ui-field.schema.json");
        assertTrue(Files.exists(schemaPath), "x-ui field schema should exist");

        JsonNode root = new ObjectMapper().readTree(Files.readString(schemaPath));
        JsonNode properties = root.path("properties");
        JsonNode validation = properties.path("validation").path("properties");

        List<String> topLevelValidationKeys = List.of(
                "required",
                "requiredMessage",
                "minLength",
                "minLengthMessage",
                "maxLength",
                "maxLengthMessage",
                "min",
                "minMessage",
                "max",
                "maxMessage",
                "range",
                "rangeMessage",
                "pattern",
                "patternMessage",
                "customValidator",
                "asyncValidator",
                "minWords",
                "allowedFileTypes",
                "fileTypeMessage",
                "maxFileSize",
                "validationTrigger",
                "validationTriggers",
                "validationDebounce",
                "showInlineErrors",
                "errorPosition"
        );

        for (String key : topLevelValidationKeys) {
            assertTrue(properties.has(key), "schema should expose top-level validation property: " + key);
        }

        List<String> mirroredValidationKeys = List.of(
                "required",
                "minLength",
                "maxLength",
                "min",
                "max",
                "pattern",
                "patternMessage",
                "rangeMessage"
        );

        for (String key : mirroredValidationKeys) {
            assertTrue(validation.has(key), "validation block should mirror key: " + key);
        }

        assertEquals(
                false,
                root.path("properties").path("validation").path("additionalProperties").asBoolean(true),
                "validation block should stay closed to undocumented keys"
        );
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
