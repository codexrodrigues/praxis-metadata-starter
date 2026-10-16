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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
    void shouldCoerceDateOnlyBoundsToFullDayIsoInstantsForOffsetDateTimeRangeField() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "fimPrevisto": ["2026-10-14", "2026-10-20"]
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("fimPrevisto").size());
        assertEquals("2026-10-14T00:00:00Z", payload.path("fimPrevisto").get(0).asText());
        assertEquals("2026-10-20T23:59:59.999999999Z", payload.path("fimPrevisto").get(1).asText());
    }

    @Test
    void shouldCoerceSingleLowerDateOnlyBoundAndUpperOnlyDateObjectForOffsetDateTimeRangeField() throws Exception {
        ObjectNode lowerOnlyPayload = (ObjectNode) mapper.readTree("""
                {
                  "fimPrevisto": ["2026-10-14"]
                }
                """);

        boolean lowerChanged = normalizer.normalizeInPlace(lowerOnlyPayload, RangeFilterDTO.class);

        assertTrue(lowerChanged);
        assertEquals(1, lowerOnlyPayload.path("fimPrevisto").size());
        assertEquals("2026-10-14T00:00:00Z", lowerOnlyPayload.path("fimPrevisto").get(0).asText());

        ObjectNode upperOnlyPayload = (ObjectNode) mapper.readTree("""
                {
                  "fimPrevisto": { "endDate": "2026-10-20" }
                }
                """);

        boolean upperChanged = normalizer.normalizeInPlace(upperOnlyPayload, RangeFilterDTO.class);

        assertTrue(upperChanged);
        assertEquals(2, upperOnlyPayload.path("fimPrevisto").size());
        assertTrue(upperOnlyPayload.path("fimPrevisto").get(0).isNull());
        assertEquals("2026-10-20T23:59:59.999999999Z", upperOnlyPayload.path("fimPrevisto").get(1).asText());
    }

    @Test
    void shouldCoerceDateOnlyBoundsToLocalDateTimeForLocalDateTimeRangeField() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "agendamentoBetween": ["2026-10-14", "2026-10-20"]
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("agendamentoBetween").size());
        assertEquals("2026-10-14T00:00:00", payload.path("agendamentoBetween").get(0).asText());
        assertEquals("2026-10-20T23:59:59.999999999", payload.path("agendamentoBetween").get(1).asText());
    }

    @Test
    void shouldCoerceIsoDateTimeBoundsToLocalDateForLocalDateRangeField() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "periodo": ["2026-10-14T03:00:00.000Z", "2026-10-20T23:59:59.999Z"]
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("periodo").size());
        assertEquals("2026-10-14", payload.path("periodo").get(0).asText());
        assertEquals("2026-10-20", payload.path("periodo").get(1).asText());
    }

    @Test
    void shouldSwapInvertedDateOnlyBoundsBeforeCoercingStartAndEndOfDay() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "fimPrevisto": {
                    "startDate": "2026-10-20",
                    "endDate": "2026-10-14",
                    "preset": "custom",
                    "label": "Período invertido"
                  }
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("fimPrevisto").size());
        assertEquals("2026-10-14T00:00:00Z", payload.path("fimPrevisto").get(0).asText());
        assertEquals("2026-10-20T23:59:59.999999999Z", payload.path("fimPrevisto").get(1).asText());
    }

    @Test
    void shouldCoercePeriodRangeTokensForDateAndDateTimeFields() throws Exception {
        ObjectNode monthPayload = (ObjectNode) mapper.readTree("""
                {
                  "periodo": ["2026-02", "2026-04"],
                  "fimPrevisto": ["2026-Q1", "2026-Q3"],
                  "agendamentoBetween": ["2026", "2025"]
                }
                """);

        boolean changed = normalizer.normalizeInPlace(monthPayload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals("2026-02-01", monthPayload.path("periodo").get(0).asText());
        assertEquals("2026-04-30", monthPayload.path("periodo").get(1).asText());
        assertEquals("2026-01-01T00:00:00Z", monthPayload.path("fimPrevisto").get(0).asText());
        assertEquals("2026-09-30T23:59:59.999999999Z", monthPayload.path("fimPrevisto").get(1).asText());
        assertEquals("2025-01-01T00:00:00", monthPayload.path("agendamentoBetween").get(0).asText());
        assertEquals("2026-12-31T23:59:59.999999999", monthPayload.path("agendamentoBetween").get(1).asText());
    }

    @Test
    void shouldNormalizeAndSwapInvertedLocalTimeRangeBounds() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "horarioBetween": {
                    "start": "18:00",
                    "end": "08:30"
                  }
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RangeFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("horarioBetween").size());
        assertEquals("08:30", payload.path("horarioBetween").get(0).asText());
        assertEquals("18:00", payload.path("horarioBetween").get(1).asText());
    }

    static class RangeFilterDTO implements GenericFilterDTO {
        @UISchema(numericFormat = NumericFormat.CURRENCY)
        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "valor")
        private List<BigDecimal> valorBetween;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "data")
        private List<LocalDate> periodo;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "fimPrevisto")
        private List<OffsetDateTime> fimPrevisto;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "agendamento")
        private List<LocalDateTime> agendamentoBetween;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "horario")
        private List<LocalTime> horarioBetween;
    }
}
