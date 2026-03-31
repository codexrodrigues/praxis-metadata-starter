package org.praxisplatform.uischema.action;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.surface.ResourceStateSnapshot;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultActionAvailabilityEvaluatorTest {

    private final DefaultActionAvailabilityEvaluator evaluator = new DefaultActionAvailabilityEvaluator();

    @Test
    void blocksItemActionWithoutConcreteResourceContext() {
        AvailabilityDecision decision = evaluator.evaluate(
                definition("approve", List.of("employee:approve"), List.of("INACTIVE")),
                new ActionAvailabilityContext("example.employees", "/employees", null, null, Locale.ROOT, null, Set.of(), null)
        );

        assertFalse(decision.allowed());
        assertEquals("resource-context-required", decision.reason());
        assertFalse((Boolean) decision.metadata().get("contextual"));
        assertEquals("ITEM", decision.metadata().get("scope"));
    }

    @Test
    void blocksWhenAuthorityIsMissing() {
        AvailabilityDecision decision = evaluator.evaluate(
                definition("approve", List.of("employee:approve"), List.of()),
                new ActionAvailabilityContext("example.employees", "/employees", 42L, null, Locale.ROOT, null, Set.of(), null)
        );

        assertFalse(decision.allowed());
        assertEquals("missing-authority", decision.reason());
        assertEquals(List.of("employee:approve"), decision.metadata().get("requiredAuthorities"));
    }

    @Test
    void shortCircuitsOnMissingAuthorityWithoutLeakingLaterStateMetadata() {
        AvailabilityDecision decision = evaluator.evaluate(
                definition("approve", List.of("employee:approve"), List.of("INACTIVE")),
                new ActionAvailabilityContext(
                        "example.employees",
                        "/employees",
                        42L,
                        null,
                        Locale.ROOT,
                        () -> "qa-user",
                        Set.of(),
                        ResourceStateSnapshot.of("ACTIVE")
                )
        );

        assertFalse(decision.allowed());
        assertEquals("missing-authority", decision.reason());
        assertEquals(List.of("employee:approve"), decision.metadata().get("requiredAuthorities"));
        assertEquals(List.of("employee:approve"), decision.metadata().get("missingAuthorities"));
        assertNull(decision.metadata().get("allowedStates"));
        assertNull(decision.metadata().get("resourceState"));
    }

    @Test
    void blocksWhenResourceStateDoesNotMatch() {
        AvailabilityDecision decision = evaluator.evaluate(
                definition("approve", List.of("employee:approve"), List.of("INACTIVE")),
                new ActionAvailabilityContext(
                        "example.employees",
                        "/employees",
                        42L,
                        null,
                        Locale.ROOT,
                        null,
                        Set.of("employee:approve"),
                        ResourceStateSnapshot.of("ACTIVE")
                )
        );

        assertFalse(decision.allowed());
        assertEquals("resource-state-blocked", decision.reason());
        assertEquals("ACTIVE", decision.metadata().get("resourceState"));
    }

    @Test
    void blocksWhenAllowedStateIsDeclaredButStateSnapshotIsUnavailable() {
        AvailabilityDecision decision = evaluator.evaluate(
                definition("approve", List.of("employee:approve"), List.of("INACTIVE")),
                new ActionAvailabilityContext(
                        "example.employees",
                        "/employees",
                        42L,
                        null,
                        Locale.ROOT,
                        () -> "qa-user",
                        Set.of("employee:approve"),
                        null
                )
        );

        assertFalse(decision.allowed());
        assertEquals("resource-state-unavailable", decision.reason());
        assertEquals(List.of("INACTIVE"), decision.metadata().get("allowedStates"));
        assertNull(decision.metadata().get("resourceState"));
    }

    @Test
    void allowsWhenAuthorityAndStateMatch() {
        AvailabilityDecision decision = evaluator.evaluate(
                definition("approve", List.of("employee:approve"), List.of("INACTIVE")),
                new ActionAvailabilityContext(
                        "example.employees",
                        "/employees",
                        42L,
                        "tenant-a",
                        Locale.ROOT,
                        () -> "qa-user",
                        Set.of("employee:approve"),
                        ResourceStateSnapshot.of("INACTIVE")
                )
        );

        assertTrue(decision.allowed());
        assertEquals(null, decision.reason());
        assertTrue((Boolean) decision.metadata().get("contextual"));
        assertTrue((Boolean) decision.metadata().get("tenantPresent"));
        assertTrue((Boolean) decision.metadata().get("principalPresent"));
    }

    private ActionDefinition definition(String id, List<String> requiredAuthorities, List<String> allowedStates) {
        return new ActionDefinition(
                id,
                "example.employees",
                "/employees",
                "example",
                ActionScope.ITEM,
                "Approve",
                "",
                new CanonicalOperationRef("example", "approveEmployee", "/employees/{id}/actions/approve", "POST"),
                new CanonicalSchemaRef("schema-request", "request", "/schemas/filtered?path=/employees/{id}/actions/approve"),
                new CanonicalSchemaRef("schema-response", "response", "/schemas/filtered?path=/employees/{id}/actions/approve"),
                10,
                "Approved",
                requiredAuthorities,
                allowedStates,
                List.of("workflow")
        );
    }
}
