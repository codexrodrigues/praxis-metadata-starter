package org.praxisplatform.uischema.bulk;

/** Internal target-local rejection. It escapes the unit transaction to roll back all domain writes. */
final class BulkUnitRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final BulkUnitAdmission admission;

    BulkUnitRejectedException(BulkUnitAdmission admission) {
        super("Bulk target rejected", null, false, false);
        if (admission == null || admission.decision() == BulkUnitAdmission.Decision.ADMIT
                || admission.decision() == BulkUnitAdmission.Decision.STOP)
            throw new IllegalArgumentException("A target-local rejection is required");
        this.admission = admission;
    }

    BulkUnitAdmission admission() { return admission; }
}
