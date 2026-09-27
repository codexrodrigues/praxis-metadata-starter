package org.praxisplatform.uischema.bulk;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.EnumSet;

/**
 * Effective server-owned limits for one bulk operation. These values are part of the operational
 * fingerprint; they are not accepted from evaluation or confirmation requests.
 */
public record BulkOperationalProfile(
        Set<BulkMode> operationModes,
        Set<BulkExecutionMode> executionModes,
        Set<BulkSelectionMode> selectionModes,
        int maxTargets,
        int maxRequestBytes,
        Duration proposalLifetime,
        Duration unitDeadline) {

    public BulkOperationalProfile {
        operationModes = Set.copyOf(Objects.requireNonNull(operationModes, "operationModes"));
        executionModes = Set.copyOf(Objects.requireNonNull(executionModes, "executionModes"));
        selectionModes = Set.copyOf(Objects.requireNonNull(selectionModes, "selectionModes"));
        if (operationModes.isEmpty() || executionModes.isEmpty() || selectionModes.isEmpty())
            throw new IllegalArgumentException("At least one operation, execution and selection mode must be enabled");
        if (maxTargets < 1 || maxTargets > BulkProtocolLimits.defaults().maxTargets())
            throw new IllegalArgumentException("maxTargets exceeds the bulk protocol ceiling");
        if (maxRequestBytes < 1 || maxRequestBytes > BulkProtocolLimits.defaults().maxRequestBytes())
            throw new IllegalArgumentException("maxRequestBytes exceeds the bulk protocol ceiling");
        positive(proposalLifetime, "proposalLifetime");
        positive(unitDeadline, "unitDeadline");
        if (!operationModes.equals(EnumSet.of(BulkMode.DOMAIN_COMMAND))
                || !executionModes.equals(EnumSet.of(BulkExecutionMode.SYNC))
                || !selectionModes.equals(EnumSet.of(BulkSelectionMode.EXPLICIT))) {
            throw new IllegalArgumentException("This P1 operational profile only supports DOMAIN_COMMAND/SYNC/EXPLICIT");
        }
        if (maxTargets > 200) throw new IllegalArgumentException("P1 SYNC/PER_ITEM is limited to 200 targets");
        if (proposalLifetime.compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException("Proposal lifetime exceeds the P1 ceiling of 15 minutes");
        if (unitDeadline.compareTo(Duration.ofSeconds(5)) > 0)
            throw new IllegalArgumentException("Unit deadline exceeds the P1 ceiling of 5 seconds");
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        try {
            if (value.toMillis() == 0 || !Duration.ofMillis(value.toMillis()).equals(value))
                throw new IllegalArgumentException(name + " must use exact millisecond precision");
        }
        catch (ArithmeticException exception) { throw new IllegalArgumentException(name + " is too large", exception); }
    }
}
