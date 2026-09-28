package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import javax.crypto.SecretKey;

/**
 * Server-owned key rotation and lifetime policy for protected bulk read cursors.
 *
 * <p>The key material is snapshotted on construction and is deliberately not exposed through
 * accessors or serialization. Replacing this immutable configuration creates a new issuance
 * snapshot while retained keys continue to decode their unexpired cursors.</p>
 */
@JsonIgnoreType
public final class BulkReadCursorConfiguration {
    private final BulkReadCursorCodec.KeySet keySet;
    private final Duration ttl;

    public BulkReadCursorConfiguration(String activeKeyId, Map<String, SecretKey> keys, Duration ttl) {
        this.keySet = new BulkReadCursorCodec.KeySet(activeKeyId, keys);
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(BulkReadCursorCodec.MAX_TTL) > 0)
            throw new IllegalArgumentException("Bulk read cursor TTL must be within (0, 15 minutes]");
    }

    BulkReadCursorCodec.KeySet keySet() { return keySet; }
    Duration ttl() { return ttl; }

    @Override public String toString() { return "BulkReadCursorConfiguration[protected]"; }
}
