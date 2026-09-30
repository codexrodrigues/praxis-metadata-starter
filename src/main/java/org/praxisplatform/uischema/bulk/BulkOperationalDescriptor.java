package org.praxisplatform.uischema.bulk;

import java.util.Objects;

/** Internal complete structural and operational tuple; never a capability or readiness claim. */
record BulkOperationalDescriptor(
        BulkOperationControlIdentity identity,
        String structuralRevision,
        String descriptorFingerprint,
        String providerId,
        String providerRevision,
        BulkOperationalProfile profile,
        BulkOperationStructuralDescriptor structural,
        BulkExecutionInfrastructure infrastructure) {

    BulkOperationalDescriptor {
        Objects.requireNonNull(identity, "identity");
        text(structuralRevision, "structuralRevision");
        text(descriptorFingerprint, "descriptorFingerprint");
        if (!descriptorFingerprint.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("descriptorFingerprint must be canonical SHA-256");
        text(providerId, "providerId");
        text(providerRevision, "providerRevision");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(structural, "structural");
        Objects.requireNonNull(infrastructure, "infrastructure");
        if (!identity.namespaceId().equals(infrastructure.namespace()))
            throw new IllegalArgumentException("Control identity and runtime infrastructure namespaces differ");
    }

    BulkOperationControlExpectation expectation(long generation) {
        return new BulkOperationControlExpectation(generation, descriptorFingerprint, structuralRevision);
    }

    private static void text(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl) || value.length() > 200)
            throw new IllegalArgumentException(name + " must be canonical nonblank text");
    }
}
