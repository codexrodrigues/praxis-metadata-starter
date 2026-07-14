package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.stats.ComparisonPeriodMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;

import java.time.LocalDate;

/** Declares the current period and how its previous counterpart is derived. */
public record ComparisonPeriodRequest(
        ComparisonPeriodPreset preset,
        LocalDate from,
        LocalDate to,
        String timezone,
        ComparisonPeriodMode mode
) { }
