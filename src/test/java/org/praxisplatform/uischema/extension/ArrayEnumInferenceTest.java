package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import java.lang.annotation.Annotation;

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
        Annotation[] anns = new Annotation[]{ TestUISchemaDefaults.instance() };
        resolver.applyBeanValidatorAnnotations(property, anns, null, false);
        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.CHIP_INPUT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void largeArrayEnumShouldUseMultiSelectWithoutResidualFilterHintByDefault() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        ArraySchema property = new ArraySchema();
        StringSchema items = new StringSchema();
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int i=0;i<50;i++) values.add("V"+i);
        items.setEnum(values);
        property.setItems(items);
        Annotation[] anns = new Annotation[]{ TestUISchemaDefaults.instance() };
        resolver.applyBeanValidatorAnnotations(property, anns, null, false);
        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.MULTI_SELECT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertFalse(
                xui.containsKey("filterControlType"),
                "resolver nao deve emitir propriedades residuais de filtro por padrao"
        );
    }

    @Test
    void objectArrayShouldPublishEditableCollectionContract() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        ArraySchema property = new ArraySchema();
        property.setItems(new ObjectSchema());
        property.setMinItems(1);
        property.setMaxItems(5);
        Annotation[] anns = new Annotation[]{ TestUISchemaDefaults.instance() };

        resolver.applyBeanValidatorAnnotations(property, anns, null, false);

        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.ARRAY.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals(FieldDataType.ARRAY.getValue(), xui.get(FieldConfigProperties.TYPE.getValue()));
        assertTrue(xui.get("array") instanceof Map);

        @SuppressWarnings("unchecked")
        Map<String, Object> array = (Map<String, Object>) xui.get("array");
        assertEquals("object", array.get("itemType"));
        assertEquals("cards", array.get("mode"));
        assertEquals("removeFromPayload", array.get("deleteMode"));
        assertEquals(1, array.get("minItems"));
        assertEquals(5, array.get("maxItems"));
    }

    @Test
    void referencedItemArrayShouldPublishItemSchemaRef() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        ArraySchema property = new ArraySchema();
        Schema<?> item = new Schema<>();
        item.set$ref("#/components/schemas/MissaoEquipePlanejadaItemDTO");
        property.setItems(item);
        Annotation[] anns = new Annotation[]{ TestUISchemaDefaults.instance() };

        resolver.applyBeanValidatorAnnotations(property, anns, null, false);

        Map<String,Object> xui = getXui(property);
        assertEquals(FieldControlType.ARRAY.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));

        @SuppressWarnings("unchecked")
        Map<String, Object> array = (Map<String, Object>) xui.get("array");
        assertEquals("#/components/schemas/MissaoEquipePlanejadaItemDTO", array.get("itemSchemaRef"));
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
