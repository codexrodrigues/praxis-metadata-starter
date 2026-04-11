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
    void shouldNormalizeRelationAliasObjectToCanonicalFieldAndRemoveAlias() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valor": { "minPrice": 6500, "maxPrice": 15000 }
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertFalse(payload.has("valor"));
        assertEquals(2, payload.path("valorBetween").size());
        assertEquals("6500", payload.path("valorBetween").get(0).asText());
        assertEquals("15000", payload.path("valorBetween").get(1).asText());
    }

    @Test
    void shouldNormalizeSplitBoundAliasesAndRemoveOriginalKeys() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valorMin": null,
                  "valorMax": 1800
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertFalse(payload.has("valorMin"));
        assertFalse(payload.has("valorMax"));
        assertEquals(2, payload.path("valorBetween").size());
        assertTrue(payload.path("valorBetween").get(0).isNull());
        assertEquals("1800", payload.path("valorBetween").get(1).asText());
    }

    @Test
    void shouldRejectConflictingCanonicalAndAliasSources() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valorBetween": [100, 200],
                  "valor": { "minPrice": 300, "maxPrice": 400 }
                }
                """);

        assertThrows(
                InvalidFilterPayloadException.class,
                () -> normalizer.normalizeInPlace(payload, RangeFilterDTO.class)
        );
    }

    @Test
    void shouldRejectMonetaryObjectWithoutBoundsEvenWhenCurrencyIsPresent() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valor": { "currency": "BRL" }
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals(1, payload.path("valorBetween").size());
        assertTrue(payload.path("valorBetween").get(0).isNull());
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
    void shouldSwapNumericRangeWhenScientificNotationIsGreaterThanUpperBound() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valorBetween": ["1e3", "500"]
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals("500", payload.path("valorBetween").get(0).asText());
        assertEquals("1e3", payload.path("valorBetween").get(1).asText());
    }

    @Test
    void shouldNotCorruptNumericTokenContainingAlphabeticCharacters() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valorBetween": ["1abc3", "2"]
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertFalse(changed);
        assertEquals("1abc3", payload.path("valorBetween").get(0).asText());
        assertEquals("2", payload.path("valorBetween").get(1).asText());
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

    @Test
    void shouldRejectScalarRangePayloadInStrictMode() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valorBetween": 1500
                }
                """);

        assertThrows(
                InvalidFilterPayloadException.class,
                () -> normalizer.normalizeInPlace(payload, RangeFilterDTO.class)
        );
    }

    @Test
    void shouldNormalizeScalarRangePayloadWhenScalarFallbackIsEnabled() throws Exception {
        RangePayloadNormalizer compatibilityNormalizer = new RangePayloadNormalizer(true, false);
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "valorBetween": 1500
                }
                """);

        boolean changed = compatibilityNormalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals(1, payload.path("valorBetween").size());
        assertEquals("1500", payload.path("valorBetween").get(0).asText());
    }

    static class RangeFilterDTO implements GenericFilterDTO {
        @UISchema(numericFormat = NumericFormat.CURRENCY)
        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "valor")
        private List<BigDecimal> valorBetween;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "data")
        private List<LocalDate> periodo;
    }
}
