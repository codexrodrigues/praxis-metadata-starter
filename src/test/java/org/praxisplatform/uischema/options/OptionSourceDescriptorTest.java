package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
