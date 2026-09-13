package org.praxisplatform.uischema.bulk;

/** Public reconciled outcome for one bulk target. */
public enum BulkItemStatus {
    CONFIRMED,
    UNCHANGED,
    DENIED,
    INVALID,
    CONFLICT,
    NOT_PROCESSED,
    UNKNOWN;

    public boolean requiresDiagnostic() {
        return this != CONFIRMED && this != UNCHANGED;
    }
}
