package org.praxisplatform.uischema.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes and decodes cursor tokens. The default implementation uses
 * URL-safe Base64 without padding.
 *
 * @since 1.0.0
 */
public interface CursorEncoder {

    CursorEncoder BASE64_URL = new CursorEncoder() {
        @Override
        public String encode(String value) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decode(String value) {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        }
    };

    /**
     * Encode the raw cursor value to a transport-safe string.
     */
    String encode(String value);

    /**
     * Decode the encoded cursor value back to its raw form.
     */
    String decode(String value);
}

