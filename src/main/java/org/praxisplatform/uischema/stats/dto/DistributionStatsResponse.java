package org.praxisplatform.uischema.stats.dto;

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
        List<DistributionBucket> buckets
) {
}
