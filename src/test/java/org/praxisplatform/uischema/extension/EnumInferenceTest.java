package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnumInferenceTest {

    @Test
    void smallEnumShouldUseRadio() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setEnum(Arrays.asList("A","B","C"));
        resolver.applyBeanValidatorAnnotations(property, new java.lang.annotation.Annotation[]{}, null, false);
        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.RADIO.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void mediumEnumShouldUseSelect() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setEnum(Arrays.asList("A","B","C","D","E","F","G","H","I","J")); // 10
        resolver.applyBeanValidatorAnnotations(property, new java.lang.annotation.Annotation[]{}, null, false);
        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.SELECT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void largeEnumShouldUseAutoComplete() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int i=0;i<100;i++) values.add("V"+i);
        property.setEnum(values);
        resolver.applyBeanValidatorAnnotations(property, new java.lang.annotation.Annotation[]{}, null, false);
        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.AUTO_COMPLETE.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
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

