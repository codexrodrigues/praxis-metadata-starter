package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;

import java.time.LocalDateTime;

/**
 * Concrete OpenAPI wrapper for stats distribution responses.
 */
public class RestApiResponseDistributionStatsResponse extends RestApiResponse<DistributionStatsResponse> {

    public RestApiResponseDistributionStatsResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
