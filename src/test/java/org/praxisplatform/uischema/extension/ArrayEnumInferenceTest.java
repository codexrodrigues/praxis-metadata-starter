package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArrayEnumInferenceTest {

    @Test
    void smallArrayEnumShouldUseChipInput() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        ArraySchema property = new ArraySchema();
        StringSchema items = new StringSchema();
        items.setEnum(Arrays.asList("A","B","C"));
        property.setItems(items);
        resolver.applyBeanValidatorAnnotations(property, new java.lang.annotation.Annotation[]{}, null, false);
        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.CHIP_INPUT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void largeArrayEnumShouldUseMultiSelectAndFilterHint() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        ArraySchema property = new ArraySchema();
        StringSchema items = new StringSchema();
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int i=0;i<50;i++) values.add("V"+i);
        items.setEnum(values);
        property.setItems(items);
        resolver.applyBeanValidatorAnnotations(property, new java.lang.annotation.Annotation[]{}, null, false);
        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.MULTI_SELECT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("multiColumnComboBox", xui.get(FieldConfigProperties.FILTER_CONTROL_TYPE.getValue()));
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
