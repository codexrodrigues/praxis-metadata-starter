package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.StatsBucketOrder;

import java.util.List;

/** Request for a single governed dimension compared over two related periods. */
public record ComparisonStatsRequest<FD extends GenericFilterDTO>(
        FD filter,
        String field,
        String periodField,
        List<StatsMetricRequest> metrics,
        ComparisonPeriodRequest period,
        Integer limit,
        StatsBucketOrder orderBy
) { }
