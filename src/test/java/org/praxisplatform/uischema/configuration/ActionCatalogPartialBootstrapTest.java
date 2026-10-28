package org.praxisplatform.uischema.configuration;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.*;
import org.praxisplatform.uischema.capability.*;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ActionCatalogPartialBootstrapTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class));

    @Test
    void customActionRegistryWithoutMvcKeepsOrdinaryActionAndDeniesUncomposedDuplicate() {
        runner.withBean(ActionDefinitionRegistry.class, this::registry).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ActionCatalogService.class)
                    .doesNotHaveBean(SurfaceCatalogService.class).doesNotHaveBean(CapabilityService.class);
            var actions = context.getBean(ActionCatalogService.class);
            assertOrdinaryAllowedAndDuplicateDenied(actions);
            assertThat(ReflectionTestUtils.getField(actions, "operationAvailability")).isNull();
        });
    }

    @Test
    void mvcDefaultsComposeExactlyOneFacadeAndUseTheRegisteredSurfaceCatalog() {
        runner.withBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ActionCatalogService.class)
                    .hasSingleBean(SurfaceCatalogService.class).hasSingleBean(CapabilityService.class);
            var evaluator = ReflectionTestUtils.getField(context.getBean(ActionCatalogService.class), "operationAvailability");
            assertThat(evaluator).isNotNull();
            assertThat(ReflectionTestUtils.getField(evaluator, "surfaces"))
                    .isSameAs(context.getBean(SurfaceCatalogService.class));
            assertThat(ReflectionTestUtils.getField(context.getBean(CapabilityService.class), "actionCatalogService"))
                    .isSameAs(context.getBean(ActionCatalogService.class));
        });
    }

    @Test
    void customCatalogIsPreservedWithoutMvcOrSurfaceDependency() {
        var custom = customCatalog();
        runner.withBean(ActionDefinitionRegistry.class, this::registry)
                .withBean(ActionCatalogService.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ActionCatalogService.class)
                    .doesNotHaveBean(SurfaceCatalogService.class);
            assertThat(context.getBean(ActionCatalogService.class)).isSameAs(custom);
            assertOrdinaryAllowedAndDuplicateDenied(custom);
        });
    }

    @Test
    void customCatalogBacksOffDefaultEvenWhenMvcDependenciesArePresent() {
        var custom = customCatalog();
        runner.withBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new)
                .withBean(ActionCatalogService.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ActionCatalogService.class)
                    .hasSingleBean(SurfaceCatalogService.class).hasSingleBean(CapabilityService.class);
            assertThat(context.getBean(ActionCatalogService.class)).isSameAs(custom);
            assertThat(ReflectionTestUtils.getField(context.getBean(CapabilityService.class), "actionCatalogService"))
                    .isSameAs(custom);
            assertOrdinaryAllowedAndDuplicateDenied(custom);
        });
    }

    @Test
    void ambiguousSurfaceCatalogsAreNotSilentlySelected() {
        runner.withBean(ActionDefinitionRegistry.class, this::registry)
                .withBean("firstSurfaces", SurfaceCatalogService.class, () -> org.mockito.Mockito.mock(SurfaceCatalogService.class))
                .withBean("secondSurfaces", SurfaceCatalogService.class, () -> org.mockito.Mockito.mock(SurfaceCatalogService.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class);
                });
    }

    private void assertOrdinaryAllowedAndDuplicateDenied(ActionCatalogService catalog) {
        var actions = catalog.findByResourceKey("example.custom").actions();
        assertThat(actions).hasSize(2);
        assertThat(actions.stream().filter(a -> a.id().equals("recalculate")).findFirst().orElseThrow()
                .availability().allowed()).isTrue();
        var duplicate = catalog.findItemActions("example.custom", 7L).actions().getFirst();
        assertThat(duplicate.id()).isEqualTo("duplicate-draft");
        assertThat(duplicate.availability().allowed()).isFalse();
        assertThat(duplicate.availability().reason()).isEqualTo("operation-availability-unconfigured");
    }

    private ActionCatalogService customCatalog() {
        return new ActionCatalogService(registry(), new DefaultActionAvailabilityEvaluator(),
                new DefaultActionAvailabilityContextResolver());
    }

    private ActionDefinitionRegistry registry() {
        var definitions = List.of(definition("recalculate", ActionScope.COLLECTION),
                definition("duplicate-draft", ActionScope.ITEM));
        return new ActionDefinitionRegistry() {
            public List<ActionDefinition> findByResourceKey(String key) { return definitions; }
            public List<ActionDefinition> findByGroup(String group) { return definitions; }
        };
    }

    private ActionDefinition definition(String id, ActionScope scope) {
        return new ActionDefinition(id, "example.custom", "/custom", "example", scope, id,
                "Custom registry fixture", new CanonicalOperationRef("example", id,
                        scope == ActionScope.ITEM ? "/custom/{id}/duplicate-draft" : "/custom/actions/recalculate", "POST"),
                null, null, 0, null, List.of(), List.of(), List.of(), ActionExecutionContract.defaults(scope));
    }
}
