package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/** One resolved inclusive date window included in a comparison response. */
public record ComparisonPeriodWindow(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate from,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate to,
        String timezone
) { }
