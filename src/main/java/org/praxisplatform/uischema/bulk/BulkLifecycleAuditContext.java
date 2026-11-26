package org.praxisplatform.uischema.bulk;

/**
 * Caller metadata persisted alongside governed bulk lifecycle transitions in
 * {@code praxis_bulk.praxis_bulk_lifecycle_audit}.
 */
public record BulkLifecycleAuditContext(String actor, String realm, String correlationId) {
    private static final int MAX_ACTOR_LENGTH = 128;
    private static final int MAX_REALM_LENGTH = 64;
    private static final int MAX_CORRELATION_LENGTH = 128;

    public BulkLifecycleAuditContext {
        actor = requireCanonical(actor, "actor", MAX_ACTOR_LENGTH);
        realm = requireCanonical(realm, "realm", MAX_REALM_LENGTH);
        correlationId = requireCanonical(correlationId, "correlationId", MAX_CORRELATION_LENGTH);
    }

    public static BulkLifecycleAuditContext of(String actor, String realm, String correlationId) {
        return new BulkLifecycleAuditContext(actor, realm, correlationId);
    }

    public static BulkLifecycleAuditContext sanitized(String actor, String realm, String correlationId) {
        return new BulkLifecycleAuditContext(
                sanitizeToken(actor, "anonymous", MAX_ACTOR_LENGTH),
                sanitizeToken(realm, "platform", MAX_REALM_LENGTH),
                sanitizeToken(correlationId, "-", MAX_CORRELATION_LENGTH));
    }

    public static BulkLifecycleAuditContext system() {
        return new BulkLifecycleAuditContext("system", "platform", "-");
    }

    private static String requireCanonical(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > maxLength || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be canonical nonblank text (max " + maxLength + ")");
        }
        return value;
    }

    private static String sanitizeToken(String raw, String fallback, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String cleaned = raw.trim().replaceAll("[\\p{Cntrl}\\s]+", "_");
        if (cleaned.isEmpty()) {
            return fallback;
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
