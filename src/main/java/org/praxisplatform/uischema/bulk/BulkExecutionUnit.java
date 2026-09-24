package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.UUID;

/** Protected immutable unit supplied to a domain callback inside the operational transaction. */
@JsonIgnoreType
public final class BulkExecutionUnit {
    private final UUID executionId;
    private final UUID attemptId;
    private final int ordinal;
    private final BulkTargetEvidence<?> targetEvidence;

    BulkExecutionUnit(UUID executionId, UUID attemptId, int ordinal, BulkTargetEvidence<?> targetEvidence) {
        this.executionId = executionId;
        this.attemptId = attemptId;
        this.ordinal = ordinal;
        this.targetEvidence = targetEvidence;
    }

    public UUID executionId() { return executionId; }
    public UUID attemptId() { return attemptId; }
    public int ordinal() { return ordinal; }
    public BulkTargetEvidence<?> targetEvidence() { return targetEvidence; }
    @Override public String toString() { return "BulkExecutionUnit[protected]"; }
}
