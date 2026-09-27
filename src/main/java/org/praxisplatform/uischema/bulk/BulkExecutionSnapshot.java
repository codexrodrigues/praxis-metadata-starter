package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.time.Instant;
import java.util.UUID;

/** Protected durable-control read model, scoped and authorized by the host before use. */
@JsonIgnoreType
public final class BulkExecutionSnapshot {
    private final UUID executionId;
    private final UUID proposalId;
    private final BulkDurableExecutionStatus status;
    private final int nextOrdinal;
    private final int targetCount;
    private final int receiptCount;
    private final int admissionCount;
    private final Instant deadlineAt;
    private final BulkExecutionControl control;
    private final BulkUnitReasonCode terminalReasonCode;
    private final Instant cancelRequestedAt;

    BulkExecutionSnapshot(UUID executionId, UUID proposalId, BulkDurableExecutionStatus status,
            int nextOrdinal, int targetCount, int receiptCount, int admissionCount, Instant deadlineAt,
            BulkExecutionControl control, BulkUnitReasonCode terminalReasonCode, Instant cancelRequestedAt) {
        this.executionId = executionId;
        this.proposalId = proposalId;
        this.status = status;
        this.nextOrdinal = nextOrdinal;
        this.targetCount = targetCount;
        this.receiptCount = receiptCount;
        this.admissionCount = admissionCount;
        this.deadlineAt = deadlineAt;
        this.control = control;
        this.terminalReasonCode = terminalReasonCode;
        this.cancelRequestedAt = cancelRequestedAt;
    }

    public UUID executionId() { return executionId; }
    public UUID proposalId() { return proposalId; }
    public BulkDurableExecutionStatus status() { return status; }
    public int nextOrdinal() { return nextOrdinal; }
    public int targetCount() { return targetCount; }
    public int receiptCount() { return receiptCount; }
    public int admissionCount() { return admissionCount; }
    public Instant deadlineAt() { return deadlineAt; }
    public BulkExecutionControl control() { return control; }
    public BulkUnitReasonCode terminalReasonCode() { return terminalReasonCode; }
    public Instant cancelRequestedAt() { return cancelRequestedAt; }
    @Override public String toString() { return "BulkExecutionSnapshot[protected]"; }
}
