package org.praxisplatform.uischema.bulk;

/** Whole-set admission runs before the first domain mutation in the kernel transaction. */
@FunctionalInterface
public interface BulkAtomicAdmissionCallback {
    BulkAtomicAdmission admit(BulkAtomicExecutionSet set);
}
