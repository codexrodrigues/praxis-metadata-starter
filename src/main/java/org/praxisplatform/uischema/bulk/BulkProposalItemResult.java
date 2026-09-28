package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

/** Public, allowlisted result of evaluating one target in a retained bulk proposal. */
public final class BulkProposalItemResult<WI> {
    public enum Decision { EXECUTABLE, BLOCKED }

    private final WI id;
    private final Decision decision;
    private final List<ResourceCommandMessage> diagnostics;

    @JsonCreator
    public BulkProposalItemResult(
            @JsonProperty("id") WI id,
            @JsonProperty("decision") Decision decision,
            @JsonProperty("diagnostics") List<ResourceCommandMessage> diagnostics) {
        validateWireIdentity(id);
        this.id = id;
        this.decision = Objects.requireNonNull(decision, "decision is required");
        List<ResourceCommandMessage> safe = BulkResponseChecks.diagnostics(
                diagnostics, decision == Decision.BLOCKED);
        if (safe.size() > 16)
            throw new IllegalArgumentException("Too many target diagnostics");
        if (decision == Decision.EXECUTABLE && !safe.isEmpty())
            throw new IllegalArgumentException("Executable proposal results cannot have blocking diagnostics");
        if (safe.stream().anyMatch(message -> message.target() != null))
            throw new IllegalArgumentException("bulk proposal diagnostics must not expose a target");
        this.diagnostics = safe;
    }

    private static void validateWireIdentity(Object id) {
        if (id instanceof String text) {
            if (text.isEmpty()) throw new IllegalArgumentException("id must not be empty");
            return;
        }
        if (!(id instanceof Integer))
            throw new IllegalArgumentException("bulk proposal item id must be a supported wire identity");
    }

    @JsonProperty("id") public WI id() { return id; }
    @JsonProperty("decision") public Decision decision() { return decision; }
    @JsonProperty("diagnostics") public List<ResourceCommandMessage> diagnostics() { return diagnostics; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof BulkProposalItemResult<?> that
                && id.equals(that.id) && decision == that.decision && diagnostics.equals(that.diagnostics);
    }

    @Override public int hashCode() { return Objects.hash(id, decision, diagnostics); }

    @Override public String toString() { return "BulkProposalItemResult[decision=" + decision + "]"; }
}
