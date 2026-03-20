package org.praxisplatform.uischema.numeric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NumberFormatStyleTest {

    @Test
    void fromValueIsCaseInsensitive() {
        for (NumberFormatStyle style : NumberFormatStyle.values()) {
            assertEquals(style, NumberFormatStyle.fromValue(style.getValue().toUpperCase()));
            assertEquals(style, NumberFormatStyle.fromValue(style.getValue().toLowerCase()));
        }
    }

    @Test
    void fromValueUsesEnglishErrorMessageForUnknownValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> NumberFormatStyle.fromValue("unknown-style")
        );

        assertEquals("Unknown numeric format style: unknown-style", exception.getMessage());
    }
}
