package org.praxisplatform.uischema.customfields;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.filter.annotation.Filterable;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomFieldSpecContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishedExampleMatchesActualProjectionAndFieldSpec() throws Exception {
        CustomFieldDescriptor descriptor = new CustomFieldDescriptor("numero_calcado", "Shoe size", null,
                CustomFieldDataType.INTEGER, FieldControlType.AUTO, "Uniform", 1,
                true, false, true, false, true, null,
                BigDecimal.valueOf(20), BigDecimal.valueOf(50), null, null, null, null, null,
                List.of(), List.of(), null, Filterable.FilterOperation.EQUAL, Map.of());
        var enricher = new CustomFieldSchemaEnricher((resource, tenant, schemaType) -> List.of(descriptor), mapper);
        Map<String, Object> schema = new LinkedHashMap<>();
        enricher.enrich(schema, "hr.employees", "org-a", "request");
        JsonNode container = mapper.readTree(mapper.writeValueAsBytes(schema)).path("properties").path("customFields");
        JsonNode example = mapper.readTree(Path.of("docs/spec/examples/custom-fields.schema.valid.json").toFile());
        assertEquals(example, container);
        JsonSchema fieldSpec = fieldSpec();
        assertTrue(fieldSpec.validate(container.path("x-ui")).isEmpty());
        assertTrue(fieldSpec.validate(container.path("properties").path("numero_calcado").path("x-ui")).isEmpty());
    }

    @Test
    void projectsOptionalPresentationAttributesIntoXUiAndValidatesAgainstFieldSpec() throws Exception {
        CustomFieldDescriptor descriptor = new CustomFieldDescriptor("processo_sei", "Processo SEI", "Número oficial",
                CustomFieldDataType.STRING, FieldControlType.INPUT, "Complemento", 10,
                false, false, true, false, false, null,
                null, null, null, 30, null, null, null,
                List.of(), List.of(), null, Filterable.FilterOperation.EQUAL, Map.of(), Map.of(),
                true, "00000.000000/2026-00", "50%");
        var enricher = new CustomFieldSchemaEnricher((resource, tenant, schemaType) -> List.of(descriptor), mapper);
        Map<String, Object> schema = new LinkedHashMap<>();
        enricher.enrich(schema, "hr.employees", "org-a", "response");
        JsonNode xUi = mapper.valueToTree(schema).at("/properties/customFields/properties/processo_sei/x-ui");
        assertTrue(xUi.path("tableHidden").asBoolean());
        assertEquals("00000.000000/2026-00", xUi.path("placeholder").asText());
        assertEquals("50%", xUi.path("width").asText());
        assertTrue(fieldSpec().validate(xUi).isEmpty());

        Map<String, Object> filterSchema = new LinkedHashMap<>();
        enricher.enrich(filterSchema, "hr.employees", "org-a", "request", true);
        JsonNode filterXUi = mapper.valueToTree(filterSchema).at("/properties/customFields/properties/processo_sei/x-ui");
        assertFalse(filterXUi.has("tableHidden"));
        assertEquals("00000.000000/2026-00", filterXUi.path("placeholder").asText());
    }

    @Test
    void fieldSpecRejectsUnsafeKeysAndNonTenantScope() throws Exception {
        JsonSchema spec = fieldSpec();
        ObjectNode invalid = mapper.createObjectNode().put("customField", true)
                .put("customFieldKey", "../salary").put("customFieldScope", "tenant");
        assertFalse(spec.validate(invalid).isEmpty());
        invalid.put("customFieldKey", "salary").put("customFieldScope", "global");
        assertFalse(spec.validate(invalid).isEmpty());
        invalid.put("customFieldScope", "tenant");
        assertTrue(spec.validate(invalid).isEmpty());
    }

    @Test
    void definitionAndPublishedSpecRejectTheSameConsumerReservedKeysWithoutAliases() throws Exception {
        JsonSchema spec = fieldSpec();
        List<String> reserved = List.of("constructor", "prototype", "hasOwnProperty", "isPrototypeOf",
                "propertyIsEnumerable", "toLocaleString", "toString", "valueOf");
        JsonNode reservedSpec = mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile())
                .at("/properties/customFieldKey/not/enum");
        assertEquals(mapper.valueToTree(reserved), reservedSpec);
        for (String key : reserved) {
            assertThrows(IllegalArgumentException.class, () -> CustomFieldFixtures.field(key, CustomFieldDataType.STRING), key);
            assertFalse(spec.validate(mapper.createObjectNode().put("customField", true)
                    .put("customFieldKey", key).put("customFieldScope", "tenant")).isEmpty(), key);
        }
        for (String key : List.of("Constructor", "prototype_value", "toStringValue", "a", "A_0")) {
            assertDoesNotThrow(() -> CustomFieldFixtures.field(key, CustomFieldDataType.STRING), key);
            assertTrue(spec.validate(mapper.createObjectNode().put("customFieldKey", key)).isEmpty(), key);
        }
    }

    @Test
    void projectsRichUiExtensionsAndFormatsValidatedAgainstPublishedFieldSpec() throws Exception {
        Map<String, Object> uiExtensions = Map.of(
                "helpText", "Ajuda exibida abaixo do campo",
                "icon", "payments",
                "mask", "000.000.000-00",
                "presentation", Map.of("presenter", "badge", "tone", "success", "icon", "verified", "appearance", "soft"),
                "conditionalDisplay", Map.of("==", List.of(Map.of("var", "regime"), "ESTATUTARIO")));
        CustomFieldDescriptor rich = new CustomFieldDescriptor("teto_auxilio", "Teto Auxílio", "Ajuda exibida",
                CustomFieldDataType.DECIMAL, FieldControlType.CURRENCY_INPUT, "Financeiro", 10,
                false, false, true, false, true, null,
                BigDecimal.ZERO, BigDecimal.valueOf(5000), null, null, null, null, null,
                List.of(), List.of(), null, Filterable.FilterOperation.GREATER_OR_EQUAL, Map.of(), Map.of(),
                false, "0,00", "50%", uiExtensions);
        CustomFieldDescriptor email = new CustomFieldDescriptor("email_alt", "E-mail Alternativo", null,
                CustomFieldDataType.STRING, FieldControlType.EMAIL_INPUT, "Contato", 20,
                false, false, true, false, true, null,
                null, null, null, null, null, null, null,
                List.of(), List.of(), null, Filterable.FilterOperation.LIKE, Map.of());

        var enricher = new CustomFieldSchemaEnricher((resource, tenant, schemaType) -> List.of(rich, email), mapper);
        Map<String, Object> schema = new LinkedHashMap<>();
        enricher.enrich(schema, "hr.employees", "org-a", "response");
        JsonNode root = mapper.valueToTree(schema);
        JsonNode richNode = root.at("/properties/customFields/properties/teto_auxilio");
        JsonNode richUi = richNode.path("x-ui");
        assertEquals("currency", richUi.path("numericFormat").asText());
        assertEquals("currency", richUi.path("valuePresentation").path("type").asText());
        assertEquals("badge", richUi.path("presentation").path("presenter").asText());
        assertEquals("Ajuda exibida abaixo do campo", richUi.path("helpText").asText());
        assertTrue(richUi.path("conditionalDisplay").has("=="));
        assertTrue(fieldSpec().validate(richUi).isEmpty(), "Rich x-ui failed field spec validation");

        JsonNode emailNode = root.at("/properties/customFields/properties/email_alt");
        assertEquals("email", emailNode.path("format").asText());
        assertTrue(fieldSpec().validate(emailNode.path("x-ui")).isEmpty());

        Map<String, Object> filterSchema = new LinkedHashMap<>();
        enricher.enrich(filterSchema, "hr.employees", "org-a", "request", true);
        JsonNode filterUi = mapper.valueToTree(filterSchema).at("/properties/customFields/properties/teto_auxilio/x-ui");
        assertFalse(filterUi.has("presentation"));
        assertFalse(filterUi.has("conditionalDisplay"));
        assertFalse(filterUi.has("helpText"));

        CustomFieldDescriptor freeTags = new CustomFieldDescriptor("habilidades", "Habilidades", null,
                CustomFieldDataType.ARRAY, FieldControlType.CHIP_INPUT, "Perfil", 30,
                false, false, true, false, true, null,
                null, null, null, null, null, null, null,
                List.of(), List.of(), null, Filterable.FilterOperation.IN, Map.of());
        Map<String, Object> arraySchema = new LinkedHashMap<>();
        new CustomFieldSchemaEnricher((r, t, s) -> List.of(freeTags), mapper)
                .enrich(arraySchema, "hr.employees", "org-a", "request");
        assertEquals("string", mapper.valueToTree(arraySchema)
                .at("/properties/customFields/properties/habilidades/items/type").asText());
    }

    private JsonSchema fieldSpec() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile()));
    }
}
