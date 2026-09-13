package org.praxisplatform.uischema.bulk;

/** A selected wire identity and its opaque resource ETag, never a schema ETag. */
public record BulkTarget<WI>(WI id, String expectedVersion) {
    public BulkTarget {
        java.util.Objects.requireNonNull(id, "id is required");
        BulkContractChecks.text(expectedVersion, "expectedVersion");
    }
}
