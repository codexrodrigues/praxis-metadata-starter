package org.praxisplatform.uischema.filter.range;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangePayloadNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RangePayloadNormalizer normalizer = new RangePayloadNormalizer();

    @Test
    void shouldNormalizeUpperOnlyMonetaryObjectToNullThenUpper() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valorBetween": { "maxPrice": 9999 }
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("valorBetween").size());
        assertTrue(payload.path("valorBetween").get(0).isNull());
        assertEquals("9999", payload.path("valorBetween").get(1).asText());
    }

    @Test
    void shouldNormalizeUpperOnlyDateObjectToNullThenUpper() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "periodo": { "endDate": "2026-01-31" }
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("periodo").size());
        assertTrue(payload.path("periodo").get(0).isNull());
        assertEquals("2026-01-31", payload.path("periodo").get(1).asText());
    }

    @Test
    void shouldPreserveUpperOnlyArraySemanticsWhenNullPlaceholderIsProvided() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valorBetween": [null, 321]
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertFalse(changed);
        assertEquals(2, payload.path("valorBetween").size());
        assertTrue(payload.path("valorBetween").get(0).isNull());
        assertEquals("321", payload.path("valorBetween").get(1).asText());
    }

    @Test
    void shouldRejectRangeArraysWithMoreThanTwoBounds() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valorBetween": [100, 200, 300]
                }
                """);

        assertThrows(
                InvalidFilterPayloadException.class,
                () -> normalizer.normalizeInPlace(payload, RangeFilterDTO.class)
        );
    }

    static class RangeFilterDTO implements GenericFilterDTO {
        @UISchema(numericFormat = NumericFormat.CURRENCY)
        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "valor")
        private List<BigDecimal> valorBetween;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "data")
        private List<LocalDate> periodo;
    }
}
