package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

import java.util.List;
import java.util.Objects;

/** Typed result of evaluating one target; it is evidence, not continuing authorization. */
@JsonIgnoreType
public final class BulkTargetEligibility {
    public enum Decision { EXECUTABLE, BLOCKED }

    private final Decision decision;
    private final List<ResourceCommandMessage> diagnostics;

    private BulkTargetEligibility(Decision decision, List<ResourceCommandMessage> diagnostics) {
        this.decision = Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (diagnostics.size() > 16) throw new IllegalArgumentException("Too many target diagnostics");
        this.diagnostics = BulkResponseChecks.diagnostics(diagnostics, decision == Decision.BLOCKED);
        if (decision == Decision.EXECUTABLE && !this.diagnostics.isEmpty())
            throw new IllegalArgumentException("Executable targets cannot have blocking diagnostics");
    }

    public static BulkTargetEligibility executable() {
        return new BulkTargetEligibility(Decision.EXECUTABLE, List.of());
    }

    public static BulkTargetEligibility blocked(List<ResourceCommandMessage> diagnostics) {
        return new BulkTargetEligibility(Decision.BLOCKED, diagnostics);
    }

    public Decision decision() { return decision; }
    public List<ResourceCommandMessage> diagnostics() { return diagnostics; }
    public boolean isExecutable() { return decision == Decision.EXECUTABLE; }
    @Override public String toString() { return "BulkTargetEligibility[" + decision + "]"; }
}
