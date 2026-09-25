package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

/** Protected control state. It is not the public execution projection. */
@JsonIgnoreType
public enum BulkDurableExecutionStatus {
    RUNNING,
    UNIT_IN_FLIGHT,
    UNIT_COMMITTED_PENDING_ACK,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    STOPPED,
    RECONCILIATION_REQUIRED
}
