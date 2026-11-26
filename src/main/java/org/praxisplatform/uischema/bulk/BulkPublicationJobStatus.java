package org.praxisplatform.uischema.bulk;

/** Execution lifecycle state of an asynchronous bulk publication job. */
public enum BulkPublicationJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
