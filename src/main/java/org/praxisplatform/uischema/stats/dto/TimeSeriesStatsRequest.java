package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;

import java.time.LocalDate;

/**
 * Canonical request contract for time-series stats.
 *
 * @param <FD> filter DTO type
 */
public record TimeSeriesStatsRequest<FD extends GenericFilterDTO>(
        FD filter,
        String field,
        TimeSeriesGranularity granularity,
        StatsMetricRequest metric,
        LocalDate from,
        LocalDate to,
        Boolean fillGaps
) {
}
