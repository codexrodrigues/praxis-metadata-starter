package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.UUID;

/** Receipt-backed result for exactly the ordinal requested by the caller. */
@JsonIgnoreType
public final class BulkUnitExecutionResult {
    private final UUID executionId;
    private final int ordinal;
    private final BulkUnitOutcome outcome;
    private final BulkItemStatus itemStatus;
    private final BulkUnitReasonCode reasonCode;
    private final boolean replayed;
    private final BulkExecutionSnapshot execution;

    BulkUnitExecutionResult(UUID executionId, int ordinal, BulkUnitOutcome outcome, BulkItemStatus itemStatus,
            BulkUnitReasonCode reasonCode, boolean replayed, BulkExecutionSnapshot execution) {
        this.executionId = executionId;
        this.ordinal = ordinal;
        this.outcome = outcome;
        this.itemStatus = itemStatus;
        this.reasonCode = reasonCode;
        this.replayed = replayed;
        this.execution = execution;
    }

    public UUID executionId() { return executionId; }
    public int ordinal() { return ordinal; }
    public BulkUnitOutcome outcome() { return outcome; }
    public BulkItemStatus itemStatus() { return itemStatus; }
    public BulkUnitReasonCode reasonCode() { return reasonCode; }
    public boolean durableResultPresent() {
        return outcome != null || itemStatus == BulkItemStatus.DENIED
                || itemStatus == BulkItemStatus.INVALID || itemStatus == BulkItemStatus.CONFLICT;
    }
    public boolean receiptPresent() { return outcome != null; }
    public boolean replayed() { return replayed; }
    public BulkDurableExecutionStatus status() { return execution.status(); }
    public BulkExecutionSnapshot execution() { return execution; }
    public BulkExecutionControl control() { return execution.control(); }
    @Override public String toString() { return "BulkUnitExecutionResult[protected]"; }
}
