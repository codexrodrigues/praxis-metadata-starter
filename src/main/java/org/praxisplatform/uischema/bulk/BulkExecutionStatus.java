package org.praxisplatform.uischema.bulk;

/** Public lifecycle state of a bulk execution. */
public enum BulkExecutionStatus {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    CANCELLED,
    STOPPED,
    RECONCILIATION_REQUIRED;

    /** Only reconciled terminal states receive a terminal timestamp and retention clock. */
    public boolean terminal() {
        return this == COMPLETED
                || this == COMPLETED_WITH_ERRORS
                || this == CANCELLED
                || this == STOPPED;
    }
}
