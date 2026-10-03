package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One protected, ordered set admitted and mutated in a single operational transaction. */
@JsonIgnoreType
public final class BulkAtomicExecutionSet {
    private final UUID executionId;
    private final UUID attemptId;
    private final String setDigest;
    private final List<BulkExecutionUnit> units;
    private final Instant unitDeadline;
    private final long monotonicDeadlineNanos;

    BulkAtomicExecutionSet(UUID executionId, UUID attemptId, String setDigest,
            List<BulkExecutionUnit> units, Instant unitDeadline, long monotonicDeadlineNanos) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId");
        this.setDigest = Objects.requireNonNull(setDigest, "setDigest");
        if (units == null || units.isEmpty() || units.size() > 50)
            throw new IllegalArgumentException("Atomic set requires 1 to 50 canonical units");
        this.units = List.copyOf(units);
        this.unitDeadline = Objects.requireNonNull(unitDeadline, "unitDeadline");
        this.monotonicDeadlineNanos = monotonicDeadlineNanos;
        for (int ordinal = 0; ordinal < this.units.size(); ordinal++)
            if (this.units.get(ordinal).ordinal() != ordinal
                    || !executionId.equals(this.units.get(ordinal).executionId())
                    || !attemptId.equals(this.units.get(ordinal).attemptId())
                    || !unitDeadline.equals(this.units.get(ordinal).unitDeadline())
                    || !Objects.equals(this.units.get(0).control().executionId(),
                            this.units.get(ordinal).control().executionId())
                    || !Objects.equals(this.units.get(0).control().ownerId(),
                            this.units.get(ordinal).control().ownerId())
                    || this.units.get(0).control().epoch() != this.units.get(ordinal).control().epoch()
                    || this.units.get(0).originalIntent() != this.units.get(ordinal).originalIntent()
                    || this.units.get(0).governance() != this.units.get(ordinal).governance())
                throw new IllegalArgumentException("Atomic units are not ordered or bound to the attempt");
    }

    public UUID executionId() { return executionId; }
    public UUID attemptId() { return attemptId; }
    public String setDigest() { return setDigest; }
    public List<BulkExecutionUnit> units() { return units; }
    public Instant unitDeadline() { return unitDeadline; }
    public Duration remainingBudget() {
        long remaining = monotonicDeadlineNanos - System.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }
    @Override public String toString() { return "BulkAtomicExecutionSet[protected]"; }
}
