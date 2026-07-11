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
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ResourceVersionPreconditionException.required();
        }
        String candidate = ifMatch.trim();
        if ("*".equals(candidate) || candidate.contains(",") || !candidate.startsWith("\"") || !candidate.endsWith("\"")) {
            throw ResourceVersionPreconditionException.invalid();
        }
        if (!etags.matches(candidate, resourceKey, resourceId, version)) {
            throw ResourceVersionPreconditionException.stale();
        }
    }
}
