package org.praxisplatform.uischema.stats;

import org.praxisplatform.uischema.stats.dto.ComparisonPeriodRequest;
import org.praxisplatform.uischema.stats.dto.ResolvedComparisonPeriod;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Resolves named comparison periods against an injected clock. */
public final class ComparisonPeriodResolver {
    private final Clock clock;

    public ComparisonPeriodResolver(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public ResolvedComparisonPeriod resolve(ComparisonPeriodRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Comparison period is required.");
        }
        ZoneId zone = resolveZone(request.timezone());
        ComparisonPeriodMode mode = request.mode() == null
                ? ComparisonPeriodMode.PREVIOUS_ALIGNED
                : request.mode();
        boolean hasPreset = request.preset() != null;
        boolean hasCustomDates = request.from() != null || request.to() != null;
        if (hasPreset && hasCustomDates) {
            throw new IllegalArgumentException("Comparison period must use either a preset or both from and to dates, not both.");
        }
        LocalDate currentFrom;
        LocalDate currentTo;
        if (hasPreset) {
            LocalDate today = LocalDate.now(clock.withZone(zone));
            LocalDate[] resolved = preset(request.preset(), today);
            currentFrom = resolved[0];
            currentTo = resolved[1];
        } else {
            if (request.from() == null || request.to() == null) {
                throw new IllegalArgumentException("Comparison period requires a preset or both from and to dates.");
            }
            if (request.from().isAfter(request.to())) {
                throw new IllegalArgumentException("Comparison period is invalid: from must be on or before to.");
            }
            if (mode == ComparisonPeriodMode.PREVIOUS_CALENDAR_PERIOD) {
                throw new IllegalArgumentException("previousCalendarPeriod is supported only with a period preset.");
            }
            currentFrom = request.from();
            currentTo = request.to();
        }
        LocalDate previousTo;
        LocalDate previousFrom;
        if (mode == ComparisonPeriodMode.PREVIOUS_CALENDAR_PERIOD && hasPreset) {
            LocalDate[] prior = previousCalendarPeriod(request.preset(), currentFrom, currentTo);
            previousFrom = prior[0];
            previousTo = prior[1];
        } else {
            long days = java.time.temporal.ChronoUnit.DAYS.between(currentFrom, currentTo) + 1;
            previousTo = currentFrom.minusDays(1);
            previousFrom = previousTo.minusDays(days - 1);
        }
        return new ResolvedComparisonPeriod(currentFrom, currentTo, previousFrom, previousTo, zone.getId(), mode);
    }

    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("Comparison timezone is required.");
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Comparison timezone is invalid: " + timezone);
        }
    }

    private LocalDate[] preset(ComparisonPeriodPreset preset, LocalDate today) {
        return switch (preset) {
            case TODAY -> new LocalDate[]{today, today};
            case YESTERDAY -> new LocalDate[]{today.minusDays(1), today.minusDays(1)};
            case LAST_7_DAYS -> new LocalDate[]{today.minusDays(6), today};
            case LAST_30_DAYS -> new LocalDate[]{today.minusDays(29), today};
            case THIS_MONTH -> new LocalDate[]{today.withDayOfMonth(1), today};
            case LAST_MONTH -> {
                LocalDate start = today.withDayOfMonth(1).minusMonths(1);
                yield new LocalDate[]{start, start.withDayOfMonth(start.lengthOfMonth())};
            }
            case THIS_QUARTER -> {
                int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                yield new LocalDate[]{today.withMonth(firstMonth).withDayOfMonth(1), today};
            }
            case THIS_YEAR -> new LocalDate[]{today.withDayOfYear(1), today};
        };
    }

    private LocalDate[] previousCalendarPeriod(ComparisonPeriodPreset preset, LocalDate currentFrom, LocalDate currentTo) {
        return switch (preset) {
            case THIS_MONTH, LAST_MONTH -> {
                LocalDate start = currentFrom.minusMonths(1).withDayOfMonth(1);
                yield new LocalDate[]{start, start.withDayOfMonth(start.lengthOfMonth())};
            }
            case THIS_QUARTER -> {
                LocalDate start = currentFrom.minusMonths(3);
                yield new LocalDate[]{start, start.plusMonths(3).minusDays(1)};
            }
            case THIS_YEAR -> {
                LocalDate start = currentFrom.minusYears(1);
                yield new LocalDate[]{start, start.withMonth(12).withDayOfMonth(31)};
            }
            default -> {
                long days = java.time.temporal.ChronoUnit.DAYS.between(currentFrom, currentTo) + 1;
                LocalDate end = currentFrom.minusDays(1);
                yield new LocalDate[]{end.minusDays(days - 1), end};
            }
        };
    }
}
