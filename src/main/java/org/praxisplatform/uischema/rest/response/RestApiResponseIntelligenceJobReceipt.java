package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.intelligence.contracts.jobs.IntelligenceJobReceipt;

import java.time.LocalDateTime;

/**
 * Envelope concreto de OpenAPI para recibos de submissao assincrona de jobs analiticos em
 * {@code /stats/simulation/jobs} e {@code /stats/counterfactual/jobs}.
 */
public class RestApiResponseIntelligenceJobReceipt extends RestApiResponse<IntelligenceJobReceipt> {

    public RestApiResponseIntelligenceJobReceipt() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
