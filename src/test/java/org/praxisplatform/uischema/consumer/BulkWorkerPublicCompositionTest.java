package org.praxisplatform.uischema.consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkWorkerPublicCompositionTest {
    private BulkCapacityBinding capacity() {
        return new BulkCapacityBinding("deployment", "tenant", "test", "binding", 1,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
    }
    private BulkExecutionInfrastructure infrastructure() {
        return infrastructure("deployment");
    }
    private BulkExecutionInfrastructure infrastructure(String deployment) {
        var source = mock(javax.sql.DataSource.class);
        return new BulkExecutionInfrastructure(source,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),
                "tenant:test:worker", deployment,
                new BulkExecutionRoleConfiguration("owner", java.util.Set.of("runtime"),
                        java.util.Set.of(), java.util.Set.of("control")));
    }
    private BulkDurableWorkerComposition.Operation operation(AtomicInteger allocated) {
        return new BulkDurableWorkerComposition.Operation("resource",
                new CanonicalOperationRef("group", "approve", "/resource/approve", "POST"),
                () -> { allocated.incrementAndGet(); throw new AssertionError("Construction must stay lazy"); });
    }
    @Test void publicCompositionCopiesDeclarationsAndNeverAllocatesCallbacksOrStartsWork() {
        var allocated = new AtomicInteger();
        var operations = new ArrayList<>(List.of(operation(allocated)));
        var binding = new BulkDurableWorkerComposition.Binding(infrastructure(), capacity(), operations);
        operations.clear();
        var lifecycle = BulkDurableWorkerComposition.compose(List.of(binding));
        assertThat(binding.operations()).hasSize(1);
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(lifecycle.isAutoStartup()).isTrue();
        assertThat(allocated).hasValue(0);
        assertThat(binding.toString()).isEqualTo("Binding[protected]");
    }
    @Test void rejectsDeploymentMismatchDuplicatesAndEmptyCallbackDeclarations() {
        var operation = operation(new AtomicInteger());
        var infrastructure = infrastructure();
        var capacity = capacity();
        assertThatThrownBy(() -> new BulkDurableWorkerComposition.Binding(infrastructure, capacity, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkDurableWorkerComposition.Binding(infrastructure, capacity, List.of(operation, operation)))
                .isInstanceOf(IllegalArgumentException.class);
        var binding = new BulkDurableWorkerComposition.Binding(infrastructure, capacity, List.of(operation));
        assertThatThrownBy(() -> BulkDurableWorkerComposition.compose(List.of(binding, binding)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkDurableWorkerComposition.Binding(infrastructure("other"), capacity, List.of(operation)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bindingRetainsOriginalValidationAndProtectsDiagnosticRepresentation() {
        var binding = capacity();
        assertThat(binding.toString()).isEqualTo("BulkCapacityBinding[protected]");
        assertThatThrownBy(() -> new BulkCapacityBinding("deployment", "tenant", "test", "binding", 0,
                binding.databaseId(), binding.attestationId(), binding.authorityId(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(BulkDurableWorkerComposition.compose(List.of()).isAutoStartup()).isFalse();
    }
}
