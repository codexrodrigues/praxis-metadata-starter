package org.praxisplatform.uischema.bulk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Purpose-separated versioned identities used by bulk quota buckets and retained tombstones. */
final class BulkScopeDigests {
    static final int VERSION = 1;

    private BulkScopeDigests() { }

    /** Stable per authenticated principal and deployment; resource and operation are intentionally absent. */
    static String subjectQuotaDigest(String deploymentId, String canonicalSubjectId) {
        return digest("praxis.bulk.subject-quota/1", deploymentId, canonicalSubjectId);
    }

    static String idempotencyKeyDigest(String canonicalKey) {
        return digest("praxis.bulk.idempotency/1", canonicalKey);
    }

    /** Stable replay/read scope; deliberately distinct from the quota identity. */
    static String authorizationScopeDigest(String namespaceId, String canonicalSubjectId,
            String resourceKey, String operationId) {
        return digest("praxis.bulk.authorization-scope/1", namespaceId, canonicalSubjectId,
                resourceKey, operationId);
    }

    private static String digest(String purpose, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, purpose);
            for (String value : values) {
                if (value == null || value.isBlank() || !value.equals(value.strip())
                        || value.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Bulk scope identity must be canonical nonblank text");
                }
                update(digest, value);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
