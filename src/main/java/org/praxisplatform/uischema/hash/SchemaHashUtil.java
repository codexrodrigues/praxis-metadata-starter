package org.praxisplatform.uischema.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for computing a SHA-256 hash of a canonical JSON tree.
 */
public final class SchemaHashUtil {

    private static final ObjectWriter WRITER = new ObjectMapper().writer();

    private SchemaHashUtil() {}

    public static String sha256Hex(JsonNode canonicalJson) {
        try {
            byte[] bytes = WRITER.writeValueAsBytes(canonicalJson);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            return toHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute schema hash", e);
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit((b & 0xF), 16));
        }
        return sb.toString();
    }
}

