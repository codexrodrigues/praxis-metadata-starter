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

class InlineControlTypeContractTest {

    private static class DummyInlineContract {
        @UISchema(controlType = FieldControlType.INLINE_MULTISELECT)
        String inlineMultiSelect;

        @UISchema(controlType = FieldControlType.INLINE_RATING)
        String inlineRating;

        @UISchema(controlType = FieldControlType.INLINE_DISTANCE_RADIUS)
        String inlineDistanceRadius;

        @UISchema(controlType = FieldControlType.INLINE_PIPELINE_STATUS)
        String inlinePipelineStatus;

        @UISchema(controlType = FieldControlType.INLINE_SCORE_PRIORITY)
        String inlineScorePriority;

        @UISchema(controlType = FieldControlType.INLINE_RELATIVE_PERIOD)
        String inlineRelativePeriod;

        @UISchema(controlType = FieldControlType.INLINE_SENTIMENT)
        String inlineSentiment;

        @UISchema(controlType = FieldControlType.INLINE_COLOR_LABEL)
        String inlineColorLabel;
    }

    @Test
    void resolverShouldExposeCanonicalInlineControlTypes() throws Exception {
        assertInlineControlType("inlineMultiSelect", FieldControlType.INLINE_MULTISELECT);
        assertInlineControlType("inlineRating", FieldControlType.INLINE_RATING);
        assertInlineControlType("inlineDistanceRadius", FieldControlType.INLINE_DISTANCE_RADIUS);
        assertInlineControlType("inlinePipelineStatus", FieldControlType.INLINE_PIPELINE_STATUS);
        assertInlineControlType("inlineScorePriority", FieldControlType.INLINE_SCORE_PRIORITY);
        assertInlineControlType("inlineRelativePeriod", FieldControlType.INLINE_RELATIVE_PERIOD);
        assertInlineControlType("inlineSentiment", FieldControlType.INLINE_SENTIMENT);
        assertInlineControlType("inlineColorLabel", FieldControlType.INLINE_COLOR_LABEL);
    }

    private static void assertInlineControlType(String fieldName, FieldControlType expectedControlType) throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName(fieldName);

        Field field = DummyInlineContract.class.getDeclaredField(fieldName);
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
