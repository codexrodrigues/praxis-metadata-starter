package org.praxisplatform.uischema.bulk;

import java.util.Objects;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

/** Trusted server binding; never populate namespace or subject from unverified request headers. */
public record BulkFingerprintContext(String namespaceId, String subjectId, String resourceKey,
                                     CanonicalOperationRef operationRef, String schemaRevision,
                                     ActionCollectionAtomicity atomicity) {
    public BulkFingerprintContext {
        BulkContractChecks.text(namespaceId, "namespaceId");
        BulkContractChecks.text(subjectId, "subjectId");
        BulkContractChecks.text(resourceKey, "resourceKey");
        BulkContractChecks.text(schemaRevision, "schemaRevision");
        Objects.requireNonNull(operationRef, "operationRef is required");
        BulkContractChecks.text(operationRef.operationId(), "operationId");
        BulkContractChecks.text(operationRef.path(), "operation path");
        BulkContractChecks.text(operationRef.method(), "operation method");
        if (atomicity == null || atomicity == ActionCollectionAtomicity.NOT_APPLICABLE)
            throw new IllegalArgumentException("Bulk atomicity is required");
    }
    @Override public String toString() { return "BulkFingerprintContext[protected]"; }
}
