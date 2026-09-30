package org.praxisplatform.uischema.configuration;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiUiSchemaAutoConfigurationHttpBudgetTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class))
            .withBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new)
            .withPropertyValues("springdoc.cache.disabled=true");

    @Test
    void configuredDurationsReachTheOwnedTransportAndCompositionBudget() {
        runner.withPropertyValues("praxis.openapi.http.connect-timeout=125ms",
                "praxis.openapi.http.read-timeout=750ms", "praxis.openapi.bulk-composition-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(RestTemplate.class);
                    assertThat(client).isInstanceOf(OpenApiInternalRestTemplate.class);
                    Object factory = ReflectionTestUtils.getField(client, "ownedFactory");
                    assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(Duration.ofMillis(125));
                    assertThat(ReflectionTestUtils.getField(factory, "responseTimeout")).isEqualTo(Duration.ofMillis(750));
                    var documents = context.getBean(OpenApiDocumentService.class);
                    assertThat(ReflectionTestUtils.getField(documents, "bulkCompositionTimeout")).isEqualTo(Duration.ofSeconds(2));
                    assertThat(documents.supportsFreshBulkLifecycleComposition()).isTrue();
                });
    }

    @Test
    void invalidTimeoutsFailAtStartup() {
        for (String property : new String[] {"praxis.openapi.http.connect-timeout=0ms",
                "praxis.openapi.http.read-timeout=-1ms", "praxis.openapi.bulk-composition-timeout=0ms"}) {
            runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void unrelatedCustomRestTemplateIsPreservedButCannotAdvertiseBoundedBulkFreshness() {
        var hostClient = new RestTemplate();
        var factory = hostClient.getRequestFactory();
        runner.withBean("hostRestTemplate", RestTemplate.class, () -> hostClient).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RestTemplate.class)).isSameAs(hostClient);
            assertThat(hostClient.getRequestFactory()).isSameAs(factory);
            assertThat(context.getBean(OpenApiDocumentService.class).supportsFreshBulkLifecycleComposition()).isFalse();
        });
    }
}
