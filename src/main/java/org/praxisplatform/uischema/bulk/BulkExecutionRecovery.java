package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

/** Result of explicit conservative recovery; no domain callback is invoked. */
@JsonIgnoreType
public final class BulkExecutionRecovery {
    private final BulkExecutionSnapshot execution;

    BulkExecutionRecovery(BulkExecutionSnapshot execution) { this.execution = execution; }
    public BulkExecutionSnapshot execution() { return execution; }
    public BulkDurableExecutionStatus status() { return execution.status(); }
    public BulkExecutionControl control() { return execution.control(); }
    public int receiptCount() { return execution.receiptCount(); }
    @Override public String toString() { return "BulkExecutionRecovery[protected]"; }
}
