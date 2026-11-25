package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import jakarta.validation.constraints.Digits;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.NumericFormat;

import java.lang.annotation.Annotation;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NumericPercentInferenceTest {

    private static class Dummy {
        @Digits(integer = 5, fraction = 2)
        String salary;

        @Digits(integer = 2, fraction = 0)
        Integer totalDays;
    }

    @Test
    void percentShouldApplyDefaultsWhenAbsent() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("number").format("percent");
        property.setName("percentual");

        resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ TestUISchemaDefaults.instance() }, null, false);

        Map<String,Object> xui = getXui(property);
        assertEquals("0.01", xui.get(FieldConfigProperties.NUMERIC_STEP.getValue()));
        assertEquals("0–100%", xui.get(FieldConfigProperties.PLACEHOLDER.getValue()));
        assertEquals("0", String.valueOf(xui.get(FieldConfigProperties.NUMERIC_MIN.getValue())));
        assertEquals("100", String.valueOf(xui.get(FieldConfigProperties.NUMERIC_MAX.getValue())));
    }

    @Test
    void digitsShouldDefineNumericStepAndSemanticNumericFormatWhenAbsent() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> decimalProp = new Schema<>().type("number");
        decimalProp.setName("salary");
        Annotation salaryDigits = Dummy.class.getDeclaredField("salary").getAnnotation(Digits.class);

        resolver.applyBeanValidatorAnnotations(
                decimalProp, new Annotation[]{ TestUISchemaDefaults.instance(), salaryDigits }, null, false);

        Map<String, Object> decimalXui = getXui(decimalProp);
        assertEquals("0.01", decimalXui.get(FieldConfigProperties.NUMERIC_STEP.getValue()));
        assertEquals(NumericFormat.DECIMAL.getValue(), decimalXui.get(FieldConfigProperties.NUMERIC_FORMAT.getValue()));

        Schema<?> intProp = new Schema<>().type("integer");
        intProp.setName("totalDays");
        Annotation totalDaysDigits = Dummy.class.getDeclaredField("totalDays").getAnnotation(Digits.class);

        resolver.applyBeanValidatorAnnotations(
                intProp, new Annotation[]{ TestUISchemaDefaults.instance(), totalDaysDigits }, null, false);

        Map<String, Object> intXui = getXui(intProp);
        assertEquals("1", intXui.get(FieldConfigProperties.NUMERIC_STEP.getValue()));
        assertEquals(NumericFormat.INTEGER.getValue(), intXui.get(FieldConfigProperties.NUMERIC_FORMAT.getValue()));
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
