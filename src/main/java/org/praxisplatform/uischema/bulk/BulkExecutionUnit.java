package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;

/** Protected immutable unit supplied to a domain callback inside the operational transaction. */
@JsonIgnoreType
public final class BulkExecutionUnit {
    private final UUID executionId;
    private final UUID attemptId;
    private final int ordinal;
    private final BulkTargetEvidence<?> targetEvidence;
    private final BulkIntentSnapshot originalIntent;
    private final BulkEvaluationGovernance governance;
    private final Instant executionDeadline;
    private final Instant unitDeadline;
    private final long monotonicDeadlineNanos;
    private final BulkExecutionControl control;

    BulkExecutionUnit(UUID executionId, UUID attemptId, int ordinal, BulkTargetEvidence<?> targetEvidence,
            BulkIntentSnapshot originalIntent, BulkEvaluationGovernance governance, Instant executionDeadline,
            Instant unitDeadline, long monotonicDeadlineNanos, BulkExecutionControl control) {
        this.executionId = executionId;
        this.attemptId = attemptId;
        this.ordinal = ordinal;
        this.targetEvidence = targetEvidence;
        this.originalIntent = originalIntent;
        this.governance = governance;
        this.executionDeadline = executionDeadline;
        this.unitDeadline = unitDeadline;
        this.monotonicDeadlineNanos = monotonicDeadlineNanos;
        this.control = control;
    }

    public UUID executionId() { return executionId; }
    public UUID attemptId() { return attemptId; }
    public int ordinal() { return ordinal; }
    public BulkTargetEvidence<?> targetEvidence() { return targetEvidence; }
    public BulkIntentSnapshot originalIntent() { return originalIntent; }
    public BulkEvaluationGovernance governance() { return governance; }
    public Instant executionDeadline() { return executionDeadline; }
    /** Absolute durable deadline shared by admission and mutation for this attempt. */
    public Instant unitDeadline() { return unitDeadline; }
    /** Remaining unit budget based on a monotonic clock, avoiding host/database clock skew. */
    public Duration remainingBudget() {
        long remaining = monotonicDeadlineNanos - System.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }
    public BulkExecutionControl control() { return control; }
    @Override public String toString() { return "BulkExecutionUnit[protected]"; }
}
