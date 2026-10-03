package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.Objects;

/** Protected result of one set attempt, never an independently authorized HTTP projection. */
@JsonIgnoreType
public final class BulkAtomicExecutionResult {
    private final BulkExecutionSnapshot execution;
    private final boolean replayed;
    private final boolean durableReceiptPresent;

    BulkAtomicExecutionResult(BulkExecutionSnapshot execution, boolean replayed,
            boolean durableReceiptPresent) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.replayed = replayed;
        this.durableReceiptPresent = durableReceiptPresent;
    }
    public BulkExecutionSnapshot execution() { return execution; }
    public BulkDurableExecutionStatus status() { return execution.status(); }
    public boolean replayed() { return replayed; }
    public boolean durableReceiptPresent() { return durableReceiptPresent; }
    @Override public String toString() { return "BulkAtomicExecutionResult[protected]"; }
}
