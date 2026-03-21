package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.StatsBucketOrder;

/**
 * Canonical request contract for group-by stats.
 *
 * @param <FD> filter DTO type
 */
public record GroupByStatsRequest<FD extends GenericFilterDTO>(
        FD filter,
        String field,
        StatsMetricRequest metric,
        Integer limit,
        StatsBucketOrder orderBy
) {
}
