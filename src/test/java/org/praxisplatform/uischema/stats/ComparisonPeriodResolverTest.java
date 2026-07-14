package org.praxisplatform.uischema.stats;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.stats.dto.ComparisonPeriodRequest;
import org.praxisplatform.uischema.stats.dto.ResolvedComparisonPeriod;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComparisonPeriodResolverTest {
    private final ComparisonPeriodResolver resolver = new ComparisonPeriodResolver(
            Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void resolvesCalendarMonthAndItsPreviousFullMonth() {
        ResolvedComparisonPeriod period = resolver.resolve(new ComparisonPeriodRequest(
                ComparisonPeriodPreset.THIS_MONTH, null, null, "America/Sao_Paulo",
                ComparisonPeriodMode.PREVIOUS_CALENDAR_PERIOD
        ));

        assertEquals(LocalDate.of(2026, 3, 1), period.currentFrom());
        assertEquals(LocalDate.of(2026, 3, 15), period.currentTo());
        assertEquals(LocalDate.of(2026, 2, 1), period.previousFrom());
        assertEquals(LocalDate.of(2026, 2, 28), period.previousTo());
    }

    @Test
    void resolvesCustomRangeUsingAlignedPreviousRange() {
        ResolvedComparisonPeriod period = resolver.resolve(new ComparisonPeriodRequest(
                null, LocalDate.of(2026, 2, 10), LocalDate.of(2026, 2, 12), "UTC", null
        ));

        assertEquals(LocalDate.of(2026, 2, 7), period.previousFrom());
        assertEquals(LocalDate.of(2026, 2, 9), period.previousTo());
    }

    @Test
    void rejectsCalendarModeForCustomRange() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(new ComparisonPeriodRequest(
                null, LocalDate.of(2026, 2, 10), LocalDate.of(2026, 2, 12), "UTC",
                ComparisonPeriodMode.PREVIOUS_CALENDAR_PERIOD
        )));
    }

    @Test
    void rejectsAmbiguousPresetAndCustomDates() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(new ComparisonPeriodRequest(
                ComparisonPeriodPreset.LAST_7_DAYS, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7), "UTC", null
        )));
    }
}
