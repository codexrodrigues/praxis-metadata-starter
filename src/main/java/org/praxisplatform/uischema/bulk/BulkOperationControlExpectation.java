package org.praxisplatform.uischema.bulk;

import java.util.Objects;

/** Immutable locally composed control tuple required for a new governed bulk mutation. */
public record BulkOperationControlExpectation(
        long generation,
        String descriptorFingerprint,
        String structuralRevision) {

    public BulkOperationControlExpectation {
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        descriptorFingerprint = Objects.requireNonNull(descriptorFingerprint, "descriptorFingerprint");
        structuralRevision = Objects.requireNonNull(structuralRevision, "structuralRevision");
        if (!descriptorFingerprint.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("descriptorFingerprint must be canonical SHA-256");
        if (structuralRevision.isBlank() || !structuralRevision.equals(structuralRevision.strip())
                || structuralRevision.codePoints().anyMatch(Character::isISOControl)
                || structuralRevision.length() > 200)
            throw new IllegalArgumentException("structuralRevision must be canonical nonblank text");
    }
}
