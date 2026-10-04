package org.praxisplatform.uischema.bulk;

/** Safe storage failure without SQL, payload, parameters or driver exception details. */
public final class BulkProposalStorageException extends RuntimeException {
    public enum Reason { CONFLICT, CAPACITY, CORRUPT, INVALID_SELECTION, UNAVAILABLE }
    private final Reason reason;
    BulkProposalStorageException(Reason reason) { super("Protected proposal storage: " + reason); this.reason = reason; }
    /** Empty or oversized server-resolved QUERY population; never includes target details. */
    public static BulkProposalStorageException invalidSelection() {
        return new BulkProposalStorageException(Reason.INVALID_SELECTION);
    }
    public Reason reason() { return reason; }
}
