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

    private JsonSchema fieldSpec() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile()));
    }
}
