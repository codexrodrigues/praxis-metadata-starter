package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;

import java.time.LocalDateTime;

/**
 * Concrete OpenAPI wrapper for stats time-series responses.
 */
public class RestApiResponseTimeSeriesStatsResponse extends RestApiResponse<TimeSeriesStatsResponse> {

    public RestApiResponseTimeSeriesStatsResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
