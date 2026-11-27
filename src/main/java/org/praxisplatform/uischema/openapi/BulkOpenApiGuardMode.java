package org.praxisplatform.uischema.openapi;

import java.util.Locale;

/**
 * Configures the blast radius of governed bulk OpenAPI publication guards on documentary and
 * structural metadata reads.
 *
 * <ul>
 *   <li>{@link #GLOBAL_STRICT}: All documentary ({@code /v3/api-docs/**}) and contextual/schema reads
 *       require an installed, ledger-attested OpenAPI publication and fail closed with {@code 503}
 *       whenever publication is absent or suspended.</li>
 *   <li>{@link #BULK_SCOPED}: Bulk discovery, readiness, admission, and execution remain strictly
 *       fail-closed against the durable publication fence, while ordinary OpenAPI and
 *       {@code /schemas/**} reads degrade cleanly to the local document cache when publication is
 *       absent, suspended, or converging.</li>
 * </ul>
 */
public enum BulkOpenApiGuardMode {
    GLOBAL_STRICT,
    BULK_SCOPED;

    /**
     * Parses a configuration value (case-insensitive, allowing {@code -} or {@code _}), defaulting
     * {@code null}/blank values to {@link #GLOBAL_STRICT}.
     */
    public static BulkOpenApiGuardMode fromProperty(String raw) {
        if (raw == null || raw.isBlank()) {
            return GLOBAL_STRICT;
        }
        String normalized = raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid praxis.bulk.lifecycle.openapi-guard-mode '" + raw
                            + "'; expected GLOBAL_STRICT or BULK_SCOPED",
                    invalid);
        }
    }
}
