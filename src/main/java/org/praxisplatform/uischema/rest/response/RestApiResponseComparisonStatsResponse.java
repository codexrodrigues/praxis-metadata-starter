package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;

import java.time.LocalDateTime;

/** OpenAPI envelope for period-over-period comparison stats responses. */
public class RestApiResponseComparisonStatsResponse extends RestApiResponse<ComparisonStatsResponse> {
    public RestApiResponseComparisonStatsResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
