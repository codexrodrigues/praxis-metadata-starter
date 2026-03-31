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
        SurfaceDefinition definition = definition("detail", SurfaceScope.ITEM);
        SurfaceAvailabilityContext context = new SurfaceAvailabilityContext(
                "example.employees",
                "/employees",
                null,
                null,
                Locale.forLanguageTag("pt-BR"),
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
        SurfaceDefinition definition = definition("profile", SurfaceScope.ITEM);
        SurfaceAvailabilityContext context = new SurfaceAvailabilityContext(
                "example.employees",
                "/employees",
                10L,
                "tenant-a",
                Locale.forLanguageTag("pt-BR"),
                () -> "qa-user"
        );

        AvailabilityDecision decision = evaluator.evaluate(definition, context);

        assertTrue(decision.allowed());
        assertNull(decision.reason());
        assertTrue((Boolean) decision.metadata().get("contextual"));
        assertTrue((Boolean) decision.metadata().get("tenantPresent"));
        assertTrue((Boolean) decision.metadata().get("principalPresent"));
        assertEquals("ITEM", decision.metadata().get("scope"));
    }

    private SurfaceDefinition definition(String id, SurfaceScope scope) {
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
                List.of()
        );
    }
}
