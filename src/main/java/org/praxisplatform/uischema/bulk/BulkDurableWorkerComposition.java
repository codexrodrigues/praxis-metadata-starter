package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.context.SmartLifecycle;

/**
 * Explicit trusted server composition. Construction does not access the database or start work.
 * Supply the stable operational datasource and local transaction manager shared by domain mutation.
 * Neither this API nor a declared binding grants authority or accepts jobs.
 * The host explicitly registers the returned lifecycle; nonempty compositions then start at refresh.
 * Stop acknowledgement follows actual worker termination; arbitrary callbacks have no hard deadline.
 */
public final class BulkDurableWorkerComposition {
    private BulkDurableWorkerComposition() { }

    /**
     * One fresh pair per new unit admission, allocated after the receipt-first replay check.
     * Admission and mutation receive the real kernel unit and share only this pair's local state.
     * Cleanup runs once after execution returns or throws; it must not access DB, policy, or domain.
     * Cleanup failures are sanitized and cannot mask the original outcome; callbacks may not commit.
     */
    @JsonIgnoreType
    public record UnitCallbacks(BulkUnitAdmissionCallback admission,
            BulkUnitMutationCallback mutation, Runnable cleanup) {
        public UnitCallbacks {
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(mutation, "mutation");
            Objects.requireNonNull(cleanup, "cleanup");
        }
        @Override public String toString() { return "UnitCallbacks[protected]"; }
    }

    /** Factory must only allocate local state; authorization belongs in admission using the real unit. */
    @JsonIgnoreType
    public record Operation(String resourceKey, CanonicalOperationRef operation,
            Supplier<UnitCallbacks> callbacks) {
        public Operation {
            new BulkDurableWorker.Handler(resourceKey, operation, callbacks);
        }
        @Override public String toString() { return "Operation[protected]"; }
    }

    /** Expected identity is checked against durable authority by the kernel, never by construction alone. */
    @JsonIgnoreType
    public record Binding(BulkExecutionInfrastructure infrastructure, BulkCapacityBinding capacity,
            List<Operation> operations) {
        public Binding {
            Objects.requireNonNull(infrastructure, "infrastructure");
            Objects.requireNonNull(capacity, "capacity");
            if (!infrastructure.deploymentId().equals(capacity.deploymentId()))
                throw new IllegalArgumentException("Worker deployment binding differs");
            operations = List.copyOf(operations);
            internalBinding(infrastructure, capacity, operations);
        }
        @Override public String toString() { return "Binding[protected]"; }
    }

    /** Register the returned lifecycle explicitly. Spring starts nonempty compositions during refresh. */
    public static SmartLifecycle compose(List<Binding> bindings) {
        return new BulkDurableWorker(List.copyOf(bindings).stream()
                .map(binding -> internalBinding(binding.infrastructure(), binding.capacity(), binding.operations()))
                .toList());
    }

    private static BulkDurableWorker.Binding internalBinding(BulkExecutionInfrastructure infrastructure,
            BulkCapacityBinding capacity, List<Operation> operations) {
        return new BulkDurableWorker.Binding(new JdbcBulkDurableExecution(infrastructure, null, capacity),
                operations.stream().map(operation -> new BulkDurableWorker.Handler(operation.resourceKey(),
                        operation.operation(), operation.callbacks())).toList());
    }
}
