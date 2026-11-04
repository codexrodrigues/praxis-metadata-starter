package org.praxisplatform.uischema.bulk;

import java.util.List;

/** Explicit host authority. Context comes from authenticated server state; no permissive defaults. */
public interface BulkResourcePolicy {
    BulkFingerprintContext context(String confirmationOperationId);
    /** Current operation grant, before any proposal capture or confirmation reservation. */
    void authorize(BulkFingerprintContext context);
    BulkEvaluationGovernance evaluateGovernance(BulkFingerprintContext context);
    BulkUnitAdmission admit(BulkExecutionUnit unit);
    BulkReadAuthorizationProvider readAuthorization(String confirmationOperationId);
    BulkProposalProjectionProvider proposalProjection(String confirmationOperationId);
    /** Only catalogued public messages may be persisted in the preview projection. */
    List<BulkPreviewProjection.PublicDiagnostic> publicDiagnostics(String confirmationOperationId);
}
