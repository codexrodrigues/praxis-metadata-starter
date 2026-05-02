package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionSourceSchemaParityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void optionSourceSchemaShouldPublishDependencyFilterMapContract() throws Exception {
        JsonNode schema = readProjectJson("docs/spec/x-ui-field.schema.json");
        JsonNode optionSource = schema.path("definitions").path("optionSource");
        JsonNode properties = optionSource.path("properties");
        JsonNode dependencyFilterMap = properties.path("dependencyFilterMap");

        assertEquals(false, optionSource.path("additionalProperties").asBoolean(true),
                "optionSource schema should stay closed to undocumented keys");
        assertTrue(properties.has("dependsOn"), "optionSource schema should expose dependsOn");
        assertTrue(properties.has("dependencyFilterMap"), "optionSource schema should expose dependencyFilterMap");
        assertEquals("\\S", properties.path("dependsOn").path("items").path("pattern").asText());
        assertEquals("object", dependencyFilterMap.path("type").asText());
        assertEquals("string", dependencyFilterMap.path("additionalProperties").path("type").asText());
        assertEquals(1, dependencyFilterMap.path("additionalProperties").path("minLength").asInt());
        assertEquals("\\S", dependencyFilterMap.path("additionalProperties").path("pattern").asText());
    }

    @Test
    void optionSourceSchemaShouldPublishRichFilteringContract() throws Exception {
        JsonNode schema = readProjectJson("docs/spec/x-ui-field.schema.json");
        JsonNode optionSource = schema.path("definitions").path("optionSource");
        JsonNode filtering = optionSource.path("properties").path("filtering");
        JsonNode filteringDefinition = schema.path("definitions").path("lookupFiltering");
        JsonNode filterDefinition = schema.path("definitions").path("lookupFilterDefinition");
        JsonNode sortDefinition = schema.path("definitions").path("lookupSortOption");

        assertTrue(optionSource.path("properties").has("filtering"),
                "optionSource schema should expose filtering for entity lookups");
        assertEquals("#/definitions/lookupFiltering", filtering.path("$ref").asText());
        assertEquals("object", filteringDefinition.path("type").asText());
        assertEquals("#/definitions/lookupFilterDefinition",
                filteringDefinition.path("properties").path("availableFilters").path("items").path("$ref").asText());
        assertEquals("#/definitions/lookupSortOption",
                filteringDefinition.path("properties").path("sortOptions").path("items").path("$ref").asText());
        assertEquals("array", filterDefinition.path("properties").path("operators").path("type").asText());
        assertEquals("asc", sortDefinition.path("properties").path("direction").path("default").asText());
    }

    @Test
    void resourceEntityOptionSourceExampleShouldOnlyUseDocumentedOptionSourceKeys() throws Exception {
        JsonNode schema = readProjectJson("docs/spec/x-ui-field.schema.json");
        JsonNode example = readProjectJson("docs/spec/examples/x-ui-field-option-source-resource.valid.json");
        JsonNode documentedProperties = schema.path("definitions").path("optionSource").path("properties");
        JsonNode optionSource = example.path("optionSource");

        assertFalse(optionSource.path("dependencyFilterMap").isMissingNode(),
                "resource entity example should include dependencyFilterMap");
        assertEquals("tenant.id", optionSource.path("dependencyFilterMap").path("tenantId").asText());
        assertEquals("contains", optionSource.path("filtering").path("availableFilters").get(1).path("defaultOperator").asText());
        assertEquals("company-status", optionSource.path("filtering").path("availableFilters").get(0).path("optionsSource").asText());
        assertEquals("legalNameAsc", optionSource.path("filtering").path("defaultSort").asText());

        Iterator<String> fieldNames = optionSource.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            assertTrue(documentedProperties.has(fieldName),
                    "example optionSource key should be declared in schema: " + fieldName);
        }
    }

    private JsonNode readProjectJson(String relativePath) throws Exception {
        Path path = resolveProjectFile(relativePath);
        assertTrue(Files.exists(path), relativePath + " should exist");
        return objectMapper.readTree(Files.readString(path));
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
