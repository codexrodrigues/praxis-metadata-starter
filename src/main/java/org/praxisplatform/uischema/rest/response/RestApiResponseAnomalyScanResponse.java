package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse;

import java.time.LocalDateTime;

/**
 * Envelope concreto de OpenAPI para respostas de varredura estatistica de anomalias em
 * {@code /stats/anomalies}.
 */
public class RestApiResponseAnomalyScanResponse extends RestApiResponse<AnomalyScanResponse> {

    public RestApiResponseAnomalyScanResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
