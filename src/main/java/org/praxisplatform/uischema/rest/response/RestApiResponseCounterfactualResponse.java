package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualResponse;

import java.time.LocalDateTime;

/**
 * Envelope concreto de OpenAPI para respostas de busca contrafactual em {@code /stats/counterfactual}.
 */
public class RestApiResponseCounterfactualResponse extends RestApiResponse<CounterfactualResponse> {

    public RestApiResponseCounterfactualResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
