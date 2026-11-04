package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.determination.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlatformAuthoringBuildersTest {
    @Test void directoryBuildsEverySubcontractWithoutAdvertisingWrites() {
        var descriptor = EntityLookupDescriptor.directoryBuilder("departments", "code", "name", "region")
                .status("status").dependencies(Map.of("companyId", "companyId"))
                .detailSurface("detail", "preview").build();
        assertEquals(List.of("name", "region"), descriptor.descriptionPropertyPaths());
        assertEquals(List.of("code", "name", "region"), descriptor.searchPropertyPaths());
        assertTrue(descriptor.selectionPolicy().allowRetainInvalidExistingValue());
        assertEquals("status", descriptor.selectionPolicy().statusPropertyPath());
        assertEquals("directory", descriptor.display().preset());
        assertEquals(4, descriptor.display().fields().size());
        assertEquals("description", descriptor.filtering().defaultSort());
        assertFalse(descriptor.filtering().searchStrategies().isEmpty());
        assertEquals("preview", descriptor.detail().previewSurfaceId());
        assertEquals("departments", descriptor.detail().resourceKey());
        assertEquals("drawer", descriptor.detail().presentation());
        assertEquals(Map.of("companyId", "companyId"), descriptor.dependencyFilterMap());
        assertTrue(descriptor.capabilities().detail()); assertFalse(descriptor.capabilities().create());
        assertFalse(descriptor.capabilities().edit());
        assertTrue(descriptor.toMetadataMap().containsKey("filtering"));
    }

    @Test void directoryRetainsExplicitPolicyAndRejectsIncompleteIdentity() {
        var policy = new LookupSelectionPolicy(null, "status", List.of("OPEN"), List.of("CLOSED"), true, "Unavailable", "Choose an open record");
        var directory = EntityLookupDescriptor.directoryBuilder("items", "code", "name").selectionPolicy(policy).build();
        assertSame(policy, directory.selectionPolicy());
        assertThrows(IllegalArgumentException.class, () -> EntityLookupDescriptor.directoryBuilder("", "code", "name"));
        assertThrows(IllegalArgumentException.class, () -> EntityLookupDescriptor.directoryBuilder("items", "code"));
    }

    @Test void directoryUsesTheSameNormalizedDescriptionPathsInEverySubcontract() {
        var descriptor = EntityLookupDescriptor.directoryBuilder("items", "code", "", "name", "name").status(" ").build();
        assertEquals(List.of("name"), descriptor.descriptionPropertyPaths());
        assertEquals(List.of("code", "name"), descriptor.searchPropertyPaths());
        assertEquals(2, descriptor.display().fields().size());
        assertEquals("name", descriptor.display().primaryPropertyPath());
        assertNull(descriptor.statusPropertyPath());
    }

    @Test void determinationUsesExactCrudOperationIdsAndImmutableBindings() {
        var builder = ReactiveDeterminationDefinition.forCrudResource("recalculate", "createItem", "updateItem", "calculateItem")
                .source("/amount").input("/amount", "/draft/amount").output("/total", "/total")
                .provenance(new ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST, "pricing", "1"));
        var definition = builder.build(); builder.source("/discount");
        assertEquals(List.of("/amount"), definition.sourcePaths());
        assertEquals(List.of(new ReactiveDeterminationScope("createItem", ReactiveDeterminationFormMode.CREATE),
                new ReactiveDeterminationScope("updateItem", ReactiveDeterminationFormMode.EDIT)), definition.scopes());
        assertEquals(ReactiveDeterminationTriggerMode.ON_CHANGE, definition.triggerMode());
        assertEquals("calculateItem", definition.operationId());
        assertEquals("/draft/amount", definition.inputs().getFirst().requestPath());
        assertEquals("/total", definition.outputs().getFirst().responsePath());
    }

    @Test void determinationRequiresSourcesOutputsProvenanceAndJsonPointers() {
        assertThrows(IllegalArgumentException.class, () -> ReactiveDeterminationDefinition.forCrudResource("x", "create", "update", "calculate").build());
        assertThrows(IllegalArgumentException.class, () -> ReactiveDeterminationDefinition.forCrudResource("x", "create", "create", "calculate"));
        assertThrows(IllegalArgumentException.class, () -> ReactiveDeterminationDefinition.forCrudResource("x", "create", "update", "calculate").source("amount"));
        var onInit = ReactiveDeterminationDefinition.forCrudResource("x", "create", "update", "calculate")
                .triggerMode(ReactiveDeterminationTriggerMode.ON_INIT).output("/default", "/value")
                .provenance(new ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST, "defaults", "1")).build();
        assertTrue(onInit.sourcePaths().isEmpty());
    }
}

