package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, publicly safe result of bulk evaluation.
 *
 * <p>The caller supplies an already redacted intent. This SDK defensively copies it but cannot
 * decide which business values may be revealed. Full selection bindings, versions, parameters,
 * and protected intent belong to the separate protected storage, never to this public projection.</p>
 */
public final class BulkProposal {
    private final String proposalId;
    private final CanonicalOperationRef operationRef;
    private final BulkMode mode;
    private final BulkExecutionMode executionMode;
    private final ActionCollectionAtomicity atomicity;
    private final BulkProposalStatus status;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final BulkProposalTotals totals;
    private final JsonNode redactedIntent;
    private final List<ResourceCommandMessage> diagnostics;
    private final List<BulkEvidenceReference> evidence;

    @JsonCreator
    public BulkProposal(
            @JsonProperty("proposalId") String proposalId,
            @JsonProperty("operationRef") CanonicalOperationRef operationRef,
            @JsonProperty("mode") BulkMode mode,
            @JsonProperty("executionMode") BulkExecutionMode executionMode,
            @JsonProperty("atomicity") ActionCollectionAtomicity atomicity,
            @JsonProperty("status") BulkProposalStatus status,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("expiresAt") Instant expiresAt,
            @JsonProperty("totals") BulkProposalTotals totals,
            @JsonProperty("redactedIntent") JsonNode redactedIntent,
            @JsonProperty("diagnostics") List<ResourceCommandMessage> diagnostics,
            @JsonProperty("evidence") List<BulkEvidenceReference> evidence
    ) {
        BulkResponseChecks.text(proposalId, "proposalId");
        this.proposalId = proposalId;
        this.operationRef = BulkResponseChecks.operation(operationRef);
        this.mode = Objects.requireNonNull(mode, "mode is required");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode is required");
        this.atomicity = BulkResponseChecks.atomicity(atomicity);
        this.status = Objects.requireNonNull(status, "status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        this.totals = Objects.requireNonNull(totals, "totals is required");
        this.redactedIntent = BulkResponseChecks.redactedIntent(redactedIntent);
        this.diagnostics = BulkResponseChecks.diagnostics(diagnostics, status == BulkProposalStatus.BLOCKED);
        this.evidence = BulkResponseChecks.evidence(evidence);
        validateStatus();
    }

    private void validateStatus() {
        if (status == BulkProposalStatus.READY
                && (totals.targetCount() == 0
                || totals.evaluated() != totals.targetCount()
                || totals.executable() != totals.targetCount()
                || totals.blocked() != 0)) {
            throw new IllegalArgumentException("READY proposals require a complete executable target set");
        }
    }

    @JsonProperty("proposalId")
    public String proposalId() { return proposalId; }

    @JsonProperty("operationRef")
    public CanonicalOperationRef operationRef() { return operationRef; }

    @JsonProperty("mode")
    public BulkMode mode() { return mode; }

    @JsonProperty("executionMode")
    public BulkExecutionMode executionMode() { return executionMode; }

    @JsonProperty("atomicity")
    public ActionCollectionAtomicity atomicity() { return atomicity; }

    @JsonProperty("status")
    public BulkProposalStatus status() { return status; }

    @JsonProperty("createdAt")
    public Instant createdAt() { return createdAt; }

    @JsonProperty("expiresAt")
    public Instant expiresAt() { return expiresAt; }

    @JsonProperty("totals")
    public BulkProposalTotals totals() { return totals; }

    @JsonProperty("redactedIntent")
    public JsonNode redactedIntent() { return redactedIntent.deepCopy(); }

    @JsonProperty("diagnostics")
    public List<ResourceCommandMessage> diagnostics() { return diagnostics; }

    @JsonProperty("evidence")
    public List<BulkEvidenceReference> evidence() { return evidence; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BulkProposal that)) return false;
        return proposalId.equals(that.proposalId) && operationRef.equals(that.operationRef) && mode == that.mode
                && executionMode == that.executionMode && atomicity == that.atomicity && status == that.status
                && createdAt.equals(that.createdAt) && expiresAt.equals(that.expiresAt) && totals.equals(that.totals)
                && redactedIntent.equals(that.redactedIntent) && diagnostics.equals(that.diagnostics)
                && evidence.equals(that.evidence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(proposalId, operationRef, mode, executionMode, atomicity, status, createdAt, expiresAt,
                totals, redactedIntent, diagnostics, evidence);
    }

    @Override
    public String toString() {
        return "BulkProposal[mode=" + mode + ", status=" + status + "]";
    }
}
