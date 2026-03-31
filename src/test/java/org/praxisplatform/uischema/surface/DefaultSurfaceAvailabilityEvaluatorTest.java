package org.praxisplatform.uischema.surface;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSurfaceAvailabilityEvaluatorTest {

    private final DefaultSurfaceAvailabilityEvaluator evaluator = new DefaultSurfaceAvailabilityEvaluator();

    @Test
    void deniesItemSurfaceWithoutConcreteResourceContext() {
        SurfaceDefinition definition = definition("detail", SurfaceScope.ITEM, List.of(), List.of());
        SurfaceAvailabilityContext context = new SurfaceAvailabilityContext(
                "example.employees",
                "/employees",
                null,
                null,
                Locale.forLanguageTag("pt-BR"),
                null,
                java.util.Set.of(),
                null
        );

        AvailabilityDecision decision = evaluator.evaluate(definition, context);

        assertFalse(decision.allowed());
        assertEquals("resource-context-required", decision.reason());
        assertFalse((Boolean) decision.metadata().get("contextual"));
        assertEquals("ITEM", decision.metadata().get("scope"));
    }

    @Test
    void allowsContextualItemSurfaceAndCarriesAvailabilityMetadata() {
        SurfaceDefinition definition = definition("profile", SurfaceScope.ITEM, List.of(), List.of());
        SurfaceAvailabilityContext context = new SurfaceAvailabilityContext(
                "example.employees",
                "/employees",
                10L,
                "tenant-a",
                Locale.forLanguageTag("pt-BR"),
                () -> "qa-user",
                java.util.Set.of("employee:profile:update"),
                ResourceStateSnapshot.of("ACTIVE")
        );

        AvailabilityDecision decision = evaluator.evaluate(definition, context);

        assertTrue(decision.allowed());
        assertNull(decision.reason());
        assertTrue((Boolean) decision.metadata().get("contextual"));
        assertTrue((Boolean) decision.metadata().get("tenantPresent"));
        assertTrue((Boolean) decision.metadata().get("principalPresent"));
        assertEquals("ITEM", decision.metadata().get("scope"));
    }

    @Test
    void deniesWhenRequiredAuthorityIsMissing() {
        SurfaceDefinition definition = definition(
                "profile",
                SurfaceScope.ITEM,
                List.of("employee:profile:update"),
                List.of()
        );
        SurfaceAvailabilityContext context = new SurfaceAvailabilityContext(
                "example.employees",
                "/employees",
                10L,
                null,
                Locale.forLanguageTag("pt-BR"),
                () -> "qa-user",
                java.util.Set.of(),
                ResourceStateSnapshot.of("ACTIVE")
        );

        AvailabilityDecision decision = evaluator.evaluate(definition, context);

        assertFalse(decision.allowed());
        assertEquals("missing-authority", decision.reason());
        assertEquals(List.of("employee:profile:update"), decision.metadata().get("requiredAuthorities"));
        assertEquals(List.of("employee:profile:update"), decision.metadata().get("missingAuthorities"));
    }

    @Test
    void shortCircuitsOnMissingAuthorityWithoutLeakingLaterStateMetadata() {
        SurfaceDefinition definition = definition(
                "profile",
                SurfaceScope.ITEM,
                List.of("employee:profile:update"),
                List.of("ACTIVE")
        );
        SurfaceAvailabilityContext context = new SurfaceAvailabilityContext(
                "example.employees",
                "/employees",
                10L,
                null,
                Locale.forLanguageTag("pt-BR"),
                () -> "qa-user",
                java.util.Set.of(),
                ResourceStateSnapshot.of("INACTIVE")
        );

        AvailabilityDecision decision = evaluator.evaluate(definition, context);

        assertFalse(decision.allowed());
        assertEquals("missing-authority", decision.reason());
        assertEquals(List.of("employee:profile:update"), decision.metadata().get("requiredAuthorities"));
        assertEquals(List.of("employee:profile:update"), decision.metadata().get("missingAuthorities"));
        assertNull(decision.metadata().get("allowedStates"));
        assertNull(decision.metadata().get("resourceState"));
    }

    @Test
    void deniesWhenResourceStateDoesNotMatchAllowedStates() {
        SurfaceDefinition definition = definition(
                "profile",
                SurfaceScope.ITEM,
                List.of("employee:profile:update"),
                List.of("ACTIVE")
        );
        SurfaceAvailabilityContext context = new SurfaceAvailabilityContext(
                "example.employees",
                "/employees",
                10L,
                null,
                Locale.forLanguageTag("pt-BR"),
                () -> "qa-user",
                java.util.Set.of("employee:profile:update"),
                ResourceStateSnapshot.of("INACTIVE")
        );

        AvailabilityDecision decision = evaluator.evaluate(definition, context);

        assertFalse(decision.allowed());
        assertEquals("resource-state-blocked", decision.reason());
        assertEquals("INACTIVE", decision.metadata().get("resourceState"));
        assertEquals(List.of("ACTIVE"), decision.metadata().get("allowedStates"));
    }

    @Test
    void deniesWhenAllowedStateIsDeclaredButStateSnapshotIsUnavailable() {
        SurfaceDefinition definition = definition(
                "profile",
                SurfaceScope.ITEM,
                List.of("employee:profile:update"),
                List.of("ACTIVE")
        );
        SurfaceAvailabilityContext context = new SurfaceAvailabilityContext(
                "example.employees",
                "/employees",
                10L,
                null,
                Locale.forLanguageTag("pt-BR"),
                () -> "qa-user",
                java.util.Set.of("employee:profile:update"),
                null
        );

        AvailabilityDecision decision = evaluator.evaluate(definition, context);

        assertFalse(decision.allowed());
        assertEquals("resource-state-unavailable", decision.reason());
        assertEquals(List.of("ACTIVE"), decision.metadata().get("allowedStates"));
        assertNull(decision.metadata().get("resourceState"));
    }

    private SurfaceDefinition definition(
            String id,
            SurfaceScope scope,
            List<String> requiredAuthorities,
            List<String> allowedStates
    ) {
        return new SurfaceDefinition(
                id,
                "example.employees",
                "/employees",
                "example",
                scope == SurfaceScope.ITEM ? SurfaceKind.PARTIAL_FORM : SurfaceKind.FORM,
                scope,
                id,
                "",
                id,
                "request",
                new CanonicalOperationRef("example", id, "/employees/{id}/" + id, "PATCH"),
                new CanonicalSchemaRef("schema-id", "request", "/schemas/filtered?path=/employees"),
                10,
                requiredAuthorities,
                allowedStates,
                List.of()
        );
    }
}
