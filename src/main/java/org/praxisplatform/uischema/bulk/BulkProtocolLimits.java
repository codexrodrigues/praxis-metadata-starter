package org.praxisplatform.uischema.bulk;

/** Structural ceilings. Operation-specific sync/atomic/authorization limits still apply later. */
public record BulkProtocolLimits(int maxRequestBytes, int maxDepth, int maxFields,
                                  int maxTargets, int maxExclusions) {
    public static BulkProtocolLimits defaults() { return new BulkProtocolLimits(8 * 1024 * 1024, 16, 50, 10000, 10000); }
    public BulkProtocolLimits {
        bounded(maxRequestBytes, 8 * 1024 * 1024);
        bounded(maxDepth, 16);
        bounded(maxFields, 50);
        bounded(maxTargets, 10000);
        bounded(maxExclusions, 10000);
    }
    private static void bounded(int value, int ceiling) {
        if (value < 1 || value > ceiling) throw new IllegalArgumentException("Invalid bulk protocol limit");
    }
}
