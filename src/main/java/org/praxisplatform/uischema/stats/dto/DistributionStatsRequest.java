package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;

/**
 * Contrato canonico de request para distribuicoes agregadas.
 *
 * <p>
 * Esse request cobre cenarios como histogramas e distribuicoes por faixas sobre campos numericos,
 * sempre aplicados ao conjunto filtrado do recurso. Os parametros de bucket podem ser informados
 * por tamanho, quantidade ou ambos, conforme o {@code mode} suportado pela implementacao.
 * </p>
 *
 * @param <FD> tipo do filtro do recurso
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
