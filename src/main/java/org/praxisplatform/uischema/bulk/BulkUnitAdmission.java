package org.praxisplatform.uischema.bulk;

import java.util.Objects;

/** Closed gate decision. STOP is common to the execution; other denials are target-local. */
public final class BulkUnitAdmission {
    public enum Decision { ADMIT, DENIED, INVALID, CONFLICT, STOP }
    private final Decision decision;
    private final BulkUnitReasonCode reason;
    private BulkUnitAdmission(Decision decision, BulkUnitReasonCode reason) {
        this.decision = Objects.requireNonNull(decision);
        this.reason = reason;
        if ((decision == Decision.ADMIT) != (reason == null)) throw new IllegalArgumentException("reason/decision mismatch");
    }
    public static BulkUnitAdmission admit() { return new BulkUnitAdmission(Decision.ADMIT, null); }
    public static BulkUnitAdmission denied(BulkUnitReasonCode reason) { return local(Decision.DENIED, reason); }
    public static BulkUnitAdmission invalid(BulkUnitReasonCode reason) { return local(Decision.INVALID, reason); }
    public static BulkUnitAdmission conflict(BulkUnitReasonCode reason) { return local(Decision.CONFLICT, reason); }
    public static BulkUnitAdmission stop(BulkUnitReasonCode reason) {
        Objects.requireNonNull(reason);
        if (reason.name().startsWith("TARGET_") || reason == BulkUnitReasonCode.LEGACY_REASON_NOT_RECORDED)
            throw new IllegalArgumentException("reason cannot be supplied as a new common stop");
        return new BulkUnitAdmission(Decision.STOP, reason);
    }
    private static BulkUnitAdmission local(Decision decision, BulkUnitReasonCode reason) {
        Objects.requireNonNull(reason);
        if (!reason.name().startsWith("TARGET_")) throw new IllegalArgumentException("local result requires target reason");
        boolean allowed = switch (decision) {
            case DENIED -> reason == BulkUnitReasonCode.TARGET_DENIED;
            case INVALID -> reason == BulkUnitReasonCode.TARGET_NOT_FOUND || reason == BulkUnitReasonCode.TARGET_INVALID;
            case CONFLICT -> reason == BulkUnitReasonCode.TARGET_VERSION_CONFLICT
                    || reason == BulkUnitReasonCode.TARGET_STATE_CONFLICT
                    || reason == BulkUnitReasonCode.TARGET_DEPENDENCY_CHANGED;
            default -> false;
        };
        if (!allowed) throw new IllegalArgumentException("reason does not belong to local outcome");
        return new BulkUnitAdmission(decision, reason);
    }
    public Decision decision() { return decision; }
    public BulkUnitReasonCode reason() { return reason; }
    @Override public String toString() { return "BulkUnitAdmission[" + decision + "]"; }
}
