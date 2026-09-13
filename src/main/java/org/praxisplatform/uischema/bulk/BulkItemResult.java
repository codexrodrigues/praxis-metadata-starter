package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

import java.util.List;
import java.util.Objects;

/** Public result for one target, retaining its exact supported wire identity. */
public final class BulkItemResult<WI> {
    private final WI id;
    private final BulkItemStatus status;
    private final List<ResourceCommandMessage> diagnostics;

    @JsonCreator
    public BulkItemResult(
            @JsonProperty("id") WI id,
            @JsonProperty("status") BulkItemStatus status,
            @JsonProperty("diagnostics") List<ResourceCommandMessage> diagnostics
    ) {
        validateWireId(id);
        this.id = id;
        this.status = Objects.requireNonNull(status, "status is required");
        this.diagnostics = BulkResponseChecks.diagnostics(diagnostics, status.requiresDiagnostic());
    }

    private static void validateWireId(Object id) {
        if (id instanceof String text) {
            if (text.isEmpty()) throw new IllegalArgumentException("id must not be empty");
            return;
        }
        if (!(id instanceof Integer)) {
            throw new IllegalArgumentException("bulk item result id must be a supported wire identity");
        }
    }

    @JsonProperty("id") public WI id() { return id; }
    @JsonProperty("status") public BulkItemStatus status() { return status; }
    @JsonProperty("diagnostics") public List<ResourceCommandMessage> diagnostics() { return diagnostics; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BulkItemResult<?> that)) return false;
        return id.equals(that.id) && status == that.status && diagnostics.equals(that.diagnostics);
    }

    @Override
    public int hashCode() { return Objects.hash(id, status, diagnostics); }

    @Override
    public String toString() { return "BulkItemResult[status=" + status + "]"; }
}
