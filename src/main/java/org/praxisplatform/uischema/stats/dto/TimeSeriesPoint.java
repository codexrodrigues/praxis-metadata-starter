package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.Map;

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
        long count,
        Map<String, Number> values
) {
    public TimeSeriesPoint(LocalDate start, LocalDate end, String label, Number value, long count) {
        this(start, end, label, value, count, null);
    }
}
