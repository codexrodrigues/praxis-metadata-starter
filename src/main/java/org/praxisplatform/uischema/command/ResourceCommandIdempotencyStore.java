package org.praxisplatform.uischema.command;

import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import java.util.function.Supplier;

/** Optional host-owned transactional receipt boundary. A failure must roll back both receipt and mutation. */
public interface ResourceCommandIdempotencyStore {
    record Identity(String scope, String actor, String resourceKey, String resourceId, String commandId, String key) {
        public Identity {
            for (String part : new String[]{scope, actor, resourceKey, resourceId, commandId, key})
                if (part == null || part.isBlank()) throw new IllegalArgumentException("Receipt identity must be complete");
            if (key.length() > 256) throw new IllegalArgumentException("Idempotency-Key exceeds 256 characters");
        }
    }

    /**
     * Runs current item authorization inside the shared write transaction, before reading or
     * returning a historical receipt. Only an authorized cache miss may invoke mutation;
     * receipt and domain effects must commit or roll back together. Replay must retain its
     * captured body/version and skip new-transition availability and mutation callbacks.
     */
    <T> ResourceRepresentationResult<T> execute(Identity identity, String fingerprint, Class<T> bodyType,
                                               Runnable authorize,
                                               Supplier<ResourceRepresentationResult<T>> mutation);
}
