package org.praxisplatform.uischema.concurrency;

/** Validates the {@code If-Match} shape used by Praxis business actions. */
public final class ResourceVersionPreconditions {

    private ResourceVersionPreconditions() { }

    public static void requireMatch(
            ResourceVersionEtagService etags,
            String ifMatch,
            String resourceKey,
            Object resourceId,
            long version
    ) {
        requireMatch(etags, ifMatch, ResourceVersionScope.GLOBAL, resourceKey, resourceId, version);
    }

    public static void requireMatch(
            ResourceVersionEtagService etags,
            String ifMatch,
            ResourceVersionScope scope,
            String resourceKey,
            Object resourceId,
            long version
    ) {
        String candidate = requireStrongEtag(ifMatch);
        if (!etags.matches(candidate, scope, resourceKey, resourceId, version)) {
            throw ResourceVersionPreconditionException.stale();
        }
    }

    /**
     * Validates the transport contract without comparing it to the current persisted version.
     *
     * <p>An idempotent replay may carry the original, now stale, ETag, but it must not bypass a
     * required {@code If-Match} precondition or accept a weak/multi-value header.</p>
     *
     * @param ifMatch raw {@code If-Match} request header
     * @return the normalized strong ETag
     * @throws ResourceVersionPreconditionException when the header is absent or is not one strong ETag
     */
    public static String requireStrongEtag(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ResourceVersionPreconditionException.required();
        }
        String candidate = ifMatch.trim();
        if ("*".equals(candidate) || candidate.contains(",") || !candidate.startsWith("\"") || !candidate.endsWith("\"")) {
            throw ResourceVersionPreconditionException.invalid();
        }
        return candidate;
    }
}
