package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/**
 * Point in a canonical time-series response.
 */
public record TimeSeriesPoint(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate start,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate end,
        String label,
        Number value,
        long count
) {
}
