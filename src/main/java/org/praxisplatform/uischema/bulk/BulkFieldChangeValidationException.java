package org.praxisplatform.uischema.bulk;

/** Caller-supplied field changes violate the declared bulk update contract. */
public final class BulkFieldChangeValidationException extends IllegalArgumentException {
    /** @param message safe description of the invalid field change */
    public BulkFieldChangeValidationException(String message) { super(message); }
    /** @param message safe description of the invalid field change
     * @param cause private binding failure, never a public diagnostic */
    public BulkFieldChangeValidationException(String message, Throwable cause) { super(message, cause); }
}
