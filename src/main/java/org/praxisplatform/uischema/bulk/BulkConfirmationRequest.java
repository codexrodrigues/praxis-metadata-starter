package org.praxisplatform.uischema.bulk;

/** Confirmation carries no new parameters, targets, policy or concurrency tokens. */
public record BulkConfirmationRequest(String proposalId) {
    public BulkConfirmationRequest { BulkContractChecks.text(proposalId, "proposalId"); }
}
