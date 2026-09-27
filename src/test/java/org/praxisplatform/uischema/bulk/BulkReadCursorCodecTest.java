package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert;

class BulkReadCursorCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-27T20:00:00Z");
    private static final UUID PROPOSAL_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID EXECUTION_ID = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    private static final byte[] EFFECTIVE_SCOPE = bytes(7);
    private static final byte[] OLD_KEY = bytes(11);
    private static final byte[] NEW_KEY = bytes(29);

    @Test
    void roundTripsBothReadPurposesWithoutRevealingClaimsInToken() {
        for (var purpose : BulkReadCursorCodec.Purpose.values()) {
            var codec = codec("cursor-key-a", OLD_KEY);
            var expected = claims(purpose, NOW, NOW.plus(Duration.ofMinutes(5)));

            String token = codec.encode(expected);
            var decoded = codec.decode(token, purpose);

            assertThat(decoded.expired()).isFalse();
            assertClaimsEqual(decoded.claims(), expected);
            assertThat(token).doesNotContain("123e4567", "alice", "payroll", "sha256");
        }
    }

    @Test
    void oldKeyRemainsReadableAfterRestartUntilRotationWindowEnds() {
        var issuer = codec("cursor-key-old", OLD_KEY);
        String token = issuer.encode(claims(BulkReadCursorCodec.Purpose.EXECUTION_RESULTS,
                NOW, NOW.plus(Duration.ofMinutes(15))));

        var restartedWithRotation = new BulkReadCursorCodec(new BulkReadCursorCodec.KeySet("cursor-key-new",
                Map.of("cursor-key-new", key(NEW_KEY), "cursor-key-old", key(OLD_KEY))),
                new java.security.SecureRandom(), Clock.fixed(NOW.plusSeconds(1), ZoneId.of("UTC")));
        assertThat(restartedWithRotation.decode(token, BulkReadCursorCodec.Purpose.EXECUTION_RESULTS).expired())
                .isFalse();

        var retiredTooSoon = codec("cursor-key-new", NEW_KEY);
        assertInvalid(() -> retiredTooSoon.decode(token, BulkReadCursorCodec.Purpose.EXECUTION_RESULTS));
    }

    @Test
    void separatesPurposeAndAuthenticatesEveryEnvelopeSegment() {
        var codec = codec("cursor-key-a", OLD_KEY);
        String token = codec.encode(claims(BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS,
                NOW, NOW.plusSeconds(300)));

        assertInvalid(() -> codec.decode(token, BulkReadCursorCodec.Purpose.EXECUTION_RESULTS));
        assertInvalid(() -> codec.decode(replaceSegment(token, 1, "cursor-key-b"),
                BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS));
        assertInvalid(() -> codec.decode(replaceSegment(token, 2, changeLastChar(segment(token, 2))),
                BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS));
        assertInvalid(() -> codec.decode(token + ".extra", BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS));
        assertInvalid(() -> codec.decode("pbrc1.cursor-key-a.%", BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS));
    }

    @Test
    void rejectsAuthenticatedMalformedUtf8TrailingPayloadAndTruncatedCiphertext() throws Exception {
        var codec = codec("cursor-key-a", OLD_KEY);
        var purpose = BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS;
        Envelope envelope = envelope(codec.encode(claims(purpose, NOW, NOW.plusSeconds(300))));
        byte[] plaintext = open(envelope, purpose, OLD_KEY);

        byte[] malformedUtf8 = plaintext.clone();
        malformedUtf8[23] = (byte) 0xc3; // first namespace byte, followed by an ASCII byte
        assertInvalid(() -> codec.decode(seal(envelope.keyId(), purpose, OLD_KEY,
                nonce(1), malformedUtf8), purpose));

        byte[] nonCanonicalBoolean = plaintext.clone();
        nonCanonicalBoolean[18] = 2; // executionId presence flag must be exactly zero or one
        assertInvalid(() -> codec.decode(seal(envelope.keyId(), purpose, OLD_KEY,
                nonce(3), nonCanonicalBoolean), purpose));

        byte[] trailing = Arrays.copyOf(plaintext, plaintext.length + 1);
        trailing[trailing.length - 1] = 1;
        assertInvalid(() -> codec.decode(seal(envelope.keyId(), purpose, OLD_KEY,
                nonce(2), trailing), purpose));

        byte[] truncated = Arrays.copyOf(envelope.ciphertext(), envelope.ciphertext().length - 1);
        assertInvalid(() -> codec.decode(join(envelope.keyId(), envelope.nonce(), truncated), purpose));

        byte[] changedTag = envelope.ciphertext().clone();
        changedTag[changedTag.length - 1] ^= 1;
        assertInvalid(() -> codec.decode(join(envelope.keyId(), envelope.nonce(), changedTag), purpose));
    }

    @Test
    void bindsHistoricalAndEffectiveReadScopesWithoutAuthorizingThem() {
        var codec = codec("cursor-key-a", OLD_KEY);
        var claims = claims(BulkReadCursorCodec.Purpose.EXECUTION_RESULTS, NOW, NOW.plusSeconds(300));
        String token = codec.encode(claims);
        var decoded = codec.decode(token, BulkReadCursorCodec.Purpose.EXECUTION_RESULTS).claims();

        assertThat(decoded.scope()).isEqualTo(claims.scope());
        assertThat(decoded.scope().toString()).isEqualTo("BulkReadCursorScope[protected]");
        assertThat(decoded.scope()).isNotEqualTo(new BulkReadCursorCodec.Scope(
                "production", "someone-else", "payroll", "approvePayroll"));
        assertThat(decoded.matchesEffectiveScope(EFFECTIVE_SCOPE)).isTrue();
        assertThat(decoded.matchesEffectiveScope(bytes(8))).isFalse();
        assertThat(decoded.matchesEffectiveScope(null)).isFalse();
        assertThat(decoded.toString()).isEqualTo("BulkReadCursorClaims[protected]");
    }

    @Test
    void usesFreshRandomNoncesForRepeatedClaims() {
        var codec = codec("cursor-key-a", OLD_KEY);
        var claims = claims(BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS, NOW, NOW.plusSeconds(300));
        Set<String> nonces = new HashSet<>();
        for (int i = 0; i < 64; i++) nonces.add(segment(codec.encode(claims), 2));
        assertThat(nonces).hasSize(64);
    }

    @Test
    void returnsAuthenticatedExpiredClaimsForCallerToAuthorizeBeforeMappingStatus() {
        var issuer = codec("cursor-key-a", OLD_KEY);
        String token = issuer.encode(claims(BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS,
                NOW, NOW.plusSeconds(30)));
        var afterExpiry = new BulkReadCursorCodec(keySet("cursor-key-a", OLD_KEY),
                new java.security.SecureRandom(), Clock.fixed(NOW.plusSeconds(31), ZoneId.of("UTC")));

        var decoded = afterExpiry.decode(token, BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS);
        assertThat(decoded.expired()).isTrue();
        assertThat(decoded.claims().proposalId()).isEqualTo(PROPOSAL_ID);
    }

    @Test
    void rejectsInvalidPageWindowTtlTextAndFingerprintAtConstruction() {
        assertThatThrownBy(() -> new BulkReadCursorCodec.Claims(BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS,
                PROPOSAL_ID, null, scope(), EFFECTIVE_SCOPE, BulkReadCursorCodec.Direction.NEXT,
                10, 200, 10, "projection/1", NOW, NOW.plusSeconds(30)))
                .isInstanceOf(BulkReadCursorCodec.BulkReadCursorException.class);
        assertThatThrownBy(() -> new BulkReadCursorCodec.Claims(BulkReadCursorCodec.Purpose.EXECUTION_RESULTS,
                PROPOSAL_ID, EXECUTION_ID, scope(), EFFECTIVE_SCOPE, BulkReadCursorCodec.Direction.NEXT,
                0, 201, 10, "projection/1", NOW, NOW.plusSeconds(30)))
                .isInstanceOf(BulkReadCursorCodec.BulkReadCursorException.class);
        assertThatThrownBy(() -> new BulkReadCursorCodec.Claims(BulkReadCursorCodec.Purpose.EXECUTION_RESULTS,
                PROPOSAL_ID, EXECUTION_ID, scope(), EFFECTIVE_SCOPE, BulkReadCursorCodec.Direction.NEXT,
                0, 20, 10, "projection/1", NOW, NOW.plus(BulkReadCursorCodec.MAX_TTL).plusNanos(1)))
                .isInstanceOf(BulkReadCursorCodec.BulkReadCursorException.class);
        assertThatThrownBy(() -> new BulkReadCursorCodec.Claims(BulkReadCursorCodec.Purpose.EXECUTION_RESULTS,
                PROPOSAL_ID, EXECUTION_ID, scope(), new byte[31], BulkReadCursorCodec.Direction.NEXT,
                0, 20, 10, "projection/1", NOW, NOW.plusSeconds(30)))
                .isInstanceOf(BulkReadCursorCodec.BulkReadCursorException.class);
        assertThatThrownBy(() -> new BulkReadCursorCodec.Scope("n".repeat(257), "alice", "payroll", "approve"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkReadCursorCodec.Claims(BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS,
                PROPOSAL_ID, null, scope(), EFFECTIVE_SCOPE, BulkReadCursorCodec.Direction.NEXT,
                0, 20, 10, "p".repeat(129), NOW, NOW.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkReadCursorCodec.Scope("production", "alice\uD800", "payroll", "approve"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkReadCursorCodec.KeySet("cursor-key-a",
                Map.of("cursor-key-a", key(new byte[16]))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkReadCursorCodec.KeySet("missing",
                Map.of("cursor-key-a", key(OLD_KEY))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkReadCursorCodec.KeySet("cursor.key",
                Map.of("cursor.key", key(OLD_KEY))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsTokenAndHidesAuthenticationDetails() {
        var codec = codec("cursor-key-a", OLD_KEY);
        assertInvalid(() -> codec.decode("x".repeat(8193), BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS));
        assertInvalid(() -> codec.decode("pbrc1.cursor-key-unknown.AA.AA",
                BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS));
        assertThatThrownBy(() -> codec.decode("pbrc1.cursor-key-a.AA.AA",
                BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS))
                .isInstanceOf(BulkReadCursorCodec.BulkReadCursorException.class)
                .hasMessage("Invalid bulk read cursor")
                .hasNoCause();
    }

    @Test
    void claimsDefensivelyCopyFingerprintAndHaveValueEquality() {
        byte[] source = bytes(13);
        var first = new BulkReadCursorCodec.Claims(BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS,
                PROPOSAL_ID, null, scope(), source, BulkReadCursorCodec.Direction.NEXT,
                4, 50, 10, "payroll-results/1", NOW, NOW.plusSeconds(300));
        source[0] = 99;
        byte[] exposed = first.authorizationScopeFingerprint();
        exposed[0] = 88;

        var equivalent = new BulkReadCursorCodec.Claims(BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS,
                PROPOSAL_ID, null, scope(), bytes(13), BulkReadCursorCodec.Direction.NEXT,
                4, 50, 10, "payroll-results/1", NOW, NOW.plusSeconds(300));

        assertThat(first.matchesEffectiveScope(bytes(13))).isTrue();
        assertThat(first).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
    }

    @Test
    void acceptsDocumentedMaximumPageAndLifetimeBounds() {
        var codec = codec("cursor-key-a", OLD_KEY);
        var claims = new BulkReadCursorCodec.Claims(BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS,
                PROPOSAL_ID, null, scope(), EFFECTIVE_SCOPE, BulkReadCursorCodec.Direction.NEXT,
                9_999, 200, 10_000, "projection/1", NOW, NOW.plus(BulkReadCursorCodec.MAX_TTL));

        var decoded = codec.decode(codec.encode(claims), BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS);

        assertThat(decoded.claims()).isEqualTo(claims);
        assertThat(decoded.expired()).isFalse();
    }

    private static BulkReadCursorCodec codec(String keyId, byte[] material) {
        return new BulkReadCursorCodec(keySet(keyId, material), new java.security.SecureRandom(),
                Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    private static BulkReadCursorCodec.KeySet keySet(String keyId, byte[] material) {
        return new BulkReadCursorCodec.KeySet(keyId, Map.of(keyId, key(material)));
    }

    private static SecretKeySpec key(byte[] material) {
        return new SecretKeySpec(material.clone(), "AES");
    }

    private static BulkReadCursorCodec.Claims claims(BulkReadCursorCodec.Purpose purpose,
            Instant issuedAt, Instant expiresAt) {
        return new BulkReadCursorCodec.Claims(purpose, PROPOSAL_ID,
                purpose == BulkReadCursorCodec.Purpose.EXECUTION_RESULTS ? EXECUTION_ID : null,
                scope(), EFFECTIVE_SCOPE, BulkReadCursorCodec.Direction.NEXT,
                4, 50, 10, "payroll-results/1", issuedAt, expiresAt);
    }

    private static BulkReadCursorCodec.Scope scope() {
        return new BulkReadCursorCodec.Scope("production", "alice", "payroll", "approvePayroll");
    }

    private static byte[] bytes(int seed) {
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (seed + i);
        return result;
    }

    private static void assertClaimsEqual(BulkReadCursorCodec.Claims actual, BulkReadCursorCodec.Claims expected) {
        assertThat(actual.purpose()).isEqualTo(expected.purpose());
        assertThat(actual.proposalId()).isEqualTo(expected.proposalId());
        assertThat(actual.executionId()).isEqualTo(expected.executionId());
        assertThat(actual.scope()).isEqualTo(expected.scope());
        assertThat(actual.matchesEffectiveScope(expected.authorizationScopeFingerprint())).isTrue();
        assertThat(actual.direction()).isEqualTo(expected.direction());
        assertThat(actual.lastOrdinalExclusive()).isEqualTo(expected.lastOrdinalExclusive());
        assertThat(actual.pageSize()).isEqualTo(expected.pageSize());
        assertThat(actual.watermarkExclusive()).isEqualTo(expected.watermarkExclusive());
        assertThat(actual.projectorRevision()).isEqualTo(expected.projectorRevision());
        assertThat(actual.issuedAt()).isEqualTo(expected.issuedAt());
        assertThat(actual.expiresAt()).isEqualTo(expected.expiresAt());
    }

    private static String segment(String token, int index) { return token.split("\\.", -1)[index]; }

    private static String replaceSegment(String token, int index, String replacement) {
        String[] segments = token.split("\\.", -1);
        segments[index] = replacement;
        return String.join(".", segments);
    }

    private static String changeLastChar(String value) {
        char last = value.charAt(value.length() - 1);
        return value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');
    }

    private static Envelope envelope(String token) {
        String[] segments = token.split("\\.", -1);
        return new Envelope(segments[1], Base64.getUrlDecoder().decode(segments[2]),
                Base64.getUrlDecoder().decode(segments[3]));
    }

    private static byte[] open(Envelope envelope, BulkReadCursorCodec.Purpose purpose, byte[] material)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(material), new GCMParameterSpec(128, envelope.nonce()));
        cipher.updateAAD(aad(envelope.keyId(), purpose));
        return cipher.doFinal(envelope.ciphertext());
    }

    private static String seal(String keyId, BulkReadCursorCodec.Purpose purpose, byte[] material,
            byte[] nonce, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(material), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(keyId, purpose));
        return join(keyId, nonce, cipher.doFinal(plaintext));
    }

    private static byte[] aad(String keyId, BulkReadCursorCodec.Purpose purpose) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            byte[] domain = "praxis.bulk.read-cursor".getBytes(StandardCharsets.US_ASCII);
            output.writeInt(domain.length);
            output.write(domain);
            output.writeByte(1); // payload version
            output.writeByte(purpose.wireCode());
            byte[] encodedKeyId = keyId.getBytes(StandardCharsets.UTF_8);
            output.writeInt(encodedKeyId.length);
            output.write(encodedKeyId);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String join(String keyId, byte[] nonce, byte[] ciphertext) {
        return "pbrc1." + keyId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
    }

    private static byte[] nonce(int seed) {
        byte[] nonce = new byte[12];
        Arrays.fill(nonce, (byte) seed);
        return nonce;
    }

    private record Envelope(String keyId, byte[] nonce, byte[] ciphertext) { }

    private static void assertInvalid(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(BulkReadCursorCodec.BulkReadCursorException.class)
                .hasMessage("Invalid bulk read cursor").hasNoCause();
    }
}
