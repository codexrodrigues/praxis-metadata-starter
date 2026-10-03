package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.Objects;

/** Whole-set decision; rejection never records a partial target admission. */
@JsonIgnoreType
public final class BulkAtomicAdmission {
    private static final BulkAtomicAdmission ADMIT = new BulkAtomicAdmission(null);
    private final BulkUnitReasonCode rejection;

    private BulkAtomicAdmission(BulkUnitReasonCode rejection) { this.rejection = rejection; }
    public static BulkAtomicAdmission admit() { return ADMIT; }
    public static BulkAtomicAdmission reject(BulkUnitReasonCode reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == BulkUnitReasonCode.LEGACY_REASON_NOT_RECORDED
                || reason == BulkUnitReasonCode.CANCELLED_BY_USER)
            throw new IllegalArgumentException("Reserved atomic rejection reason");
        return new BulkAtomicAdmission(reason);
    }
    public boolean admitted() { return rejection == null; }
    public BulkUnitReasonCode rejection() { return rejection; }
    @Override public String toString() { return "BulkAtomicAdmission[protected]"; }
}
