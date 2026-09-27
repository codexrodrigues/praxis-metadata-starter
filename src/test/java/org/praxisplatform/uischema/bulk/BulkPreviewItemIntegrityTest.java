package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BulkPreviewItemIntegrityTest {
    /** Independent Python hashlib/struct vector for the versioned, length-framed transcript. */
    @Test void goldenVectorBindsUnicodeNulOrdinalAndEveryStoredItemField() {
        byte[] allowlist = "[{\"message\":\"Café\\u0000\"}]".getBytes(StandardCharsets.UTF_8);
        byte[] context = BulkPreviewItemIntegrity.contextDigest(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "sha256:" + "a".repeat(64), "prévia\u0000v1🚀", 2, allowlist,
                "sha256:" + "b".repeat(64));
        assertThat(HexFormat.of().formatHex(context))
                .isEqualTo("9f5a40eb25d55c1e0394984b739bf8abfe0b8f27c6e1c78bf39313c7ecfd28e4");
        byte[] wireIdentity = "\"id\\u0000\"".getBytes(StandardCharsets.UTF_8);
        byte[] expectedVersion = new byte[] {0, 1, 0, (byte) 255, (byte) 128};
        byte[] diagnostics = "[{\"message\":\"Café\\u0000\"}]".getBytes(StandardCharsets.UTF_8);
        assertThat(BulkPreviewItemIntegrity.itemDigest(context, 1, wireIdentity,
                "sha256:" + "c".repeat(64), expectedVersion, "sha256:" + "d".repeat(64),
                "BLOCKED", diagnostics))
                .isEqualTo("sha256:1afcd8ccf9bb8aca82898b8247b4cd05967ff74c867db2938e068a0a59a4f70b");
    }
}
