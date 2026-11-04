package org.praxisplatform.uischema.bulk;

import java.util.List;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;

/** Discovers only validated real confirmation handlers; performs no provisioning or publication. */
public final class BulkOperationIdentityDiscovery {
    private final BulkResourceOperationBindings bindings;
    private final CanonicalOperationResolver operations;

    public BulkOperationIdentityDiscovery(BulkResourceOperationBindings bindings, CanonicalOperationResolver operations) {
        this.bindings = java.util.Objects.requireNonNull(bindings);
        this.operations = java.util.Objects.requireNonNull(operations);
    }

    public List<BulkOperationControlIdentity> discover(String namespace) {
        return discover(namespace, null);
    }

    /** Restricts canonical confirmation identities to one declared resource. */
    public List<BulkOperationControlIdentity> discover(String namespace, String resourceKey) {
        if (!bindings.diagnostics().isEmpty())
            throw new IllegalStateException("Invalid bulk declarations: " + String.join("; ", bindings.diagnostics()));
        return bindings.bulkOperations().stream().filter(binding -> resourceKey == null || resourceKey.equals(binding.resourceKey())).map(binding -> {
            var reference = operations.resolve(binding.confirmationHandler(),
                    bindings.mappingFor(binding.confirmationHandler()).orElseThrow());
            if (!binding.confirmationOperationId().equals(reference.operationId()) || !"POST".equals(reference.method()))
                throw new IllegalStateException("Canonical bulk confirmation identity differs from MVC binding");
            return new BulkOperationControlIdentity(namespace, reference.operationId());
        }).distinct().toList();
    }
}
