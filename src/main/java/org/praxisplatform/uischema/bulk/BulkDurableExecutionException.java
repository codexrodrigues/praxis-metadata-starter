package org.praxisplatform.uischema.bulk;

/** Safe kernel failure without SQL, payload, target identity, driver detail or nested cause. */
public final class BulkDurableExecutionException extends RuntimeException {
    public enum Reason {
        NOT_FOUND,
        CONFLICT,
        EXPIRED,
        FENCED,
        NOT_EXECUTABLE,
        DEADLINE_EXCEEDED,
        RECONCILIATION_REQUIRED,
        CORRUPT,
        UNAVAILABLE
    }

    private final Reason reason;
    BulkDurableExecutionException(Reason reason) {
        super("Protected bulk execution: " + reason);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
