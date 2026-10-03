package org.praxisplatform.uischema.bulk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/** Versioned binding between one evaluation, ordinal, wire identity and expected version. */
final class BulkTargetDigest {
    private static final int[] EDGE_WHITESPACE = {0x20, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
            0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x205f, 0x3000};
    private static final int[] ISO_CONTROLS = java.util.stream.IntStream.concat(
            java.util.stream.IntStream.rangeClosed(1, 31),
            java.util.stream.IntStream.rangeClosed(127, 159)).toArray();
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

    static String wireIdentity(byte[] canonicalWireJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "praxis.bulk.wire-identity/1");
            update(digest, canonicalWireJson);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** Framed, ordered binding of the complete evaluated set; no JSON string concatenation. */
    static String setOf(String evaluationFingerprint, List<String> targetDigests) {
        if (targetDigests == null || targetDigests.isEmpty() || targetDigests.size() > 50)
            throw new IllegalArgumentException("Atomic set requires 1 to 50 targets");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "praxis.bulk.atomic-set/1");
            update(digest, evaluationFingerprint);
            update(digest, Integer.toString(targetDigests.size()));
            for (int ordinal = 0; ordinal < targetDigests.size(); ordinal++) {
                String target = targetDigests.get(ordinal);
                if (target == null || !target.matches("sha256:[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid atomic target digest");
                update(digest, Integer.toString(ordinal));
                update(digest, target);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** SQL COLLATE "C" and this unsigned UTF-8 comparator use the same byte order. */
    static String effectsOf(List<EffectReference> effects) {
        if (effects == null || effects.size() > 400)
            throw new IllegalArgumentException("Invalid atomic effect count");
        var seen = new HashSet<EffectReference>();
        for (EffectReference effect : effects)
            if (effect == null || effect.ordinal() < 0 || effect.ordinal() >= 50
                    || !validEffectReference(effect.reference()) || !seen.add(effect))
                throw new IllegalArgumentException("Invalid atomic effect reference");
        var ordered = new ArrayList<>(effects);
        ordered.sort((left, right) -> {
            int ordinal = Integer.compare(left.ordinal(), right.ordinal());
            return ordinal != 0 ? ordinal : Arrays.compareUnsigned(
                    left.reference().getBytes(StandardCharsets.UTF_8),
                    right.reference().getBytes(StandardCharsets.UTF_8));
        });
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "praxis.bulk.atomic-effects/1");
            update(digest, Integer.toString(ordered.size()));
            for (EffectReference effect : ordered) {
                update(digest, Integer.toString(effect.ordinal()));
                update(digest, effect.reference());
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    record EffectReference(int ordinal, String reference) {
        EffectReference {
            if (reference == null) throw new IllegalArgumentException("Null atomic effect reference");
        }
    }

    /** Fixed Java 21 whitespace/control repertoire, mirrored by the V16 CHECK via chr(). */
    static boolean validEffectReference(String reference) {
        if (reference == null || reference.isEmpty() || reference.length() > 400
                || reference.codePointCount(0, reference.length()) > 200
                || !StandardCharsets.UTF_8.newEncoder().canEncode(reference)) return false;
        if (edgeWhitespace(reference.codePointAt(0))
                || edgeWhitespace(reference.codePointBefore(reference.length()))) return false;
        return reference.codePoints().noneMatch(BulkTargetDigest::isoControl);
    }

    private static boolean isoControl(int codePoint) {
        return codePoint <= 0x1f || codePoint >= 0x7f && codePoint <= 0x9f;
    }

    private static boolean edgeWhitespace(int codePoint) {
        return Arrays.binarySearch(EDGE_WHITESPACE, codePoint) >= 0;
    }

    static int[] edgeWhitespaceCodePoints() { return EDGE_WHITESPACE.clone(); }
    static int[] storedControlCodePoints() { return ISO_CONTROLS.clone(); }

    private static void update(MessageDigest digest, String value) {
        update(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void update(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
