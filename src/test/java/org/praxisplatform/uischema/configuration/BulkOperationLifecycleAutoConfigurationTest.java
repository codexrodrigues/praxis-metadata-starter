package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.bulk.BulkControlPlaneInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionRoleConfiguration;
import org.praxisplatform.uischema.bulk.BulkOperationControlIdentity;
import org.praxisplatform.uischema.bulk.BulkOperationLifecycle;
import org.praxisplatform.uischema.bulk.BulkPublicationAdminController;
import org.praxisplatform.uischema.bulk.BulkPublicationJobService;
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
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
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
            assertThat(context).hasSingleBean(BulkPublicationJobService.class);
            assertThat(context).doesNotHaveBean(BulkPublicationAdminController.class);
            assertThat(context).hasBean("governedOpenApiPublicationFilterRegistration");
        });
    }

    @Test
    void registersLifecycleWhenExplicitlyEnabled() {
        contextRunner()
                .withPropertyValues("praxis.bulk.lifecycle.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(BulkOperationLifecycle.class);
                    assertThat(context).hasSingleBean(BulkPublicationJobService.class);
                    assertThat(context).doesNotHaveBean(BulkPublicationAdminController.class);
                    assertThat(context).hasBean("governedOpenApiPublicationFilterRegistration");
                });
    }

    @Test
    void registersAdminControllerOnlyWhenExplicitlyEnabled() {
        contextRunner()
                .withPropertyValues("praxis.bulk.lifecycle.admin-endpoints.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(BulkOperationLifecycle.class);
                    assertThat(context).hasSingleBean(BulkPublicationJobService.class);
                    assertThat(context).hasSingleBean(BulkPublicationAdminController.class);
                });
    }

    @Test
    void skipsLifecycleAndFilterWhenDeclarativelyDisabled() {
        contextRunner()
                .withPropertyValues(
                        "praxis.bulk.lifecycle.enabled=false",
                        "praxis.bulk.lifecycle.admin-endpoints.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BulkOperationLifecycleAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(BulkOperationLifecycle.class);
                    assertThat(context).doesNotHaveBean(BulkPublicationJobService.class);
                    assertThat(context).doesNotHaveBean(BulkPublicationAdminController.class);
                    assertThat(context).doesNotHaveBean("governedOpenApiPublicationFilterRegistration");
                });
    }

    @Test
    void componentScanDoesNotInstantiateAdminControllerWhenLifecycleDisabled() {
        new WebApplicationContextRunner()
                .withUserConfiguration(HostBulkPackageScanConfiguration.class)
                .withConfiguration(AutoConfigurations.of(BulkOperationLifecycleAutoConfiguration.class))
                .withPropertyValues(
                        "praxis.bulk.lifecycle.enabled=false",
                        "praxis.bulk.lifecycle.admin-endpoints.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(BulkPublicationAdminController.class);
                });
    }

    @Test
    void componentScanDoesNotBypassAdminEndpointsOptInGateWhenLifecycleEnabled() {
        contextRunner()
                .withUserConfiguration(HostBulkPackageScanConfiguration.class)
                .withPropertyValues("praxis.bulk.lifecycle.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BulkPublicationJobService.class);
                    assertThat(context).doesNotHaveBean(BulkPublicationAdminController.class);
                });
    }

    @Test
    void componentScanAllowsAutoConfiguredAdminControllerWhenExplicitlyEnabled() {
        contextRunner()
                .withUserConfiguration(HostBulkPackageScanConfiguration.class)
                .withPropertyValues(
                        "praxis.bulk.lifecycle.enabled=true",
                        "praxis.bulk.lifecycle.admin-endpoints.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BulkPublicationAdminController.class);
                    RequestMappingHandlerMapping handlerMapping =
                            context.getBean(RequestMappingHandlerMapping.class);
                    assertThat(handlerMapping.getHandlerMethods().keySet())
                            .anySatisfy(info -> assertThat(info.getPatternValues())
                                    .contains("/api/platform/bulk-operations/publication/jobs"));
                });
    }

    @Test
    void autoReadyInitializerRunsOnApplicationReadyEventNotContextRefreshedEvent() {
        var identity = new BulkOperationControlIdentity("ns", "op.bulk");
        BulkOperationLifecycle lifecycle = mock(BulkOperationLifecycle.class,
                org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        org.mockito.Mockito.when(lifecycle.configuredIdentities()).thenReturn(List.of(identity));

        contextRunner()
                .withBean(BulkOperationLifecycle.class, () -> lifecycle)
                .withPropertyValues("praxis.bulk.lifecycle.auto-ready-on-startup=true")
                .run(context -> {
                    assertThat(context).hasBean("praxisBulkLifecycleAutoReadyInitializer");
                    org.mockito.Mockito.verify(lifecycle, org.mockito.Mockito.never())
                            .ensureReadyOnStartup(org.mockito.ArgumentMatchers.any());

                    var app = new org.springframework.boot.SpringApplication(Object.class);
                    var sourceCtx = context.getSourceApplicationContext();
                    context.publishEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(
                            app, new String[0], sourceCtx, Duration.ofMillis(1)));
                    context.publishEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(
                            app, new String[0], sourceCtx, Duration.ofMillis(1)));

                    org.mockito.Mockito.verify(lifecycle, org.mockito.Mockito.times(1))
                            .ensureReadyOnStartup(identity);
                });
    }

    @Configuration
    @ComponentScan(
            basePackageClasses = BulkPublicationAdminController.class,
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "org\\.praxisplatform\\.uischema\\.bulk\\.(?:.*Test.*|p3fixture\\..*|persistence\\..*)"))
    static class HostBulkPackageScanConfiguration {
    }
}
