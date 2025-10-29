package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EndpointPropertiesTest {

    private CustomOpenApiResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CustomOpenApiResolver(new ObjectMapper());
    }

    static class DummyDTO {
        @UISchema(
                controlType = FieldControlType.SELECT,
                valueField = "id",
                displayField = "nome",
                endpoint = "/api/items",
                emptyOptionText = "Select...",
                multiple = true
        )
        private String item;
    }

    @Test
    void includesEndpointAndMappingProperties() throws NoSuchFieldException {
        Schema<?> schema = new Schema<>();
        schema.setName("item");

        Field field = DummyDTO.class.getDeclaredField("item");
        resolver.applyBeanValidatorAnnotations(schema, field.getAnnotations(), null, true);

        assertNotNull(schema.getExtensions());
        assertTrue(schema.getExtensions().containsKey("x-ui"));

        @SuppressWarnings("unchecked")
        Map<String, Object> xUi = (Map<String, Object>) schema.getExtensions().get("x-ui");

        assertEquals("/api/items", xUi.get(FieldConfigProperties.ENDPOINT.getValue()));
        assertEquals("id", xUi.get(FieldConfigProperties.VALUE_FIELD.getValue()));
        assertEquals("nome", xUi.get(FieldConfigProperties.DISPLAY_FIELD.getValue()));
        assertEquals("Select...", xUi.get(FieldConfigProperties.EMPTY_OPTION_TEXT.getValue()));
        assertEquals(true, xUi.get(FieldConfigProperties.MULTIPLE.getValue()));
    }
}

