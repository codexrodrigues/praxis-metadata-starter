package org.praxisplatform.uischema.bulk;

/** Explicit deployment identity for one confirmation operation's durable readiness control. */
public record BulkOperationControlIdentity(String namespaceId, String confirmationOperationId) {
    public BulkOperationControlIdentity {
        canonical(namespaceId, "namespaceId");
        canonical(confirmationOperationId, "confirmationOperationId");
    }

    private static void canonical(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 200 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be canonical nonblank text");
        }
    }
}
