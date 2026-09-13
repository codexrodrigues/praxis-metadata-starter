package org.praxisplatform.uischema.bulk;

/** Safe storage failure without SQL, payload, parameters or driver exception details. */
public final class BulkProposalStorageException extends RuntimeException {
    public enum Reason { CONFLICT, CORRUPT, UNAVAILABLE }
    private final Reason reason;
    BulkProposalStorageException(Reason reason) { super("Protected proposal storage: " + reason); this.reason = reason; }
    public Reason reason() { return reason; }
}
