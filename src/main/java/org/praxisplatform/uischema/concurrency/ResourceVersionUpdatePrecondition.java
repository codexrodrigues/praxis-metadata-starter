package org.praxisplatform.uischema.concurrency;

import java.util.Objects;

/**
 * Strong update precondition that must be evaluated against the locked current version.
 *
 * <p>The controller binds the public {@code If-Match} value to the canonical resource identity,
 * while the command service decides when the persisted version is read. Implementations must call
 * {@link #requireMatch(long)} inside the same transaction and lock scope used by the update.</p>
 */
public final class ResourceVersionUpdatePrecondition<ID> {

    private final ResourceVersionEtagService etags;
    private final String ifMatch;
    private final String resourceKey;
    private final ID resourceId;

    public ResourceVersionUpdatePrecondition(
            ResourceVersionEtagService etags,
            String ifMatch,
            String resourceKey,
            ID resourceId
    ) {
        this.etags = Objects.requireNonNull(etags, "etags must not be null");
        this.ifMatch = ifMatch;
        this.resourceKey = Objects.requireNonNull(resourceKey, "resourceKey must not be null");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId must not be null");
    }

    /** Validates the bound header against the version read under the command transaction. */
    public void requireMatch(long currentVersion) {
        ResourceVersionPreconditions.requireMatch(etags, ifMatch, resourceKey, resourceId, currentVersion);
    }
}
