package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;

import java.time.LocalDateTime;

/**
 * Envelope concreto de OpenAPI para respostas de {@code group-by stats}.
 *
 * <p>
 * A classe existe principalmente para ajudar a geracao OpenAPI/Javadoc a materializar o tipo
 * generico {@code RestApiResponse<GroupByStatsResponse>} como uma superficie documentavel.
 * </p>
 */
public class RestApiResponseGroupByStatsResponse extends RestApiResponse<GroupByStatsResponse> {

    public RestApiResponseGroupByStatsResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
