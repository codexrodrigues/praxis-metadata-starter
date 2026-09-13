package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Public execution snapshot; item results are retrieved separately through the future cursor endpoint. */
public final class BulkExecution {
    private final String executionId;
    private final String proposalId;
    private final CanonicalOperationRef operationRef;
    private final BulkMode mode;
    private final BulkExecutionMode executionMode;
    private final ActionCollectionAtomicity atomicity;
    private final BulkExecutionStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant terminalAt;
    private final BulkExecutionTotals totals;
    private final List<ResourceCommandMessage> diagnostics;

    @JsonCreator
    public BulkExecution(
            @JsonProperty("executionId") String executionId,
            @JsonProperty("proposalId") String proposalId,
            @JsonProperty("operationRef") CanonicalOperationRef operationRef,
            @JsonProperty("mode") BulkMode mode,
            @JsonProperty("executionMode") BulkExecutionMode executionMode,
            @JsonProperty("atomicity") ActionCollectionAtomicity atomicity,
            @JsonProperty("status") BulkExecutionStatus status,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("terminalAt") Instant terminalAt,
            @JsonProperty("totals") BulkExecutionTotals totals,
            @JsonProperty("diagnostics") List<ResourceCommandMessage> diagnostics
    ) {
        BulkResponseChecks.text(executionId, "executionId");
        BulkResponseChecks.text(proposalId, "proposalId");
        this.executionId = executionId;
        this.proposalId = proposalId;
        this.operationRef = BulkResponseChecks.operation(operationRef);
        this.mode = Objects.requireNonNull(mode, "mode is required");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode is required");
        this.atomicity = BulkResponseChecks.atomicity(atomicity);
        this.status = Objects.requireNonNull(status, "status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        this.terminalAt = terminalAt;
        this.totals = Objects.requireNonNull(totals, "totals is required");
        this.diagnostics = BulkResponseChecks.diagnostics(diagnostics, status == BulkExecutionStatus.STOPPED);
        validateStatus();
    }

    private void validateStatus() {
        if (status.terminal() != (terminalAt != null)) {
            throw new IllegalArgumentException("terminalAt must be present only for terminal execution states");
        }
        if (terminalAt != null && (terminalAt.isBefore(createdAt) || terminalAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("terminalAt must be between createdAt and updatedAt");
        }
        if (status.terminal() && (totals.pending() != 0 || totals.unknown() != 0)) {
            throw new IllegalArgumentException("terminal executions cannot retain pending or unknown targets");
        }
        if (status == BulkExecutionStatus.QUEUED && !totals.allPending()) {
            throw new IllegalArgumentException("QUEUED executions must report every target as pending");
        }
        if (status == BulkExecutionStatus.COMPLETED && !totals.onlySuccessfulOutcomes()) {
            throw new IllegalArgumentException("COMPLETED executions can only contain confirmed or unchanged targets");
        }
        if (status == BulkExecutionStatus.COMPLETED_WITH_ERRORS && totals.failures() == 0) {
            throw new IllegalArgumentException("COMPLETED_WITH_ERRORS requires a non-success outcome");
        }
        if ((status == BulkExecutionStatus.CANCELLED || status == BulkExecutionStatus.STOPPED)
                && totals.notProcessed() == 0) {
            throw new IllegalArgumentException("Interrupted terminal executions require explicit unprocessed targets");
        }
        if (atomicity == ActionCollectionAtomicity.ATOMIC
                && totals.confirmed() > 0 && totals.atomicRollbackOrIncomplete()) {
            throw new IllegalArgumentException("ATOMIC executions cannot confirm targets after rollback or incomplete processing");
        }
    }

    @JsonProperty("executionId") public String executionId() { return executionId; }
    @JsonProperty("proposalId") public String proposalId() { return proposalId; }
    @JsonProperty("operationRef") public CanonicalOperationRef operationRef() { return operationRef; }
    @JsonProperty("mode") public BulkMode mode() { return mode; }
    @JsonProperty("executionMode") public BulkExecutionMode executionMode() { return executionMode; }
    @JsonProperty("atomicity") public ActionCollectionAtomicity atomicity() { return atomicity; }
    @JsonProperty("status") public BulkExecutionStatus status() { return status; }
    @JsonProperty("createdAt") public Instant createdAt() { return createdAt; }
    @JsonProperty("updatedAt") public Instant updatedAt() { return updatedAt; }
    @JsonProperty("terminalAt") public Instant terminalAt() { return terminalAt; }
    @JsonProperty("totals") public BulkExecutionTotals totals() { return totals; }
    @JsonProperty("diagnostics") public List<ResourceCommandMessage> diagnostics() { return diagnostics; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BulkExecution that)) return false;
        return executionId.equals(that.executionId) && proposalId.equals(that.proposalId)
                && operationRef.equals(that.operationRef) && mode == that.mode && executionMode == that.executionMode
                && atomicity == that.atomicity && status == that.status && createdAt.equals(that.createdAt)
                && updatedAt.equals(that.updatedAt) && Objects.equals(terminalAt, that.terminalAt)
                && totals.equals(that.totals) && diagnostics.equals(that.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(executionId, proposalId, operationRef, mode, executionMode, atomicity, status, createdAt,
                updatedAt, terminalAt, totals, diagnostics);
    }

    @Override
    public String toString() {
        return "BulkExecution[mode=" + mode + ", status=" + status + "]";
    }
}
