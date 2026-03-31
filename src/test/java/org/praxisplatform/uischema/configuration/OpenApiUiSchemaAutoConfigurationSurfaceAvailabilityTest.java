package org.praxisplatform.uischema.configuration;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.surface.DefaultSurfaceAvailabilityEvaluator;
import org.praxisplatform.uischema.surface.ResourceStateSnapshot;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityContext;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityEvaluator;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityRule;
import org.praxisplatform.uischema.surface.SurfaceDefinition;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest {

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
}
