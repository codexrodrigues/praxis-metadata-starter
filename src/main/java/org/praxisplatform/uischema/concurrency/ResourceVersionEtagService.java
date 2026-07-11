package org.praxisplatform.uischema.concurrency;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Cria e valida ETags fortes para uma versao persistida de recurso.
 *
 * <p>O valor e vinculado ao resource key, identificador e versao por HMAC; assim, um ETag de
 * outro recurso ou de outra versao nao pode ser reutilizado como precondicao.</p>
 */
public final class ResourceVersionEtagService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final SecretKeySpec secretKey;

    public ResourceVersionEtagService(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Resource version ETag secret must not be blank.");
        }
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String create(String resourceKey, Object resourceId, long version) {
        if (resourceKey == null || resourceKey.isBlank() || resourceId == null || version < 0) {
            throw new IllegalArgumentException("Resource key, resource id and non-negative version are required.");
        }
        String payload = resourceKey.trim() + "\n" + resourceId + "\n" + version;
        return "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload)) + "\"";
    }

    public boolean matches(String candidate, String resourceKey, Object resourceId, long version) {
        return candidate != null && create(resourceKey, resourceId, version).equals(candidate.trim());
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKey);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not create resource version ETag.", ex);
        }
    }
}
