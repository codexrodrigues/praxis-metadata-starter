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
            "/stats/inference",
            "/stats/anomalies",
            "/stats/simulation",
            "/stats/counterfactual",
            "/stats/recipes/*",
            "/options/filter",
            "/option-sources/*/options/filter",
            "/option-sources/*/options/by-ids"
    );

    private static final List<String> INTELLIGENCE_JOB_POST_SUFFIXES = List.of(
            "/stats/simulation/jobs",
            "/stats/counterfactual/jobs"
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
        return expandResourceSuffixPatterns(rootPrefix, maxResourceDepth, READ_ONLY_POST_SUFFIXES);
    }

    /**
     * Returns {@code PathPatternParser}-safe patterns matching stateful asynchronous intelligence
     * job-creation {@code POST} routes ({@code /stats/simulation/jobs} and {@code /stats/counterfactual/jobs})
     * under {@code /api} across 1-, 2-, and 3-segment resource base paths.
     *
     * <p>Kept separate from {@link #readOnlyPostPatterns()} so hosts configure CSRF and write-rate
     * authorization explicitly for stateful job submission endpoints.</p>
     */
    public static String[] intelligenceJobPostPatterns() {
        return intelligenceJobPostPatterns("/api", DEFAULT_MAX_RESOURCE_DEPTH);
    }

    /**
     * Returns {@code PathPatternParser}-safe patterns matching stateful asynchronous intelligence
     * job-creation {@code POST} routes under the specified {@code rootPrefix}.
     */
    public static String[] intelligenceJobPostPatterns(String rootPrefix) {
        return intelligenceJobPostPatterns(rootPrefix, DEFAULT_MAX_RESOURCE_DEPTH);
    }

    /**
     * Returns {@code PathPatternParser}-safe patterns matching stateful asynchronous intelligence
     * job-creation {@code POST} routes under {@code rootPrefix} up to {@code maxResourceDepth} segments deep.
     */
    public static String[] intelligenceJobPostPatterns(String rootPrefix, int maxResourceDepth) {
        return expandResourceSuffixPatterns(rootPrefix, maxResourceDepth, INTELLIGENCE_JOB_POST_SUFFIXES);
    }

    /**
     * Returns {@code PathPatternParser}-safe patterns matching asynchronous intelligence job polling
     * ({@code GET /api/praxis/intelligence/jobs/*}) and cancellation
     * ({@code POST/DELETE /api/praxis/intelligence/jobs/*&#47;cancel}) endpoints.
     */
    public static String[] intelligenceJobPollAndCancelPatterns() {
        return intelligenceJobPollAndCancelPatterns("");
    }

    /**
     * Returns {@code PathPatternParser}-safe patterns matching asynchronous intelligence job polling
     * and cancellation endpoints under the specified context/prefix.
     */
    public static String[] intelligenceJobPollAndCancelPatterns(String rootPrefix) {
        String normalizedPrefix = normalizePrefix(rootPrefix);
        return new String[] {
                normalizedPrefix + "/api/praxis/intelligence/jobs/*",
                normalizedPrefix + "/api/praxis/intelligence/jobs/*/cancel"
        };
    }

    private static String[] expandResourceSuffixPatterns(
            String rootPrefix,
            int maxResourceDepth,
            List<String> suffixes
    ) {
        if (maxResourceDepth < 1 || maxResourceDepth > 6) {
            throw new IllegalArgumentException("maxResourceDepth must be between 1 and 6");
        }
        String normalizedPrefix = normalizePrefix(rootPrefix);
        List<String> patterns = new ArrayList<>();
        StringBuilder depthWildcards = new StringBuilder();
        for (int depth = 1; depth <= maxResourceDepth; depth++) {
            depthWildcards.append("/*");
            String resourceBase = normalizedPrefix + depthWildcards;
            for (String suffix : suffixes) {
                patterns.add(resourceBase + suffix);
            }
        }
        return patterns.toArray(String[]::new);
    }

    private static String normalizePrefix(String rootPrefix) {
        Objects.requireNonNull(rootPrefix, "rootPrefix");
        String normalizedPrefix = rootPrefix.trim();
        while (normalizedPrefix.endsWith("/")) {
            normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
        }
        if (!normalizedPrefix.isEmpty() && !normalizedPrefix.startsWith("/")) {
            normalizedPrefix = "/" + normalizedPrefix;
        }
        return normalizedPrefix;
    }
}
