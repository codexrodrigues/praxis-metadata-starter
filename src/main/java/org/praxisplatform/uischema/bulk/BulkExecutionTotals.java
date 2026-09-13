package org.praxisplatform.uischema.bulk;

/**
 * Reconciled execution counts. The storage layer is the future source of truth for these values;
 * this value object only prevents incoherent public projections.
 */
public record BulkExecutionTotals(
        long targetCount,
        long pending,
        long confirmed,
        long unchanged,
        long denied,
        long invalid,
        long conflict,
        long notProcessed,
        long unknown
) {
    public static final long MAX_TARGETS = 10_000;

    public BulkExecutionTotals {
        BulkResponseChecks.count(targetCount, "targetCount");
        BulkResponseChecks.count(pending, "pending");
        BulkResponseChecks.count(confirmed, "confirmed");
        BulkResponseChecks.count(unchanged, "unchanged");
        BulkResponseChecks.count(denied, "denied");
        BulkResponseChecks.count(invalid, "invalid");
        BulkResponseChecks.count(conflict, "conflict");
        BulkResponseChecks.count(notProcessed, "notProcessed");
        BulkResponseChecks.count(unknown, "unknown");
        if (targetCount == 0) {
            throw new IllegalArgumentException("targetCount must be positive for an execution");
        }
        if (targetCount > MAX_TARGETS) {
            throw new IllegalArgumentException("targetCount exceeds the bulk response limit");
        }
        if (sum(pending, confirmed, unchanged, denied, invalid, conflict, notProcessed, unknown) != targetCount) {
            throw new IllegalArgumentException("execution outcome counts must equal targetCount");
        }
    }

    public long failures() {
        return Math.addExact(Math.addExact(denied, invalid), Math.addExact(conflict, notProcessed));
    }

    public boolean onlySuccessfulOutcomes() {
        return pending == 0 && denied == 0 && invalid == 0 && conflict == 0
                && notProcessed == 0 && unknown == 0;
    }

    public boolean allPending() {
        return pending == targetCount && confirmed == 0 && unchanged == 0 && denied == 0
                && invalid == 0 && conflict == 0 && notProcessed == 0 && unknown == 0;
    }

    public boolean atomicRollbackOrIncomplete() {
        return pending > 0 || denied > 0 || invalid > 0 || conflict > 0 || notProcessed > 0 || unknown > 0;
    }

    private static long sum(long... values) {
        long result = 0;
        for (long value : values) {
            result = Math.addExact(result, value);
        }
        return result;
    }
}
