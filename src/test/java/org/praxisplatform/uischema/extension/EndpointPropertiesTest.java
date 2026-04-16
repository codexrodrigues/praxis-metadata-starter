package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
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

        @UISchema(
                controlType = FieldControlType.SELECT,
                extraProperties = {
                        @ExtensionProperty(name = "optionSource.key", value = "payrollProfile"),
                        @ExtensionProperty(name = "optionSource.type", value = "DISTINCT_DIMENSION"),
                        @ExtensionProperty(name = "optionSource.dependsOn", value = "[\"universo\"]"),
                        @ExtensionProperty(name = "optionSource.dependencyFilterMap", value = "{\"universo\":\"empresa.universo\"}"),
                        @ExtensionProperty(name = "optionSource.excludeSelfField", value = "true"),
                        @ExtensionProperty(name = "optionSource.pageSize", value = "25")
                }
        )
        private String profile;
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

    @Test
    void nestsOptionSourceExtraPropertiesInsideXUi() throws NoSuchFieldException {
        Schema<?> schema = new Schema<>();
        schema.setName("profile");

        Field field = DummyDTO.class.getDeclaredField("profile");
        resolver.applyBeanValidatorAnnotations(schema, field.getAnnotations(), null, true);

        assertNotNull(schema.getExtensions());
        @SuppressWarnings("unchecked")
        Map<String, Object> xUi = (Map<String, Object>) schema.getExtensions().get("x-ui");
        assertNotNull(xUi);

        @SuppressWarnings("unchecked")
        Map<String, Object> optionSource = (Map<String, Object>) xUi.get("optionSource");
        assertNotNull(optionSource);
        assertEquals("payrollProfile", optionSource.get("key"));
        assertEquals("DISTINCT_DIMENSION", optionSource.get("type"));
        assertEquals(Boolean.TRUE, optionSource.get("excludeSelfField"));
        assertEquals(25, optionSource.get("pageSize"));
        assertEquals(java.util.List.of("universo"), optionSource.get("dependsOn"));
        assertEquals(Map.of("universo", "empresa.universo"), optionSource.get("dependencyFilterMap"));
    }
}

