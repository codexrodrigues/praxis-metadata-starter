package org.praxisplatform.uischema.action;

/**
 * Canonical execution contract published with an action.
 *
 * <p>The contract describes protocol, interaction, selection, outcome and refresh semantics.
 * It never duplicates the action request or response schema.</p>
 */
public record ActionExecutionContract(
        ActionInteractionPolicy interaction,
        ActionPreconditionPolicy preconditions,
        ActionSelectionPolicy selection,
        ActionOutcomePolicy outcome,
        ActionRefreshPolicy refresh
) {
    public ActionExecutionContract {
        interaction = interaction == null
                ? new ActionInteractionPolicy(null, null, false, false)
                : interaction;
        preconditions = preconditions == null
                ? new ActionPreconditionPolicy(null, null, null, null)
                : preconditions;
        selection = selection == null ? new ActionSelectionPolicy(null, null, null) : selection;
        outcome = outcome == null ? new ActionOutcomePolicy(null, null) : outcome;
        refresh = refresh == null ? new ActionRefreshPolicy(false, true, true, true, null) : refresh;
    }

    public static ActionExecutionContract defaults(ActionScope scope) {
        boolean itemScoped = scope == ActionScope.ITEM;
        return new ActionExecutionContract(
                null,
                null,
                null,
                null,
                new ActionRefreshPolicy(itemScoped, true, true, true, null)
        );
    }

    public void validateFor(ActionScope scope) {
        ActionScope resolvedScope = scope == null ? ActionScope.ITEM : scope;
        boolean hasSelection = selection.idsField() != null
                || selection.versionsField() != null
                || selection.maxItems() != null;
        if (resolvedScope == ActionScope.ITEM && hasSelection) {
            throw new IllegalArgumentException("selection policy is only valid for collection actions");
        }
        if (resolvedScope == ActionScope.ITEM
                && preconditions.resourceVersionTransport() == ActionResourceVersionTransport.SELECTION_MAP) {
            throw new IllegalArgumentException("item actions cannot use SELECTION_MAP resource versions");
        }
        if (resolvedScope == ActionScope.COLLECTION
                && preconditions.resourceVersionTransport() == ActionResourceVersionTransport.IF_MATCH) {
            throw new IllegalArgumentException("collection actions cannot use IF_MATCH resource versions");
        }
        if (preconditions.resourceVersionTransport() == ActionResourceVersionTransport.SELECTION_MAP
                && selection.versionsField() == null) {
            throw new IllegalArgumentException("SELECTION_MAP resource versions require selection.versionsField");
        }
        if (resolvedScope == ActionScope.ITEM
                && outcome.atomicity() != ActionCollectionAtomicity.NOT_APPLICABLE) {
            throw new IllegalArgumentException("collection atomicity is not applicable to item actions");
        }
        if (resolvedScope == ActionScope.ITEM && outcome.mode() == ActionOutcomeMode.PER_ITEM) {
            throw new IllegalArgumentException("PER_ITEM outcomes are only valid for collection actions");
        }
        if (interaction.mode() == ActionInteractionMode.DIRECT && interaction.confirmationRequired()) {
            throw new IllegalArgumentException("DIRECT interaction cannot require confirmation");
        }
    }
}
