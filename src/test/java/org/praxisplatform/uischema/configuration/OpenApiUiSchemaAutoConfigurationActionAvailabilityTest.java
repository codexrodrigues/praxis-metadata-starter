package org.praxisplatform.uischema.configuration;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionAvailabilityRule;
import org.praxisplatform.uischema.action.ActionAvailabilityEvaluator;
import org.praxisplatform.uischema.action.DefaultActionAvailabilityEvaluator;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.action.ActionAvailabilityContext;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.surface.ResourceStateSnapshot;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiUiSchemaAutoConfigurationActionAvailabilityTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class));

    @Test
    void actionAvailabilityEvaluatorUsesRulesProvidedByAutoConfigurationCallSite() {
        OpenApiUiSchemaAutoConfiguration configuration = new OpenApiUiSchemaAutoConfiguration();
        ActionAvailabilityRule customRule = (definition, context) ->
                AvailabilityDecision.deny("custom-rule", Map.of("source", "custom"));

        ActionAvailabilityEvaluator evaluator = configuration.actionAvailabilityEvaluator(List.of(customRule));

        assertInstanceOf(DefaultActionAvailabilityEvaluator.class, evaluator);

        AvailabilityDecision decision = evaluator.evaluate(definition(), new ActionAvailabilityContext(
                "example.employees",
                "/employees",
                10L,
                null,
                Locale.ROOT,
                null,
                Set.of("employee:approve"),
                ResourceStateSnapshot.of("INACTIVE")
        ));

        assertEquals(false, decision.allowed());
        assertEquals("custom-rule", decision.reason());
        assertEquals("custom", decision.metadata().get("source"));
        assertNotNull(decision.metadata());
    }

    @Test
    void actionAvailabilityEvaluatorSupportsContainerLevelOverrideAndOrdering() {
        contextRunner
                .withBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new)
                .withBean(
                        "contextualActionAvailabilityRule",
                        ActionAvailabilityRule.class,
                        CustomContextualActionAvailabilityRule::new
                )
                .withBean(
                        "terminalActionAvailabilityRule",
                        ActionAvailabilityRule.class,
                        TerminalActionAvailabilityRule::new
                )
                .withBean(
                        "lateActionAvailabilityRule",
                        ActionAvailabilityRule.class,
                        LateActionAvailabilityRule::new
                )
                .run(context -> {
                    ActionAvailabilityEvaluator evaluator = context.getBean(ActionAvailabilityEvaluator.class);

                    AvailabilityDecision decision = evaluator.evaluate(definition(), new ActionAvailabilityContext(
                            "example.employees",
                            "/employees",
                            10L,
                            null,
                            Locale.ROOT,
                            null,
                            Set.of("employee:approve"),
                            ResourceStateSnapshot.of("INACTIVE")
                    ));

                    assertInstanceOf(DefaultActionAvailabilityEvaluator.class, evaluator);
                    assertEquals(false, decision.allowed());
                    assertEquals("custom-terminal", decision.reason());
                    assertEquals("custom-contextual", decision.metadata().get("contextualSource"));
                    assertEquals(true, decision.metadata().get("terminalRule"));
                    assertEquals(null, decision.metadata().get("lateRule"));
                });
    }

    private ActionDefinition definition() {
        return new ActionDefinition(
                "approve",
                "example.employees",
                "/employees",
                "example",
                ActionScope.ITEM,
                "Approve",
                "",
                new CanonicalOperationRef("example", "approveEmployee", "/employees/{id}/actions/approve", "POST"),
                new CanonicalSchemaRef("request-id", "request", "/schemas/filtered?path=/employees/{id}/actions/approve"),
                new CanonicalSchemaRef("response-id", "response", "/schemas/filtered?path=/employees/{id}/actions/approve"),
                10,
                "Approved",
                List.of("employee:approve"),
                List.of("INACTIVE"),
                List.of("workflow")
        );
    }

    static final class CustomContextualActionAvailabilityRule implements ActionAvailabilityRule, Ordered {

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public AvailabilityDecision evaluate(ActionDefinition definition, ActionAvailabilityContext context) {
            return AvailabilityDecision.allow(Map.of("contextualSource", "custom-contextual"));
        }
    }

    static final class TerminalActionAvailabilityRule implements ActionAvailabilityRule, Ordered {

        @Override
        public int getOrder() {
            return 50;
        }

        @Override
        public AvailabilityDecision evaluate(ActionDefinition definition, ActionAvailabilityContext context) {
            return AvailabilityDecision.deny("custom-terminal", Map.of("terminalRule", true));
        }
    }

    static final class LateActionAvailabilityRule implements ActionAvailabilityRule, Ordered {

        @Override
        public int getOrder() {
            return 60;
        }

        @Override
        public AvailabilityDecision evaluate(ActionDefinition definition, ActionAvailabilityContext context) {
            return AvailabilityDecision.allow(Map.of("lateRule", true));
        }
    }
}
