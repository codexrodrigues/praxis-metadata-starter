package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;

import java.time.LocalDateTime;

/**
 * Envelope concreto de OpenAPI para respostas de {@code distribution stats}.
 *
 * <p>
 * A classe materializa o tipo generico {@code RestApiResponse<DistributionStatsResponse>} para
 * publicacao correta da superficie de distribuicoes agregadas no OpenAPI.
 * </p>
 */
public class RestApiResponseDistributionStatsResponse extends RestApiResponse<DistributionStatsResponse> {

    public RestApiResponseDistributionStatsResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
