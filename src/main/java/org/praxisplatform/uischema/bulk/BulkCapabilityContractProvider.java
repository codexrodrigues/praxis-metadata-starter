package org.praxisplatform.uischema.bulk;

import java.util.List;
import java.util.Map;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionExecutionContract;

/**
 * Optional SPI projecting descriptive bulk execution contracts for resource capabilities
 * and workflow actions, decoupling UI discovery from legacy durable publication lifecycles.
 */
public interface BulkCapabilityContractProvider {

    /**
     * Returns CRUD bulk contracts keyed by canonical capability ID (for example
     * {@code bulk-update} or {@code bulk-update-items}) for the supplied resource.
     */
    Map<String, BulkExecutionContract> resourceContracts(String resourceKey);

    /**
     * Returns action execution contracts keyed by {@code definition.operation().operationId()}
     * for collection-scoped bulk domain commands.
     */
    Map<String, ActionExecutionContract> actionContracts(List<ActionDefinition> definitions);
}
