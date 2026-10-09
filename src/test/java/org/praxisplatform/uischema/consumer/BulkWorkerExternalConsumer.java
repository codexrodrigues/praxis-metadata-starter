package org.praxisplatform.uischema.consumer;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.praxisplatform.uischema.bulk.*;

/** External consumer composes from public inputs, never receives an internal kernel. */
public final class BulkWorkerExternalConsumer {
    private BulkWorkerExternalConsumer() { }
    public static SmartLifecycle compose(DataSource runtime, String namespace, String deployment,
            BulkExecutionRoleConfiguration roles, String tenant, String environment, String binding,
            long generation, UUID database, UUID attestation, UUID authority, long epoch,
            BulkDurableWorkerComposition.Operation operation) {
        var infrastructure = new BulkExecutionInfrastructure(runtime, new DataSourceTransactionManager(runtime),
                namespace, deployment, roles);
        var expectation = new BulkCapacityBinding(deployment, tenant, environment, binding,
                generation, database, attestation, authority, epoch);
        return BulkDurableWorkerComposition.compose(List.of(
                new BulkDurableWorkerComposition.Binding(infrastructure, expectation, List.of(operation))));
    }
}
