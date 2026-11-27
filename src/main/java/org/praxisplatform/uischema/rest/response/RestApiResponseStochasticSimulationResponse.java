package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.intelligence.contracts.simulation.StochasticSimulationResponse;

import java.time.LocalDateTime;

/**
 * Envelope concreto de OpenAPI para respostas de simulacao estocastica em {@code /stats/simulation}.
 */
public class RestApiResponseStochasticSimulationResponse extends RestApiResponse<StochasticSimulationResponse> {

    public RestApiResponseStochasticSimulationResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
