package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.annotation.Annotation;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VisibilityFlagsTest {

    private static class Dummy {
        @UISchema(tableHidden = true, formHidden = true)
        String id;
    }

    @Test
    void resolverShouldApplyTableAndFormHiddenFlags() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("id");

        Annotation uiSchema = Dummy.class.getDeclaredField("id").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(Boolean.TRUE, xui.get(FieldConfigProperties.TABLE_HIDDEN.getValue()));
        assertEquals(Boolean.TRUE, xui.get(FieldConfigProperties.FORM_HIDDEN.getValue()));
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

