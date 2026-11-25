package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.Map;

/**
 * Point in a canonical time-series response.
 *
 * <p>
 * Quando um ponto temporal e suprimido por k-anonymity (em {@code fillGaps: true}), {@code value}
 * e {@code count} sao retornados como {@code null} com {@code suppressed = true}, diferenciando
 * protecao LGPD de um periodo genuinamente zerado ({@code count = 0}).
 * </p>
 */
public record TimeSeriesPoint(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate start,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate end,
        String label,
        Number value,
        Long count,
        Map<String, Number> values,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean suppressed
) {
    public TimeSeriesPoint(LocalDate start, LocalDate end, String label, Number value, long count) {
        this(start, end, label, value, Long.valueOf(count), null, null);
    }

    public TimeSeriesPoint(
            LocalDate start,
            LocalDate end,
            String label,
            Number value,
            Long count,
            Map<String, Number> values
    ) {
        this(start, end, label, value, count, values, null);
    }

    /**
     * Retorna {@code count} como primitivo seguro ({@code 0L} quando suprimido ou ausente).
     */
    public long countOrZero() {
        return count == null ? 0L : count;
    }
}
