package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.time.Instant;

/** Internal validated MVCC observation. It is never an HTTP response or an authorization decision. */
@JsonIgnoreType
record BulkConsistentExecutionRead(Kind kind, BulkExecutionSnapshot execution,
        Instant createdAt, Instant updatedAt, Instant terminalAt,
        int confirmed, int unchanged, int denied, int invalid, int conflict, int unknown,
        String tombstoneTerminalStatus) {
    enum Kind { ABSENT, LIVE, TOMBSTONE }

    static BulkConsistentExecutionRead absent() {
        return new BulkConsistentExecutionRead(Kind.ABSENT, null, null, null, null,
                0, 0, 0, 0, 0, 0, null);
    }

    static BulkConsistentExecutionRead tombstone(String terminalStatus) {
        return new BulkConsistentExecutionRead(Kind.TOMBSTONE, null, null, null, null,
                0, 0, 0, 0, 0, 0, terminalStatus);
    }
}
