package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionSourceDescriptorTest {

    @Test
    void defaultsPolicyAndNormalizesOptionalPaths() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "payrollProfile",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/human-resources/vw-analytics-folha-pagamento",
                "profileFilter",
                "payrollProfile",
                " ",
                null,
                List.of("competenciaBetween", "universo"),
                null
        );

        assertEquals("payrollProfile", descriptor.key());
        assertEquals(OptionSourceType.DISTINCT_DIMENSION, descriptor.type());
        assertEquals("/api/human-resources/vw-analytics-folha-pagamento", descriptor.resourcePath());
        assertEquals("profileFilter", descriptor.filterField());
        assertEquals("profileFilter", descriptor.effectiveFilterField());
        assertEquals("payrollProfile", descriptor.propertyPath());
        assertEquals(null, descriptor.labelPropertyPath());
        assertEquals(null, descriptor.valuePropertyPath());
        assertEquals(List.of("competenciaBetween", "universo"), descriptor.dependsOn());
        assertEquals(Map.of(), descriptor.dependencyFilterMap());
        assertEquals("contains", descriptor.policy().searchMode());
        assertEquals(25, descriptor.policy().defaultPageSize());
        assertEquals("label", descriptor.policy().defaultSort());
        assertEquals("/api/human-resources/vw-analytics-folha-pagamento/option-sources/payrollProfile/options/filter",
                descriptor.runtimeContract().filterEndpoint());
        assertEquals("/api/human-resources/vw-analytics-folha-pagamento/option-sources/payrollProfile/options/by-ids",
                descriptor.runtimeContract().byIdsEndpoint());
    }

    @Test
    void requiresKeyTypeAndResourcePath() {
        assertThrows(IllegalArgumentException.class, () -> new OptionSourceDescriptor(
                " ",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/test",
                null,
                null,
                null,
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new OptionSourceDescriptor(
                "perfil",
                null,
                "/api/test",
                null,
                null,
                null,
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new OptionSourceDescriptor(
                "perfil",
                OptionSourceType.DISTINCT_DIMENSION,
                " ",
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void rejectsOptionSourceKeysThatAreNotUrlSafe() {
        assertThrows(IllegalArgumentException.class, () -> new OptionSourceDescriptor(
                "payroll/profile",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/test",
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void publishesDependencyFilterMapForAnyOptionSourceType() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "payrollProfile",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/human-resources/vw-analytics-folha-pagamento",
                null,
                "payrollProfile",
                null,
                null,
                List.of(" universo ", "", "universo"),
                Map.of("universo", "empresa.universo"),
                OptionSourcePolicy.defaults(),
                null
        );

        Map<String, Object> metadata = descriptor.toMetadataMap();

        assertEquals(List.of("universo"), metadata.get("dependsOn"));
        assertEquals(Map.of("universo", "empresa.universo"), metadata.get("dependencyFilterMap"));
        assertEquals("/api/human-resources/vw-analytics-folha-pagamento/option-sources/payrollProfile/options/filter",
                metadata.get("filterEndpoint"));
        assertEquals("/api/human-resources/vw-analytics-folha-pagamento/option-sources/payrollProfile/options/by-ids",
                metadata.get("byIdsEndpoint"));
        assertEquals("required", metadata.get("selectedReloadPolicy"));
        assertEquals("reject", metadata.get("invalidSortPolicy"));
    }

    @Test
    void supportsExplicitUnsupportedSelectedReloadPolicy() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "legacyStatus",
                OptionSourceType.LIGHT_LOOKUP,
                "/api/legacy/statuses",
                null,
                null,
                "label",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                null,
                OptionSourceExecutionMode.PROVIDER_REQUIRED,
                new OptionSourceRuntimeContract(
                        "/api/legacy/statuses/option-sources/legacyStatus/options/filter",
                        null,
                        OptionSourceSelectedReloadPolicy.UNSUPPORTED_WITH_WAIVER,
                        OptionSourceInvalidSortPolicy.UNSUPPORTED
                )
        );

        Map<String, Object> metadata = descriptor.toMetadataMap();

        assertEquals("/api/legacy/statuses/option-sources/legacyStatus/options/filter", metadata.get("filterEndpoint"));
        assertEquals("unsupported-with-waiver", metadata.get("selectedReloadPolicy"));
        assertEquals("unsupported", metadata.get("invalidSortPolicy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesTopLevelFilteringForProviderBackedSourcesWithoutEntityLookup() {
        LookupFilteringDescriptor filtering = new LookupFilteringDescriptor(
                List.of(new LookupFilterDefinition(
                        "tipoEvento",
                        "Tipo de evento",
                        "text",
                        List.of("equals", "contains"),
                        "equals",
                        null,
                        false,
                        false
                )),
                Map.of(),
                List.of(),
                null,
                List.of("tipoEvento"),
                "Filtrar por tipo de evento"
        );
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "externalDependencyLookup",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/events",
                null,
                null,
                "label",
                "id",
                List.of("tipoEvento"),
                Map.of("tipoEvento", "tipoEvento"),
                OptionSourcePolicy.defaults(),
                null,
                filtering,
                OptionSourceExecutionMode.PROVIDER_REQUIRED
        );

        Map<String, Object> metadata = descriptor.toMetadataMap();

        assertEquals(filtering, descriptor.effectiveFiltering());
        Map<String, Object> publishedFiltering = (Map<String, Object>) metadata.get("filtering");
        List<Map<String, Object>> availableFilters = (List<Map<String, Object>>) publishedFiltering.get("availableFilters");
        assertEquals("tipoEvento", availableFilters.get(0).get("field"));
        assertEquals(List.of("equals", "contains"), availableFilters.get(0).get("operators"));
        assertEquals(List.of("tipoEvento"), publishedFiltering.get("quickFilterFields"));
    }

    @Test
    void keepsEntityLookupFilteringAsCompatibilityFallback() {
        LookupFilteringDescriptor filtering = new LookupFilteringDescriptor(
                List.of(new LookupFilterDefinition(
                        "status",
                        "Status",
                        "enum",
                        List.of("equals"),
                        "equals",
                        null,
                        false,
                        false
                )),
                Map.of(),
                List.of(),
                null,
                List.of(),
                null
        );
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "company",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/companies",
                null,
                null,
                "legalName",
                "id",
                List.of(),
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        "company",
                        "code",
                        List.of(),
                        null,
                        null,
                        null,
                        List.of("legalName"),
                        Map.of(),
                        null,
                        new LookupCapabilities(true, true, true, false, false, true, false, true, true, true),
                        null,
                        filtering
                )
        );

        assertEquals(filtering, descriptor.effectiveFiltering());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesResourceEntityLookupMetadata() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "company",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/companies",
                null,
                null,
                "legalName",
                "id",
                List.of("tenantId"),
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        "company",
                        "code",
                        List.of("documentNumber", "city", "state"),
                        "status",
                        "disabled",
                        "disabledReason",
                        List.of("code", "legalName", "documentNumber"),
                        Map.of("tenantId", "tenant.id"),
                        new LookupSelectionPolicy(
                                "selectable",
                                "status",
                                List.of("ACTIVE"),
                                List.of("BLOCKED"),
                                true,
                                "{{label}} cannot be selected: {{disabledReason}}",
                                null
                        ),
                        new LookupCapabilities(true, true, true, false, false, true, false, true, true, true),
                        new LookupDetailDescriptor(null, null, null, "surface", "detail", "drawer", "praxis-dynamic-form", "view"),
                        new LookupDisplayDescriptor(
                                "directory",
                                "form",
                                "comfortable",
                                "compact",
                                "list",
                                "legalName",
                                List.of(
                                        new LookupDisplayFieldDescriptor("document", "documentNumber", "Documento", "badge", "chip", "neutral", null),
                                        new LookupDisplayFieldDescriptor("location", "city", "Cidade", "location_on", "text", "info", null),
                                        new LookupDisplayFieldDescriptor("status", "status", "Status", "verified", "badge", "success", null)
                                ),
                                List.of("documentNumber", "city", "state"),
                                List.of("status", "city"),
                                null,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                3
                        ),
                        new LookupFilteringDescriptor(
                                List.of(
                                        new LookupFilterDefinition(
                                                "status",
                                                "Status",
                                                "enum",
                                                List.of("equals", "in"),
                                                "in",
                                                "company-status",
                                                false,
                                                false
                                        ),
                                        new LookupFilterDefinition(
                                                "documentNumber",
                                                "Documento",
                                                "text",
                                                List.of("contains", "equals"),
                                                "contains",
                                                null,
                                                false,
                                                false
                                        )
                                ),
                                Map.of("status", List.of("ACTIVE")),
                                List.of(
                                        new LookupSortOption("legalNameAsc", "legalName", "asc", "Nome A-Z"),
                                        new LookupSortOption("codeAsc", "code", "asc", "Codigo A-Z")
                                ),
                                "legalNameAsc",
                                List.of("code", "legalName"),
                                "Buscar empresa por codigo, nome ou documento"
                        )
                )
        );

        Map<String, Object> metadata = descriptor.toMetadataMap();

        assertEquals("company", metadata.get("key"));
        assertEquals("RESOURCE_ENTITY", metadata.get("type"));
        assertEquals("company", metadata.get("entityKey"));
        assertEquals("id", metadata.get("valuePropertyPath"));
        assertEquals("legalName", metadata.get("labelPropertyPath"));
        assertEquals("code", metadata.get("codePropertyPath"));
        assertEquals(List.of("documentNumber", "city", "state"), metadata.get("descriptionPropertyPaths"));
        assertEquals(List.of("code", "legalName", "documentNumber"), metadata.get("searchPropertyPaths"));
        assertEquals(Map.of("tenantId", "tenant.id"), metadata.get("dependencyFilterMap"));

        Map<String, Object> selectionPolicy = (Map<String, Object>) metadata.get("selectionPolicy");
        assertEquals("selectable", selectionPolicy.get("selectablePropertyPath"));
        assertEquals(List.of("ACTIVE"), selectionPolicy.get("allowedStatuses"));
        assertEquals(true, selectionPolicy.get("allowRetainInvalidExistingValue"));

        Map<String, Object> capabilities = (Map<String, Object>) metadata.get("capabilities");
        assertEquals(true, capabilities.get("filter"));
        assertEquals(true, capabilities.get("byIds"));
        assertEquals(true, capabilities.get("detail"));
        assertEquals(false, capabilities.get("create"));

        Map<String, Object> detail = (Map<String, Object>) metadata.get("detail");
        assertEquals("surface", detail.get("kind"));
        assertEquals("detail", detail.get("surfaceId"));
        assertEquals("drawer", detail.get("presentation"));
        assertEquals("praxis-dynamic-form", detail.get("preferredWidget"));
        assertEquals("view", detail.get("mode"));

        Map<String, Object> display = (Map<String, Object>) metadata.get("display");
        assertEquals("directory", display.get("preset"));
        assertEquals("form", display.get("usage"));
        assertEquals("comfortable", display.get("density"));
        assertEquals("compact", display.get("selectedLayout"));
        assertEquals("list", display.get("resultLayout"));
        assertEquals("legalName", display.get("primaryPropertyPath"));
        List<Map<String, Object>> displayFields = (List<Map<String, Object>>) display.get("fields");
        assertEquals("document", displayFields.get(0).get("key"));
        assertEquals("documentNumber", displayFields.get(0).get("propertyPath"));
        assertEquals("badge", displayFields.get(0).get("icon"));
        assertEquals("chip", displayFields.get(0).get("presentation"));
        assertEquals(List.of("documentNumber", "city", "state"), display.get("secondaryPropertyPaths"));
        assertEquals(List.of("status", "city"), display.get("badgePropertyPaths"));
        assertEquals(true, display.get("showAvatar"));
        assertEquals(3, display.get("maxVisibleBadges"));

        Map<String, Object> filtering = (Map<String, Object>) metadata.get("filtering");
        assertEquals("legalNameAsc", filtering.get("defaultSort"));
        assertEquals(List.of("code", "legalName"), filtering.get("quickFilterFields"));
        assertEquals("Buscar empresa por codigo, nome ou documento", filtering.get("searchPlaceholder"));
    }
}
