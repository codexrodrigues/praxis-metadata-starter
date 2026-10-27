package org.praxisplatform.uischema.customfields;

import java.util.List;
import org.springframework.validation.FieldError;

/** Structured field errors without retaining submitted values in the HTTP response. */
public class CustomFieldValidationException extends RuntimeException {
    private final List<FieldError> fieldErrors;
    public CustomFieldValidationException(List<FieldError> fieldErrors) {
        super("Custom field validation failed");
        this.fieldErrors = List.copyOf(fieldErrors);
    }
    public List<FieldError> getFieldErrors() { return fieldErrors; }
}
