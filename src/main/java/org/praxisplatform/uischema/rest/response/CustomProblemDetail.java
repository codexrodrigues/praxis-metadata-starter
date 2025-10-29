package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.ProblemDetail;

@Getter
@Setter
public class CustomProblemDetail extends ProblemDetail {

    private String message;
    private ErrorCategory category;

    public CustomProblemDetail(String message) {
        this.message = message;
        this.category = null;
    }

}
