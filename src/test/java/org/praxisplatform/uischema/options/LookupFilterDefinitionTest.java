package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupFilterDefinitionTest {

    @Test
    void defaultsOperatorsByTypeWhenTheyAreNotExplicitlyDeclared() {
        LookupFilterDefinition definition = new LookupFilterDefinition(
                "documentNumber",
                "Documento",
                "text",
                null,
                null,
                null,
                false,
                false
        );

        assertEquals(List.of("contains", "startsWith", "equals"), definition.operators());
        assertEquals("contains", definition.defaultOperator());
        assertEquals("documentNumber", definition.effectiveEntityPropertyPath());
    }

    @Test
    void rejectsOperatorThatIsNotSupportedByFieldType() {
        assertThrows(IllegalArgumentException.class, () -> new LookupFilterDefinition(
                "status",
                "Status",
                "enum",
                List.of("contains"),
                "contains",
                null,
                false,
                false
        ));
    }

    @Test
    void publishesOptionsOptionSourceDependenciesAndKeepsTargetPropertyPathInternal() {
        LookupFilterDefinition definition = new LookupFilterDefinition(
                "departmentId",
                "Department",
                "reference",
                List.of("equals", "in"),
                "equals",
                "department",
                List.of(Map.of("value", "ENG", "label", "Engineering")),
                Map.of("key", "department", "type", "RESOURCE_ENTITY", "resourcePath", "/api/departments"),
                List.of("companyId"),
                Map.of("companyId", "company.id"),
                true,
                false,
                "department.id"
        );

        assertEquals("department.id", definition.effectiveEntityPropertyPath());
        Map<String, Object> metadata = definition.toMetadataMap();
        assertEquals(List.of(Map.of("value", "ENG", "label", "Engineering")), metadata.get("options"));
        assertEquals(Map.of("key", "department", "type", "RESOURCE_ENTITY", "resourcePath", "/api/departments"),
                metadata.get("optionSource"));
        assertEquals(List.of("companyId"), metadata.get("dependsOn"));
        assertEquals(Map.of("companyId", "company.id"), metadata.get("dependencyFilterMap"));
        assertTrue((Boolean) metadata.get("required"));
        assertFalse(metadata.containsKey("targetPropertyPath"));
    }

    @Test
    void rejectsDependencyFilterMapKeyNotDeclaredInDependsOn() {
        assertThrows(IllegalArgumentException.class, () -> new LookupFilterDefinition(
                "departmentId",
                "Department",
                "reference",
                List.of("equals"),
                "equals",
                "department",
                List.of(),
                Map.of(),
                List.of("companyId"),
                Map.of("tenantId", "tenant.id"),
                false,
                false
        ));
    }
}
