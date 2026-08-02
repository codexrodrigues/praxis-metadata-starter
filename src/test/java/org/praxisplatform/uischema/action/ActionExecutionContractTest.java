package org.praxisplatform.uischema.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionExecutionContractTest {

    @Test
    void acceptsItemIfMatchAndCollectionSelectionMapPolicies() {
        ActionExecutionContract item = contract(
                ActionResourceVersionTransport.IF_MATCH,
                null,
                null,
                ActionOutcomeMode.SINGLE,
                ActionCollectionAtomicity.NOT_APPLICABLE
        );
        ActionExecutionContract collection = contract(
                ActionResourceVersionTransport.SELECTION_MAP,
                "ids",
                "expectedVersions",
                ActionOutcomeMode.PER_ITEM,
                ActionCollectionAtomicity.ATOMIC
        );

        assertDoesNotThrow(() -> item.validateFor(ActionScope.ITEM));
        assertDoesNotThrow(() -> collection.validateFor(ActionScope.COLLECTION));
    }

    @Test
    void rejectsTransportAndSelectionPoliciesThatContradictScope() {
        ActionExecutionContract itemWithSelection = contract(
                ActionResourceVersionTransport.SELECTION_MAP,
                "ids",
                "expectedVersions",
                ActionOutcomeMode.PER_ITEM,
                ActionCollectionAtomicity.PER_ITEM
        );
        ActionExecutionContract collectionWithIfMatch = contract(
                ActionResourceVersionTransport.IF_MATCH,
                null,
                null,
                ActionOutcomeMode.SINGLE,
                ActionCollectionAtomicity.ATOMIC
        );

        assertThrows(IllegalArgumentException.class,
                () -> itemWithSelection.validateFor(ActionScope.ITEM));
        assertThrows(IllegalArgumentException.class,
                () -> collectionWithIfMatch.validateFor(ActionScope.COLLECTION));
    }

    @Test
    void rejectsSelectionMapWithoutVersionBindingAndDirectConfirmation() {
        ActionExecutionContract missingVersionBinding = contract(
                ActionResourceVersionTransport.SELECTION_MAP,
                "ids",
                null,
                ActionOutcomeMode.SINGLE,
                ActionCollectionAtomicity.ATOMIC
        );
        ActionExecutionContract directConfirmation = new ActionExecutionContract(
                new ActionInteractionPolicy(
                        ActionInteractionMode.DIRECT,
                        ActionRiskLevel.HIGH,
                        true,
                        false
                ),
                new ActionPreconditionPolicy(null, null, null, null),
                null,
                null,
                null
        );

        assertThrows(IllegalArgumentException.class,
                () -> missingVersionBinding.validateFor(ActionScope.COLLECTION));
        assertThrows(IllegalArgumentException.class,
                () -> directConfirmation.validateFor(ActionScope.ITEM));
    }

    private ActionExecutionContract contract(
            ActionResourceVersionTransport transport,
            String idsField,
            String versionsField,
            ActionOutcomeMode outcomeMode,
            ActionCollectionAtomicity atomicity
    ) {
        ActionRequirement resourceVersion = transport == ActionResourceVersionTransport.NONE
                ? ActionRequirement.NONE
                : ActionRequirement.REQUIRED;
        return new ActionExecutionContract(
                null,
                new ActionPreconditionPolicy(
                        ActionRequirement.REQUIRED,
                        ActionRequirement.OPTIONAL,
                        resourceVersion,
                        transport
                ),
                new ActionSelectionPolicy(idsField, versionsField, idsField == null ? null : 200),
                new ActionOutcomePolicy(outcomeMode, atomicity),
                null
        );
    }
}
