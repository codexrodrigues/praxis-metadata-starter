package org.praxisplatform.uischema.bulk;

/**
 * Typed lifecycle failure indicating bulk infrastructure, local serving photograph, or
 * transaction boundary unavailability (typically HTTP 503).
 */
public class BulkLifecycleUnavailableException extends IllegalStateException {

    public BulkLifecycleUnavailableException(String message) {
        super(message);
    }

    public BulkLifecycleUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
