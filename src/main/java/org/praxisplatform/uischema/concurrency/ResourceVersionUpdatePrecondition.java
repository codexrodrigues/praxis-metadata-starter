package org.praxisplatform.uischema.concurrency;

import java.util.Objects;

/**
 * Carries an HTTP {@code If-Match} precondition into the transactional resource update boundary.
 *
 * <p>The controller validates only the header shape. The command service must call
 * {@link #requireMatch(Object, long)} with the version read under its own transaction/lock before
 * applying any mutation.</p>
 */
public final class ResourceVersionUpdatePrecondition {

    private final ResourceVersionEtagService etags;
    private final String ifMatch;
    private final String resourceKey;

    private ResourceVersionUpdatePrecondition(
            ResourceVersionEtagService etags,
            String ifMatch,
            String resourceKey
    ) {
        this.etags = Objects.requireNonNull(etags, "etags must not be null");
        this.ifMatch = ResourceVersionPreconditions.requireSingleStrongTag(ifMatch);
        if (resourceKey == null || resourceKey.isBlank()) {
            throw new IllegalArgumentException("resourceKey must not be blank");
        }
        this.resourceKey = resourceKey;
    }

    public static ResourceVersionUpdatePrecondition required(
            ResourceVersionEtagService etags,
            String ifMatch,
            String resourceKey
    ) {
        return new ResourceVersionUpdatePrecondition(etags, ifMatch, resourceKey);
    }

    public void requireMatch(Object resourceId, long currentVersion) {
        ResourceVersionPreconditions.requireMatch(etags, ifMatch, resourceKey, resourceId, currentVersion);
    }
}
