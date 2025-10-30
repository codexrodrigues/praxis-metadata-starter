package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BooleanInferenceTest {

    @Test
    void booleanWithoutEnumShouldBeCheckbox() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        BooleanSchema property = new BooleanSchema();
        resolver.applyBeanValidatorAnnotations(property, new java.lang.annotation.Annotation[]{}, null, false);
        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.CHECKBOX.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void booleanTextualEnumShouldPreferRadio() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        StringSchema property = new StringSchema();
        property.setEnum(Arrays.asList("Sim","Não"));
        resolver.applyBeanValidatorAnnotations(property, new java.lang.annotation.Annotation[]{}, null, false);
        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.RADIO.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getXui(Schema<?> property) {
        assertNotNull(property.getExtensions(), "Extensions should not be null");
        Object xui = property.getExtensions().get("x-ui");
        assertNotNull(xui, "x-ui extension should be present");
        assertTrue(xui instanceof Map, "x-ui should be a Map");
        return (Map<String, Object>) xui;
    }
}
