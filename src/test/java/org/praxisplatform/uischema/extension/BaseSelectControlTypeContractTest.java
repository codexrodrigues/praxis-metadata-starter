package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseSelectControlTypeContractTest {

    private static class DummySelectContract {
        @UISchema(controlType = FieldControlType.SEARCHABLE_SELECT)
        String searchableSelect;

        @UISchema(controlType = FieldControlType.ASYNC_SELECT)
        String asyncSelect;
    }

    @Test
    void resolverShouldExposeCanonicalBaseSelectControlTypes() throws Exception {
        assertControlType("searchableSelect", FieldControlType.SEARCHABLE_SELECT);
        assertControlType("asyncSelect", FieldControlType.ASYNC_SELECT);
    }

    private static void assertControlType(String fieldName, FieldControlType expectedControlType) throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName(fieldName);

        Field field = DummySelectContract.class.getDeclaredField(fieldName);
        resolver.applyBeanValidatorAnnotations(property, field.getAnnotations(), null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(expectedControlType.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
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
