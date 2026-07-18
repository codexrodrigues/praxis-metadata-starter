package org.praxisplatform.uischema.rest.response;

import java.time.LocalDateTime;

/** Concrete OpenAPI projection of the canonical Praxis failure envelope. */
public class RestApiErrorResponse extends RestApiResponse<Object> {

    public RestApiErrorResponse() {
        super(null, null, null, null, null, LocalDateTime.now());
    }
}
