package org.praxisplatform.uischema.bulk;

/** Domain mutation of the entire set in the same transaction as its durable header and children. */
@FunctionalInterface
public interface BulkAtomicMutationCallback {
    BulkAtomicMutationResult apply(BulkAtomicExecutionSet set);
}
