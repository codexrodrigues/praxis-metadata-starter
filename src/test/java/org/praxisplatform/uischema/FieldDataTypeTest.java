package org.praxisplatform.uischema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldDataTypeTest {

    @Test
    void fromValueIsCaseInsensitive() {
        for (FieldDataType type : FieldDataType.values()) {
            assertEquals(type, FieldDataType.fromValue(type.getValue().toUpperCase()));
            assertEquals(type, FieldDataType.fromValue(type.getValue().toLowerCase()));
        }
    }

    @Test
    void fromValueUsesEnglishErrorMessageForUnknownValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> FieldDataType.fromValue("unknown-type")
        );

        assertEquals("Unknown data type: unknown-type", exception.getMessage());
    }
}
