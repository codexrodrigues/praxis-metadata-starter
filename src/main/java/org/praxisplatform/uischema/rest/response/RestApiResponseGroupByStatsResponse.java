package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;

import java.time.LocalDateTime;

/**
 * Concrete OpenAPI wrapper for stats group-by responses.
 */
public class RestApiResponseGroupByStatsResponse extends RestApiResponse<GroupByStatsResponse> {

    public RestApiResponseGroupByStatsResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
