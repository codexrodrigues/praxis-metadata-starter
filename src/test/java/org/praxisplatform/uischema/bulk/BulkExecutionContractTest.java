package org.praxisplatform.uischema.bulk;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Projection invariants for the same bounded ATOMIC domain command composed from an action. */
class BulkExecutionContractTest {
    @Test
    void commandAtomicKeepsParametersAndActionIdentityWithoutCrudCapability() {
        for (int maxTargets : List.of(1, 50)) {
            var contract = command(maxTargets, 5_000);
            assertEquals(BulkMode.DOMAIN_COMMAND, contract.mode());
            assertEquals(ActionCollectionAtomicity.ATOMIC, contract.atomicity());
            assertEquals("/properties/parameters", contract.parametersPointer());
            assertNull(contract.editableFields());
            assertEquals("items.bulk-approve", contract.confirmationOperation().operation().operationId());
            assertThrows(IllegalArgumentException.class, contract::crudCapabilityId);
        }
    }

    @Test
    void commandAtomicCannotProjectAnOversizedOrOverlongProfile() {
        assertThrows(IllegalArgumentException.class, () -> command(51, 5_000));
        assertThrows(IllegalArgumentException.class, () -> command(50, 5_001));
    }

    @Test
    void commandPerItemStillKeepsItsExistingCeilingAndParameters() {
        var contract = contract(ActionCollectionAtomicity.PER_ITEM, 200, 5_000);
        assertEquals(200, contract.limits().maxTargets());
        assertEquals("/properties/parameters", contract.parametersPointer());
        assertThrows(IllegalArgumentException.class, () -> contract(ActionCollectionAtomicity.PER_ITEM, 201, 5_000));
    }

    private static BulkExecutionContract command(int maxTargets, long unitDeadlineMillis) {
        return contract(ActionCollectionAtomicity.ATOMIC, maxTargets, unitDeadlineMillis);
    }

    private static BulkExecutionContract contract(ActionCollectionAtomicity atomicity,
            int maxTargets, long unitDeadlineMillis) {
        return new BulkExecutionContract(BulkMode.DOMAIN_COMMAND,
                operation("items.bulk-approve.evaluation", true),
                operation("items.bulk-approve", true),
                operation("items.bulk.proposal", false),
                operation("items.bulk.proposal-results", false),
                operation("items.bulk.execution", false),
                operation("items.bulk.execution-results", false),
                operation("items.bulk.cancel", false),
                List.of(BulkSelectionMode.EXPLICIT), List.of(BulkExecutionMode.SYNC),
                atomicity, new BulkExecutionContract.Limits(maxTargets, 1_024, 300_000, unitDeadlineMillis),
                "/properties/parameters", null);
    }

    private static BulkExecutionContract.Operation operation(String id, boolean request) {
        var reference = new CanonicalOperationRef("inventory", id, "/api/items/" + id, "POST");
        return new BulkExecutionContract.Operation(reference,
                request ? new CanonicalSchemaRef(id + ".request", "request", "/schemas/filtered?role=request") : null,
                new CanonicalSchemaRef(id + ".response", "response", "/schemas/filtered?role=response"));
    }
}
