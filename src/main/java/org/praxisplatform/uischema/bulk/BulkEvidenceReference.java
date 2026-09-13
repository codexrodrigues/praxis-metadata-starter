package org.praxisplatform.uischema.bulk;

import java.util.Objects;

/**
 * Stable, sanitized reference to a policy or fact used during evaluation.
 *
 * <p>This deliberately does not carry arbitrary provider metadata, target identities, parameters,
 * or a policy payload. Those details belong to the protected proposal payload and their canonical
 * owners.</p>
 */
public record BulkEvidenceReference(
        BulkEvidenceKind kind,
        String referenceId,
        String revision,
        String fingerprint
) {
    public BulkEvidenceReference {
        kind = Objects.requireNonNull(kind, "kind is required");
        BulkResponseChecks.text(referenceId, "referenceId");
        BulkResponseChecks.text(revision, "revision");
        BulkResponseChecks.text(fingerprint, "fingerprint");
    }

    @Override
    public String toString() {
        return "BulkEvidenceReference[kind=" + kind + "]";
    }
}
