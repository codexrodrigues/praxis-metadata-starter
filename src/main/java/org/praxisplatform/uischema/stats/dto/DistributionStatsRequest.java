package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;

/**
 * Canonical request contract for distribution stats.
 *
 * @param <FD> filter DTO type
 */
public record DistributionStatsRequest<FD extends GenericFilterDTO>(
        FD filter,
        String field,
        DistributionMode mode,
        StatsMetricRequest metric,
        Number bucketSize,
        Integer bucketCount,
        Integer limit,
        StatsBucketOrder orderBy
) {
}
