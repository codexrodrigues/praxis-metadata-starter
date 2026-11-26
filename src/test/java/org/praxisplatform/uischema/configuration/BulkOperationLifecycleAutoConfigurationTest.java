package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.bulk.BulkControlPlaneInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionRoleConfiguration;
import org.praxisplatform.uischema.bulk.BulkOperationLifecycle;
import org.praxisplatform.uischema.bulk.BulkResourceOperationBindings;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BulkOperationLifecycleAutoConfigurationTest {

    private WebApplicationContextRunner contextRunner() {
        var roles = new BulkExecutionRoleConfiguration("owner", Set.of("runtime"), Set.of(), Set.of("control"));
        var runtimeDs = mock(DataSource.class);
        var controlDs = mock(DataSource.class);
        var runtime = new BulkExecutionInfrastructure(
                runtimeDs, new DataSourceTransactionManager(runtimeDs), "ns", "dep", roles);
        var controlPlane = new BulkControlPlaneInfrastructure(
                controlDs, new DataSourceTransactionManager(controlDs), "ns", "dep", "control", runtime);

        var mvc = new RequestMappingHandlerMapping();

        var client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10));
        var documents = new CachedOpenApiDocumentService(
                client, new ObjectMapper(), mock(OpenApiDocsSupport.class), true);

        var bindings = BulkResourceOperationBindings.from(mvc);

        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BulkOperationLifecycleAutoConfiguration.class))
                .withBean(BulkControlPlaneInfrastructure.class, () -> controlPlane)
                .withBean(BulkExecutionInfrastructure.class, () -> runtime)
                .withBean(RequestMappingHandlerMapping.class, () -> mvc)
                .withBean(CanonicalOperationResolver.class, () -> mock(CanonicalOperationResolver.class))
                .withBean(OpenApiDocumentService.class, () -> documents)
                .withBean(ActionDefinitionRegistry.class, () -> mock(ActionDefinitionRegistry.class))
                .withBean(SchemaReferenceResolver.class, () -> mock(SchemaReferenceResolver.class))
                .withBean(BulkResourceOperationBindings.class, () -> bindings)
                .withBean(CanonicalCapabilityResolver.class, () -> mock(CanonicalCapabilityResolver.class))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @Test
    void registersLifecycleAndFilterByDefaultWhenPropertyIsOmitted() {
        contextRunner().run(context -> {
            assertThat(context).hasSingleBean(BulkOperationLifecycleAutoConfiguration.class);
            assertThat(context).hasSingleBean(BulkOperationLifecycle.class);
            assertThat(context).hasBean("governedOpenApiPublicationFilterRegistration");
        });
    }

    @Test
    void registersLifecycleWhenExplicitlyEnabled() {
        contextRunner()
                .withPropertyValues("praxis.bulk.lifecycle.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(BulkOperationLifecycle.class);
                    assertThat(context).hasBean("governedOpenApiPublicationFilterRegistration");
                });
    }

    @Test
    void skipsLifecycleAndFilterWhenDeclarativelyDisabled() {
        contextRunner()
                .withPropertyValues("praxis.bulk.lifecycle.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BulkOperationLifecycleAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(BulkOperationLifecycle.class);
                    assertThat(context).doesNotHaveBean("governedOpenApiPublicationFilterRegistration");
                });
    }
}
