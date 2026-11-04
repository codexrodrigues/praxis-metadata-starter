package org.praxisplatform.uischema.bulk;

import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Correlates retained IDs with a real confirmation operation in the current namespace/resource.
 * Global read grants are checked before correlating an ID, in the same operational snapshot.
 * The engine's authorized readers still enforce creator and complete target visibility.
 * Construction performs no database access, provisioning, publication or worker startup.
 */
public final class BulkOperationStatusLookup {
    private final BulkExecutionInfrastructure infrastructure;
    private final BulkOperationIdentityDiscovery discovery;

    public BulkOperationStatusLookup(BulkExecutionInfrastructure infrastructure, BulkOperationIdentityDiscovery discovery) {
        this.infrastructure = Objects.requireNonNull(infrastructure);
        this.discovery = Objects.requireNonNull(discovery);
    }

    public String proposalOperationId(String resourceKey, UUID proposalId, BulkResourcePolicy policy) {
        return resolve(resourceKey, proposalId, false, policy);
    }

    public String executionOperationId(String resourceKey, UUID executionId, BulkResourcePolicy policy) {
        return resolve(resourceKey, executionId, true, policy);
    }

    private String resolve(String resourceKey, UUID id, boolean execution, BulkResourcePolicy policy) {
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policy, "policy");
        var declared = discovery.discover(infrastructure.namespace(), resourceKey).stream()
                .map(BulkOperationControlIdentity::confirmationOperationId).collect(java.util.stream.Collectors.toSet());
        if (declared.isEmpty()) throw unavailable();
        // Canonical MVC/OpenAPI resolution belongs outside JDBC. Only the current grant check
        // joins the operational snapshot; resolving a binding here does not authorize an ID.
        var bindings = new ArrayList<ReadBinding>();
        String subject = null;
        for (String operationId : declared.stream().sorted().toList()) {
            try {
                var context = Objects.requireNonNull(policy.context(operationId));
                var authorization = Objects.requireNonNull(policy.readAuthorization(operationId));
                if (!infrastructure.namespace().equals(context.namespaceId()) || !resourceKey.equals(context.resourceKey())
                        || !operationId.equals(context.operationRef().operationId())
                        || authorization.executionInfrastructure() != infrastructure
                        || !operationId.equals(authorization.confirmationOperationId())
                        || subject != null && !subject.equals(context.subjectId()))
                    throw new IllegalStateException("Bulk read policy binding differs from the requested resource");
                subject = context.subjectId();
                bindings.add(new ReadBinding(new BulkReadAuthorizationProvider.Context(
                        context.namespaceId(), resourceKey, operationId, subject), authorization));
            } catch (RuntimeException failure) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Bulk observation is unavailable", failure);
            }
        }
        return infrastructure.withConsistentRead(connection -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            var allowed = new ArrayList<String>();
            for (var binding : bindings) {
                try {
                    var decision = binding.authorization().preAuthorize(binding.context(), remaining(deadline));
                    remaining(deadline);
                    if (decision == null || decision == BulkReadAuthorizationProvider.GlobalDecision.UNAVAILABLE)
                        throw new IllegalStateException("Bulk read authority is unavailable");
                    if (decision == BulkReadAuthorizationProvider.GlobalDecision.ALLOWED) allowed.add(binding.context().operationId());
                } catch (RuntimeException failure) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Bulk observation is unavailable", failure);
                }
            }
            if (allowed.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bulk observation is unavailable");
            String sql = (execution
                    ? "select operation_id from praxis_bulk.praxis_bulk_execution where namespace_id=? and resource_key=? and execution_id=?"
                    : "select operation_id from praxis_bulk.praxis_bulk_proposal where namespace_id=? and resource_key=? and proposal_id=?")
                    + " and operation_id in (" + String.join(",", java.util.Collections.nCopies(allowed.size(), "?")) + ")";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(Math.max(1, (int)Math.ceil(remaining(deadline).toNanos() / 1_000_000_000d)));
                statement.setString(1, infrastructure.namespace());
                statement.setString(2, resourceKey);
                statement.setObject(3, id);
                for (int index = 0; index < allowed.size(); index++) statement.setString(index + 4, allowed.get(index));
                try (var rows = statement.executeQuery()) {
                    remaining(deadline);
                    if (!rows.next()) throw unavailable();
                    String operationId = rows.getString(1);
                    if (!allowed.contains(operationId) || rows.next()) throw unavailable();
                    return operationId;
                }
            }
        });
    }

    private record ReadBinding(BulkReadAuthorizationProvider.Context context, BulkReadAuthorizationProvider authorization) { }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Bulk observation is unavailable");
        return Duration.ofNanos(nanos);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Bulk observation is unavailable");
    }
}
