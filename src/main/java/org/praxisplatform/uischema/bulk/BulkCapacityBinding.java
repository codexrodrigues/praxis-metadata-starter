package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.Objects;
import java.util.UUID;

/**
 * Expected capacity identity from trusted provisioning, outside request headers and restored copies.
 * Construction validates configuration only: it does not attest a database or install rights.
 * See {@code docs/spec/BULK-CAPACITY-INSTALLATION.md} for the owner provisioning protocol.
 * Never invent UUIDs to make a runtime appear ready or copy identity from a restored marker.
 *
 * @param deploymentId canonical deployment assigned by server provisioning; must match infrastructure
 * @param tenantId canonical tenant assigned to the local capacity binding
 * @param environment canonical deployment environment assigned by provisioning
 * @param bindingId canonical local binding identifier assigned by provisioning
 * @param generation positive provisioned binding generation; not an execution owner epoch
 * @param databaseId nonnull provisioned physical database UUID, fixed by owner bootstrap
 * @param attestationId nonnull provisioned attestation UUID verified through the witness protocol
 * @param authorityId nonnull UUID of the independently provisioned capacity authority
 * @param authorityEpoch positive expected authority epoch; not a unit control owner epoch
 */
@JsonIgnoreType
public record BulkCapacityBinding(String deploymentId, String tenantId, String environment, String bindingId,
        long generation, UUID databaseId, UUID attestationId,
        UUID authorityId, long authorityEpoch) {
    public BulkCapacityBinding {
        deploymentId = BulkCapacityAuthorityMigrator.canonical(deploymentId);
        tenantId = BulkCapacityAuthorityMigrator.canonical(tenantId);
        environment = BulkCapacityAuthorityMigrator.canonical(environment);
        bindingId = BulkCapacityAuthorityMigrator.canonical(bindingId);
        Objects.requireNonNull(databaseId, "databaseId");
        Objects.requireNonNull(attestationId, "attestationId");
        Objects.requireNonNull(authorityId, "authorityId");
        if (generation <= 0 || authorityEpoch <= 0)
            throw new IllegalArgumentException("Capacity binding generation and epoch must be positive");
    }
    @Override public String toString() { return "BulkCapacityBinding[protected]"; }
}
