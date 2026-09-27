package org.praxisplatform.uischema.bulk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned binding between one evaluation, ordinal, wire identity and expected version. */
final class BulkTargetDigest {
    private BulkTargetDigest() { }

    static String of(String evaluationFingerprint, int ordinal, Object wireId, String expectedVersion) {
        if (ordinal < 0 || (!(wireId instanceof Integer) && !(wireId instanceof String)))
            throw new IllegalArgumentException("Invalid protected target identity");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "praxis.bulk.unit/1");
            update(digest, evaluationFingerprint);
            update(digest, Integer.toString(ordinal));
            update(digest, wireId instanceof Integer ? "integer" : "string");
            update(digest, wireId.toString());
            update(digest, expectedVersion);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
