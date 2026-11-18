package org.praxisplatform.uischema.stats.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.praxisplatform.uischema.stats.DistributionMode;

import java.util.List;

/**
 * Contrato canonico de resposta para distribuicoes agregadas.
 *
 * <p>
 * A resposta informa o campo analisado, o modo de distribuicao aplicado e a colecao de buckets
 * resultante, pronta para histogramas, tabelas analiticas e cards de distribuicao.
 * </p>
 */
public record DistributionStatsResponse(
        String field,
        DistributionMode mode,
        StatsMetricRequest metric,
        List<DistributionBucket> buckets,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean suppressed,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer suppressedBucketCount,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer minBucketCount
) {
    public DistributionStatsResponse(
            String field,
            DistributionMode mode,
            StatsMetricRequest metric,
            List<DistributionBucket> buckets
    ) {
        this(field, mode, metric, buckets, null, null, null);
    }
}
