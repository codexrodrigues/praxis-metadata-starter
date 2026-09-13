package org.praxisplatform.uischema.bulk;

/** Counts produced by proposal evaluation, never a mutable execution counter. */
public record BulkProposalTotals(
        long targetCount,
        long evaluated,
        long executable,
        long blocked
) {
    public BulkProposalTotals {
        BulkResponseChecks.count(targetCount, "targetCount");
        BulkResponseChecks.count(evaluated, "evaluated");
        BulkResponseChecks.count(executable, "executable");
        BulkResponseChecks.count(blocked, "blocked");
        if (evaluated > targetCount) {
            throw new IllegalArgumentException("evaluated must not exceed targetCount");
        }
        if (Math.addExact(executable, blocked) != evaluated) {
            throw new IllegalArgumentException("evaluated must equal executable plus blocked");
        }
    }
}
