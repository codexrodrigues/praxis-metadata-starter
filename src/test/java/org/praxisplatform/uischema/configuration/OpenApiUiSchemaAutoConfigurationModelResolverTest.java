package org.praxisplatform.uischema.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SpringDocConfigProperties.ApiDocs.OpenApiVersion;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenApiUiSchemaAutoConfigurationModelResolverTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class));

    @Test
    void mvcMetadataContextWiresTheDeterminationCatalogAndNonWebContextDoesNot() {
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class,
                        org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping::new)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(org.praxisplatform.uischema.domain.ReactiveDeterminationCatalogSource.class)
                            .hasSingleBean(org.praxisplatform.uischema.domain.SemanticDomainCatalogService.class);
                });
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(org.praxisplatform.uischema.domain.ReactiveDeterminationCatalogSource.class);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolverUsesTheExistingSpringdocDialect(boolean openApi31) {
        SpringDocConfigProperties properties = new SpringDocConfigProperties();
        properties.getApiDocs().setVersion(openApi31 ? OpenApiVersion.OPENAPI_3_1 : OpenApiVersion.OPENAPI_3_0);

        runner.withInitializer(context -> context.getBeanFactory()
                .registerSingleton("springDocConfigProperties", properties)).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(openApi31, context.getBean(CustomOpenApiResolver.class).isOpenapi31());
            assertEquals(properties, context.getBean(SpringDocConfigProperties.class));
        });
    }

    @Test
    void nonWebContextWithoutSpringdocPropertiesKeepsItsExistingResolverDefault() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeansOfType(SpringDocConfigProperties.class).size());
            assertFalse(context.getBean(CustomOpenApiResolver.class).isOpenapi31());
        });
    }

    @Test
    void disabledApiDocsDoesNotRequireOrCreateSpringdocProperties() {
        runner.withPropertyValues("springdoc.api-docs.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeansOfType(SpringDocConfigProperties.class).size());
            assertFalse(context.getBean(CustomOpenApiResolver.class).isOpenapi31());
        });
    }
}
