package org.praxisplatform.uischema.concurrency;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * A response body and the persisted item revision captured from the same resource state.
 *
 * <p>This is a Java service result, not an HTTP envelope. Controllers expose {@link #body()}
 * and use the captured revision to produce the item ETag without reading the resource again.
 * A missing revision is valid only when the result does not certify a versioned resource.
 * It must not be replaced by a later lookup, an expected input version or a guessed value.</p>
 *
 * <p>Capture the body and revision from the same entity in the read transaction, or after
 * the last mutation hook and flush in the write transaction. This does not establish a
 * snapshot of related resources, links or availability, and does not copy a mutable body.</p>
 *
 * @param body the detached response projection, which must not be changed after capture
 * @param persistedVersion the revision represented by that projection, if available
 * @param <T> response body type
 */
public record ResourceRepresentationResult<T>(T body, OptionalLong persistedVersion) {

    public ResourceRepresentationResult {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(persistedVersion, "persistedVersion");
        if (persistedVersion.isPresent() && persistedVersion.getAsLong() < 0) {
            throw new IllegalArgumentException("A persisted resource revision must not be negative.");
        }
    }

    public static <T> ResourceRepresentationResult<T> unversioned(T body) {
        return new ResourceRepresentationResult<>(body, OptionalLong.empty());
    }

    public static <T> ResourceRepresentationResult<T> versioned(T body, long persistedVersion) {
        return new ResourceRepresentationResult<>(body, OptionalLong.of(persistedVersion));
    }

    /** Rejects a versioned operation whose result cannot certify its persisted revision. */
    public long requirePersistedVersion() {
        return persistedVersion.orElseThrow(() -> new IllegalStateException(
                "A versioned resource result must contain its captured persisted revision."));
    }
}
