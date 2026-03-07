package org.praxisplatform.uischema.filter.range;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RangeNumberParserTest {

    @Test
    void shouldParseScientificNotation() {
        assertEquals(0, new BigDecimal("1000").compareTo(RangeNumberParser.parse("1e3")));
        assertEquals(0, new BigDecimal("0.25").compareTo(RangeNumberParser.parse("2.5e-1")));
    }

    @Test
    void shouldParseLocalizedCurrencyValue() {
        assertEquals(new BigDecimal("1234.56"), RangeNumberParser.parse("R$ 1.234,56"));
        assertEquals(new BigDecimal("1234.56"), RangeNumberParser.parse("USD 1,234.56"));
    }

    @Test
    void shouldRejectAmbiguousOrInvalidAlphabeticValues() {
        assertNull(RangeNumberParser.parse("1abc3"));
        assertNull(RangeNumberParser.parse("abc"));
    }
}
