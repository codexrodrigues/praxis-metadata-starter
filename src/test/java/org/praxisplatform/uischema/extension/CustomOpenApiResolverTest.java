package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.annotation.AiUsageMode;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernanceKind;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.annotation.Annotation;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomOpenApiResolverTest {

    private static class Dummy {
        @UISchema(controlType = org.praxisplatform.uischema.FieldControlType.INPUT)
        String descricao;
    }

    private static class PercentDummy {
        @UISchema(numericFormat = NumericFormat.PERCENT)
        Double taxaDesconto;
    }

    private static class OverrideDummy {
        @UISchema(extraProperties = {
                @io.swagger.v3.oas.annotations.extensions.ExtensionProperty(
                        name = "valuePresentation.type",
                        value = "currency"
                )
        })
        String competencia;
    }

    private static class GovernanceDummy {
        @DomainGovernance(
                kind = DomainGovernanceKind.PRIVACY,
                classification = DomainClassification.CONFIDENTIAL,
                dataCategory = DomainDataCategory.PERSONAL,
                complianceTags = {"LGPD", "GDPR"},
                aiUsage = @AiUsagePolicy(
                        visibility = AiUsageMode.MASK,
                        trainingUse = AiUsageMode.DENY,
                        ruleAuthoring = AiUsageMode.REVIEW_REQUIRED,
                        reasoningUse = AiUsageMode.REVIEW_REQUIRED
                ),
                reason = "Documento pessoal do colaborador.",
                confidence = 0.99d
        )
        String cpf;
    }

    @Test
    void resolverShouldKeepInputForNomeCompletoWithMax200() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("nomeCompleto");
        property.setMaxLength(200);

        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { TestUISchemaDefaults.instance() }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(FieldControlType.INPUT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void resolverShouldUseTextareaForDescricaoWithMax1000() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("descricao");
        property.setMaxLength(1000);

        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { TestUISchemaDefaults.instance() }, null, false);

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
        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { uiSchema, TestUISchemaDefaults.instance() }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(FieldControlType.INPUT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void resolverShouldPublishDateValuePresentationFromOpenApiFormat() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string").format("date");
        property.setName("dataNascimento");

        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { TestUISchemaDefaults.instance() }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(Map.of(FieldConfigProperties.TYPE.getValue(), "date"), xui.get(FieldConfigProperties.VALUE_PRESENTATION.getValue()));
    }

    @Test
    void resolverShouldPublishPercentageValuePresentationFromNumericFormat() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<Object> property = new Schema<>().type("number");
        property.setName("taxaDesconto");

        Annotation uiSchema = PercentDummy.class.getDeclaredField("taxaDesconto").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { uiSchema, TestUISchemaDefaults.instance() }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(Map.of(FieldConfigProperties.TYPE.getValue(), "percentage"), xui.get(FieldConfigProperties.VALUE_PRESENTATION.getValue()));
    }

    @Test
    void extraPropertiesShouldOverrideNestedValuePresentation() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string").format("date");
        property.setName("competencia");

        Annotation uiSchema = OverrideDummy.class.getDeclaredField("competencia").getAnnotation(UISchema.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { uiSchema, TestUISchemaDefaults.instance() }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(Map.of(FieldConfigProperties.TYPE.getValue(), "currency"), xui.get(FieldConfigProperties.VALUE_PRESENTATION.getValue()));
    }

    @Test
    void resolverShouldSkipValuePresentationForRangeControls() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("array");
        property.setName("periodo");
        property.addExtension(
                "x-ui",
                new java.util.LinkedHashMap<String, Object>(Map.of(
                        FieldConfigProperties.CONTROL_TYPE.getValue(),
                        FieldControlType.DATE_RANGE.getValue()
                ))
        );

        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { TestUISchemaDefaults.instance() }, null, false);

        Map<String, Object> xui = getXui(property);
        assertNull(xui.get(FieldConfigProperties.VALUE_PRESENTATION.getValue()));
    }

    @Test
    void resolverShouldSkipValuePresentationForSelectionControls() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<Object> property = new Schema<>().type("number");
        property.setName("statusId");
        java.util.List<Object> options = new java.util.ArrayList<>();
        options.add(1L);
        options.add(2L);
        property.setEnum(options);

        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { TestUISchemaDefaults.instance() }, null, false);

        Map<String, Object> xui = getXui(property);
        assertEquals(FieldControlType.SELECT.getValue(), xui.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertNull(xui.get(FieldConfigProperties.VALUE_PRESENTATION.getValue()));
    }

    @Test
    void resolverShouldSkipValuePresentationForInlineSelectionControls() {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("departamentoId");
        property.addExtension(
                "x-ui",
                new java.util.LinkedHashMap<String, Object>(Map.of(
                        FieldConfigProperties.CONTROL_TYPE.getValue(),
                        FieldControlType.INLINE_SELECT.getValue()
                ))
        );

        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { TestUISchemaDefaults.instance() }, null, false);

        Map<String, Object> xui = getXui(property);
        assertNull(xui.get(FieldConfigProperties.VALUE_PRESENTATION.getValue()));
    }

    @Test
    void resolverShouldPublishExplicitDomainGovernanceExtension() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(new ObjectMapper());
        Schema<?> property = new Schema<>().type("string");
        property.setName("cpf");

        Annotation governance = GovernanceDummy.class.getDeclaredField("cpf").getAnnotation(DomainGovernance.class);
        resolver.applyBeanValidatorAnnotations(property, new Annotation[] { governance, TestUISchemaDefaults.instance() }, null, false);

        assertNotNull(property.getExtensions(), "Extensions should not be null");
        Object rawGovernance = property.getExtensions().get("x-domain-governance");
        assertNotNull(rawGovernance, "x-domain-governance extension should be present");
        assertTrue(rawGovernance instanceof Map, "x-domain-governance should be a Map");

        @SuppressWarnings("unchecked")
        Map<String, Object> governanceMap = (Map<String, Object>) rawGovernance;
        assertEquals("privacy", governanceMap.get("annotationType"));
        assertEquals("confidential", governanceMap.get("classification"));
        assertEquals("personal", governanceMap.get("dataCategory"));
        assertEquals("java.annotation", governanceMap.get("source"));
        assertEquals(0.99d, governanceMap.get("confidence"));

        @SuppressWarnings("unchecked")
        Map<String, Object> aiUsage = (Map<String, Object>) governanceMap.get("aiUsage");
        assertEquals("mask", aiUsage.get("visibility"));
        assertEquals("deny", aiUsage.get("trainingUse"));
        assertEquals("review_required", aiUsage.get("ruleAuthoring"));
        assertEquals("review_required", aiUsage.get("reasoningUse"));
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
