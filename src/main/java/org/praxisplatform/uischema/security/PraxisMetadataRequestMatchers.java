package org.praxisplatform.uischema.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link org.springframework.web.util.pattern.PathPatternParser}-safe URL matchers for Praxis
 * read-only {@code POST} endpoints (query filtering, cursor navigation, locate, analytical stats,
 * collection export, and option-source lookups).
 *
 * <p>Spring Security 6.5's {@code PathPatternParser} rejects mid-path {@code /**&#47;} wildcards
 * such as {@code &#47;**&#47;filter}. This helper emits explicit fixed-depth wildcard patterns
 * ({@code /api/*&#47;...}, {@code /api/*&#47;*&#47;...}, {@code /api/*&#47;*&#47;*&#47;...}) so hosts
 * can exempt read-only query POST routes from CSRF or distinguish read vs write authorization cleanly
 * without ever sweeping in mutations, commands, or bulk confirmation/evaluation routes.</p>
 */
public final class PraxisMetadataRequestMatchers {

    private static final List<String> READ_ONLY_POST_SUFFIXES = List.of(
            "/filter",
            "/filter/cursor",
            "/locate",
            "/export",
            "/stats/group-by",
            "/stats/timeseries",
            "/stats/distribution",
            "/stats/comparison",
            "/options/filter",
            "/option-sources/*/options/filter",
            "/option-sources/*/options/by-ids"
    );

    private static final int DEFAULT_MAX_RESOURCE_DEPTH = 3;

    private PraxisMetadataRequestMatchers() {
    }

    /**
     * Returns {@code PathPatternParser}-safe patterns matching Praxis read-only {@code POST} endpoints
     * under {@code /api} across 1-, 2-, and 3-segment resource base paths (e.g. {@code /api/employees/filter},
     * {@code /api/workforce/employees/filter}, {@code /api/v1/workforce/employees/filter}).
     */
    public static String[] readOnlyPostPatterns() {
        return readOnlyPostPatterns("/api", DEFAULT_MAX_RESOURCE_DEPTH);
    }

    /**
     * Returns {@code PathPatternParser}-safe patterns matching Praxis read-only {@code POST} endpoints
     * under the specified root prefix (for example {@code "/api"} or {@code ""}).
     */
    public static String[] readOnlyPostPatterns(String rootPrefix) {
        return readOnlyPostPatterns(rootPrefix, DEFAULT_MAX_RESOURCE_DEPTH);
    }

    /**
     * Returns {@code PathPatternParser}-safe patterns matching Praxis read-only {@code POST} endpoints
     * under {@code rootPrefix} up to {@code maxResourceDepth} path segments deep.
     */
    public static String[] readOnlyPostPatterns(String rootPrefix, int maxResourceDepth) {
        Objects.requireNonNull(rootPrefix, "rootPrefix");
        if (maxResourceDepth < 1 || maxResourceDepth > 6) {
            throw new IllegalArgumentException("maxResourceDepth must be between 1 and 6");
        }
        String normalizedPrefix = rootPrefix.trim();
        while (normalizedPrefix.endsWith("/")) {
            normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
        }
        if (!normalizedPrefix.isEmpty() && !normalizedPrefix.startsWith("/")) {
            normalizedPrefix = "/" + normalizedPrefix;
        }

        List<String> patterns = new ArrayList<>();
        StringBuilder depthWildcards = new StringBuilder();
        for (int depth = 1; depth <= maxResourceDepth; depth++) {
            depthWildcards.append("/*");
            String resourceBase = normalizedPrefix + depthWildcards;
            for (String suffix : READ_ONLY_POST_SUFFIXES) {
                patterns.add(resourceBase + suffix);
            }
        }
        return patterns.toArray(String[]::new);
    }
}
