package org.praxisplatform.uischema.bulk;

/**
 * Typed lifecycle failure indicating a durable control/publication CAS or state transition conflict
 * (typically HTTP 409).
 */
public class BulkPublicationConflictException extends IllegalStateException {

    public BulkPublicationConflictException(String message) {
        super(message);
    }

    public BulkPublicationConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
