package org.praxisplatform.uischema.configuration;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.surface.DefaultSurfaceAvailabilityEvaluator;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityContext;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityEvaluator;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityRule;
import org.praxisplatform.uischema.surface.SurfaceDefinition;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class));

    @Test
    void surfaceAvailabilityEvaluatorUsesRulesProvidedByAutoConfigurationCallSite() {
        OpenApiUiSchemaAutoConfiguration configuration = new OpenApiUiSchemaAutoConfiguration();
        SurfaceAvailabilityRule customRule = (definition, context) ->
                AvailabilityDecision.deny("custom-rule", Map.of("source", "custom"));

        SurfaceAvailabilityEvaluator evaluator = configuration.surfaceAvailabilityEvaluator(List.of(customRule));

        assertInstanceOf(DefaultSurfaceAvailabilityEvaluator.class, evaluator);

        AvailabilityDecision decision = evaluator.evaluate(definition(), new SurfaceAvailabilityContext(
                "example.employees",
                "/employees",
                10L,
                null,
                Locale.ROOT,
                null,
                java.util.Set.of(),
                ResourceStateSnapshot.of("ACTIVE")
        ));

        assertEquals(false, decision.allowed());
        assertEquals("custom-rule", decision.reason());
        assertEquals("custom", decision.metadata().get("source"));
    }

    @Test
    void surfaceAvailabilityEvaluatorSupportsContainerLevelOverrideAndOrdering() {
        contextRunner
                .withBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new)
                .withBean(
                        "contextualSurfaceAvailabilityRule",
                        SurfaceAvailabilityRule.class,
                        CustomContextualSurfaceAvailabilityRule::new
                )
                .withBean(
                        "terminalSurfaceAvailabilityRule",
                        SurfaceAvailabilityRule.class,
                        TerminalSurfaceAvailabilityRule::new
                )
                .withBean(
                        "lateSurfaceAvailabilityRule",
                        SurfaceAvailabilityRule.class,
                        LateSurfaceAvailabilityRule::new
                )
                .run(context -> {
                    SurfaceAvailabilityEvaluator evaluator = context.getBean(SurfaceAvailabilityEvaluator.class);

                    AvailabilityDecision decision = evaluator.evaluate(definition(), new SurfaceAvailabilityContext(
                            "example.employees",
                            "/employees",
                            10L,
                            null,
                            Locale.ROOT,
                            null,
                            java.util.Set.of("employee:profile:update"),
                            ResourceStateSnapshot.of("ACTIVE")
                    ));

                    assertInstanceOf(DefaultSurfaceAvailabilityEvaluator.class, evaluator);
                    assertEquals(false, decision.allowed());
                    assertEquals("custom-terminal", decision.reason());
                    assertEquals("custom-contextual", decision.metadata().get("contextualSource"));
                    assertEquals(true, decision.metadata().get("terminalRule"));
                    assertEquals(null, decision.metadata().get("lateRule"));
                });
    }

    private SurfaceDefinition definition() {
        SurfaceDefinition definition = new SurfaceDefinition(
                "profile",
                "example.employees",
                "/employees",
                "example",
                SurfaceKind.PARTIAL_FORM,
                SurfaceScope.ITEM,
                "Perfil",
                "",
                "profile",
                "request",
                new CanonicalOperationRef("example", "updateProfile", "/employees/{id}/profile", "PATCH"),
                new CanonicalSchemaRef("schema-id", "request", "/schemas/filtered?path=/employees/{id}/profile"),
                10,
                List.of(),
                List.of("ACTIVE"),
                List.of()
        );
        assertNotNull(definition);
        return definition;
    }

    static final class CustomContextualSurfaceAvailabilityRule implements SurfaceAvailabilityRule, Ordered {

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context) {
            return AvailabilityDecision.allow(Map.of("contextualSource", "custom-contextual"));
        }
    }

    static final class TerminalSurfaceAvailabilityRule implements SurfaceAvailabilityRule, Ordered {

        @Override
        public int getOrder() {
            return 50;
        }

        @Override
        public AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context) {
            return AvailabilityDecision.deny("custom-terminal", Map.of("terminalRule", true));
        }
    }

    static final class LateSurfaceAvailabilityRule implements SurfaceAvailabilityRule, Ordered {

        @Override
        public int getOrder() {
            return 60;
        }

        @Override
        public AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context) {
            return AvailabilityDecision.allow(Map.of("lateRule", true));
        }
    }
}
