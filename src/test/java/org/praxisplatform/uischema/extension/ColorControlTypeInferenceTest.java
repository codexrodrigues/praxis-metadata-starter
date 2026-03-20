package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.util.OpenApiUiUtils;

import java.lang.annotation.Annotation;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorControlTypeInferenceTest {

    @Test
    void openApiFormatColorShouldInferColorInput() {
        assertEquals(
            FieldControlType.COLOR_INPUT.getValue(),
            OpenApiUiUtils.determineBasicControlType("string", "color", false, null, false, false)
        );
    }

    @Test
    void colorLikeFieldNameShouldInferColorInput() {
        assertEquals(
            FieldControlType.COLOR_INPUT.getValue(),
            OpenApiUiUtils.determineSmartControlTypeByFieldName("brandColor")
        );
        assertEquals(
            FieldControlType.COLOR_INPUT.getValue(),
            OpenApiUiUtils.determineSmartControlTypeByFieldName("corPrimaria")
        );
    }

    @Test
    void resolverShouldPublishColorInputForColorFormatWhenControlTypeIsAuto() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string").format("color");
        property.setName("brandColor");

        Annotation[] annotations = new Annotation[] { TestUISchemaDefaults.instance() };
        resolver.applyBeanValidatorAnnotations(property, annotations, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(
            FieldControlType.COLOR_INPUT.getValue(),
            xui.get(FieldConfigProperties.CONTROL_TYPE.getValue())
        );
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
