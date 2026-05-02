package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
