package org.praxisplatform.uischema.bulk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Internal authenticated, encrypted continuation token for the bounded RS2/RS4 readers.
 * It carries server-owned claims but never authenticates or authorizes a caller.
 */
final class BulkReadCursorCodec {
    static final Duration MAX_TTL = Duration.ofMinutes(15);
    private static final Duration MAX_FUTURE_ISSUANCE_SKEW = Duration.ofSeconds(30);
    private static final String TOKEN_VERSION = "pbrc1";
    private static final byte PAYLOAD_VERSION = 1;
    private static final byte[] DOMAIN = "praxis.bulk.read-cursor".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final int MAX_TOKEN_CHARS = 8192;
    private static final int MAX_PAYLOAD_BYTES = 4096;
    private static final int MAX_KEY_COUNT = 8;
    private static final int MAX_TEXT_BYTES = 256;
    private static final int MAX_PROJECTOR_BYTES = 128;
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_ORDINAL = 10_000;

    private final KeySet keySet;
    private final SecureRandom random;
    private final Clock clock;

    BulkReadCursorCodec(KeySet keySet) {
        this(keySet, new SecureRandom(), Clock.systemUTC());
    }

    BulkReadCursorCodec(KeySet keySet, SecureRandom random, Clock clock) {
        this.keySet = Objects.requireNonNull(keySet, "keySet");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    String encode(Claims claims) {
        Objects.requireNonNull(claims, "claims");
        Instant now = clock.instant();
        validateIssuable(claims, now);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String keyId = keySet.activeKeyId();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySet.activeKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(claims.purpose(), keyId));
            byte[] ciphertext = cipher.doFinal(serialize(claims));
            String token = TOKEN_VERSION + "." + keyId + "." + encode64(nonce) + "." + encode64(ciphertext);
            if (token.length() > MAX_TOKEN_CHARS) throw invalid();
            return token;
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Bulk read cursor encryption is unavailable");
        }
    }

    Decoded decode(String token, Purpose expectedPurpose) {
        Objects.requireNonNull(expectedPurpose, "expectedPurpose");
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_CHARS) throw invalid();
        String[] segments = token.split("\\.", -1);
        if (segments.length != 4 || !TOKEN_VERSION.equals(segments[0]) || !validKeyId(segments[1])) throw invalid();
        SecretKey key = keySet.key(segments[1]);
        if (key == null) throw invalid();
        try {
            byte[] nonce = decode64(segments[2]);
            byte[] ciphertext = decode64(segments[3]);
            if (nonce.length != NONCE_BYTES || ciphertext.length < TAG_BYTES
                    || ciphertext.length > MAX_PAYLOAD_BYTES + TAG_BYTES) throw invalid();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(expectedPurpose, segments[1]));
            Claims claims = deserialize(cipher.doFinal(ciphertext));
            if (claims.purpose() != expectedPurpose) throw invalid();
            Instant now = clock.instant();
            if (claims.issuedAt().isAfter(now.plus(MAX_FUTURE_ISSUANCE_SKEW))) throw invalid();
            return new Decoded(claims, !claims.expiresAt().isAfter(now));
        } catch (BulkReadCursorException error) {
            throw error;
        } catch (GeneralSecurityException | IOException | RuntimeException error) {
            throw invalid();
        }
    }

    private static void validateIssuable(Claims claims, Instant now) {
        if (claims.issuedAt().isAfter(now.plus(MAX_FUTURE_ISSUANCE_SKEW))
                || !claims.expiresAt().isAfter(now)
                || Duration.between(claims.issuedAt(), claims.expiresAt()).compareTo(MAX_TTL) > 0) throw invalid();
    }

    private static byte[] serialize(Claims claims) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(PAYLOAD_VERSION);
            output.writeByte(claims.purpose().wireCode());
            writeUuid(output, claims.proposalId());
            output.writeBoolean(claims.executionId() != null);
            if (claims.executionId() != null) writeUuid(output, claims.executionId());
            writeText(output, claims.scope().namespaceId(), MAX_TEXT_BYTES);
            writeText(output, claims.scope().creatorSubjectId(), MAX_TEXT_BYTES);
            writeText(output, claims.scope().resourceKey(), MAX_TEXT_BYTES);
            writeText(output, claims.scope().operationId(), MAX_TEXT_BYTES);
            output.write(claims.authorizationScopeFingerprint());
            output.writeByte(claims.direction().wireCode());
            output.writeInt(claims.lastOrdinalExclusive());
            output.writeInt(claims.pageSize());
            output.writeInt(claims.watermarkExclusive());
            writeText(output, claims.projectorRevision(), MAX_PROJECTOR_BYTES);
            writeInstant(output, claims.issuedAt());
            writeInstant(output, claims.expiresAt());
            output.flush();
            byte[] payload = bytes.toByteArray();
            if (payload.length > MAX_PAYLOAD_BYTES) throw invalid();
            return payload;
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode bulk cursor claims");
        }
    }

    private static Claims deserialize(byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) throw invalid();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        if (input.readUnsignedByte() != PAYLOAD_VERSION) throw invalid();
        Purpose purpose = Purpose.fromWireCode(input.readUnsignedByte());
        UUID proposalId = readUuid(input);
        int hasExecutionId = input.readUnsignedByte();
        if (hasExecutionId != 0 && hasExecutionId != 1) throw invalid();
        UUID executionId = hasExecutionId == 1 ? readUuid(input) : null;
        Scope scope = new Scope(readText(input, MAX_TEXT_BYTES), readText(input, MAX_TEXT_BYTES),
                readText(input, MAX_TEXT_BYTES), readText(input, MAX_TEXT_BYTES));
        byte[] authorizationScopeFingerprint = input.readNBytes(FINGERPRINT_BYTES);
        if (authorizationScopeFingerprint.length != FINGERPRINT_BYTES) throw invalid();
        Direction direction = Direction.fromWireCode(input.readUnsignedByte());
        int lastOrdinalExclusive = input.readInt();
        int pageSize = input.readInt();
        int watermarkExclusive = input.readInt();
        String projectorRevision = readText(input, MAX_PROJECTOR_BYTES);
        Instant issuedAt = readInstant(input);
        Instant expiresAt = readInstant(input);
        if (input.available() != 0) throw invalid();
        return new Claims(purpose, proposalId, executionId, scope, authorizationScopeFingerprint,
                direction, lastOrdinalExclusive, pageSize, watermarkExclusive, projectorRevision, issuedAt, expiresAt);
    }

    private static byte[] aad(Purpose purpose, String keyId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            DataOutputStream output = new DataOutputStream(bytes);
            writeBytes(output, DOMAIN);
            output.writeByte(PAYLOAD_VERSION);
            output.writeByte(purpose.wireCode());
            writeText(output, keyId, 64);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode bulk cursor AAD");
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeInstant(DataOutputStream output, Instant value) throws IOException {
        output.writeLong(value.getEpochSecond());
        output.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        long seconds = input.readLong();
        int nanos = input.readInt();
        if (nanos < 0 || nanos > 999_999_999) throw invalid();
        try {
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (RuntimeException invalidInstant) {
            throw invalid();
        }
    }

    private static void writeText(DataOutputStream output, String value, int maxBytes) throws IOException {
        byte[] bytes;
        try {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer encoded = encoder.encode(java.nio.CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException malformed) {
            throw invalid();
        }
        if (bytes.length == 0 || bytes.length > maxBytes) throw invalid();
        writeBytes(output, bytes);
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > maxBytes || length > input.available()) throw invalid();
        byte[] bytes = input.readNBytes(length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw invalid();
        }
    }

    private static byte[] decode64(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!encode64(decoded).equals(value)) throw invalid();
            return decoded;
        } catch (IllegalArgumentException invalidBase64) {
            throw invalid();
        }
    }

    private static String encode64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean validKeyId(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '-' && c != '_') return false;
        }
        return true;
    }

    private static BulkReadCursorException invalid() {
        return new BulkReadCursorException();
    }

    enum Purpose {
        PROPOSAL_RESULTS(1), EXECUTION_RESULTS(2);
        private final int wireCode;
        Purpose(int wireCode) { this.wireCode = wireCode; }
        int wireCode() { return wireCode; }
        static Purpose fromWireCode(int code) {
            return Arrays.stream(values()).filter(value -> value.wireCode == code).findFirst().orElseThrow(BulkReadCursorCodec::invalid);
        }
    }

    enum Direction {
        NEXT(1);
        private final int wireCode;
        Direction(int wireCode) { this.wireCode = wireCode; }
        int wireCode() { return wireCode; }
        static Direction fromWireCode(int code) {
            return Arrays.stream(values()).filter(value -> value.wireCode == code).findFirst().orElseThrow(BulkReadCursorCodec::invalid);
        }
    }

    record Scope(String namespaceId, String creatorSubjectId, String resourceKey, String operationId) {
        Scope {
            validateText(namespaceId, "namespaceId", MAX_TEXT_BYTES);
            validateText(creatorSubjectId, "creatorSubjectId", MAX_TEXT_BYTES);
            validateText(resourceKey, "resourceKey", MAX_TEXT_BYTES);
            validateText(operationId, "operationId", MAX_TEXT_BYTES);
        }

        @Override public String toString() { return "BulkReadCursorScope[protected]"; }
    }

    record Claims(Purpose purpose, UUID proposalId, UUID executionId, Scope scope,
            byte[] authorizationScopeFingerprint, Direction direction, int lastOrdinalExclusive,
            int pageSize, int watermarkExclusive, String projectorRevision, Instant issuedAt, Instant expiresAt) {
        Claims {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(proposalId, "proposalId");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if ((purpose == Purpose.PROPOSAL_RESULTS && executionId != null)
                    || (purpose == Purpose.EXECUTION_RESULTS && executionId == null)) throw invalid();
            if (authorizationScopeFingerprint == null || authorizationScopeFingerprint.length != FINGERPRINT_BYTES)
                throw invalid();
            authorizationScopeFingerprint = authorizationScopeFingerprint.clone();
            if (watermarkExclusive < 1 || watermarkExclusive > MAX_ORDINAL
                    || lastOrdinalExclusive < 0 || lastOrdinalExclusive >= watermarkExclusive
                    || pageSize < 1 || pageSize > 200) throw invalid();
            validateText(projectorRevision, "projectorRevision", MAX_PROJECTOR_BYTES);
            if (!expiresAt.isAfter(issuedAt) || Duration.between(issuedAt, expiresAt).compareTo(MAX_TTL) > 0)
                throw invalid();
        }

        @Override public byte[] authorizationScopeFingerprint() { return authorizationScopeFingerprint.clone(); }

        boolean matchesEffectiveScope(byte[] currentFingerprint) {
            return currentFingerprint != null && currentFingerprint.length == FINGERPRINT_BYTES
                    && MessageDigest.isEqual(authorizationScopeFingerprint, currentFingerprint);
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Claims that)) return false;
            return purpose == that.purpose && Objects.equals(proposalId, that.proposalId)
                    && Objects.equals(executionId, that.executionId) && Objects.equals(scope, that.scope)
                    && Arrays.equals(authorizationScopeFingerprint, that.authorizationScopeFingerprint)
                    && direction == that.direction && lastOrdinalExclusive == that.lastOrdinalExclusive
                    && pageSize == that.pageSize && watermarkExclusive == that.watermarkExclusive
                    && Objects.equals(projectorRevision, that.projectorRevision)
                    && Objects.equals(issuedAt, that.issuedAt) && Objects.equals(expiresAt, that.expiresAt);
        }

        @Override public int hashCode() {
            int result = Objects.hash(purpose, proposalId, executionId, scope, direction,
                    lastOrdinalExclusive, pageSize, watermarkExclusive, projectorRevision, issuedAt, expiresAt);
            return 31 * result + Arrays.hashCode(authorizationScopeFingerprint);
        }

        @Override public String toString() { return "BulkReadCursorClaims[protected]"; }
    }

    record Decoded(Claims claims, boolean expired) {
        Decoded { Objects.requireNonNull(claims, "claims"); }
    }

    static final class KeySet {
        private final String activeKeyId;
        private final Map<String, SecretKey> keys;

        KeySet(String activeKeyId, Map<String, SecretKey> keys) {
            if (!validKeyId(activeKeyId) || keys == null || keys.isEmpty() || keys.size() > MAX_KEY_COUNT
                    || !keys.containsKey(activeKeyId)) throw new IllegalArgumentException("Invalid bulk cursor key set");
            Map<String, SecretKey> copy = new HashMap<>();
            keys.forEach((id, key) -> {
                if (!validKeyId(id) || key == null || !"AES".equalsIgnoreCase(key.getAlgorithm()))
                    throw new IllegalArgumentException("Invalid bulk cursor key set");
                byte[] encoded = key.getEncoded();
                if (encoded == null || encoded.length != 32)
                    throw new IllegalArgumentException("Bulk cursor keys must use AES-256");
                copy.put(id, new javax.crypto.spec.SecretKeySpec(encoded.clone(), "AES"));
            });
            this.activeKeyId = activeKeyId;
            this.keys = Map.copyOf(copy);
        }

        String activeKeyId() { return activeKeyId; }
        SecretKey activeKey() { return keys.get(activeKeyId); }
        SecretKey key(String id) { return keys.get(id); }
    }

    static final class BulkReadCursorException extends RuntimeException {
        private BulkReadCursorException() { super("Invalid bulk read cursor"); }
    }

    private static void validateText(String value, String name, int maxBytes) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid " + name);
        try {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            if (encoder.encode(java.nio.CharBuffer.wrap(value)).remaining() > maxBytes)
                throw new IllegalArgumentException("Invalid " + name);
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }
}
