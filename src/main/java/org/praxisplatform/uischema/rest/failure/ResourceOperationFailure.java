package org.praxisplatform.uischema.rest.failure;

/**
 * Safe public decision describing why a resource operation failed.
 *
 * @param kind governed semantic kind
 * @param code stable public code
 * @param safeMessage sanitized public message
 * @param target optional path in the public request contract
 */
public record ResourceOperationFailure(
        ResourceOperationFailureKind kind,
        String code,
        String safeMessage,
        String target
) {
    private static final int MAX_CODE_LENGTH = 200;
    private static final int MAX_TARGET_LENGTH = 512;

    public ResourceOperationFailure {
        if (kind == null) {
            throw new IllegalArgumentException("Resource operation failure kind is required.");
        }
        code = requireText(code, "Resource operation failure code is required.");
        safeMessage = requireText(safeMessage, "Resource operation safe message is required.");
        target = normalize(target);
        requirePublicToken(code, MAX_CODE_LENGTH, "Resource operation failure code");
        if (target != null) {
            requirePublicToken(target, MAX_TARGET_LENGTH, "Resource operation failure target");
        }
    }

    public static ResourceOperationFailure of(
            ResourceOperationFailureKind kind,
            String code,
            String safeMessage
    ) {
        return new ResourceOperationFailure(kind, code, safeMessage, null);
    }

    private static String requireText(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void requirePublicToken(String value, int maxLength, String label) {
        if (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " must be a bounded public value without control characters.");
        }
    }
}
