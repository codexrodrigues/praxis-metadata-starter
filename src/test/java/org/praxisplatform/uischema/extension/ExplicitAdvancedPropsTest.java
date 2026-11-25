package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.*;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.annotation.Annotation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitAdvancedPropsTest {

    private static class DummyAdvanced {
        @UISchema(
                isFlex = true,
                displayOrientation = "row",
                validationMode = "onBlur",
                unique = true,
                mask = "999.999.999-99",
                conditionalRequired = "{\"!!\":[{\"var\":\"form.someExpr\"}]}",
                viewOnlyStyle = "muted",
                validationTriggers = "change",
                conditionalDisplay = "{\"==\":[{\"var\":\"form.otherField\"},\"x\"]}",
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
                controlType = FieldControlType.INLINE_SEARCHABLE_SELECT,
                filter = "contains",
                filterOptions = "{\"mode\":\"contains\"}",
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
                maxFileSize = "12345",
                extraProperties = @ExtensionProperty(
                        name = "conditionalValidation",
                        value = "[{\"condition\":{\"==\":[{\"var\":\"form.accountType\"},\"business\"]},\"validators\":{\"required\":true,\"requiredMessage\":\"Business email is required\"}}]"
                )
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
        assertTrue(xui.get(FieldConfigProperties.CONDITIONAL_REQUIRED.getValue()) instanceof Map<?, ?>);
        assertEquals("muted", xui.get(FieldConfigProperties.VIEW_ONLY_STYLE.getValue()));
        assertEquals("change", xui.get(FieldConfigProperties.VALIDATION_TRIGGERS.getValue()));

        // Dependências e condicionais
        assertTrue(xui.get(FieldConfigProperties.CONDITIONAL_DISPLAY.getValue()) instanceof Map<?, ?>);
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
        assertEquals(FieldControlType.INLINE_SEARCHABLE_SELECT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));

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
        assertTrue(xui.get(ValidationProperties.CONDITIONAL_REQUIRED.getValue()) instanceof Map<?, ?>);
        assertTrue(xui.get(ValidationProperties.CONDITIONAL_VALIDATION.getValue()) instanceof List<?>);
        List<?> conditionalValidation = (List<?>) xui.get(ValidationProperties.CONDITIONAL_VALIDATION.getValue());
        assertEquals(1, conditionalValidation.size());
        assertTrue(conditionalValidation.get(0) instanceof Map<?, ?>);
        Map<?, ?> conditionalRule = (Map<?, ?>) conditionalValidation.get(0);
        assertTrue(conditionalRule.get("condition") instanceof Map<?, ?>);
        assertTrue(conditionalRule.get("validators") instanceof Map<?, ?>);

        // Arquivos
        assertEquals(AllowedFileTypes.PDF.getValue(), xui.get(ValidationProperties.ALLOWED_FILE_TYPES.getValue()));
        assertEquals("12345", String.valueOf(xui.get(ValidationProperties.MAX_FILE_SIZE.getValue())));
    }

    @Test
    void resolverShouldRejectTextualConditionalDisplayDsl() throws Exception {
        assertInvalidCondition(TextualConditionalDisplay.class, "conditionalDisplay");
    }

    @Test
    void resolverShouldRejectUnknownJsonLogicOperator() throws Exception {
        assertInvalidCondition(UnknownOperatorConditionalDisplay.class, "unsupported Json Logic operator `unknownOp`");
    }

    @Test
    void resolverShouldRejectJsonSchemaConditionalObject() throws Exception {
        assertInvalidCondition(JsonSchemaConditionalValidation.class, "must contain exactly one Json Logic operator");
    }

    @Test
    void resolverShouldRejectInsufficientOperatorArity() throws Exception {
        assertInvalidCondition(InsufficientArityConditionalDisplay.class, "requires at least 2 arguments");
    }

    @Test
    void resolverShouldRejectExcessiveOperatorArity() throws Exception {
        assertInvalidCondition(ExcessiveArityConditionalDisplay.class, "accepts at most 2 arguments");
    }

    @Test
    void resolverShouldRejectLiteralTypeThatAngularRuntimeRejects() throws Exception {
        assertInvalidCondition(InvalidLiteralTypeConditionalDisplay.class, "must be a string, array literal, or expression");
    }

    @Test
    void resolverShouldRejectStructuredLiteralWhereStringIsRequired() throws Exception {
        assertInvalidCondition(InvalidStringLiteralConditionalDisplay.class, "must be a string literal or expression");
    }

    @Test
    void resolverShouldRejectStructuredLiteralWhereObjectIsRequired() throws Exception {
        assertInvalidCondition(InvalidObjectLiteralConditionalDisplay.class, "must be an object literal or expression");
    }

    @Test
    void resolverShouldRejectMalformedJsonWithActionableMessage() throws Exception {
        assertInvalidCondition(MalformedJsonConditionalDisplay.class, "must contain valid JSON");
    }

    @Test
    void resolverShouldAllowNullConditionalValidationCondition() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("campo");

        Annotation uiSchema = NullConditionalValidation.class.getDeclaredField("campo").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);
        List<?> conditionalValidation = (List<?>) xui.get(ValidationProperties.CONDITIONAL_VALIDATION.getValue());
        Map<?, ?> conditionalRule = (Map<?, ?>) conditionalValidation.get(0);
        assertNull(conditionalRule.get("condition"));
    }

    @Test
    void resolverShouldPublishValidJsonLogicObjectInConditionalDisplay() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("campo");

        Annotation uiSchema = ValidJsonLogicConditionalDisplay.class.getDeclaredField("campo").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);
        Object condition = xui.get(FieldConfigProperties.CONDITIONAL_DISPLAY.getValue());
        assertTrue(condition instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) condition).containsKey("=="));
    }

    private static class TextualConditionalDisplay {
        @UISchema(conditionalDisplay = "otherField == 'x'")
        String campo;
    }

    private static class UnknownOperatorConditionalDisplay {
        @UISchema(conditionalDisplay = "{\"unknownOp\":[{\"var\":\"form.status\"},\"active\"]}")
        String campo;
    }

    private static class JsonSchemaConditionalValidation {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "conditionalValidation",
                value = "[{\"condition\":{\"if\":{\"properties\":{\"status\":{\"const\":\"active\"}}},\"then\":{\"required\":[\"name\"]}},\"validators\":{\"required\":true}}]"
        ))
        String campo;
    }

    private static class InsufficientArityConditionalDisplay {
        @UISchema(conditionalDisplay = "{\"==\":[{\"var\":\"form.status\"}]}")
        String campo;
    }

    private static class ExcessiveArityConditionalDisplay {
        @UISchema(conditionalDisplay = "{\"in\":[\"A\",[\"A\",\"B\"],\"extra\"]}")
        String campo;
    }

    private static class InvalidLiteralTypeConditionalDisplay {
        @UISchema(conditionalDisplay = "{\"in\":[\"A\",42]}")
        String campo;
    }

    private static class InvalidStringLiteralConditionalDisplay {
        @UISchema(conditionalDisplay = "{\"startsWith\":[[\"A\"],\"A\"]}")
        String campo;
    }

    private static class InvalidObjectLiteralConditionalDisplay {
        @UISchema(conditionalDisplay = "{\"jsonGet\":[[],\"path\"]}")
        String campo;
    }

    private static class MalformedJsonConditionalDisplay {
        @UISchema(conditionalDisplay = "{\"==\":[{\"var\":\"form.status\"},\"active\"")
        String campo;
    }

    private static class NullConditionalValidation {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "conditionalValidation",
                value = "[{\"condition\":null,\"validators\":{\"required\":true}}]"
        ))
        String campo;
    }

    @Test
    void resolverShouldPublishValidPresentationRulesAndConformToFieldSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(mapper);
        Schema<?> property = new Schema<>().type("integer");
        property.setName("weeklyHours");

        Annotation uiSchema = ValidPresentationRules.class.getDeclaredField("campo").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);
        assertTrue(xui.get(FieldConfigProperties.PRESENTATION_RULES.getValue()) instanceof List<?>);
        List<?> rules = (List<?>) xui.get(FieldConfigProperties.PRESENTATION_RULES.getValue());
        assertEquals(2, rules.size());

        JsonSchema fieldSpec = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile()));
        Set<ValidationMessage> errors = fieldSpec.validate(mapper.valueToTree(xui));
        assertTrue(errors.isEmpty(), "Expected valid x-ui payload for presentationRules, got: " + errors);
    }

    @Test
    void resolverShouldRejectNonArrayPresentationRules() throws Exception {
        assertInvalidCondition(NonArrayPresentationRules.class, "presentationRules must be an array");
    }

    @Test
    void resolverShouldRejectPresentationRuleWithoutWhen() throws Exception {
        assertInvalidCondition(MissingWhenPresentationRules.class, "presentationRules[0].when is required");
    }

    @Test
    void resolverShouldRejectPresentationRuleWithUnknownJsonLogicOperator() throws Exception {
        assertInvalidCondition(InvalidOperatorPresentationRules.class, "unsupported Json Logic operator `unknownOp`");
    }

    @Test
    void resolverShouldRejectPresentationRuleWithoutSetOrEffect() throws Exception {
        assertInvalidCondition(MissingSetPresentationRules.class, "must define a non-empty 'set' or 'effect'");
    }

    @Test
    void resolverShouldRejectPresentationRuleWithEmptySet() throws Exception {
        assertInvalidCondition(EmptySetPresentationRules.class, "must define a non-empty 'set' or 'effect'");
    }

    @Test
    void resolverShouldRejectUnknownRuleTopLevelProperty() throws Exception {
        assertInvalidCondition(UnknownRulePropertyPresentationRules.class, "contains unsupported property 'extra'");
    }

    @Test
    void resolverShouldRejectUnknownPresentationPropertyInsideSet() throws Exception {
        assertInvalidCondition(
                UnknownSetPropertyPresentationRules.class,
                "contains unsupported presentation property 'presetner'");
    }

    @Test
    void resolverShouldRejectInvalidToneAndPresenterInsideSet() throws Exception {
        assertInvalidCondition(InvalidTonePresentationRules.class, "presentationRules[0].set.tone has unsupported value 'amber'");
        assertInvalidCondition(InvalidPresenterPresentationRules.class, "presentationRules[0].set.presenter has unsupported value 'sparkline'");
    }

    @Test
    void resolverShouldRejectInvalidSuffixAndBlankIconInsideSet() throws Exception {
        assertInvalidCondition(NonStringSuffixPresentationRules.class, "presentationRules[0].set.suffix must be a non-empty string");
        assertInvalidCondition(BlankSuffixPresentationRules.class, "presentationRules[0].set.suffix must be a non-empty string");
        assertInvalidCondition(BlankBaseSuffixPresentation.class, "x-ui.presentation for field campo.suffix must be a non-empty string");
        assertInvalidCondition(InvalidBaseTonePresentation.class, "x-ui.presentation for field campo.tone has unsupported value 'amber'");
        assertInvalidCondition(InvalidBasePresenterPresentation.class, "x-ui.presentation for field campo.presenter has unsupported value 'sparkline'");
        assertInvalidCondition(BlankIconPresentationRules.class, "presentationRules[0].set.icon must be a non-empty string");
    }

    @Test
    void resolverShouldAllowBooleanPresentationKeysMutedToneAndSpacedAffixes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(mapper);
        Schema<?> property = new Schema<>().type("boolean");
        property.setName("ativo");

        Annotation uiSchema = ValidBooleanPresentationWithAffixes.class.getDeclaredField("campo").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);
        JsonSchema fieldSpec = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile()));
        Set<ValidationMessage> errors = fieldSpec.validate(mapper.valueToTree(xui));
        assertTrue(errors.isEmpty(), "Expected valid x-ui payload for boolean presentation + spaced prefix/muted tone, got: " + errors);
    }

    @Test
    void fieldSchemaShouldRejectPresentationRuleMissingEffectOrHavingEmptySet() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchema fieldSpec = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile()));

        Set<ValidationMessage> missingEffectErrors = fieldSpec.validate(
                mapper.readTree("{\"presentationRules\":[{\"when\":true}]}"));
        assertFalse(missingEffectErrors.isEmpty(), "Expected schema failure when both set and effect are omitted");

        Set<ValidationMessage> emptySetErrors = fieldSpec.validate(
                mapper.readTree("{\"presentationRules\":[{\"when\":true,\"set\":{}}]}"));
        assertFalse(emptySetErrors.isEmpty(), "Expected schema failure when set is empty");
    }

    @Test
    void resolverAndSchemaShouldAcceptObjectQuickViewAndAllElevenTonesInPresentationInteractions() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(mapper);
        Schema<?> property = new Schema<>().type("string");
        property.setName("employeeCode");

        Annotation uiSchema = ValidQuickViewAndTonesPresentation.class.getDeclaredField("campo").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ uiSchema }, null, false);

        Map<String, Object> xui = getXui(property);
        @SuppressWarnings("unchecked")
        Map<String, Object> presentation = (Map<String, Object>) xui.get(FieldConfigProperties.PRESENTATION.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> interactions = (Map<String, Object>) presentation.get("interactions");
        assertEquals(Boolean.TRUE, interactions.get("copy"));
        assertTrue(interactions.get("quickView") instanceof Map<?, ?>);

        JsonSchema fieldSpec = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile()));
        Set<ValidationMessage> errors = fieldSpec.validate(mapper.valueToTree(xui));
        assertTrue(errors.isEmpty(), "Expected valid x-ui payload for interactions.quickView object and canonical tones, got: " + errors);
    }

    @Test
    void resolverAndSchemaShouldRejectNonObjectOrEmptyQuickViewInteraction() throws Exception {
        assertInvalidCondition(BooleanQuickViewInteraction.class, "interactions.quickView must be a non-empty object");
        assertInvalidCondition(StringQuickViewInteraction.class, "interactions.quickView must be a non-empty object");
        assertInvalidCondition(EmptyQuickViewInteraction.class, "interactions.quickView must be a non-empty object");

        ObjectMapper mapper = new ObjectMapper();
        JsonSchema fieldSpec = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile()));
        assertFalse(fieldSpec.validate(
                mapper.readTree("{\"presentation\":{\"interactions\":{\"quickView\":true}}}")).isEmpty());
        assertFalse(fieldSpec.validate(
                mapper.readTree("{\"presentation\":{\"interactions\":{\"quickView\":{}}}}")).isEmpty());
    }

    private static class ValidJsonLogicConditionalDisplay {
        @UISchema(conditionalDisplay = "{\"==\":[{\"var\":\"form.status\"},\"active\"]}")
        String campo;
    }

    private static class ValidPresentationRules {
        @UISchema(
                extraProperties = {
                        @ExtensionProperty(name = "presentation.presenter", value = "iconValue"),
                        @ExtensionProperty(name = "presentation.suffix", value = "h/wk"),
                        @ExtensionProperty(
                                name = "presentationRules",
                                value = "[{\"when\":{\"==\":[{\"var\":\"status\"},\"RETIRED\"]},\"set\":{\"presenter\":\"iconValue\",\"tone\":\"neutral\",\"suffix\":\"h (hist)\"}},{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]},\"effect\":{\"presenter\":\"chip\",\"tone\":\"warning\",\"appearance\":\"soft\",\"icon\":\"schedule\",\"suffix\":\"h/wk\",\"tooltip\":\"Above standard weekly cap\"}}]"
                        )
                }
        )
        String campo;
    }

    private static class NonArrayPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(name = "presentationRules", value = "{\"when\":{}}"))
        String campo;
    }

    private static class MissingWhenPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(name = "presentationRules", value = "[{\"set\":{\"tone\":\"warning\"}}]"))
        String campo;
    }

    private static class InvalidOperatorPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\"unknownOp\":[{\"var\":\"weeklyHours\"},44]},\"set\":{\"tone\":\"warning\"}}]"
        ))
        String campo;
    }

    private static class MissingSetPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]}}]"
        ))
        String campo;
    }

    private static class EmptySetPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]},\"set\":{}}]"
        ))
        String campo;
    }

    private static class UnknownRulePropertyPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]},\"set\":{\"tone\":\"warning\"},\"extra\":123}]"
        ))
        String campo;
    }

    private static class UnknownSetPropertyPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]},\"set\":{\"presetner\":\"chip\"}}]"
        ))
        String campo;
    }

    private static class InvalidTonePresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]},\"set\":{\"tone\":\"amber\"}}]"
        ))
        String campo;
    }

    private static class InvalidPresenterPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]},\"set\":{\"presenter\":\"sparkline\"}}]"
        ))
        String campo;
    }

    private static class NonStringSuffixPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]},\"set\":{\"suffix\":123}}]"
        ))
        String campo;
    }

    private static class BlankIconPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]},\"set\":{\"icon\":\"   \"}}]"
        ))
        String campo;
    }

    private static class BlankSuffixPresentationRules {
        @UISchema(extraProperties = @ExtensionProperty(
                name = "presentationRules",
                value = "[{\"when\":{\">\":[{\"var\":\"weeklyHours\"},44]},\"set\":{\"suffix\":\"   \"}}]"
        ))
        String campo;
    }

    private static class BlankBaseSuffixPresentation {
        @UISchema(extraProperties = @ExtensionProperty(name = "presentation.suffix", value = "   "))
        String campo;
    }

    private static class InvalidBaseTonePresentation {
        @UISchema(extraProperties = @ExtensionProperty(name = "presentation.tone", value = "amber"))
        String campo;
    }

    private static class InvalidBasePresenterPresentation {
        @UISchema(extraProperties = @ExtensionProperty(name = "presentation.presenter", value = "sparkline"))
        String campo;
    }

    private static class ValidBooleanPresentationWithAffixes {
        @UISchema(
                extraProperties = {
                        @ExtensionProperty(name = "presentation.presenter", value = "badge"),
                        @ExtensionProperty(name = "presentation.prefix", value = "R$ "),
                        @ExtensionProperty(name = "presentation.suffix", value = " pts"),
                        @ExtensionProperty(name = "presentation.tone", value = "muted"),
                        @ExtensionProperty(name = "presentation.iconTrue", value = "check_circle"),
                        @ExtensionProperty(name = "presentation.iconFalse", value = "cancel"),
                        @ExtensionProperty(name = "presentation.labelTrue", value = "Sim"),
                        @ExtensionProperty(name = "presentation.labelFalse", value = "Não"),
                        @ExtensionProperty(name = "presentation.toneTrue", value = "success"),
                        @ExtensionProperty(name = "presentation.toneFalse", value = "neutral")
                }
        )
        String campo;
    }

    private static class ValidQuickViewAndTonesPresentation {
        @UISchema(
                extraProperties = {
                        @ExtensionProperty(name = "presentation.presenter", value = "chip"),
                        @ExtensionProperty(name = "presentation.tone", value = "secondary"),
                        @ExtensionProperty(name = "presentation.appearance", value = "soft"),
                        @ExtensionProperty(name = "presentation.interactions.copy", value = "true"),
                        @ExtensionProperty(
                                name = "presentation.interactions.quickView",
                                value = "{\"resourceKey\":\"workforce.employees\",\"surfaceId\":\"detail\",\"detailPresentation\":\"drawer\"}"
                        )
                }
        )
        String campo;
    }

    private static class BooleanQuickViewInteraction {
        @UISchema(extraProperties = @ExtensionProperty(name = "presentation.interactions.quickView", value = "true"))
        String campo;
    }

    private static class StringQuickViewInteraction {
        @UISchema(extraProperties = @ExtensionProperty(name = "presentation.interactions.quickView", value = "detail"))
        String campo;
    }

    private static class EmptyQuickViewInteraction {
        @UISchema(extraProperties = @ExtensionProperty(name = "presentation.interactions.quickView", value = "{}"))
        String campo;
    }

    private void assertInvalidCondition(Class<?> source, String expectedMessageFragment) throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("campo");

        Annotation uiSchema = source.getDeclaredField("campo").getAnnotation(UISchema.class);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolver.applyBeanValidatorAnnotations(property, new Annotation[]{ uiSchema }, null, false));
        assertTrue(ex.getMessage().contains(expectedMessageFragment), ex.getMessage());
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
