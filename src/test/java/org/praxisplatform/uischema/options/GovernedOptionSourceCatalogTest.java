package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GovernedOptionSourceCatalogTest {

    @Test
    void createsProviderBackedRichEntityLookupDescriptor() {
        OptionSourcePolicy policy = new OptionSourcePolicy(
                true,
                true,
                "contains",
                0,
                20,
                100,
                true,
                true,
                "label"
        );
        EntityLookupDescriptor entityLookup = new EntityLookupDescriptor(
                "city",
                "code",
                List.of("state", "country"),
                "status",
                "disabled",
                "disabledReason",
                List.of("code", "name", "state"),
                Map.of("stateId", "state.id"),
                new LookupSelectionPolicy(
                        "selectable",
                        "status",
                        List.of("ACTIVE"),
                        List.of("BLOCKED"),
                        true,
                        "{{label}} is blocked",
                        null
                ),
                new LookupCapabilities(true, true, true, false, false, true, false, false, false, false),
                new LookupDetailDescriptor(null, null, null, "surface", "detail", "drawer", "praxis-dynamic-form", "view"),
                new LookupFilteringDescriptor(
                        List.of(new LookupFilterDefinition(
                                "status",
                                "Status",
                                "enum",
                                List.of("equals", "in"),
                                "in",
                                "city-status",
                                false,
                                false
                        )),
                        Map.of("status", List.of("ACTIVE")),
                        List.of(new LookupSortOption("nameAsc", "name", "asc", "Name A-Z")),
                        "nameAsc",
                        List.of("code", "name"),
                        "Search city"
                )
        );

        OptionSourceDescriptor descriptor = GovernedOptionSourceCatalog.providerBackedLookup(
                "cities",
                "/api/cities",
                "cityId",
                "id",
                "name",
                "id",
                List.of("stateId"),
                Map.of("stateId", "state.id"),
                policy,
                entityLookup
        );

        assertEquals("cities", descriptor.key());
        assertEquals(OptionSourceType.RESOURCE_ENTITY, descriptor.type());
        assertEquals("/api/cities", descriptor.resourcePath());
        assertEquals("cityId", descriptor.filterField());
        assertEquals("id", descriptor.propertyPath());
        assertEquals("name", descriptor.labelPropertyPath());
        assertEquals("id", descriptor.valuePropertyPath());
        assertEquals(List.of("stateId"), descriptor.dependsOn());
        assertEquals(Map.of("stateId", "state.id"), descriptor.dependencyFilterMap());
        assertSame(policy, descriptor.policy());
        assertSame(entityLookup, descriptor.entityLookup());
        assertEquals(OptionSourceExecutionMode.PROVIDER_REQUIRED, descriptor.executionMode());
        assertEquals("/api/cities/option-sources/cities/options/filter", descriptor.runtimeContract().filterEndpoint());
        assertEquals("/api/cities/option-sources/cities/options/by-ids", descriptor.runtimeContract().byIdsEndpoint());

        Map<String, Object> metadata = descriptor.toMetadataMap();

        assertEquals("RESOURCE_ENTITY", metadata.get("type"));
        assertEquals("city", metadata.get("entityKey"));
        assertEquals("id", metadata.get("propertyPath"));
        assertEquals("code", metadata.get("codePropertyPath"));
        assertEquals(List.of("state", "country"), metadata.get("descriptionPropertyPaths"));
        assertEquals(List.of("code", "name", "state"), metadata.get("searchPropertyPaths"));
        assertEquals(Map.of("stateId", "state.id"), metadata.get("dependencyFilterMap"));
        assertEquals("/api/cities/option-sources/cities/options/filter", metadata.get("filterEndpoint"));
        assertEquals("/api/cities/option-sources/cities/options/by-ids", metadata.get("byIdsEndpoint"));
        assertNotNull(metadata.get("selectionPolicy"));
        assertNotNull(metadata.get("capabilities"));
        assertNotNull(metadata.get("detail"));
        assertNotNull(metadata.get("filtering"));
    }

    @Test
    void rejectsMissingRichEntityLookupDescriptor() {
        assertThrows(NullPointerException.class, () -> GovernedOptionSourceCatalog.providerBackedLookup(
                "cities",
                "/api/cities",
                "cityId",
                "name",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                null
        ));
    }
}
