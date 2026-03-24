package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;

import java.time.LocalDateTime;

/**
 * Envelope concreto de OpenAPI para respostas de {@code time-series stats}.
 *
 * <p>
 * A classe materializa o tipo generico {@code RestApiResponse<TimeSeriesStatsResponse>} para que
 * as ferramentas de documentacao publiquem corretamente essa superficie estatistica.
 * </p>
 */
public class RestApiResponseTimeSeriesStatsResponse extends RestApiResponse<TimeSeriesStatsResponse> {

    public RestApiResponseTimeSeriesStatsResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
