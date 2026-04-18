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
                        new LookupDetailDescriptor("/api/companies/{id}", "/companies/{id}", "drawer")
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
        assertEquals("/api/companies/{id}", detail.get("hrefTemplate"));
        assertEquals("/companies/{id}", detail.get("routeTemplate"));
        assertEquals("drawer", detail.get("openDetailMode"));
    }
}
