package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

/** Minimal result written into the atomic receipt; it carries no public message payload. */
@JsonIgnoreType
public final class BulkUnitMutationResult {
    private static final BulkUnitMutationResult CONFIRMED = new BulkUnitMutationResult(BulkUnitOutcome.CONFIRMED);
    private static final BulkUnitMutationResult UNCHANGED = new BulkUnitMutationResult(BulkUnitOutcome.UNCHANGED);
    private final BulkUnitOutcome outcome;

    private BulkUnitMutationResult(BulkUnitOutcome outcome) { this.outcome = outcome; }
    public static BulkUnitMutationResult confirmed() { return CONFIRMED; }
    public static BulkUnitMutationResult unchanged() { return UNCHANGED; }
    public BulkUnitOutcome outcome() { return outcome; }
    @Override public String toString() { return "BulkUnitMutationResult[protected]"; }
}
