package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SchemaDescriptionHelpTextSeparationTest {

    private CustomOpenApiResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CustomOpenApiResolver(new ObjectMapper());
    }

    @Test
    void schemaDescriptionDoesNotBecomeUiHelpText() {
        Schema<String> schema = new Schema<>();
        schema.setType("string");
        schema.setDescription("Semantic business description for AI and OpenAPI documentation.");

        resolver.applyBeanValidatorAnnotations(schema, new java.lang.annotation.Annotation[]{
                TestUISchemaDefaults.instance()
        }, null, true);

        Map<String, Object> xUi = xUi(schema);

        assertFalse(xUi.containsKey(FieldConfigProperties.HELP_TEXT.getValue()));
    }

    @Test
    void explicitUiSchemaHelpTextIsPublishedAsUiHelpText() {
        Schema<String> schema = new Schema<>();
        schema.setType("string");
        schema.setDescription("Semantic business description for AI and OpenAPI documentation.");

        resolver.applyBeanValidatorAnnotations(schema, new java.lang.annotation.Annotation[]{
                uiSchemaWithHelpText("Short UI help.")
        }, null, true);

        Map<String, Object> xUi = xUi(schema);

        assertEquals("Short UI help.", xUi.get(FieldConfigProperties.HELP_TEXT.getValue()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> xUi(Schema<?> schema) {
        return (Map<String, Object>) schema.getExtensions().get("x-ui");
    }

    private static UISchema uiSchemaWithHelpText(String helpText) {
        UISchema defaults = TestUISchemaDefaults.instance();
        return (UISchema) Proxy.newProxyInstance(
                UISchema.class.getClassLoader(),
                new Class[]{UISchema.class},
                (proxy, method, args) -> {
                    if ("helpText".equals(method.getName())) {
                        return helpText;
                    }
                    return method.invoke(defaults, args);
                });
    }
}
