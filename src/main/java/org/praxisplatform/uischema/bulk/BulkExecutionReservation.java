package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.UUID;

/** Confirmed durable reservation or an authorized replay of one. */
@JsonIgnoreType
public final class BulkExecutionReservation {
    private final BulkExecutionSnapshot execution;
    private final boolean replayed;

    BulkExecutionReservation(BulkExecutionSnapshot execution, boolean replayed) {
        this.execution = execution;
        this.replayed = replayed;
    }

    public UUID executionId() { return execution.executionId(); }
    public UUID proposalId() { return execution.proposalId(); }
    public BulkDurableExecutionStatus status() { return execution.status(); }
    public int nextOrdinal() { return execution.nextOrdinal(); }
    public int targetCount() { return execution.targetCount(); }
    public BulkExecutionControl control() { return execution.control(); }
    public BulkExecutionSnapshot execution() { return execution; }
    public boolean replayed() { return replayed; }
    @Override public String toString() { return "BulkExecutionReservation[protected]"; }
}
