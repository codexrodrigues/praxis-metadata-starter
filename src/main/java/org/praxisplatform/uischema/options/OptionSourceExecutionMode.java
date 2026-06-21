package org.praxisplatform.uischema.options;

/**
 * Execution mode used by Praxis to resolve option-source providers.
 *
 * <p>
 * {@link #JPA} keeps the default metadata-driven JPA provider eligible. Use
 * {@link #PROVIDER_REQUIRED} when the source must be executed by a host-specific
 * provider and must never fall back to JPA path resolution.
 * </p>
 */
public enum OptionSourceExecutionMode {
    JPA,
    PROVIDER_REQUIRED
}
