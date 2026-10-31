package org.praxisplatform.uischema.bulk;

/** Safe fail-closed discovery refusal; never carries SQL, target data or a nested cause. */
public final class BulkDiscoveryUnavailableException extends RuntimeException {
    BulkDiscoveryUnavailableException() {
        super("Bulk discovery is temporarily unavailable.");
    }
}
