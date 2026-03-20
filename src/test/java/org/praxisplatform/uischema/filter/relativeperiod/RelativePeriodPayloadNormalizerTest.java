package org.praxisplatform.uischema.filter.relativeperiod;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelativePeriodPayloadNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RelativePeriodPayloadNormalizer normalizer =
            new RelativePeriodPayloadNormalizer(Clock.fixed(Instant.parse("2026-03-20T15:45:00Z"), ZoneOffset.UTC));

    @Test
    void shouldNormalizeTodayPresetIntoOnDate() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "publicadoEmPreset": "today"
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RelativeDateTimeFilterDTO.class);

        assertTrue(changed);
        assertFalse(payload.has("publicadoEmPreset"));
        assertEquals("2026-03-20", payload.path("publicadoEmOn").asText());
    }

    @Test
    void shouldNormalizeLast30PresetIntoLastDays() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "publicadoEmPreset": "last30"
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RelativeDateTimeFilterDTO.class);

        assertTrue(changed);
        assertFalse(payload.has("publicadoEmPreset"));
        assertEquals(30, payload.path("publicadoEmLastDays").asInt());
    }

    @Test
    void shouldNormalizeThisMonthPresetIntoDateTimeBetween() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "publicadoEmPreset": "thisMonth"
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RelativeDateTimeFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("publicadoEmBetween").size());
        assertEquals("2026-03-01T00:00Z", payload.path("publicadoEmBetween").get(0).asText());
        assertEquals("2026-03-20T15:45Z", payload.path("publicadoEmBetween").get(1).asText());
    }

    @Test
    void shouldNormalizeLastMonthPresetIntoDateBetween() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "periodoPreset": "lastMonth"
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RelativeDateFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("periodoBetween").size());
        assertEquals("2026-02-01", payload.path("periodoBetween").get(0).asText());
        assertEquals("2026-02-28", payload.path("periodoBetween").get(1).asText());
    }

    @Test
    void shouldNormalizeLastMonthPresetIntoDateTimeBetweenIncludingFinalFractionalInstant() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "publicadoEmPreset": "lastMonth"
                }
                """);

        boolean changed = normalizer.normalizeInPlace(payload, RelativeDateTimeFilterDTO.class);

        assertTrue(changed);
        assertEquals(2, payload.path("publicadoEmBetween").size());
        assertEquals("2026-02-01T00:00Z", payload.path("publicadoEmBetween").get(0).asText());
        assertEquals("2026-02-28T23:59:59.999999999Z", payload.path("publicadoEmBetween").get(1).asText());
    }

    @Test
    void shouldRejectConflictingPresetAndAbsoluteFields() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "publicadoEmPreset": "last7",
                  "publicadoEmLastDays": 12
                }
                """);

        InvalidFilterPayloadException error = assertThrows(
                InvalidFilterPayloadException.class,
                () -> normalizer.normalizeInPlace(payload, RelativeDateTimeFilterDTO.class));

        assertEquals(
                "Relative period preset for field 'publicadoEm' conflicts with explicit absolute filters. Use only one source.",
                error.getMessage());
    }

    @Test
    void shouldRejectUnsupportedPreset() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "publicadoEmPreset": "custom90"
                }
                """);

        InvalidFilterPayloadException error = assertThrows(
                InvalidFilterPayloadException.class,
                () -> normalizer.normalizeInPlace(payload, RelativeDateTimeFilterDTO.class));

        assertEquals(
                "Unsupported relative period preset 'custom90' for field 'publicadoEm'.",
                error.getMessage());
    }

    @Test
    void shouldFailFastWhenInlineRelativePeriodHasNoCompanionFields() throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {
                  "publicadoEmPreset": "today"
                }
                """);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> normalizer.normalizeInPlace(payload, MisconfiguredRelativeFilterDTO.class));

        assertEquals(
                "Field 'publicadoEmPreset' uses INLINE_RELATIVE_PERIOD but no companion field ending with On, LastDays, or Between was found.",
                error.getMessage());
    }

    static class RelativeDateTimeFilterDTO implements GenericFilterDTO {
        @UISchema(controlType = FieldControlType.INLINE_RELATIVE_PERIOD)
        private String publicadoEmPreset;

        @Filterable(operation = Filterable.FilterOperation.ON_DATE, relation = "publicadoEm")
        private LocalDate publicadoEmOn;

        @Filterable(operation = Filterable.FilterOperation.IN_LAST_DAYS, relation = "publicadoEm")
        private Integer publicadoEmLastDays;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "publicadoEm")
        private List<OffsetDateTime> publicadoEmBetween;
    }

    static class RelativeDateFilterDTO implements GenericFilterDTO {
        @UISchema(controlType = FieldControlType.INLINE_RELATIVE_PERIOD)
        private String periodoPreset;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "periodo")
        private List<LocalDate> periodoBetween;
    }

    static class MisconfiguredRelativeFilterDTO implements GenericFilterDTO {
        @UISchema(controlType = FieldControlType.INLINE_RELATIVE_PERIOD)
        private String publicadoEmPreset;
    }
}
