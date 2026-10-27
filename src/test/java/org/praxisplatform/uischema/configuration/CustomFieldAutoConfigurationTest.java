package org.praxisplatform.uischema.configuration;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.customfields.*;
import org.praxisplatform.uischema.fact.*;
import org.praxisplatform.uischema.schema.ApiResourceIdentityResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomFieldAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class));

    @Test void providesDefaultExtensionBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ResourceTenantContextResolver.class)
                    .hasSingleBean(CustomFieldDefinitionProvider.class).hasSingleBean(CustomFieldValidator.class)
                    .hasSingleBean(CustomFieldSchemaEnricher.class).hasSingleBean(ResourceFactCatalogProvider.class)
                    .doesNotHaveBean(ApiResourceIdentityResolver.class);
            assertThat(context.getBean(CustomFieldDefinitionProvider.class)).isInstanceOf(NoOpCustomFieldDefinitionProvider.class);
            assertThat(context.getBean(ResourceTenantContextResolver.class)).isInstanceOf(RequestHeaderResourceTenantContextResolver.class);
            assertThat(context.getBean(ResourceFactCatalogProvider.class)).isInstanceOf(AnnotationResourceFactCatalogProvider.class);
        });
    }

    @Test void installsResourceIdentityResolverWhenCanonicalMvcMappingExists() {
        var mapping = new RequestMappingHandlerMapping();
        runner.withBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class, () -> mapping)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ApiResourceIdentityResolver.class);
                    assertThat(context.getBean(ApiResourceIdentityResolver.class)
                            .resolveResourceKey("/unknown", "/unknown", "get")).isEmpty();
                });
    }

    @Test void preservesHostResourceIdentityResolverOverrideWithMvcPresent() {
        var mapping = new RequestMappingHandlerMapping();
        var resolver = new ApiResourceIdentityResolver(mapping);
        runner.withBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class, () -> mapping)
                .withBean("hostIdentityResolver", ApiResourceIdentityResolver.class, () -> resolver)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ApiResourceIdentityResolver.class);
                    assertThat(context.getBean(ApiResourceIdentityResolver.class)).isSameAs(resolver);
                });
    }

    @Test void honorsHostOverridesWithoutInstallingParallelDefaults() {
        ResourceTenantContextResolver tenants = () -> Optional.of("authenticated");
        CustomFieldDefinitionProvider definitions = (r, t, s) -> List.of();
        ResourceFactCatalogProvider facts = (r, t) -> List.of();
        runner.withBean("hostTenants", ResourceTenantContextResolver.class, () -> tenants)
                .withBean("hostFields", CustomFieldDefinitionProvider.class, () -> definitions)
                .withBean("hostFacts", ResourceFactCatalogProvider.class, () -> facts)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ResourceTenantContextResolver.class)
                            .hasSingleBean(CustomFieldDefinitionProvider.class).hasSingleBean(ResourceFactCatalogProvider.class);
                    assertThat(context.getBean(ResourceTenantContextResolver.class)).isSameAs(tenants);
                    assertThat(context.getBean(CustomFieldDefinitionProvider.class)).isSameAs(definitions);
                    assertThat(context.getBean(ResourceFactCatalogProvider.class)).isSameAs(facts);
                });
    }
}
