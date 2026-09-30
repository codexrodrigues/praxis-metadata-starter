package org.praxisplatform.uischema.configuration;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.GenerationScopedGenericResponseService;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.service.GenericResponseService;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenApiResponseGenerationAutoConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            // Deliberately reverse the input: auto-configuration ordering must select the adapter.
            .withConfiguration(AutoConfigurations.of(SpringDocWebMvcConfiguration.class,
                    OpenApiResponseGenerationAutoConfiguration.class, SpringDocConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withBean(SpringDocConfigProperties.class, SpringDocConfigProperties::new);

    @Test
    void selectsOneBuilderBeforeSpringdocAndDiscoversTheSameGlobalCustomizer() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GenericResponseService.class);
            var adapter = context.getBean(GenerationScopedGenericResponseService.class);
            assertThat(context.getBean(GenericResponseService.class)).isSameAs(adapter);
            assertThat(context.getBeansOfType(GlobalOpenApiCustomizer.class)).containsValue(adapter);
            assertThat(context).doesNotHaveBean("responseBuilder");
            assertThat(adapter.getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
        });
    }

    @Test
    void preservesAHostGenericResponseServiceOverride() {
        var host = mock(GenericResponseService.class);
        runner.withBean("hostResponses", GenericResponseService.class, () -> host).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GenericResponseService.class);
            assertThat(context.getBean(GenericResponseService.class)).isSameAs(host);
            assertThat(context).doesNotHaveBean(GenerationScopedGenericResponseService.class);
        });
    }

    @Test
    void doesNotInstallWithoutTheSpringdocCoreConfigurationBean() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenApiResponseGenerationAutoConfiguration.class,
                        SpringDocWebMvcConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringDocConfiguration.class);
                    assertThat(context).doesNotHaveBean(GenericResponseService.class);
                });
    }

    @Test
    void doesNotInstallInANonServletApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenApiResponseGenerationAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GenericResponseService.class);
                });
    }

    @Test
    void disabledApiDocsDoNotInstallTheAdapter() {
        runner.withPropertyValues("springdoc.api-docs.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(GenerationScopedGenericResponseService.class);
        });
    }
}
