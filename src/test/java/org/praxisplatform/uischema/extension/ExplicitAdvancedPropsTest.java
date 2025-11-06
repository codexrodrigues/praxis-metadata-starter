package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.*;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.annotation.Annotation;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitAdvancedPropsTest {

    private static class DummyAdvanced {
        @UISchema(
                isFlex = true,
                displayOrientation = "row",
                validationMode = "onBlur",
                unique = true,
                mask = "999.999.999-99",
                conditionalRequired = "someExpr",
                viewOnlyStyle = "muted",
                validationTriggers = "change",
                conditionalDisplay = "otherField == 'x'",
                dependentField = "otherField",
                resetOnDependentChange = true,
                inlineEditing = true,
                transformValueFunction = "fn",
                debounceTime = 300,
                hint = "hinty",
                hiddenCondition = "cond",
                tooltipOnHover = "tip",
                icon = "account",
                iconSize = "lg",
                iconColor = "red",
                iconClass = "cssClass",
                iconStyle = "styleX",
                iconFontSize = "12px",
                filter = "contains",
                filterOptions = "{\"mode\":\"contains\"}",
                filterControlType = "multiColumnComboBox",
                numericFormat = NumericFormat.DECIMAL,
                numericStep = "0.01",
                numericMin = "0",
                numericMax = "100",
                numericMaxLength = "10",
                min = "1",
                max = "99",
                requiredMessage = "req",
                minLengthMessage = "minL",
                maxLengthMessage = "maxL",
                patternMessage = "pat",
                rangeMessage = "range",
                customValidator = "customFn",
                asyncValidator = "asyncFn",
                minWords = 5,
                allowedFileTypes = AllowedFileTypes.PDF,
                maxFileSize = "12345"
        )
        String campo;
    }

    @Test
    void resolverShouldApplyManyExplicitAdvancedProps() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("campo");

        Annotation uiSchema = DummyAdvanced.class.getDeclaredField("campo").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);

        // Layout/estilo e ícones
        assertEquals(Boolean.TRUE, xui.get(FieldConfigProperties.IS_FLEX.getValue()));
        assertEquals("row", xui.get(FieldConfigProperties.DISPLAY_ORIENTATION.getValue()));
        assertEquals("account", xui.get(FieldConfigProperties.ICON.getValue()));
        assertEquals("lg", xui.get(FieldConfigProperties.ICON_SIZE.getValue()));
        assertEquals("red", xui.get(FieldConfigProperties.ICON_COLOR.getValue()));
        assertEquals("cssClass", xui.get(FieldConfigProperties.ICON_CLASS.getValue()));
        assertEquals("styleX", xui.get(FieldConfigProperties.ICON_STYLE.getValue()));
        assertEquals("12px", xui.get(FieldConfigProperties.ICON_FONT_SIZE.getValue()));

        // Comportamento/validação top-level
        assertEquals("onBlur", xui.get(FieldConfigProperties.VALIDATION_MODE.getValue()));
        assertEquals(Boolean.TRUE, xui.get(FieldConfigProperties.UNIQUE.getValue()));
        assertEquals("999.999.999-99", xui.get(FieldConfigProperties.MASK.getValue()));
        assertEquals("someExpr", xui.get(FieldConfigProperties.CONDITIONAL_REQUIRED.getValue()));
        assertEquals("muted", xui.get(FieldConfigProperties.VIEW_ONLY_STYLE.getValue()));
        assertEquals("change", xui.get(FieldConfigProperties.VALIDATION_TRIGGERS.getValue()));

        // Dependências e condicionais
        assertEquals("otherField == 'x'", xui.get(FieldConfigProperties.CONDITIONAL_DISPLAY.getValue()));
        assertEquals("otherField", xui.get(FieldConfigProperties.DEPENDENT_FIELD.getValue()));
        assertEquals(Boolean.TRUE, xui.get(FieldConfigProperties.RESET_ON_DEPENDENT_CHANGE.getValue()));

        // Outras props
        assertEquals(Boolean.TRUE, xui.get(FieldConfigProperties.INLINE_EDITING.getValue()));
        assertEquals("fn", xui.get(FieldConfigProperties.TRANSFORM_VALUE_FUNCTION.getValue()));
        assertEquals("300", xui.get(FieldConfigProperties.DEBOUNCE_TIME.getValue()));
        assertEquals("hinty", xui.get(FieldConfigProperties.HINT.getValue()));
        assertEquals("cond", xui.get(FieldConfigProperties.HIDDEN_CONDITION.getValue()));
        assertEquals("tip", xui.get(FieldConfigProperties.TOOLTIP_ON_HOVER.getValue()));

        // Filtros
        assertEquals("contains", xui.get(FieldConfigProperties.FILTER.getValue()));
        assertEquals("{\"mode\":\"contains\"}", xui.get(FieldConfigProperties.FILTER_OPTIONS.getValue()));
        assertEquals("multiColumnComboBox", xui.get(FieldConfigProperties.FILTER_CONTROL_TYPE.getValue()));

        // Numéricos
        assertEquals(NumericFormat.DECIMAL.getValue(), xui.get(FieldConfigProperties.NUMERIC_FORMAT.getValue()));
        assertEquals("0.01", xui.get(FieldConfigProperties.NUMERIC_STEP.getValue()));
        assertEquals("0", xui.get(FieldConfigProperties.NUMERIC_MIN.getValue()));
        assertEquals("100", xui.get(FieldConfigProperties.NUMERIC_MAX.getValue()));
        assertEquals("10", xui.get(FieldConfigProperties.NUMERIC_MAX_LENGTH.getValue()));

        // Validações (min/max + mensagens/validadores)
        assertEquals("1", String.valueOf(xui.get(ValidationProperties.MIN.getValue())));
        assertEquals("99", String.valueOf(xui.get(ValidationProperties.MAX.getValue())));
        assertEquals("req", xui.get(ValidationProperties.REQUIRED_MESSAGE.getValue()));
        assertEquals("minL", xui.get(ValidationProperties.MIN_LENGTH_MESSAGE.getValue()));
        assertEquals("maxL", xui.get(ValidationProperties.MAX_LENGTH_MESSAGE.getValue()));
        assertEquals("pat", xui.get(ValidationProperties.PATTERN_MESSAGE.getValue()));
        assertEquals("range", xui.get(ValidationProperties.RANGE_MESSAGE.getValue()));
        assertEquals("customFn", xui.get(ValidationProperties.CUSTOM_VALIDATOR.getValue()));
        assertEquals("asyncFn", xui.get(ValidationProperties.ASYNC_VALIDATOR.getValue()));
        assertEquals("5", String.valueOf(xui.get(ValidationProperties.MIN_WORDS.getValue())));
        // conditionalRequired também em validation namespace
        assertEquals("someExpr", xui.get(ValidationProperties.CONDITIONAL_REQUIRED.getValue()));

        // Arquivos
        assertEquals(AllowedFileTypes.PDF.getValue(), xui.get(ValidationProperties.ALLOWED_FILE_TYPES.getValue()));
        assertEquals("12345", String.valueOf(xui.get(ValidationProperties.MAX_FILE_SIZE.getValue())));
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

