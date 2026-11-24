package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.intelligence.contracts.inference.InferenceResponse;

import java.time.LocalDateTime;

/**
 * Envelope concreto de OpenAPI para respostas de inferencia estatistica em {@code /stats/inference}.
 */
public class RestApiResponseInferenceResponse extends RestApiResponse<InferenceResponse> {

    public RestApiResponseInferenceResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
