package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.UUID;

/** Opaque owner/epoch fencing value issued by the durable kernel. */
@JsonIgnoreType
public final class BulkExecutionControl {
    private final UUID executionId;
    private final String ownerId;
    private final long epoch;

    BulkExecutionControl(UUID executionId, String ownerId, long epoch) {
        this.executionId = executionId;
        this.ownerId = ownerId;
        this.epoch = epoch;
    }

    public UUID executionId() { return executionId; }
    public String ownerId() { return ownerId; }
    public long epoch() { return epoch; }
    @Override public String toString() { return "BulkExecutionControl[protected]"; }
}
