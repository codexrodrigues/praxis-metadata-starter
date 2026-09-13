package org.praxisplatform.uischema.bulk;

import java.util.List;

/** Per-record delta with the same identity/version rules as an explicit selection. */
public record BulkItemChange<WI>(WI id, String expectedVersion, List<BulkFieldChange> changes) {
    public BulkItemChange {
        new BulkTarget<>(id, expectedVersion);
        changes = BulkContractChecks.changes(changes);
    }
}
