package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.stats.ComparisonPeriodMode;

import java.time.LocalDate;

/** Fully resolved, inclusive date windows used by comparison execution. */
public record ResolvedComparisonPeriod(
        LocalDate currentFrom,
        LocalDate currentTo,
        LocalDate previousFrom,
        LocalDate previousTo,
        String timezone,
        ComparisonPeriodMode mode
) { }
