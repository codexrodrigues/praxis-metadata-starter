package org.praxisplatform.uischema.action;

/**
 * Transaction semantics declared for collection-scoped commands.
 */
public enum ActionCollectionAtomicity {
    NOT_APPLICABLE,
    ATOMIC,
    PER_ITEM
}
