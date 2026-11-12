package org.praxisplatform.uischema.action;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

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

    @Test
    void propagatesConventionalSnapshotLegalAndCustomMetadataOnAllowedAction() {
        ResourceStateSnapshot snapshot = ResourceStateSnapshot.of(
                "INACTIVE",
                java.util.Map.of(
                        "action.approve.allowed", true,
                        "action.approve.legalBasis", "Lei 8.112/1990, art. 20",
                        "action.approve.legalPurpose", "HUMAN_RESOURCES_ADMINISTRATION",
                        "action.approve.customTitle", "Homologar Estágio Probatório (36 meses)"
                )
        );

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
                        snapshot
                )
        );

        assertTrue(decision.allowed());
        assertNull(decision.reason());
        assertEquals(true, decision.metadata().get("allowed"));
        assertEquals("Lei 8.112/1990, art. 20", decision.metadata().get("legalBasis"));
        assertEquals("HUMAN_RESOURCES_ADMINISTRATION", decision.metadata().get("legalPurpose"));
        assertEquals("Homologar Estágio Probatório (36 meses)", decision.metadata().get("customTitle"));
    }

    @Test
    void deniesActionWhenSnapshotMarksAllowedFalseAndPropagatesReasonAndLegalMetadata() {
        ResourceStateSnapshot snapshot = ResourceStateSnapshot.of(
                "INACTIVE",
                java.util.Map.of(
                        "action.approve.allowed", false,
                        "action.approve.reason", "Aprovação suspensa por incompatibilidade estatutária.",
                        "action.approve.legalBasis", "CF/88, art. 40, § 1º, III",
                        "action.approve.legalPurpose", "STATUTORY_COMPLIANCE",
                        "action.approve.customTitle", "Aprovar (Indisponível para Estagiário)",
                        "action.approve.blockedByRule", "STATUTORY_REGIME_INCOMPATIBLE"
                )
        );

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
                        snapshot
                )
        );

        assertFalse(decision.allowed());
        assertEquals("Aprovação suspensa por incompatibilidade estatutária.", decision.reason());
        assertEquals(false, decision.metadata().get("allowed"));
        assertEquals("Aprovação suspensa por incompatibilidade estatutária.", decision.metadata().get("reason"));
        assertEquals("CF/88, art. 40, § 1º, III", decision.metadata().get("legalBasis"));
        assertEquals("STATUTORY_COMPLIANCE", decision.metadata().get("legalPurpose"));
        assertEquals("Aprovar (Indisponível para Estagiário)", decision.metadata().get("customTitle"));
        assertEquals("STATUTORY_REGIME_INCOMPATIBLE", decision.metadata().get("blockedByRule"));
    }

    @Test
    void appliesResourceLevelLegalFallbackWhenActionSpecificLegalMetadataIsNotProvided() {
        ResourceStateSnapshot snapshot = ResourceStateSnapshot.of(
                "INACTIVE",
                java.util.Map.of(
                        "resource.legalBasis", "Lei Complementar nº 840/2011",
                        "resource.legalPurpose", "PUBLIC_SERVICE_GOVERNANCE"
                )
        );

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
                        snapshot
                )
        );

        assertTrue(decision.allowed());
        assertEquals("Lei Complementar nº 840/2011", decision.metadata().get("legalBasis"));
        assertEquals("PUBLIC_SERVICE_GOVERNANCE", decision.metadata().get("legalPurpose"));
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
                List.of("workflow"),
                ActionExecutionContract.defaults(ActionScope.ITEM)
        );
    }
}
