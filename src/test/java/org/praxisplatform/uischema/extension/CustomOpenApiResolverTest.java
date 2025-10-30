package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.annotation.Annotation;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomOpenApiResolverTest {

    private static class Dummy {
        @UISchema(controlType = org.praxisplatform.uischema.FieldControlType.INPUT)
        String descricao;
    }

    @Test
    void resolverShouldKeepInputForNomeCompletoWithMax200() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("nomeCompleto");
        property.setMaxLength(200);

        resolver.applyBeanValidatorAnnotations(property, new Annotation[] {}, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(FieldControlType.INPUT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void resolverShouldUseTextareaForDescricaoWithMax1000() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("descricao");
        property.setMaxLength(1000);

        resolver.applyBeanValidatorAnnotations(property, new Annotation[] {}, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(FieldControlType.TEXTAREA.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void explicitUISchemaControlTypeShouldWin() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("descricao");
        property.setMaxLength(1000);

        Annotation uiSchema = Dummy.class.getDeclaredField("descricao").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(FieldControlType.INPUT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
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

